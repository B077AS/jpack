package io.github.b077as.jpack.build;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs the install folder for people to download and unpack: a {@code .zip} for Windows, a {@code .tar.gz} (which, unlike
 * a zip written by Java, carries the executable bit) for Linux. The files are sorted and carry one fixed date, so the same
 * folder always gives the same archive.
 */
public final class Archives {

    private static final long FIXED_SECONDS = 946_684_800L; // 2000-01-01
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2000, 1, 1, 0, 0, 0);

    private Archives() {
    }

    /**
     * @param image      the install folder
     * @param root       the folder name inside the archive that everything is unpacked into
     * @param target     the archive to write, without its extension
     * @param executable the paths (relative to the image) of programs the folder holds besides what is detected
     * @return the archive that was written
     */
    public static Path create(Path image, String root, Path target, String platform, Set<String> executable) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        TreeMap<String, Path> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(image)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(path)) {
                    files.put(image.relativize(path).toString().replace('\\', '/'), path);
                }
            }
        }
        if (Platforms.isWindows(platform)) {
            Path zip = target.resolveSibling(target.getFileName() + ".zip");
            try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                zos.setLevel(Deflater.BEST_COMPRESSION);
                for (var file : files.entrySet()) {
                    ZipEntry entry = new ZipEntry(root + "/" + file.getKey());
                    entry.setTimeLocal(FIXED_TIME);
                    zos.putNextEntry(entry);
                    Files.copy(file.getValue(), zos);
                    zos.closeEntry();
                }
            }
            return zip;
        }
        Set<String> programs = new java.util.TreeSet<>(Zips.executables(image));
        programs.addAll(executable);
        Path tgz = target.resolveSibling(target.getFileName() + ".tar.gz");
        try (OutputStream out = Files.newOutputStream(tgz);
             GZIPOutputStream gz = new GZIPOutputStream(out) {
                 {
                     def.setLevel(Deflater.BEST_COMPRESSION);
                 }
             }) {
            for (var file : files.entrySet()) {
                tarFile(gz, root + "/" + file.getKey(), file.getValue(), programs.contains(file.getKey()) ? 0755 : 0644);
            }
            gz.write(new byte[1024]); // end of archive
        }
        return tgz;
    }

    private static void tarFile(OutputStream out, String name, Path file, int mode) throws IOException {
        long size = Files.size(file);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 99) {
            // GNU long name: a pseudo entry holding the name, ahead of the real one.
            byte[] data = new byte[nameBytes.length + 1];
            System.arraycopy(nameBytes, 0, data, 0, nameBytes.length);
            out.write(header("././@LongLink", data.length, 0644, 'L'));
            out.write(data);
            out.write(new byte[(int) ((512 - data.length % 512) % 512)]);
        }
        out.write(header(name, size, mode, '0'));
        Files.copy(file, out);
        out.write(new byte[(int) ((512 - size % 512) % 512)]);
    }

    private static byte[] header(String name, long size, int mode, char type) {
        byte[] h = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, h, 0, Math.min(nameBytes.length, 99));
        octal(h, 100, 8, mode);
        octal(h, 108, 8, 0);
        octal(h, 116, 8, 0);
        octal(h, 124, 12, size);
        octal(h, 136, 12, FIXED_SECONDS);
        java.util.Arrays.fill(h, 148, 156, (byte) ' '); // the checksum counts these as spaces
        h[156] = (byte) type;
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 6);
        h[263] = '0';
        h[264] = '0';
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xFF;
        }
        octal(h, 148, 7, sum);
        h[155] = ' ';
        return h;
    }

    /** Writes {@code value} as zero-padded octal digits followed by a NUL, filling {@code width} bytes. */
    private static void octal(byte[] header, int at, int width, long value) {
        String digits = Long.toOctalString(value);
        if (digits.length() > width - 1) {
            throw new IllegalArgumentException("value too large for a tar header: " + value);
        }
        String text = "0".repeat(width - 1 - digits.length()) + digits;
        System.arraycopy(text.getBytes(StandardCharsets.US_ASCII), 0, header, at, text.length());
        header[at + width - 1] = 0;
    }
}
