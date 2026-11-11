package io.github.b077as.jpack.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the stub starts one program of the app: the app itself, or a launcher that runs before it. All paths are relative
 * to the install folder and written with {@code /}.
 */
public final class EntrySpec {

    /** Folder of the Java runtime. */
    public String runtime = "runtime";
    /** The Java executable, when it is not the runtime's own (a renamed copy, for the name in the task manager). */
    public String java;
    /** Options for the JVM. {@code ${home}} stands for the install folder. */
    public final List<String> jvmArgs = new ArrayList<>();
    /** Jars or folders; {@code app/*} means every jar in {@code app}. */
    public final List<String> classpath = new ArrayList<>();
    public final List<String> modulePath = new ArrayList<>();
    public String mainClass;
    public String mainModule;
    /** Arguments for the program, placed before the ones the user started the app with. */
    public final List<String> args = new ArrayList<>();
    public final Map<String, String> env = new LinkedHashMap<>();
    /** Windows: start with a console window ({@code java.exe}). */
    public boolean console;

    Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("runtime", runtime);
        if (java != null) {
            json.put("java", java);
        }
        if (!jvmArgs.isEmpty()) {
            json.put("jvmArgs", jvmArgs);
        }
        if (!classpath.isEmpty()) {
            json.put("classpath", classpath);
        }
        if (!modulePath.isEmpty()) {
            json.put("modulePath", modulePath);
        }
        if (mainModule != null) {
            json.put("mainModule", mainModule);
        }
        if (mainClass != null) {
            json.put("mainClass", mainClass);
        }
        if (!args.isEmpty()) {
            json.put("args", args);
        }
        if (!env.isEmpty()) {
            json.put("env", env);
        }
        if (console) {
            json.put("console", true);
        }
        return json;
    }
}
