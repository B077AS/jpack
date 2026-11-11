package io.github.b077as.jpack.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RawPartTest {

    @TempDir
    Path dir;

    @Test
    void aPartOfOneFileIsPublishedAsThatFileAndNotZipped() throws Exception {
        Path folder = Files.createDirectories(dir.resolve("app"));
        byte[] jar = "not really a jar".getBytes(StandardCharsets.UTF_8);
        Files.write(folder.resolve("komm-1.0.0.jar"), jar);
        Path release = Files.createDirectories(dir.resolve("release"));

        Map<String, Object> part = Packager.rawPart("app", folder, release);

        assertEquals("komm-1.0.0.jar", part.get("file"));
        assertEquals(Zips.sha256(folder.resolve("komm-1.0.0.jar")), part.get("sha256"));
        assertEquals((long) jar.length, part.get("size"));
        assertEquals(false, part.get("packed"));
        assertEquals(new String(jar, StandardCharsets.UTF_8), Files.readString(release.resolve("komm-1.0.0.jar")),
                "the release holds the file itself, byte for byte");
    }

    @Test
    void aPartWithSeveralFilesCannotBePlain() throws Exception {
        Path folder = Files.createDirectories(dir.resolve("runtime"));
        Files.writeString(folder.resolve("a"), "a");
        Files.writeString(folder.resolve("b"), "b");
        Path release = Files.createDirectories(dir.resolve("release"));

        assertThrows(IllegalArgumentException.class, () -> Packager.rawPart("runtime", folder, release));
    }
}
