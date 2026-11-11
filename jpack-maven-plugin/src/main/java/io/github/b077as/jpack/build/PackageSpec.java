package io.github.b077as.jpack.build;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Everything {@link Packager} needs to build one release for one platform. */
public final class PackageSpec {

    /** The app's name as people see it. */
    public String name;
    public String version;
    /** File name of the program people start, without {@code .exe}. Default: the name without spaces. */
    public String executable;
    /** Such as {@code windows-x64}. Default: the platform of this build. */
    public String platform = Platforms.current();

    /** The stub binary for that platform. */
    public Path stub;
    /** Whether the stub is itself a part, so that a release can replace it. */
    public boolean includeStub = true;

    /** Part name to the folder with its files. A part becomes a folder of that name in the install folder. */
    public final Map<String, Path> parts = new LinkedHashMap<>();
    public final Map<String, EntrySpec> entries = new LinkedHashMap<>();
    /** The entry the stub starts first. */
    public String start;

    /**
     * Address of the folder the release files are served from, such as
     * {@code https://github.com/owner/repo/releases/latest/download}. Without it the app is never updated.
     */
    public String updateUrl;
    /** {@code background} or {@code manual}. */
    public String policy = "background";
    /** With {@code background}: hours between two looks while the app stays open. */
    public Integer intervalHours;

    /**
     * The icon and names for the stub and the Java launcher (Windows only). Null leaves the programs as they are; with a
     * {@link Branding} the stub carries it and, when {@link #brandJava} is set, so does a renamed copy of the runtime's
     * launcher, which every entry without its own {@link EntrySpec#java} then uses.
     */
    public Branding branding;
    public boolean brandJava = true;

    /**
     * Also build an AppImage (Linux hosts and targets only) and publish it, with its block index, in the release. Needs
     * {@link #appImageTool}.
     */
    public boolean appImage;
    /** The {@code appimagetool} executable. */
    public Path appImageTool;
    /** A PNG for the AppImage's icon. */
    public Path appImageIcon;

    /** Also pack the install folder into a {@code .zip} (Windows) or {@code .tar.gz} (Linux) under {@code dist/}. */
    public boolean archive;

    /** Where {@code image/}, {@code release/} and {@code dist/} are written. */
    public Path output;

    /** Told about things that went wrong but did not stop the build, such as a program that could not be branded. */
    public Consumer<String> warn = message -> System.err.println("jpack: " + message);

    /** The name of the program people start, without extension. */
    public String executableName() {
        return executable != null && !executable.isBlank() ? executable : name.replaceAll("\\s+", "");
    }
}
