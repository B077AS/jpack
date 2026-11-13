package io.github.b077as.jpack.build;

import io.github.b077as.jpack.internal.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The settings in {@code jpack.json}, the project file that keeps a pom short. Every field is optional: what the file
 * leaves out comes from the pom, and then from defaults. Unknown names are refused, so a misspelled setting is an error
 * rather than silently ignored.
 *
 * <pre>{@code
 * {
 *   "name": "Komm",
 *   "mainClass": "komm.Launcher",
 *   "updateUrl": "https://github.com/owner/repo/releases/latest/download",
 *   "jvmArgs": ["-Xmx2g", "-Dversion=${project.version}"],
 *   "branding": { "icon": "src/jpack/icon.ico", "company": "Komm" },
 *   "launcher": { "dependency": "komm:komm-launcher", "mainClass": "komm.launcher.Launcher", "jvmArgs": ["-Xmx128m"] },
 *   "parts": [ { "name": "plugins", "directory": "src/plugins" } ]
 * }
 * }</pre>
 *
 * <p>{@code ${name}} in a text is replaced by the build's property of that name ({@code project.version},
 * {@code project.basedir}, the pom's properties, system properties); anything else, such as the stub's {@code ${home}},
 * stays as written. Relative paths are relative to the project folder.
 */
public final class AppConfig {

    public String name;
    public String version;
    public String executable;
    public String mainClass;
    public String mainModule;
    public String updateUrl;
    public String policy;
    public Integer intervalHours;
    /** False: the stub does not write {@code jpack.log}. Default true. */
    public Boolean log;
    public List<String> jvmArgs;
    public List<String> args;
    public Map<String, String> env;
    public Boolean console;
    public Boolean includeDependencies;
    public Boolean includeStub;
    public List<String> rawParts;
    public String jar;
    /** A ready runtime folder; without it one is built with jlink. */
    public String runtime;
    /** Modules for the runtime, replacing what is detected. */
    public String modules;
    /** Modules added to the ones detected. */
    public String addModules;
    public String jmods;
    public List<String> jlinkOptions;
    public Boolean archive;
    public Boolean appImage;
    /** File name of the AppImage (and, with {@code .blocks}, of its block index). Default: {@code <executable>-<version>-<arch>.AppImage}. */
    public String appImageName;
    public Boolean brandJava;
    public Branding branding;
    public LauncherSettings launcher;
    public List<PartSettings> parts;

    /** The launcher: a program that runs before the app, shows the update and hands over to the app. */
    public static final class LauncherSettings {
        /** A jar file. */
        public String jar;
        /** {@code groupId:artifactId} of a dependency of the project to use instead of a file. */
        public String dependency;
        public String mainClass;
        public List<String> jvmArgs;
    }

    public static final class PartSettings {
        public String name;
        public String directory;
    }

    private static final Set<String> TOP = Set.of("name", "version", "executable", "mainClass", "mainModule", "updateUrl", "policy",
            "intervalHours", "log", "jvmArgs", "args", "env", "console", "includeDependencies", "includeStub", "rawParts", "jar", "runtime", "modules",
            "addModules", "jmods", "jlinkOptions", "archive", "appImage", "appImageName", "brandJava", "branding", "launcher", "parts");
    private static final Set<String> BRANDING = Set.of("icon", "description", "company", "copyright", "product");
    private static final Set<String> LAUNCHER = Set.of("jar", "dependency", "mainClass", "jvmArgs");
    private static final Set<String> PART = Set.of("name", "directory");

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z0-9_.\\-]+)}");

    /**
     * Reads a project file.
     *
     * @param baseDir   what relative paths are relative to
     * @param variables the build's properties; null when a name is unknown
     */
    public static AppConfig read(Path file, Path baseDir, Function<String, String> variables) throws IOException {
        Map<String, Object> json;
        try {
            json = Json.parseObject(Files.readString(file));
        } catch (IllegalArgumentException e) {
            throw new IOException(file + " is not valid: " + e.getMessage(), e);
        }
        try {
            return from(json, baseDir, variables);
        } catch (IllegalArgumentException e) {
            throw new IOException(file + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    static AppConfig from(Map<String, Object> json, Path baseDir, Function<String, String> variables) {
        Reader in = new Reader(variables);
        in.known(json, TOP, "the file");
        AppConfig c = new AppConfig();
        c.name = in.string(json, "name");
        c.version = in.string(json, "version");
        c.executable = in.string(json, "executable");
        c.mainClass = in.string(json, "mainClass");
        c.mainModule = in.string(json, "mainModule");
        c.updateUrl = in.string(json, "updateUrl");
        c.policy = in.string(json, "policy");
        c.intervalHours = in.integer(json, "intervalHours");
        c.log = in.bool(json, "log");
        c.jvmArgs = in.strings(json, "jvmArgs");
        c.args = in.strings(json, "args");
        c.env = in.stringMap(json, "env");
        c.console = in.bool(json, "console");
        c.includeDependencies = in.bool(json, "includeDependencies");
        c.includeStub = in.bool(json, "includeStub");
        c.rawParts = in.strings(json, "rawParts");
        c.jar = path(in.string(json, "jar"), baseDir);
        c.runtime = path(in.string(json, "runtime"), baseDir);
        c.modules = in.string(json, "modules");
        c.addModules = in.string(json, "addModules");
        c.jmods = path(in.string(json, "jmods"), baseDir);
        c.jlinkOptions = in.strings(json, "jlinkOptions");
        c.archive = in.bool(json, "archive");
        c.appImage = in.bool(json, "appImage");
        c.appImageName = AppImages.checkName(in.string(json, "appImageName"));
        c.brandJava = in.bool(json, "brandJava");
        if (json.get("branding") != null) {
            Map<String, Object> b = in.object(json, "branding");
            in.known(b, BRANDING, "branding");
            c.branding = new Branding();
            String icon = path(in.string(b, "icon"), baseDir);
            c.branding.icon = icon == null ? null : Path.of(icon);
            c.branding.description = in.string(b, "description");
            c.branding.company = in.string(b, "company");
            c.branding.copyright = in.string(b, "copyright");
            c.branding.product = in.string(b, "product");
        }
        if (json.get("launcher") != null) {
            Map<String, Object> l = in.object(json, "launcher");
            in.known(l, LAUNCHER, "launcher");
            c.launcher = new LauncherSettings();
            c.launcher.jar = path(in.string(l, "jar"), baseDir);
            c.launcher.dependency = in.string(l, "dependency");
            c.launcher.mainClass = in.string(l, "mainClass");
            c.launcher.jvmArgs = in.strings(l, "jvmArgs");
        }
        if (json.get("parts") != null) {
            if (!(json.get("parts") instanceof List)) {
                throw new IllegalArgumentException("'parts' must be a list");
            }
            c.parts = new ArrayList<>();
            for (Object item : (List<Object>) json.get("parts")) {
                if (!(item instanceof Map)) {
                    throw new IllegalArgumentException("every entry of 'parts' must be an object");
                }
                Map<String, Object> p = (Map<String, Object>) item;
                in.known(p, PART, "a part");
                PartSettings part = new PartSettings();
                part.name = in.string(p, "name");
                part.directory = path(in.string(p, "directory"), baseDir);
                if (part.name == null || part.directory == null) {
                    throw new IllegalArgumentException("a part needs a 'name' and a 'directory'");
                }
                c.parts.add(part);
            }
        }
        return c;
    }

    private static String path(String value, Path baseDir) {
        return value == null ? null : baseDir.resolve(value).normalize().toString();
    }

    /** Reads typed values out of the JSON, replacing {@code ${...}} on the way. */
    private static final class Reader {
        private final Function<String, String> variables;

        Reader(Function<String, String> variables) {
            this.variables = variables;
        }

        void known(Map<String, Object> object, Set<String> allowed, String where) {
            for (String key : object.keySet()) {
                if (!allowed.contains(key)) {
                    throw new IllegalArgumentException("unknown setting '" + key + "' in " + where + " (known: " + String.join(", ", new java.util.TreeSet<>(allowed)) + ")");
                }
            }
        }

        String fill(String text) {
            Matcher m = VARIABLE.matcher(text);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String value = variables.apply(m.group(1));
                m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
            }
            m.appendTail(out);
            return out.toString();
        }

        String string(Map<String, Object> o, String key) {
            Object v = o.get(key);
            if (v == null) {
                return null;
            }
            if (!(v instanceof String)) {
                throw new IllegalArgumentException("'" + key + "' must be a text");
            }
            return fill((String) v);
        }

        Boolean bool(Map<String, Object> o, String key) {
            Object v = o.get(key);
            if (v == null) {
                return null;
            }
            if (!(v instanceof Boolean)) {
                throw new IllegalArgumentException("'" + key + "' must be true or false");
            }
            return (Boolean) v;
        }

        Integer integer(Map<String, Object> o, String key) {
            Object v = o.get(key);
            if (v == null) {
                return null;
            }
            if (!(v instanceof Long)) {
                throw new IllegalArgumentException("'" + key + "' must be a whole number");
            }
            return Math.toIntExact((Long) v);
        }

        @SuppressWarnings("unchecked")
        List<String> strings(Map<String, Object> o, String key) {
            Object v = o.get(key);
            if (v == null) {
                return null;
            }
            if (!(v instanceof List)) {
                throw new IllegalArgumentException("'" + key + "' must be a list of texts");
            }
            List<String> list = new ArrayList<>();
            for (Object item : (List<Object>) v) {
                if (!(item instanceof String)) {
                    throw new IllegalArgumentException("'" + key + "' must be a list of texts");
                }
                list.add(fill((String) item));
            }
            return list;
        }

        @SuppressWarnings("unchecked")
        Map<String, String> stringMap(Map<String, Object> o, String key) {
            Object v = o.get(key);
            if (v == null) {
                return null;
            }
            if (!(v instanceof Map)) {
                throw new IllegalArgumentException("'" + key + "' must be an object of texts");
            }
            Map<String, String> map = new LinkedHashMap<>();
            for (var entry : ((Map<String, Object>) v).entrySet()) {
                if (!(entry.getValue() instanceof String)) {
                    throw new IllegalArgumentException("'" + key + "' must be an object of texts");
                }
                map.put(entry.getKey(), fill((String) entry.getValue()));
            }
            return map;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> object(Map<String, Object> o, String key) {
            if (!(o.get(key) instanceof Map)) {
                throw new IllegalArgumentException("'" + key + "' must be an object");
            }
            return (Map<String, Object>) o.get(key);
        }
    }
}
