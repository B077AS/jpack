package io.github.b077as.jpack.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchivesTest {

    @TempDir
    Path dir;

    private Path image() throws IOException {
        Path image = Files.createDirectories(dir.resolve("image"));
        Files.writeString(image.resolve("jpack.json"), "{}");
        Files.createDirectories(image.resolve("runtime/bin"));
        Files.writeString(image.resolve("runtime/bin/java"), "java");
        Files.writeString(image.resolve("MyApp"), "stub");
        Files.writeString(image.resolve("app.jar"), "x".repeat(1000));
        if (Files.getFileAttributeView(image, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            // On a system with Unix permissions the archive follows what the files say.
            Files.setPosixFilePermissions(image.resolve("runtime/bin/java"), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        // A path longer than a classic tar header can hold.
        Path deep = image.resolve("lib/" + "d".repeat(60) + "/" + "e".repeat(60));
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("file.txt"), "deep");
        return image;
    }

    /** What a tar reader would see: name to (mode, content). */
    private record Entry(int mode, byte[] content) {
    }

    private static Map<String, Entry> readTar(Path tgz) throws IOException {
        Map<String, Entry> entries = new LinkedHashMap<>();
        try (InputStream in = new GZIPInputStream(Files.newInputStream(tgz))) {
            String longName = null;
            while (true) {
                byte[] h = in.readNBytes(512);
                if (h.length < 512 || h[0] == 0) {
                    break;
                }
                long sum = 0;
                for (int i = 0; i < 512; i++) {
                    sum += (i >= 148 && i < 156) ? ' ' : h[i] & 0xFF;
                }
                assertEquals(sum, Long.parseLong(new String(h, 148, 6, StandardCharsets.US_ASCII), 8), "checksum");
                String name = new String(h, 0, indexOfZero(h, 0, 100), StandardCharsets.UTF_8);
                int mode = Integer.parseInt(new String(h, 100, 7, StandardCharsets.US_ASCII), 8);
                long size = Long.parseLong(new String(h, 124, 11, StandardCharsets.US_ASCII), 8);
                byte[] content = in.readNBytes((int) size);
                in.skipNBytes((512 - size % 512) % 512);
                if (h[156] == 'L') {
                    longName = new String(content, 0, content.length - 1, StandardCharsets.UTF_8);
                } else {
                    entries.put(longName != null ? longName : name, new Entry(mode, content));
                    longName = null;
                }
            }
        }
        return entries;
    }

    private static int indexOfZero(byte[] bytes, int from, int max) {
        int i = from;
        while (i < from + max && bytes[i] != 0) {
            i++;
        }
        return i - from;
    }

    @Test
    void aLinuxArchiveKeepsTheExecutableBitAndLongNames() throws Exception {
        Path tgz = Archives.create(image(), "MyApp", dir.resolve("dist/MyApp-1.0-linux-x64"), "linux-x64", Set.of("MyApp"));

        assertTrue(tgz.getFileName().toString().endsWith(".tar.gz"));
        Map<String, Entry> entries = readTar(tgz);
        assertEquals(0755, entries.get("MyApp/MyApp").mode(), "the stub is a program");
        assertEquals(0755, entries.get("MyApp/runtime/bin/java").mode(), "so is everything in a bin folder");
        assertEquals(0644, entries.get("MyApp/app.jar").mode());
        assertEquals(0644, entries.get("MyApp/jpack.json").mode());
        String deep = "MyApp/lib/" + "d".repeat(60) + "/" + "e".repeat(60) + "/file.txt";
        assertArrayEquals("deep".getBytes(StandardCharsets.UTF_8), entries.get(deep).content());
        assertEquals(1000, entries.get("MyApp/app.jar").content().length);
    }

    @Test
    void aWindowsArchiveIsAZipWithTheAppFolderInside() throws Exception {
        Path zip = Archives.create(image(), "MyApp", dir.resolve("dist/MyApp-1.0-windows-x64"), "windows-x64", Set.of());

        assertTrue(zip.getFileName().toString().endsWith(".zip"));
        try (ZipFile file = new ZipFile(zip.toFile())) {
            assertTrue(file.getEntry("MyApp/jpack.json") != null);
            assertTrue(file.getEntry("MyApp/runtime/bin/java") != null);
            assertEquals(1000, file.getInputStream(file.getEntry("MyApp/app.jar")).readAllBytes().length);
        }
    }

    @Test
    void theSameFolderAlwaysGivesTheSameArchive() throws Exception {
        Path image = image();
        Path first = Archives.create(image, "MyApp", dir.resolve("one/a"), "linux-x64", Set.of("MyApp"));
        Path second = Archives.create(image, "MyApp", dir.resolve("two/a"), "linux-x64", Set.of("MyApp"));
        // gzip puts no date in this output, so the files are equal byte for byte.
        assertArrayEquals(new GZIPInputStream(new ByteArrayInputStream(Files.readAllBytes(first))).readAllBytes(),
                new GZIPInputStream(new ByteArrayInputStream(Files.readAllBytes(second))).readAllBytes());
    }
}
