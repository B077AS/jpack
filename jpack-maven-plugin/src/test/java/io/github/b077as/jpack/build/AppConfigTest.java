package io.github.b077as.jpack.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {

    @TempDir
    Path dir;

    private AppConfig read(String json) throws IOException {
        Path file = dir.resolve("jpack.json");
        Files.writeString(file, json);
        Map<String, String> variables = Map.of("project.version", "1.2.3", "custom", "value");
        return AppConfig.read(file, dir, variables::get);
    }

    @Test
    void everySettingIsReadAndTheRestStaysUnset() throws Exception {
        AppConfig c = read("""
                {
                  "name": "Komm", "mainClass": "komm.Launcher", "updateUrl": "https://x/y", "policy": "manual", "intervalHours": 6,
                  "jvmArgs": ["-Xmx2g", "-Dv=${project.version}", "-Dhome=${home}"], "env": {"A": "${custom}"},
                  "console": true, "modules": "java.base", "jar": "target/app.jar",
                  "branding": {"icon": "src/jpack/icon.ico", "company": "Acme"},
                  "launcher": {"dependency": "g:a", "mainClass": "l.Main", "jvmArgs": ["-Xmx128m"]},
                  "parts": [{"name": "plugins", "directory": "plugins"}]
                }
                """);

        assertEquals("Komm", c.name);
        assertEquals(6, c.intervalHours);
        assertEquals(List.of("-Xmx2g", "-Dv=1.2.3", "-Dhome=${home}"), c.jvmArgs, "the stub's ${home} is left for the stub");
        assertEquals(Map.of("A", "value"), c.env);
        assertEquals(true, c.console);
        assertEquals(dir.resolve("target/app.jar").normalize().toString(), c.jar);
        assertEquals(dir.resolve("src/jpack/icon.ico").normalize(), c.branding.icon);
        assertEquals("Acme", c.branding.company);
        assertEquals("g:a", c.launcher.dependency);
        assertEquals(List.of("-Xmx128m"), c.launcher.jvmArgs);
        assertEquals("plugins", c.parts.get(0).name);
        assertEquals(dir.resolve("plugins").normalize().toString(), c.parts.get(0).directory);
        assertNull(c.version);
        assertNull(c.args);
        assertNull(c.archive);
    }

    @Test
    void anEmptyFileIsFine() throws Exception {
        assertNull(read("{}").name);
    }

    @Test
    void aMisspelledSettingIsAnErrorThatNamesIt() {
        IOException e = assertThrows(IOException.class, () -> read("{\"mainClas\": \"x\"}"));
        assertTrue(e.getMessage().contains("mainClas") && e.getMessage().contains("mainClass"), e.getMessage());
        e = assertThrows(IOException.class, () -> read("{\"launcher\": {\"mainclass\": \"x\"}}"));
        assertTrue(e.getMessage().contains("mainclass"), e.getMessage());
    }

    @Test
    void aValueOfTheWrongTypeIsAnError() {
        assertThrows(IOException.class, () -> read("{\"jvmArgs\": \"-Xmx1g\"}"));
        assertThrows(IOException.class, () -> read("{\"console\": \"yes\"}"));
        assertThrows(IOException.class, () -> read("{\"intervalHours\": 1.5}"));
        assertThrows(IOException.class, () -> read("{\"parts\": [{\"name\": \"x\"}]}"));
        assertThrows(IOException.class, () -> read("{not json"));
    }

    @Test
    void anUnknownVariableIsLeftAsWritten() throws Exception {
        assertEquals(List.of("${nothing.like.this}"), read("{\"jvmArgs\": [\"${nothing.like.this}\"]}").jvmArgs);
    }
}
