package io.github.b077as.jpack.build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes the zip of a part so that the same files always give the same bytes.
 *
 * <p>That matters because a part is recognised by the hash of its zip: a runtime that did not change must hash the same
 * in every release, or every update would download it again. So entries are sorted, carry one fixed date and nothing
 * that depends on the machine or the moment of the build.
 */
final class Zips {

    /** The file inside a part that lists its programs, because a zip written by Java cannot carry Unix permissions. */
    static final String EXEC_LIST = ".jpack-exec";

    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2000, 1, 1, 0, 0, 0);

    private Zips() {
    }

    /** Zips the files of {@code folder} into {@code target}. Symbolic links are stored as the files they point to. */
    static void zip(Path folder, Path target, String platform) throws IOException {
        TreeMap<String, Path> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(path)) {
                    files.put(folder.relativize(path).toString().replace('\\', '/'), path);
                }
            }
        }
        files.remove(EXEC_LIST);
        List<String> programs = Platforms.isWindows(platform) ? List.of() : programs(files);

        try (OutputStream out = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            if (!programs.isEmpty()) {
                put(zip, EXEC_LIST, (String.join("\n", programs) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            for (var file : files.entrySet()) {
                ZipEntry entry = entry(file.getKey());
                zip.putNextEntry(entry);
                try (InputStream in = Files.newInputStream(file.getValue())) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(entry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static ZipEntry entry(String name) {
        ZipEntry entry = new ZipEntry(name);
        // setTimeLocal, not setTime: the latter converts through the build machine's time zone.
        entry.setTimeLocal(FIXED_TIME);
        return entry;
    }

    /**
     * The files that must be executable after unpacking. On a system with Unix permissions that is what the files say;
     * elsewhere (a Linux runtime unpacked on Windows) it is what a Java runtime is known to need.
     */
    /** The files of a folder that must be executable on Linux, as paths relative to it. */
    static java.util.Set<String> executables(Path folder) throws IOException {
        TreeMap<String, Path> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(path)) {
                    files.put(folder.relativize(path).toString().replace('\\', '/'), path);
                }
            }
        }
        return new java.util.TreeSet<>(programs(files));
    }

    private static List<String> programs(TreeMap<String, Path> files) {
        List<String> programs = new ArrayList<>();
        for (var file : files.entrySet()) {
            String name = file.getKey();
            Path path = file.getValue();
            boolean executable;
            if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
                executable = permissions(path).contains(PosixFilePermission.OWNER_EXECUTE);
            } else {
                String last = name.substring(name.lastIndexOf('/') + 1);
                executable = name.startsWith("bin/") || name.contains("/bin/") || last.equals("jspawnhelper") || last.equals("jexec")
                        || last.equals("stub");
            }
            if (executable) {
                programs.add(name);
            }
        }
        return programs;
    }

    private static Set<PosixFilePermission> permissions(Path path) {
        try {
            return Files.getPosixFilePermissions(path);
        } catch (IOException e) {
            return Set.of();
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
