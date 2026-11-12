package io.github.b077as.jpack.build;

import io.github.b077as.jpack.internal.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds one release of an app for one platform. Plain Java with no Maven in it, so other build tools can call it too.
 *
 * <p>It writes two folders under {@link PackageSpec#output}:
 * <ul>
 *   <li>{@code image/<platform>/}: the install folder as it should be on the user's disk (the stub under the app's name,
 *       {@code jpack.json}, one folder per part). Wrap it in an installer, a zip or a tar.gz.</li>
 *   <li>{@code release/}: what updates are served from: {@code jpack-<platform>.json} and one zip per part, named by part and
 *       platform. Upload the parts first and the manifest last, to the place {@link PackageSpec#updateUrl} points at (a GitHub release, any web server).</li>
 * </ul>
 */
public final class Packager {

    /** The manifest format this version writes: every part is a zip. */
    public static final int FORMAT = 1;

    /** The format of a release with a part that is a plain file (see {@link PackageSpec#rawParts}). */
    public static final int FORMAT_WITH_PLAIN_PARTS = 2;

    /** The part that holds the stub. */
    public static final String STUB_PART = "stub";

    /**
     * The version the stub shows in its file properties: its own, not the app's, so that the stub part stays the same file
     * from release to release. Keep it equal to the version in {@code stub/Cargo.toml}.
     */
    static final String STUB_VERSION = "0.1.2";

    private static final Pattern PART_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /** Where a build put things. */
    public record Packaged(Path image, Path release, Path manifest, Path archive, Path appImage) {
    }

    public Packaged run(PackageSpec spec) throws IOException {
        check(spec);
        String executable = spec.executableName();
        boolean brand = spec.branding != null && Platforms.isWindows(spec.platform);
        Path release = spec.output.resolve("release");
        Path image = spec.output.resolve("image").resolve(spec.platform);
        Files.createDirectories(release);
        deleteTree(image);
        Files.createDirectories(image);
        Path scratch = Files.createTempDirectory("jpack-build");
        try {
            return build(spec, executable, brand, release, image, scratch);
        } finally {
            deleteTree(scratch);
        }
    }

    private Packaged build(PackageSpec spec, String executable, boolean brand, Path release, Path image, Path scratch) throws IOException {
        // The stub that ships (in the image and as the stub part) with the app's icon and names.
        Path stubFile = spec.stub;
        if (brand) {
            stubFile = scratch.resolve("stub").resolve(Platforms.executable(spec.platform, executable));
            try {
                spec.branding.applyCopy(spec.stub, stubFile, spec.name, STUB_VERSION);
            } catch (IOException | RuntimeException e) {
                spec.warn.accept("the stub keeps its own name and icon: " + e.getMessage());
                stubFile = spec.stub;
            }
        }

        // A renamed, branded copy of the runtime's Java launcher: the process shows up under the app's name.
        Map<String, Path> partFolders = new LinkedHashMap<>(spec.parts);
        if (brand && spec.brandJava && spec.parts.containsKey("runtime")) {
            Path runtime = scratch.resolve("runtime");
            copyTree(spec.parts.get("runtime"), runtime);
            if (brandJava(spec, runtime, executable)) {
                partFolders.put("runtime", runtime);
            }
        }

        Map<String, Object> parts = new LinkedHashMap<>();
        for (var part : partFolders.entrySet()) {
            parts.put(part.getKey(), spec.rawParts.contains(part.getKey())
                    ? rawPart(part.getKey(), part.getValue(), release)
                    : zipPart(part.getKey(), part.getValue(), release, spec.platform));
            copyTree(part.getValue(), image.resolve(part.getKey()));
        }
        if (spec.includeStub) {
            Path holder = scratch.resolve("stub-part");
            Files.createDirectories(holder);
            Path copy = holder.resolve(Platforms.executable(spec.platform, "stub"));
            Files.copy(stubFile, copy);
            makeExecutable(copy);
            parts.put(STUB_PART, zipPart(STUB_PART, holder, release, spec.platform));
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        // Plain (unzipped) parts need a stub that understands them; an older stub then refuses the release instead of
        // unpacking a jar as if it were a part.
        manifest.put("format", spec.rawParts.stream().anyMatch(partFolders::containsKey) ? FORMAT_WITH_PLAIN_PARTS : FORMAT);
        manifest.put("name", spec.name);
        manifest.put("version", spec.version);
        if (spec.updateUrl != null && !spec.updateUrl.isBlank()) {
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("url", spec.updateUrl.replaceAll("/+$", "") + "/" + manifestFileName(spec.platform));
            update.put("policy", spec.policy);
            if (spec.intervalHours != null) {
                update.put("intervalHours", spec.intervalHours);
            }
            manifest.put("update", update);
        }
        manifest.put("parts", parts);
        Map<String, Object> entries = new LinkedHashMap<>();
        spec.entries.forEach((name, entry) -> entries.put(name, entry.toJson()));
        Map<String, Object> launch = new LinkedHashMap<>();
        launch.put("start", spec.start);
        launch.put("entries", entries);
        manifest.put("launch", launch);

        byte[] json = Json.writePretty(manifest).getBytes(StandardCharsets.UTF_8);
        Path manifestFile = release.resolve(manifestFileName(spec.platform));
        Files.write(manifestFile, json);
        Files.write(image.resolve("jpack.json"), json);
        removeUnreferencedZips(release);

        Path stub = image.resolve(Platforms.executable(spec.platform, executable));
        Files.copy(stubFile, stub, StandardCopyOption.REPLACE_EXISTING);
        makeExecutable(stub);

        Path archive = null;
        if (spec.archive) {
            String base = executable + "-" + spec.version + "-" + spec.platform;
            archive = Archives.create(image, executable, spec.output.resolve("dist").resolve(base), spec.platform,
                    Set.of(Platforms.executable(spec.platform, executable)));
        }
        Path appImage = buildAppImage(spec, executable, manifest, image, release, manifestFile, scratch);
        return new Packaged(image, release, manifestFile, archive, appImage);
    }

    /**
     * The AppImage of this release and its block index, and the manifest entry that points at them. The manifest inside the
     * AppImage cannot hold the hash of the file it is in, so only the published manifest gets the entry.
     */
    private static Path buildAppImage(PackageSpec spec, String executable, Map<String, Object> manifest, Path image, Path release,
                                      Path manifestFile, Path scratch) {
        if (!spec.appImage || spec.platform.startsWith("windows")) {
            return null;
        }
        if (spec.appImageTool == null) {
            spec.warn.accept("no AppImage was built: appimagetool was not found (set appImageTool, or put it on the PATH)");
            return null;
        }
        AppImages.checkName(spec.appImageName); // a bad name fails the build, it is not a warning
        try {
            String name = spec.appImageName != null ? spec.appImageName
                    : executable + "-" + spec.version + "-" + AppImages.architecture(spec.platform) + ".AppImage";
            Path built = AppImages.build(image, executable, spec.name, spec.appImageIcon, spec.appImageTool, release.resolve(name), scratch, spec.platform, spec.warn);
            byte[] index = BlockIndex.build(built, BlockIndex.DEFAULT_BLOCK_SIZE);
            Files.write(release.resolve(name + ".blocks"), index);

            Map<String, Object> blocks = new LinkedHashMap<>();
            blocks.put("file", name + ".blocks");
            blocks.put("sha256", sha256(index));
            blocks.put("size", index.length);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("file", name);
            entry.put("sha256", Zips.sha256(built));
            entry.put("size", Files.size(built));
            entry.put("blocks", blocks);
            manifest.put("appImage", entry);
            Files.write(manifestFile, Json.writePretty(manifest).getBytes(StandardCharsets.UTF_8));

            try (Stream<Path> files = Files.list(release)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    String other = file.getFileName().toString();
                    if ((other.endsWith(".AppImage") || other.endsWith(".AppImage.blocks")) && !other.equals(name) && !other.equals(name + ".blocks")) {
                        Files.delete(file);
                    }
                }
            }
            return built;
        } catch (IOException | RuntimeException e) {
            spec.warn.accept("no AppImage was built: " + e.getMessage());
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The version shown for the renamed Java launcher: the runtime's own (from its {@code release} file). It must not
     * depend on the app's version, or the runtime part would differ in every release and be downloaded again each time.
     * The stub is branded with the stub's own version for the same reason.
     */
    static String runtimeVersion(Path runtime) {
        try {
            for (String line : Files.readAllLines(runtime.resolve("release"))) {
                if (line.startsWith("JAVA_VERSION=")) {
                    return line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                }
            }
        } catch (IOException | RuntimeException e) {
            // no release file: no version to show
        }
        return "";
    }

    /**
     * Adds {@code bin/<app>.exe} (a branded copy of {@code javaw.exe}) to the runtime, and points the entries that use the
     * runtime's own launcher at it. Returns false, leaving everything as it was, when the runtime has no {@code javaw.exe}
     * or cannot be branded.
     */
    private static boolean brandJava(PackageSpec spec, Path runtime, String executable) {
        Path javaw = runtime.resolve("bin").resolve("javaw.exe");
        Path java = runtime.resolve("bin").resolve("java.exe");
        if (!Files.isRegularFile(javaw)) {
            return false;
        }
        try {
            spec.branding.applyCopy(javaw, runtime.resolve("bin").resolve(executable + ".exe"), spec.name, runtimeVersion(runtime));
            boolean console = spec.entries.values().stream().anyMatch(entry -> entry.console);
            if (console && Files.isRegularFile(java)) {
                spec.branding.applyCopy(java, runtime.resolve("bin").resolve(executable + "-console.exe"), spec.name, runtimeVersion(runtime));
            }
        } catch (IOException | RuntimeException e) {
            spec.warn.accept("the Java launcher keeps its own name and icon: " + e.getMessage());
            return false;
        }
        for (EntrySpec entry : spec.entries.values()) {
            if (entry.java == null && entry.runtime.equals("runtime")) {
                entry.java = "runtime/bin/" + executable + (entry.console ? "-console.exe" : ".exe");
            }
        }
        return true;
    }

    /** The name of the manifest for a platform, such as {@code jpack-windows-x64.json}. */
    public static String manifestFileName(String platform) {
        return "jpack-" + platform + ".json";
    }

    /**
     * A part that is one file (a jar) is published as that file, not zipped: nothing to unpack, and the release lists the
     * jar itself. It keeps its own file name, which is the same on every platform, so a release built on several
     * platforms from the same jar holds it once.
     */
    static Map<String, Object> rawPart(String name, Path folder, Path release) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(folder)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        if (files.size() != 1 || !files.get(0).getParent().equals(folder)) {
            throw new IllegalArgumentException("the part '" + name + "' can only be published as a plain file when it consists of one file, not "
                    + files.size() + " in " + folder);
        }
        Path file = files.get(0);
        String fileName = file.getFileName().toString();
        Files.copy(file, release.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("file", fileName);
        part.put("sha256", Zips.sha256(file));
        part.put("size", Files.size(file));
        part.put("packed", false);
        return part;
    }

    private static Map<String, Object> zipPart(String name, Path folder, Path release, String platform) throws IOException {
        Path temp = Files.createTempFile(release, name, ".zip.tmp");
        try {
            Zips.zip(folder, temp, platform);
            String sha256 = Zips.sha256(temp);
            // The name carries the platform, so a release folder with several platforms stays readable. It does not carry
            // the hash: the manifest lists the hash of every part and the stub checks it, so a file that does not match
            // is refused whatever it is called. Upload the parts first and the manifest last.
            String fileName = name + "-" + platform + ".zip";
            Files.move(temp, release.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("file", fileName);
            part.put("sha256", sha256);
            part.put("size", Files.size(release.resolve(fileName)));
            return part;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Zips of earlier builds that no manifest in the folder names any more would only be uploaded for nothing. */
    @SuppressWarnings("unchecked")
    private static void removeUnreferencedZips(Path release) throws IOException {
        Set<String> referenced = new HashSet<>();
        try (Stream<Path> files = Files.list(release)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (name.startsWith("jpack-") && name.endsWith(".json")) {
                    try {
                        Object parts = Json.parseObject(Files.readString(file)).get("parts");
                        if (parts instanceof Map) {
                            for (Object part : ((Map<String, Object>) parts).values()) {
                                if (part instanceof Map) {
                                    referenced.add(String.valueOf(((Map<String, Object>) part).get("file")));
                                }
                            }
                        }
                    } catch (IllegalArgumentException e) {
                        // Not one of ours: leave the folder alone rather than guess.
                        return;
                    }
                }
            }
        }
        try (Stream<Path> files = Files.list(release)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if ((name.endsWith(".zip") || name.endsWith(".jar")) && !referenced.contains(name)) {
                    Files.delete(file);
                }
            }
        }
    }

    private static void check(PackageSpec spec) {
        require(spec.name != null && !spec.name.isBlank(), "the app needs a name");
        require(spec.version != null && !spec.version.isBlank(), "the app needs a version");
        require(spec.output != null, "an output folder is needed");
        require(spec.stub != null && Files.isRegularFile(spec.stub), "the stub was not found: " + spec.stub);
        require(spec.start != null && spec.entries.containsKey(spec.start), "the start entry '" + spec.start + "' is not defined");
        require("background".equals(spec.policy) || "manual".equals(spec.policy), "the policy must be 'background' or 'manual', not '" + spec.policy + "'");
        if (spec.updateUrl != null && !spec.updateUrl.isBlank()) {
            require(spec.updateUrl.startsWith("https://") || spec.updateUrl.startsWith("http://127.0.0.1") || spec.updateUrl.startsWith("http://localhost"),
                    "the update address must start with https:// (the stub refuses anything else): " + spec.updateUrl);
        }
        for (var part : spec.parts.entrySet()) {
            String name = part.getKey();
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            require(PART_NAME.matcher(name).matches() && !lower.equals("update") && !lower.equals("old") && !lower.equals("trash")
                    && !lower.startsWith("jpack") && !lower.equals(STUB_PART), "'" + name + "' cannot be used as a part name");
            require(Files.isDirectory(part.getValue()), "the folder of part '" + name + "' does not exist: " + part.getValue());
        }
        spec.entries.forEach((name, entry) -> {
            require(entry.mainClass != null || entry.mainModule != null, "entry '" + name + "' needs a main class or a main module");
            require(spec.parts.containsKey(firstSegment(entry.runtime)), "entry '" + name + "' uses the runtime '" + entry.runtime + "', which is not a part");
        });
    }

    private static String firstSegment(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void makeExecutable(Path file) throws IOException {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(file));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        }
    }

    /** Copies a folder with everything in it. Symbolic links become copies of what they point to, as in the zip. */
    public static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
