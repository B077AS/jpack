package io.github.b077as.jpack.tests;

import io.github.b077as.jpack.build.Packager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Install locations that differ from a plain folder: odd names, symlinks, a tar.gz, a read-only folder (Linux only). */
class LinuxInstallsTest {

    @BeforeAll
    static void linuxWithAStub() {
        Assumptions.assumeTrue(Lab.PLATFORM.startsWith("linux"), "Linux installs");
        Assumptions.assumeTrue(Lab.stub() != null, "no stub built");
    }

    @Test
    void aFolderWithSpacesAndNonAsciiLettersUpdatesThroughALauncher() throws Exception {
        try (Lab lab = new Lab("my apps/Ünï côde ñ 日本")) {
            lab.install(lab.build(new Lab.Recipe("1.0.0").withLauncher()));
            lab.publish(lab.build(new Lab.Recipe("2.0.0").withLauncher()));

            Lab.Run run = lab.run("one arg", "ünï");

            assertEquals(0, run.exitCode(), run.log());
            List<String> lines = lab.lines();
            assertEquals(2, lines.size(), lines + "\n" + run.log());
            assertTrue(lines.get(1).startsWith("app 2.0.0"), lines.get(1));
            assertTrue(lines.get(1).contains("args=one arg,ünï"), lines.get(1));
            assertEquals("2.0.0", lab.installedVersion());
        }
    }

    @Test
    void anInstallStartedThroughASymlinkUpdatesTheRealFolder() throws Exception {
        try (Lab lab = new Lab("symlinked")) {
            lab.install(lab.build(new Lab.Recipe("1.0.0").withLauncher()));
            lab.publish(lab.build(new Lab.Recipe("2.0.0").withLauncher()));
            Path link = Files.createSymbolicLink(lab.dir.resolve("link"), lab.install);
            Path exeLink = lab.dir.resolve("demo-link");
            Files.createSymbolicLink(exeLink, link.resolve(lab.stubExe().getFileName()));

            for (Path start : List.of(exeLink)) {
                ProcessBuilder builder = lab.command(java.util.Map.of());
                builder.command().set(0, start.toString());
                Lab.Run run = lab.finish(builder.start(), builder);
                assertEquals(0, run.exitCode(), run.log());
            }

            List<String> lines = lab.lines();
            assertEquals(2, lines.size(), lines.toString());
            assertTrue(lines.get(1).startsWith("app 2.0.0"), lines.get(1));
            assertEquals("2.0.0", lab.installedVersion());
        }
    }

    @Test
    void aTarGzUnpackedSomewhereRunsKeepsItsExecutableBitsAndUpdates() throws Exception {
        try (Lab lab = new Lab("targz")) {
            Packager.Packaged one = lab.build(new Lab.Recipe("1.0.0").withLauncher().archive());
            lab.publish(lab.build(new Lab.Recipe("2.0.0").withLauncher()));
            assertTrue(one.archive().toString().endsWith(".tar.gz"), String.valueOf(one.archive()));

            Path target = Files.createDirectories(lab.dir.resolve("unpacked here/Demo app"));
            Process tar = new ProcessBuilder("tar", "xzf", one.archive().toString(), "-C", target.toString()).inheritIO().start();
            assertEquals(0, tar.waitFor());
            Path root;
            try (Stream<Path> top = Files.list(target)) {
                List<Path> entries = top.toList();
                root = entries.size() == 1 && Files.isDirectory(entries.get(0)) && !Files.exists(target.resolve("jpack.json")) ? entries.get(0) : target;
            }
            assertTrue(Files.isExecutable(root.resolve("Demo")), "stub is executable");
            assertTrue(Files.isExecutable(root.resolve("runtime/bin/java")), "runtime/bin/java is executable");

            ProcessBuilder builder = lab.command(java.util.Map.of());
            builder.command().set(0, root.resolve("Demo").toString());
            Lab.Run run = lab.finish(builder.start(), builder);

            assertEquals(0, run.exitCode(), run.log());
            List<String> lines = lab.lines();
            assertEquals(2, lines.size(), lines + "\n" + run.log());
            assertTrue(lines.get(1).startsWith("app 2.0.0"), lines.get(1));
            assertTrue(Files.isExecutable(root.resolve("runtime/bin/java")), "still executable after the swap");
            assertTrue(Files.isExecutable(root.resolve("Demo")), "stub still executable");
        }
    }

    @Test
    void aReadOnlyInstallStillStartsAndNothingIsLeftHalfWritten() throws Exception {
        try (Lab lab = new Lab("readonly")) {
            lab.install(lab.build(new Lab.Recipe("1.0.0").withLauncher()));
            lab.publish(lab.build(new Lab.Recipe("2.0.0").withLauncher()));
            setWritable(lab.install, false);
            try {
                Lab.Run run = lab.run();

                assertEquals(0, run.exitCode(), run.log());
                List<String> lines = lab.lines();
                assertTrue(lines.size() >= 1 && lines.stream().anyMatch(l -> l.startsWith("app 1.0.0")), lines + "\n" + run.log());
                assertEquals("1.0.0", lab.installedVersion());
                assertFalse(Files.exists(lab.install.resolve("update")), "no update/ folder");
                assertFalse(Files.exists(lab.install.resolve("old")), "no old/ folder");
            } finally {
                setWritable(lab.install, true);
            }
        }
    }

    private static void setWritable(Path root, boolean writable) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk.filter(Files::isDirectory)::iterator) {
                Set<PosixFilePermission> perms = new java.util.HashSet<>(Files.getPosixFilePermissions(path));
                if (writable) {
                    perms.add(PosixFilePermission.OWNER_WRITE);
                } else {
                    perms.remove(PosixFilePermission.OWNER_WRITE);
                }
                Files.setPosixFilePermissions(path, perms);
            }
        }
    }
}
