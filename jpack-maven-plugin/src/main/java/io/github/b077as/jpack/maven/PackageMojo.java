package io.github.b077as.jpack.maven;

import io.github.b077as.jpack.build.AppConfig;
import io.github.b077as.jpack.build.Branding;
import io.github.b077as.jpack.build.EntrySpec;
import io.github.b077as.jpack.build.PackageSpec;
import io.github.b077as.jpack.build.Packager;
import io.github.b077as.jpack.build.Platforms;
import io.github.b077as.jpack.build.Runtimes;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Builds the app's install folder and the files its updates are served from.
 *
 * <p>After {@code mvn package} there are, in {@code target/jpack}:
 * <ul>
 *   <li>{@code image/<platform>/}: what belongs on the user's disk. Wrap it in an installer, or use the archive.</li>
 *   <li>{@code release/}: the manifest and the part zips. Upload them to where {@code updateUrl} points.</li>
 *   <li>{@code dist/}: the image as a {@code .zip} (Windows) or {@code .tar.gz} (Linux), to hand to people directly.</li>
 * </ul>
 *
 * <p>Settings come from the pom, from {@code src/jpack/jpack.json} (see {@code AppConfig}) and from defaults, in that order
 * of priority. An app needs the pom to say little more than its main class and update address.
 */
@Mojo(name = "package", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class PackageMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /** The project file. Without one, only the pom and the defaults apply. */
    @Parameter(property = "jpack.configFile", defaultValue = "${project.basedir}/src/jpack/jpack.json")
    private File configFile;

    /** The app's name as people see it. Default: the project's name. */
    @Parameter(property = "jpack.name")
    private String name;

    /** Default: the project's version. */
    @Parameter(property = "jpack.version")
    private String version;

    /** File name of the program people start, without {@code .exe}. Default: the name without spaces. */
    @Parameter(property = "jpack.executable")
    private String executable;

    /** What to build for, such as {@code windows-x64} or {@code linux-x64}. Default: the machine running the build. */
    @Parameter(property = "jpack.platform")
    private String platform;

    /** The class with the app's {@code main} method. */
    @Parameter(property = "jpack.mainClass")
    private String mainClass;

    /** For a modular app: the module of the main class. The app's jars then go on the module path. */
    @Parameter(property = "jpack.mainModule")
    private String mainModule;

    /** Options for the app's JVM. {@code ${home}} stands for the install folder. */
    @Parameter
    private List<String> jvmArgs;

    /** Arguments for the app, placed before the ones the user started it with. */
    @Parameter
    private List<String> args;

    /** Environment variables for the app. */
    @Parameter
    private Map<String, String> env;

    /** Windows: start the app with a console window. */
    @Parameter(property = "jpack.console")
    private Boolean console;

    /** The Java executable to start the entries with, relative to the install folder. Default: set by branding. */
    @Parameter(property = "jpack.javaExecutable")
    private String javaExecutable;

    /** The app's jar. Default: the project's own jar, which should be a fat jar unless {@code includeDependencies} is set. */
    @Parameter(property = "jpack.jar")
    private File jar;

    /** Copy the project's runtime dependencies next to the jar and start with all of them on the class path. */
    @Parameter(property = "jpack.includeDependencies")
    private Boolean includeDependencies;

    /** A ready Java runtime (a jlink image or a JRE folder) to ship. Without it, one is built with jlink. */
    @Parameter(property = "jpack.runtime")
    private File runtime;

    /** Modules for the runtime jlink builds, comma separated, replacing what jdeps detects for the app and the launcher. */
    @Parameter(property = "jpack.modules")
    private String modules;

    /** Modules to add to the detected ones. */
    @Parameter(property = "jpack.addModules")
    private String addModules;

    /** The {@code jmods} folder of another JDK, to build the runtime for a platform other than the build machine's. */
    @Parameter(property = "jpack.jmods")
    private File jmods;

    /** Further options for jlink. */
    @Parameter
    private List<String> jlinkOptions;

    /** Further parts: folders that are installed next to the app and updated on their own. */
    @Parameter
    private List<PartConfig> parts;

    /** A launcher: a program that runs before the app, shows the update's progress, and then hands over to the app. */
    @Parameter
    private LauncherConfig launcher;

    /** The icon and names of the programs (Windows). Without an icon, {@code src/jpack/icon.ico} is used if it exists. */
    @Parameter
    private BrandingConfig branding;

    /** Build an AppImage too (Linux). Default: true when appimagetool is found. */
    @Parameter(property = "jpack.appImage")
    private Boolean appImage;

    /** The appimagetool executable; default: the APPIMAGETOOL variable, then appimagetool on the PATH. */
    @Parameter(property = "jpack.appImageTool")
    private File appImageTool;

    /** Give the Java launcher the app's name and icon too (a renamed copy of {@code javaw.exe}). Default: true. */
    @Parameter(property = "jpack.brandJava")
    private Boolean brandJava;

    /** Also write a {@code .zip} / {@code .tar.gz} of the install folder to {@code dist/}. Default: true. */
    @Parameter(property = "jpack.archive")
    private Boolean archive;

    /**
     * The address of the folder the release files are served from, such as
     * {@code https://github.com/owner/repo/releases/latest/download}. Without it the app is never updated.
     */
    @Parameter(property = "jpack.updateUrl")
    private String updateUrl;

    /**
     * {@code background}: the stub looks for updates while the app runs and uses them at the next start.
     * {@code manual}: only when the app or its launcher asks. Default: {@code manual} with a launcher, else {@code background}.
     */
    @Parameter(property = "jpack.policy")
    private String policy;

    /** With {@code background}: hours between two looks while the app stays open (default 4). */
    @Parameter(property = "jpack.intervalHours")
    private Integer intervalHours;

    /** A stub binary to use in place of the one that comes with the plugin. */
    @Parameter(property = "jpack.stub")
    private File stub;

    /** Whether releases can replace the stub itself. Default: true. */
    @Parameter(property = "jpack.includeStub")
    private Boolean includeStub;

    @Parameter(property = "jpack.outputDirectory", defaultValue = "${project.build.directory}/jpack")
    private File outputDirectory;

    @Parameter(property = "jpack.skip", defaultValue = "false")
    private boolean skip;

    /** A folder that becomes a part. */
    public static class PartConfig {
        private String name;
        private File directory;
    }

    /** The launcher's jar (or the dependency that provides it) and how to start it. */
    public static class LauncherConfig {
        /** A self-contained (fat) jar: it is the only thing on the launcher's class path. */
        private File jar;
        /** {@code groupId:artifactId} of a dependency of this project, instead of a file path. */
        private String dependency;
        private String mainClass;
        private List<String> jvmArgs;
    }

    public static class BrandingConfig {
        private File icon;
        private String description;
        private String company;
        private String copyright;
        private String product;
    }

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("jpack is skipped");
            return;
        }
        try {
            AppConfig file = readConfigFile();
            Path output = outputDirectory.toPath();
            Path work = output.resolve("work");
            Packager.deleteTree(work);
            Files.createDirectories(work);

            PackageSpec spec = new PackageSpec();
            spec.name = first(name, file.name, project.getName());
            spec.version = first(version, file.version, project.getVersion());
            spec.executable = first(executable, file.executable, null);
            spec.platform = first(platform, null, Platforms.current()).trim();
            spec.output = output;
            spec.updateUrl = first(updateUrl, file.updateUrl, null);
            LauncherSettings launcherSettings = launcherSettings(file);
            spec.policy = first(policy, file.policy, launcherSettings != null ? "manual" : "background");
            spec.intervalHours = first(intervalHours, file.intervalHours, null);
            spec.includeStub = first(includeStub, file.includeStub, true);
            spec.archive = first(archive, file.archive, true);
            Path tool = appImageTool();
            spec.appImage = first(appImage, file.appImage, tool != null);
            spec.appImageTool = tool;
            Path pngIcon = project.getBasedir().toPath().resolve("src/jpack/icon.png");
            spec.appImageIcon = Files.isRegularFile(pngIcon) ? pngIcon : null;
            spec.brandJava = first(brandJava, file.brandJava, true);
            spec.branding = branding(file);
            spec.warn = message -> getLog().warn("jpack: " + message);
            File stubFile = stub != null ? stub : null;
            spec.stub = stubFile != null ? stubFile.toPath() : bundledStub(spec.platform, work);

            String main = first(mainClass, file.mainClass, null);
            String mainMod = first(mainModule, file.mainModule, null);
            if (main == null && mainMod == null) {
                throw new MojoExecutionException("jpack needs to know what to start: set mainClass in the pom or in " + configFile.getName() + ".");
            }
            boolean withDependencies = first(includeDependencies, file.includeDependencies, false);
            File appJarFile = jar != null ? jar : file.jar != null ? new File(file.jar)
                    : new File(project.getBuild().getDirectory(), project.getBuild().getFinalName() + ".jar");
            Path launcherJar = launcherSettings == null ? null : launcherJar(launcherSettings);

            List<Path> appJars = stageApp(work.resolve("app"), appJarFile, withDependencies || mainMod != null, launcherSettings);
            spec.parts.put("runtime", runtimeFolder(work, appJars, launcherJar, file));
            spec.parts.put("app", work.resolve("app"));

            EntrySpec app = new EntrySpec();
            app.mainClass = main;
            app.mainModule = mainMod;
            app.jvmArgs.addAll(first(jvmArgs, file.jvmArgs, List.of()));
            app.args.addAll(first(args, file.args, List.of()));
            app.console = first(console, file.console, false);
            app.java = javaExecutable;
            app.env.putAll(first(env, file.env, Map.of()));
            if (mainMod != null) {
                app.modulePath.add("app");
            } else if (withDependencies) {
                app.classpath.add("app/*");
            } else {
                app.classpath.add("app/" + appJarFile.getName());
            }
            spec.entries.put("app", app);
            spec.start = "app";

            if (launcherSettings != null) {
                Path folder = work.resolve("launcher");
                Files.createDirectories(folder);
                Files.copy(launcherJar, folder.resolve(launcherJar.getFileName()));
                spec.parts.put("launcher", folder);
                EntrySpec entry = new EntrySpec();
                entry.mainClass = launcherSettings.mainClass;
                if (entry.mainClass == null) {
                    throw new MojoExecutionException("jpack: the launcher needs a mainClass.");
                }
                entry.jvmArgs.addAll(launcherSettings.jvmArgs);
                entry.classpath.add("launcher/" + launcherJar.getFileName());
                entry.java = javaExecutable;
                spec.entries.put("launcher", entry);
                spec.start = "launcher";
            }
            for (PartConfig part : parts != null ? parts : List.<PartConfig>of()) {
                spec.parts.put(part.name, part.directory.toPath());
            }
            if (file.parts != null) {
                for (AppConfig.PartSettings part : file.parts) {
                    spec.parts.putIfAbsent(part.name, Path.of(part.directory));
                }
            }

            Packager.Packaged packaged = new Packager().run(spec);
            getLog().info("jpack: install folder  " + packaged.image());
            getLog().info("jpack: release files   " + packaged.release() + "  (upload all of them)");
            if (packaged.appImage() != null) {
                getLog().info("jpack: AppImage        " + packaged.appImage());
            }
            if (packaged.archive() != null) {
                getLog().info("jpack: archive         " + packaged.archive());
            }
            if (spec.updateUrl == null || spec.updateUrl.isBlank()) {
                getLog().warn("jpack: no updateUrl is set, so this build of " + spec.name + " will never look for updates.");
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new MojoExecutionException("jpack: " + e.getMessage(), e);
        }
    }

    /** appimagetool, from the setting, the APPIMAGETOOL variable or the PATH; null when there is none. */
    private Path appImageTool() {
        if (appImageTool != null) {
            return appImageTool.toPath();
        }
        String fromEnv = System.getenv("APPIMAGETOOL");
        if (fromEnv != null && Files.isRegularFile(Path.of(fromEnv))) {
            return Path.of(fromEnv);
        }
        String path = System.getenv("PATH");
        for (String dir : path == null ? new String[0] : path.split(File.pathSeparator)) {
            for (String name : new String[] {"appimagetool", "appimagetool-x86_64.AppImage"}) {
                Path candidate = Path.of(dir).resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** The launcher after merging the pom and the file; null when there is none. */
    private record LauncherSettings(File jar, String dependency, String mainClass, List<String> jvmArgs) {
    }

    private LauncherSettings launcherSettings(AppConfig file) {
        if (launcher == null && file.launcher == null) {
            return null;
        }
        LauncherConfig pom = launcher != null ? launcher : new LauncherConfig();
        AppConfig.LauncherSettings f = file.launcher != null ? file.launcher : new AppConfig.LauncherSettings();
        return new LauncherSettings(
                first(pom.jar, f.jar == null ? null : new File(f.jar), null),
                first(pom.dependency, f.dependency, null),
                first(pom.mainClass, f.mainClass, null),
                first(pom.jvmArgs, f.jvmArgs, List.of()));
    }

    private Path launcherJar(LauncherSettings settings) throws MojoExecutionException {
        File jarFile = settings.jar;
        if (jarFile == null && settings.dependency != null) {
            for (Artifact artifact : project.getArtifacts()) {
                if ((artifact.getGroupId() + ":" + artifact.getArtifactId()).equals(settings.dependency) && artifact.getFile() != null) {
                    jarFile = artifact.getFile();
                }
            }
            if (jarFile == null) {
                throw new MojoExecutionException("jpack: the launcher dependency " + settings.dependency
                        + " is not a dependency of this project (add it, with scope provided if the app should not use it).");
            }
        }
        requireFile(jarFile, "the launcher's jar");
        return jarFile.toPath();
    }

    private Branding branding(AppConfig file) {
        Branding result = new Branding();
        BrandingConfig pom = branding != null ? branding : new BrandingConfig();
        Branding f = file.branding != null ? file.branding : new Branding();
        Path conventional = project.getBasedir().toPath().resolve("src/jpack/icon.ico");
        result.icon = first(pom.icon == null ? null : pom.icon.toPath(), f.icon, Files.isRegularFile(conventional) ? conventional : null);
        result.description = first(pom.description, f.description, null);
        result.company = first(pom.company, f.company, null);
        result.copyright = first(pom.copyright, f.copyright, null);
        result.product = first(pom.product, f.product, null);
        return result;
    }

    private AppConfig readConfigFile() throws IOException {
        if (configFile == null || !configFile.isFile()) {
            return new AppConfig();
        }
        getLog().info("jpack: settings from " + configFile);
        return AppConfig.read(configFile.toPath(), project.getBasedir().toPath(), this::property);
    }

    /** What {@code ${name}} means in the project file. */
    private String property(String key) {
        switch (key) {
            case "project.version": return project.getVersion();
            case "project.groupId": return project.getGroupId();
            case "project.artifactId": return project.getArtifactId();
            case "project.name": return project.getName();
            case "project.basedir": return project.getBasedir().getAbsolutePath();
            case "project.build.directory": return project.getBuild().getDirectory();
            case "project.build.finalName": return project.getBuild().getFinalName();
            default:
                String value = session.getUserProperties().getProperty(key);
                if (value == null) {
                    value = project.getProperties().getProperty(key);
                }
                return value != null ? value : System.getProperty(key);
        }
    }

    /** Puts the app's jar, and its dependencies if asked, into the folder of the {@code app} part. */
    private List<Path> stageApp(Path folder, File appJar, boolean dependencies, LauncherSettings launcherSettings) throws IOException, MojoExecutionException {
        requireFile(appJar, "the app's jar");
        Files.createDirectories(folder);
        List<Path> jars = new ArrayList<>();
        jars.add(Files.copy(appJar.toPath(), folder.resolve(appJar.getName())));
        if (dependencies) {
            for (Artifact artifact : project.getArtifacts()) {
                File file = artifact.getFile();
                boolean runtimeScope = Artifact.SCOPE_COMPILE.equals(artifact.getScope()) || Artifact.SCOPE_RUNTIME.equals(artifact.getScope());
                boolean isLauncher = launcherSettings != null && launcherSettings.dependency != null
                        && (artifact.getGroupId() + ":" + artifact.getArtifactId()).equals(launcherSettings.dependency);
                if (runtimeScope && !isLauncher && file != null && file.isFile() && file.getName().endsWith(".jar")) {
                    jars.add(Files.copy(file.toPath(), folder.resolve(file.getName()), StandardCopyOption.REPLACE_EXISTING));
                }
            }
        }
        return jars;
    }

    private Path runtimeFolder(Path work, List<Path> appJars, Path launcherJar, AppConfig file) throws IOException, MojoExecutionException {
        File ready = runtime != null ? runtime : file.runtime != null ? new File(file.runtime) : null;
        if (ready != null) {
            if (!ready.isDirectory()) {
                throw new MojoExecutionException("jpack: the runtime folder does not exist: " + ready);
            }
            return ready.toPath();
        }
        String wanted = first(modules, file.modules, null);
        if (wanted == null || wanted.isBlank()) {
            // The runtime serves every entry, so it needs what the app and the launcher need together.
            TreeSet<String> all = new TreeSet<>();
            addAll(all, Runtimes.detectModules(appJars.subList(0, 1), appJars.subList(1, appJars.size())));
            if (launcherJar != null) {
                addAll(all, Runtimes.detectModules(List.of(launcherJar), List.of()));
            }
            wanted = String.join(",", all);
            getLog().info("jpack: the runtime gets the modules " + wanted + " (set modules to choose them yourself, addModules to add some)");
        }
        String extra = first(addModules, file.addModules, null);
        if (extra != null && !extra.isBlank()) {
            TreeSet<String> all = new TreeSet<>();
            addAll(all, wanted);
            addAll(all, extra);
            wanted = String.join(",", all);
        }
        Path folder = work.resolve("runtime");
        File jmodsFolder = jmods != null ? jmods : file.jmods != null ? new File(file.jmods) : null;
        Runtimes.jlink(wanted, jmodsFolder != null ? jmodsFolder.toPath() : null, first(jlinkOptions, file.jlinkOptions, List.of()), folder);
        return folder;
    }

    private static void addAll(TreeSet<String> set, String commaSeparated) {
        for (String module : commaSeparated.split("[,\\s]+")) {
            if (!module.isBlank()) {
                set.add(module);
            }
        }
    }

    /** The stub that travels inside the plugin, unpacked to a file. */
    private Path bundledStub(String platform, Path work) throws IOException, MojoExecutionException {
        String fileName = Platforms.stubFileName(platform);
        String resource = "/io/github/b077as/jpack/stubs/" + platform + "/" + fileName;
        try (InputStream in = PackageMojo.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new MojoExecutionException("jpack: this build of the plugin carries no stub for " + platform
                        + ". Build one (see the README) and point <stub> at it.");
            }
            Path file = work.resolve(fileName);
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            return file;
        }
    }

    private static void requireFile(File file, String what) throws MojoExecutionException {
        if (file == null || !file.isFile()) {
            throw new MojoExecutionException("jpack: " + what + " was not found: " + file);
        }
    }

    /** The pom's value if it set one, else the file's, else the default. */
    private static <T> T first(T pom, T file, T fallback) {
        return pom != null ? pom : file != null ? file : fallback;
    }
}
