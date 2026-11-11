package io.github.b077as.jpack.build;

import java.util.Locale;

/** Names for what a release is built for, such as {@code windows-x64} or {@code linux-x64}. */
public final class Platforms {

    private Platforms() {
    }

    /** The platform this JVM runs on. */
    public static String current() {
        return os(System.getProperty("os.name", "")) + "-" + arch(System.getProperty("os.arch", ""));
    }

    static String os(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("win")) {
            return "windows";
        }
        if (lower.contains("mac") || lower.contains("darwin")) {
            return "macos";
        }
        return "linux";
    }

    static String arch(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("aarch64") || lower.equals("arm64")) {
            return "arm64";
        }
        if (lower.equals("amd64") || lower.equals("x86_64") || lower.equals("x64")) {
            return "x64";
        }
        return lower;
    }

    public static boolean isWindows(String platform) {
        return platform.startsWith("windows");
    }

    /** The file name of the stub inside the plugin and inside the stub part. */
    public static String stubFileName(String platform) {
        return isWindows(platform) ? "jpack-stub.exe" : "jpack-stub";
    }

    /** What an executable called {@code name} is called on that platform. */
    public static String executable(String platform, String name) {
        return isWindows(platform) ? name + ".exe" : name;
    }
}
