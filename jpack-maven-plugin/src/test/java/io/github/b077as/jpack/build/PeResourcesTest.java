package io.github.b077as.jpack.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeResourcesTest {

    @TempDir
    Path dir;

    @Test
    void aResourceTreeSurvivesBeingWrittenAndReadBack() throws Exception {
        PeResources.Node root = new PeResources.Node();
        root.child(24).child(1).child(1033).data = "manifest".getBytes(StandardCharsets.UTF_8);
        root.child(3).child(2).child(1033).data = new byte[] {1, 2, 3, 4, 5};
        root.child(3).child(1).child(1033).data = new byte[] {9};
        PeResources.Node named = new PeResources.Node();
        named.data = "named".getBytes(StandardCharsets.UTF_8);
        root.child(10).named.computeIfAbsent("MAINICON", key -> new PeResources.Node()).ids.put(0, named);
        root.child(10).named.get("MAINICON").codePage = 0;

        byte[] bytes = PeResources.serialize(root, 0x3000);
        PeResources.Node back = PeResources.parse(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), 0, 0x3000, 0);

        assertEquals("manifest", new String(back.ids.get(24).ids.get(1).ids.get(1033).data, StandardCharsets.UTF_8));
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, back.ids.get(3).ids.get(2).ids.get(1033).data);
        assertArrayEquals(new byte[] {9}, back.ids.get(3).ids.get(1).ids.get(1033).data);
        assertEquals("named", new String(back.ids.get(10).named.get("MAINICON").ids.get(0).data, StandardCharsets.UTF_8));
        assertEquals(0, bytes.length % 4);
    }

    @Test
    void versionNumbersAreTakenFromTheStartOfEachPart() {
        assertArrayEquals(new long[] {1, 2, 3, 0}, PeResources.parseVersion("1.2.3"));
        assertArrayEquals(new long[] {2, 0, 0, 0}, PeResources.parseVersion("2.0.0-beta.1"));
        assertArrayEquals(new long[] {0, 0, 0, 0}, PeResources.parseVersion("snapshot"));
        assertArrayEquals(new long[] {1, 2, 3, 4}, PeResources.parseVersion("1.2.3.4.5"));
        assertArrayEquals(new long[] {65535, 0, 0, 0}, PeResources.parseVersion("999999"));
        assertArrayEquals(new long[] {0, 0, 0, 0}, PeResources.parseVersion(null));
    }

    @Test
    void theVersionBlockHasTheFixedNumbersAndTheTextsInUtf16() {
        Map<String, String> strings = new LinkedHashMap<>();
        strings.put("FileDescription", "Komm");
        byte[] block = PeResources.versionInfo("1.2.3", strings);
        ByteBuffer b = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(block.length, b.getShort(0) & 0xFFFF, "the length field covers the whole block");
        assertEquals(52, b.getShort(2), "the fixed block is 52 bytes");
        // "VS_VERSION_INFO\0" is 32 bytes after 6 header bytes, then padding to 4, then the signature.
        assertEquals(0xFEEF04BD, b.getInt(40));
        assertEquals(1 << 16 | 2, b.getInt(48));
        assertEquals(3 << 16, b.getInt(52));
        String text = new String(block, StandardCharsets.UTF_16LE);
        assertTrue(text.contains("FileDescription"), text);
    }

    @Test
    void anIcoFileThatIsNotOneIsRefused() {
        PeResources.Node root = new PeResources.Node();
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> PeResources.setIcon(root, new byte[] {1, 2, 3}));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> PeResources.setIcon(root, new byte[10]));
    }

    @Test
    void somethingThatIsNotAProgramIsRefused() {
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> PeResources.edit(new byte[200], root -> { }));
    }

    @Test
    void aRealProgramCanBeBrandedStillRunsAndShowsItsNewNameAndIcon() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"), "needs a Windows program");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Path branded = dir.resolve("Demo.exe");
        Files.copy(java, branded);
        Path icon = dir.resolve("icon.ico");
        Files.write(icon, ico(32, 0xFFFF0000));

        Branding branding = new Branding();
        branding.icon = icon;
        branding.company = "Acme";
        branding.apply(branded, "Demo App", "1.2.3", "Demo.exe");

        Process run = new ProcessBuilder(branded.toString(), "-version").redirectErrorStream(true).start();
        String output = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(run.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, run.exitValue(), output);
        assertTrue(output.contains("version"), output);

        // Windows itself reads the new properties and picks up the new icon.
        String shown = powershell(
                "$i = [System.Diagnostics.FileVersionInfo]::GetVersionInfo('" + branded + "');"
                        + "Add-Type -AssemblyName System.Drawing;"
                        + "$c = [System.Drawing.Icon]::ExtractAssociatedIcon('" + branded + "').ToBitmap().GetPixel(16,16);"
                        + "'{0}|{1}|{2}|{3}|{4}|{5}' -f $i.FileDescription,$i.ProductName,$i.CompanyName,$i.FileVersion,$i.OriginalFilename,('{0},{1},{2}' -f $c.R,$c.G,$c.B)");
        assertEquals("Demo App|Demo App|Acme|1.2.3|Demo.exe|255,0,0", shown.trim());

        // Branding again (a new version of the app) replaces the earlier resources and works the same.
        Branding again = new Branding();
        again.apply(branded, "Demo App", "2.0.0", "Demo.exe");
        String second = powershell("[System.Diagnostics.FileVersionInfo]::GetVersionInfo('" + branded + "').FileVersion");
        assertEquals("2.0.0", second.trim());
        Process runAgain = new ProcessBuilder(branded.toString(), "-version").redirectErrorStream(true).start();
        runAgain.getInputStream().readAllBytes();
        assertEquals(0, runAgain.waitFor());
    }

    @Test
    void aProgramBrandedWithoutAVersionStillShowsItsName() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"), "needs a Windows program");
        Path copy = dir.resolve("NoVersion.exe");
        Files.copy(Path.of(System.getProperty("java.home"), "bin", "javaw.exe"), copy);
        new Branding().apply(copy, "Demo App", "", "NoVersion.exe");
        assertEquals("Demo App", powershell("[System.Diagnostics.FileVersionInfo]::GetVersionInfo('" + copy + "').FileDescription").trim());
    }

    @Test
    void theSameInputAlwaysGivesTheSameBytes() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"), "needs a Windows program");
        Path java = Path.of(System.getProperty("java.home"), "bin", "javaw.exe");
        Path a = dir.resolve("a.exe");
        Path b = dir.resolve("b.exe");
        Files.copy(java, a);
        Files.copy(java, b);
        new Branding().apply(a, "Demo", "1.0.0", "Demo.exe");
        new Branding().apply(b, "Demo", "1.0.0", "Demo.exe");
        assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b));
        assertNotNull(PeResources.edit(Files.readAllBytes(a), root -> assertTrue(root.ids.containsKey(PeResources.RT_VERSION))));
    }

    private static String powershell(String script) throws Exception {
        Process process = new ProcessBuilder("powershell", "-NoProfile", "-Command", script).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        return out;
    }

    /** An {@code .ico} holding one PNG image of a single colour. */
    static byte[] ico(int size, int argb) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                image.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        ByteBuffer ico = ByteBuffer.allocate(22 + png.size()).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0).putShort((short) 1).putShort((short) 1);
        ico.put((byte) size).put((byte) size).put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
        ico.putInt(png.size()).putInt(22).put(png.toByteArray());
        return ico.array();
    }
}
