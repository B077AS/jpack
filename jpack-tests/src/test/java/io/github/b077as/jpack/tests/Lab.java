package io.github.b077as.jpack.tests;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.b077as.jpack.Jpack;
import io.github.b077as.jpack.build.EntrySpec;
import io.github.b077as.jpack.build.PackageSpec;
import io.github.b077as.jpack.build.Packager;
import io.github.b077as.jpack.build.Platforms;
import io.github.b077as.jpack.build.Runtimes;
import io.github.b077as.jpack.internal.Json;
import sample.SampleApp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * Everything one end-to-end test needs: a place to build releases of the sample app, a web server that publishes them,
 * an install folder, and a way to start the installed stub and read what the sample programs wrote.
 */
final class Lab implements AutoCloseable {

    static final String PLATFORM = Platforms.current();
    static final String APP_NAME = "Demo";

    /** What a release of the sample app looks like. */
    static final class Recipe {
        final String version;
        String behavior = "exit";
        Path runtime = Runtimes_.base();
        boolean launcher;
        boolean branded;
        boolean archive;
        boolean appImage;
        String appImageName;
        java.util.List<String> rawParts = java.util.List.of();
        String policy = "manual";
        boolean log = true;
        boolean updates = true;
        Path stub = Lab.stub();

        Recipe(String version) {
            this.version = version;
        }

        Recipe behavior(String value) {
            behavior = value;
            return this;
        }

        Recipe runtime(Path value) {
            runtime = value;
            return this;
        }

        Recipe withLauncher() {
            launcher = true;
            return this;
        }

        Recipe policy(String value) {
            policy = value;
            return this;
        }

        /** The release says {@code "log": false}. */
        Recipe withoutLog() {
            log = false;
            return this;
        }

        Recipe appImage() {
            appImage = true;
            return this;
        }

        /** An AppImage with a fixed file name, the same in every release (no version in it). */
        Recipe appImage(String fileName) {
            appImage = true;
            appImageName = fileName;
            return this;
        }

        Recipe archive() {
            archive = true;
            return this;
        }

        Recipe branded() {
            branded = true;
            return this;
        }

        /** Publishes these parts as the plain file they consist of, not as a zip. */
        Recipe rawParts(String... names) {
            rawParts = java.util.List.of(names);
            return this;
        }

        Recipe withoutUpdates() {
            updates = false;
            return this;
        }

        Recipe stub(Path value) {
            stub = value;
            return this;
        }
    }

    /** One finished run of the stub. */
    record Run(int exitCode, String log) {
    }

    final Path dir;
    final Path install;
    final Path served;
    final Path out;
    /** Every request the server got, as {@code file} or {@code file bytes=N-}. */
    final List<String> requests = new CopyOnWriteArrayList<>();
    /** Files the server answers with one byte changed. */
    final Set<String> corrupted = ConcurrentHashMap.newKeySet();
    /** Files the server cuts off halfway, once. */
    final Set<String> cutOffOnce = ConcurrentHashMap.newKeySet();

    private final HttpServer server;
    private int runs;

    Lab(String name) throws IOException {
        dir = Path.of(System.getProperty("jpack.lab", "target/lab")).toAbsolutePath().resolve(name);
        Packager.deleteTree(dir);
        install = Files.createDirectories(dir.resolve("install"));
        served = Files.createDirectories(dir.resolve("served"));
        out = dir.resolve("out.txt");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        server.createContext("/", this::serve);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "lab-server");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Builds a release: the install folder and the files to publish. */
    Packager.Packaged build(Recipe recipe) throws IOException {
        Path source = Files.createDirectories(dir.resolve("source-" + recipe.version));
        Path app = Files.createDirectories(source.resolve("app"));
        jar(app.resolve("sample-app.jar"), Map.of("sample-version.txt", recipe.version, "sample-behavior.txt", recipe.behavior));

        PackageSpec spec = new PackageSpec();
        spec.name = APP_NAME;
        spec.version = recipe.version;
        spec.platform = PLATFORM;
        spec.stub = recipe.stub;
        spec.output = dir.resolve("build-" + recipe.version);
        spec.policy = recipe.policy;
        spec.log = recipe.log;
        spec.archive = recipe.archive;
        spec.updateUrl = recipe.updates ? url() : null;
        if (recipe.appImage) {
            spec.appImage = true;
            spec.appImageTool = appImageTool();
            spec.appImageName = recipe.appImageName;
        }
        if (recipe.branded) {
            spec.branding = new io.github.b077as.jpack.build.Branding();
        }
        spec.rawParts = recipe.rawParts;
        spec.parts.put("runtime", recipe.runtime);
        spec.parts.put("app", app);

        EntrySpec appEntry = new EntrySpec();
        appEntry.classpath.add("app/sample-app.jar");
        appEntry.mainClass = "sample.SampleApp";
        spec.entries.put("app", appEntry);
        spec.start = "app";

        if (recipe.launcher) {
            Path launcher = Files.createDirectories(source.resolve("launcher"));
            jar(launcher.resolve("sample-launcher.jar"), Map.of("sample-version.txt", recipe.version));
            spec.parts.put("launcher", launcher);
            EntrySpec launcherEntry = new EntrySpec();
            launcherEntry.classpath.add("launcher/sample-launcher.jar");
            launcherEntry.mainClass = "sample.SampleLauncher";
            spec.entries.put("launcher", launcherEntry);
            spec.start = "launcher";
        }
        return new Packager().run(spec);
    }

    /** Puts a release on the user's disk, as an installer would. */
    void install(Packager.Packaged release) throws IOException {
        Packager.copyTree(release.image(), install);
    }

    /** Uploads a release: from now on it is what the update address answers with. */
    void publish(Packager.Packaged release) throws IOException {
        Packager.copyTree(release.release(), served);
    }

    /** Set once an AppImage is installed: it, and not the stub in the install folder, is what runs. */
    Path appImagePath;

    /** appimagetool from the APPIMAGETOOL variable or the PATH, or null. */
    static Path appImageTool() {
        String fromEnv = System.getenv("APPIMAGETOOL");
        if (fromEnv != null && Files.isRegularFile(Path.of(fromEnv))) {
            return Path.of(fromEnv);
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir).resolve("appimagetool");
            if (!dir.isEmpty() && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Puts the release's AppImage on the user's disk, as a download would. */
    void installAppImage(Packager.Packaged release) throws IOException {
        appImagePath = dir.resolve("App.AppImage");
        Files.copy(release.appImage(), appImagePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        appImagePath.toFile().setExecutable(true);
    }

    Path stubExe() {
        return install.resolve(Platforms.executable(PLATFORM, APP_NAME));
    }

    ProcessBuilder command(Map<String, String> env, String... args) {
        List<String> command = new ArrayList<>();
        command.add(appImagePath != null ? appImagePath.toString() : stubExe().toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("SAMPLE_OUT", out.toString());
        builder.environment().put("JPACK_NO_DIALOG", "1");
        builder.environment().put("APPIMAGE_EXTRACT_AND_RUN", "1"); // containers have no FUSE
        builder.environment().put("JPACK_DEBUG", "1");
        builder.environment().put("JPACK_CHECK_DELAY_MS", "0");
        builder.environment().putAll(env);
        builder.redirectErrorStream(true);
        builder.redirectOutput(dir.resolve("run-" + (++runs) + ".log").toFile());
        return builder;
    }

    /** Starts the installed app and waits until it, and everything it started in turn, has ended. */
    Run run(String... args) throws IOException, InterruptedException {
        return run(Map.of(), args);
    }

    Run run(Map<String, String> env, String... args) throws IOException, InterruptedException {
        ProcessBuilder builder = command(env, args);
        Process process = builder.start();
        return finish(process, builder);
    }

    Run finish(Process process, ProcessBuilder builder) throws IOException, InterruptedException {
        Path log = builder.redirectOutput().file().toPath();
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("the stub did not end. Its log:\n" + Files.readString(log));
        }
        return new Run(process.exitValue(), Files.readString(log));
    }

    /** The lines the sample programs wrote, oldest first. */
    List<String> lines() throws IOException {
        return Files.exists(out) ? Files.readAllLines(out) : List.of();
    }

    /** The version {@code jpack.json} says is installed. */
    String installedVersion() throws IOException {
        return String.valueOf(Json.parseObject(Files.readString(install.resolve("jpack.json"))).get("version"));
    }

    /** The file name of a part's zip in a release. */
    @SuppressWarnings("unchecked")
    static String partFile(Packager.Packaged release, String part) throws IOException {
        Map<String, Object> parts = (Map<String, Object>) Json.parseObject(Files.readString(release.manifest())).get("parts");
        return String.valueOf(((Map<String, Object>) parts.get(part)).get("file"));
    }

    static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The stub built by build-stub.ps1 / build-stub.sh, or null when there is none for this platform. */
    static Path stub() {
        Path stub = Path.of(System.getProperty("jpack.stubs", "../stubs")).toAbsolutePath().resolve(PLATFORM).resolve(Platforms.stubFileName(PLATFORM));
        return Files.isRegularFile(stub) ? stub : null;
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            String name = exchange.getRequestURI().getPath().substring(1);
            String range = exchange.getRequestHeaders().getFirst("Range");
            requests.add(range == null ? name : name + " " + range);
            Path file = served.resolve(name).normalize();
            if (!file.startsWith(served) || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (corrupted.contains(name)) {
                bytes[bytes.length / 2] ^= 0x55;
            }
            // "bytes=N-" (the rest of a file) and "bytes=N-M" (a block range, as the AppImage update asks for).
            int from = 0;
            int to = bytes.length - 1;
            boolean partial = false;
            if (range != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("bytes=(\\d+)-(\\d*)").matcher(range);
                if (m.matches()) {
                    from = Integer.parseInt(m.group(1));
                    if (!m.group(2).isEmpty()) {
                        to = Math.min(Integer.parseInt(m.group(2)), bytes.length - 1);
                    }
                    partial = from > 0 || to < bytes.length - 1;
                }
            }
            if (partial) {
                exchange.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + to + "/" + bytes.length);
            }
            int length = to - from + 1;
            exchange.sendResponseHeaders(partial ? 206 : 200, length);
            OutputStream body = exchange.getResponseBody();
            if (cutOffOnce.remove(name)) {
                // Half the file, then the connection drops: what a flaky network looks like to the stub.
                body.write(bytes, from, length / 2);
                body.flush();
                return;
            }
            body.write(bytes, from, length);
        }
    }

    /** A jar with the sample programs, the jpack library they use, and the given text resources. */
    private static void jar(Path target, Map<String, String> resources) throws IOException {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(target))) {
            addClasses(jar, SampleApp.class, "sample/");
            addClasses(jar, Jpack.class, "io/github/b077as/jpack/");
            for (var resource : resources.entrySet()) {
                jar.putNextEntry(new JarEntry(resource.getKey()));
                jar.write(resource.getValue().getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
    }

    /** Copies the classes under {@code prefix} from wherever {@code anchor} was loaded from (a folder or a jar). */
    private static void addClasses(JarOutputStream jar, Class<?> anchor, String prefix) throws IOException {
        Path location;
        try {
            location = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        if (Files.isDirectory(location)) {
            try (Stream<Path> walk = Files.walk(location.resolve(prefix))) {
                for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    jar.putNextEntry(new JarEntry(location.relativize(file).toString().replace('\\', '/')));
                    jar.write(Files.readAllBytes(file));
                    jar.closeEntry();
                }
            }
        } else {
            try (java.util.jar.JarFile source = new java.util.jar.JarFile(location.toFile())) {
                for (JarEntry entry : (Iterable<JarEntry>) source.stream().filter(e -> !e.isDirectory() && e.getName().startsWith(prefix))::iterator) {
                    jar.putNextEntry(new JarEntry(entry.getName()));
                    jar.write(source.getInputStream(entry).readAllBytes());
                    jar.closeEntry();
                }
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** The two Java runtimes the tests ship: built once with jlink, then reused. */
    static final class Runtimes_ {
        private static Path base;
        private static Path withLogging;

        private Runtimes_() {
        }

        /** A runtime with {@code java.base} only. */
        static synchronized Path base() {
            if (base == null) {
                base = build("base", "java.base");
            }
            return base;
        }

        /** A different runtime: it also has {@code java.logging}, which the sample app reports. */
        static synchronized Path withLogging() {
            if (withLogging == null) {
                withLogging = build("logging", "java.base,java.logging");
            }
            return withLogging;
        }

        private static Path build(String name, String modules) {
            Path folder = Path.of(System.getProperty("jpack.lab", "target/lab")).toAbsolutePath().resolveSibling("test-runtimes").resolve(name + "-" + Runtime.version().feature());
            try {
                if (!Files.exists(folder.resolve("release"))) {
                    Runtimes.jlink(modules, null, List.of(), folder);
                }
                return folder;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
