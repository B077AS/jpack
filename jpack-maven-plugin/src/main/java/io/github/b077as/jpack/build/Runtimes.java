package io.github.b077as.jpack.build;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.spi.ToolProvider;

/** Builds the Java runtime that ships with an app, using the {@code jdeps} and {@code jlink} of the JDK running the build. */
public final class Runtimes {

    private Runtimes() {
    }

    /**
     * Works out which modules of the JDK the given jars use.
     *
     * @param jars      the app's jars (the first is analysed, the rest is its class path)
     * @param classpath further jars to resolve against
     * @return the module names, comma separated, as {@code jlink --add-modules} wants them
     */
    public static String detectModules(List<Path> jars, List<Path> classpath) throws IOException {
        ToolProvider jdeps = ToolProvider.findFirst("jdeps").orElseThrow(() -> new IOException("this JDK has no jdeps: name the runtime's modules yourself"));
        List<String> args = new ArrayList<>(List.of("--print-module-deps", "--ignore-missing-deps", "--multi-release", String.valueOf(Runtime.version().feature())));
        if (!classpath.isEmpty()) {
            args.add("--class-path");
            args.add(String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList()));
        }
        jars.forEach(jar -> args.add(jar.toString()));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = jdeps.run(new PrintWriter(out), new PrintWriter(err), args.toArray(String[]::new));
        String modules = out.toString().lines().map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("Warning")).reduce((first, second) -> second).orElse("");
        if (code != 0 || modules.isEmpty() || modules.contains(" ")) {
            throw new IOException("jdeps could not work out the modules the app needs (name them yourself):\n" + out + err);
        }
        return modules;
    }

    /**
     * Builds a runtime with only the given modules, without debug information, headers and man pages.
     *
     * @param modules comma separated module names
     * @param jmods   the {@code jmods} folder of the JDK for the platform the runtime is for, or null for this JDK
     * @param extra   further {@code jlink} options
     */
    public static void jlink(String modules, Path jmods, List<String> extra, Path output) throws IOException {
        ToolProvider jlink = ToolProvider.findFirst("jlink").orElseThrow(() -> new IOException("this JDK has no jlink: point the build at a ready runtime"));
        Packager.deleteTree(output);
        Files.createDirectories(output.getParent());
        List<String> args = new ArrayList<>(List.of("--add-modules", modules, "--output", output.toString(), "--strip-debug", "--no-header-files", "--no-man-pages"));
        args.add(Runtime.version().feature() >= 21 ? "--compress=zip-6" : "--compress=2");
        if (jmods != null) {
            args.add("--module-path");
            args.add(jmods.toString());
        }
        args.addAll(extra);
        StringWriter out = new StringWriter();
        int code = jlink.run(new PrintWriter(out), new PrintWriter(out), args.toArray(String[]::new));
        if (code != 0) {
            throw new IOException("jlink failed:\n" + out);
        }
    }
}
