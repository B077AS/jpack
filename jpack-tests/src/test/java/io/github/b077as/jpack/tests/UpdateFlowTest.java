package io.github.b077as.jpack.tests;

import io.github.b077as.jpack.build.Packager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real stub, real runtimes, a real web server: installs, updates, failures and rollbacks, start to finish. */
class UpdateFlowTest {

    @BeforeAll
    static void needAStub() {
        Assumptions.assumeTrue(Lab.stub() != null, "no stub built for " + Lab.PLATFORM + " (run build-stub.ps1 / build-stub.sh)");
    }

    private static boolean has(List<String> lines, String... parts) {
        return lines.stream().anyMatch(line -> List.of(parts).stream().allMatch(line::contains));
    }

    @Test
    void anInstalledAppStartsAndPassesItsArgumentsOn() throws Exception {
        try (Lab lab = new Lab("starts")) {
            lab.install(lab.build(new Lab.Recipe("1.0.0").withoutUpdates()));

            Lab.Run run = lab.run("file.txt", "second");

            assertEquals(0, run.exitCode(), run.log());
            assertEquals(List.of("app 1.0.0 logging=false args=file.txt,second installed=1.0.0"), lab.lines(), run.log());
        }
    }

    @Test
    void aBrandedAppRunsThroughItsOwnRenamedJavaLauncherAndKeepsDoingSoAfterAnUpdate() throws Exception {
        Assumptions.assumeTrue(Lab.PLATFORM.startsWith("windows"), "branding is for Windows programs");
        try (Lab lab = new Lab("branded")) {
            Packager.Packaged one = lab.build(new Lab.Recipe("1.0.0").branded().behavior("update"));
            lab.install(one);
            Packager.Packaged two = lab.build(new Lab.Recipe("2.0.0").branded());
            lab.publish(two);

            assertTrue(Files.isRegularFile(lab.install.resolve("runtime/bin/Demo.exe")), "the renamed launcher is in the runtime");
            assertTrue(Files.readString(lab.install.resolve("jpack.json")).contains("runtime/bin/Demo.exe"));
            assertEquals(Lab.partFile(one, "runtime"), Lab.partFile(two, "runtime"), "branding is the same bytes every build, so the runtime is not downloaded again");

            Lab.Run first = lab.run();
            assertEquals(0, first.exitCode(), first.log());
            assertTrue(has(lab.lines(), "app 1.0.0", "download=2.0.0"), lab.lines() + " " + first.log());

            Lab.Run second = lab.run();
            assertEquals(0, second.exitCode(), second.log());
            assertTrue(has(lab.lines(), "app 2.0.0"), lab.lines() + " " + second.log());
            assertTrue(Files.isRegularFile(lab.install.resolve("runtime/bin/Demo.exe")));
        }
    }

    @Test
    void anAppImageUpdatesItselfFetchingOnlyTheBlocksThatChangedAndTheAppThatStartsIsTheNewOne() throws Exception {
        Assumptions.assumeTrue(Lab.PLATFORM.startsWith("linux"), "AppImages are for Linux");
        Assumptions.assumeTrue(Lab.appImageTool() != null, "needs appimagetool (APPIMAGETOOL or on the PATH)");
        try (Lab lab = new Lab("appimage")) {
            Packager.Packaged one = lab.build(new Lab.Recipe("1.0.0").withLauncher().appImage());
            Packager.Packaged two = lab.build(new Lab.Recipe("2.0.0").withLauncher().appImage());
            assertTrue(one.appImage() != null && two.appImage() != null, "the AppImages were built");
            lab.installAppImage(one);
            lab.publish(two);
            long size = Files.size(two.appImage());

            Lab.Run run = lab.run();

            assertEquals(0, run.exitCode(), run.log());
            List<String> lines = lab.lines();
            assertEquals(2, lines.size(), lines + " " + run.log());
            assertTrue(has(lines.subList(0, 1), "launcher 1.0.0", "available=true", "version=2.0.0", "final=complete"), lines.get(0));
            assertEquals("app 2.0.0 logging=false args= installed=2.0.0", lines.get(1), "the new AppImage runs the app");

            assertEquals(Lab.sha256(two.appImage()), Lab.sha256(lab.appImagePath), "the AppImage file is now the new release");
            assertTrue(lab.appImagePath.toFile().canExecute());
            String name = two.appImage().getFileName().toString();
            List<String> ranged = lab.requests.stream().filter(r -> r.startsWith(name + " bytes=")).toList();
            assertTrue(lab.requests.stream().noneMatch(r -> r.equals(name)), "the whole file is never fetched: " + lab.requests);
            assertTrue(!ranged.isEmpty(), "the changed blocks are fetched with range requests");
            long fetched = ranged.stream().mapToLong(r -> {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("bytes=([0-9]+)-([0-9]+)").matcher(r);
                return m.find() ? Long.parseLong(m.group(2)) - Long.parseLong(m.group(1)) + 1 : 0;
            }).sum();
            assertTrue(fetched < size / 2, "fetched " + fetched + " of " + size + " bytes");
            assertTrue(Files.notExists(lab.dir.resolve("App.AppImage.jpack-new")), "no half-built file is left");
        }
    }

    @Test
    void theExitCodeOfTheAppIsTheExitCodeOfTheStub() throws Exception {
        try (Lab lab = new Lab("exit-code")) {
            lab.install(lab.build(new Lab.Recipe("1.0.0").withoutUpdates().behavior("code7")));
            assertEquals(7, lab.run().exitCode());
        }
    }

    @Test
    void aLauncherDownloadsAnUpdateAndTheAppThatStartsIsTheNewOne() throws Exception {
        try (Lab lab = new Lab("launcher-flow")) {
            lab.install(lab.build(new Lab.Recipe("1.0.0").withLauncher()));
            lab.publish(lab.build(new Lab.Recipe("2.0.0").withLauncher()));

            Lab.Run run = lab.run();

            assertEquals(0, run.exitCode(), run.log());
            List<String> lines = lab.lines();
            assertEquals(2, lines.size(), lines + "\n" + run.log());
            assertTrue(has(lines.subList(0, 1), "launcher 1.0.0", "entry=launcher", "available=true", "version=2.0.0", "events=some", "final=complete", "backwards=0"), lines.get(0));
            assertEquals("app 2.0.0 logging=false args= installed=2.0.0", lines.get(1));
            assertEquals("2.0.0", lab.installedVersion());
            // The replaced files are deleted once the new app has started.
            assertFalse(Files.exists(lab.install.resolve("old")), "old/ is gone");
            assertFalse(Files.exists(lab.install.resolve("update")), "update/ is gone");
        }
    }

    @Test
    void onlyThePartsThatChangedAreDownloaded() throws Exception {
        try (Lab lab = new Lab("changed-only")) {
            Packager.Packaged one = lab.build(new Lab.Recipe("1.0.0").withLauncher());
            Packager.Packaged two = lab.build(new Lab.Recipe("2.0.0").withLauncher());
            lab.install(one);
            lab.publish(two);

            lab.run();

            List<String> zips = lab.requests.stream().filter(request -> request.endsWith(".zip") || request.contains(".zip ")).toList();
            assertEquals(Lab.partFile(one, "runtime"), Lab.partFile(two, "runtime"), "the runtime did not change, so it hashes the same");
            assertEquals(Lab.partFile(one, "launcher").replaceAll("-.*", ""), "launcher");
            assertTrue(zips.stream().noneMatch(request -> request.startsWith("runtime-")), "the runtime is not downloaded again: " + zips);
            assertTrue(zips.contains(Lab.partFile(two, "app")), "the app is: " + zips);
        }
    }

    @Test
    void aNewJavaRuntimeIsSwappedInTogetherWithTheApp() throws Exception {
        try (Lab lab = new Lab("new-runtime")) {
            lab.install(lab.build(new Lab.Recipe("1.0.0").withLauncher()));
            lab.publish(lab.build(new Lab.Recipe("2.0.0").withLauncher().runtime(Lab.Runtimes_.withLogging())));

            Lab.Run run = lab.run();

            assertEquals(0, run.exitCode(), run.log());
            List<String> lines = lab.lines();
            // The launcher ran on the old Java (no java.logging); the app that follows runs on the one that was downloaded.
            assertEquals("app 2.0.0 logging=true args= installed=2.0.0", lines.get(lines.size() - 1), lines + "\n" + run.log());
            assertTrue(has(lines.subList(0, 1), "launcher 1.0.0"), lines.toString());
        }
    }

    @Test
    void anAppThatDownloadsItselfAndRestartsComesBackUpdated() throws Exception {
        try (Lab lab = new Lab("in-app")) {
            lab.install(lab.build(new Lab.Recipe("1.0.0").behavior("restart-when-updated")));
            lab.publish(lab.build(new Lab.Recipe("2.0.0").behavior("exit")));

            Lab.Run run = lab.run();

            assertEquals(0, run.exitCode(), run.log());
            List<String> lines = lab.lines();
            assertEquals(2, lines.size(), lines + "\n" + run.log());
            assertTrue(has(lines.subList(0, 1), "app 1.0.0", "download=2.0.0", "restarting"), lines.get(0));
            assertEquals("app 2.0.0 logging=false args= installed=2.0.0", lines.get(1));
        }
    }

    @Test
    void whenTheInstalledVersionIsTheNewestNothingIsDownloaded() throws Exception {
        try (Lab lab = new Lab("up-to-date")) {
            Packager.Packaged one = lab.build(new Lab.Recipe("1.0.0").behavior("update"));
            lab.install(one);
            lab.publish(one);

            lab.run();

            assertEquals(List.of("app 1.0.0 logging=false args= installed=1.0.0 download=none"), lab.lines());
            assertTrue(lab.requests.stream().noneMatch(request -> request.contains(".zip")), lab.requests.toString());
        }
    }

    @Test
    void aDownloadThatDoesNotMatchItsHashIsRefusedAndTheInstalledAppKeepsWorking() throws Exception {
        try (Lab lab = new Lab("corrupt")) {
            lab.install(lab.build(new Lab.Recipe("1.0.0").behavior("update")));
            Packager.Packaged two = lab.build(new Lab.Recipe("2.0.0"));
            lab.publish(two);
            lab.corrupted.add(Lab.partFile(two, "app"));

            Lab.Run run = lab.run();

            assertEquals(0, run.exitCode(), run.log());
            List<String> lines = lab.lines();
            assertTrue(has(lines, "app 1.0.0", "download=error:", "hash"), lines + "\n" + run.log());
            assertEquals("1.0.0", lab.installedVersion());
            assertFalse(Files.exists(lab.install.resolve("update").resolve("READY")), "nothing is left half-applied");
        }
    }

    @Test
    void aDownloadThatIsCutOffContinuesWhereItStopped() throws Exception {
        try (Lab lab = new Lab("resume")) {
            lab.install(lab.build(new Lab.Recipe("1.0.0").behavior("update")));
            Packager.Packaged two = lab.build(new Lab.Recipe("2.0.0"));
            lab.publish(two);
            lab.cutOffOnce.add(Lab.partFile(two, "app"));

            Lab.Run run = lab.run();

            assertEquals(0, run.exitCode(), run.log());
            assertTrue(has(lab.lines(), "app 1.0.0", "download=2.0.0"), lab.lines() + "\n" + run.log());
            String app = Lab.partFile(two, "app");
            assertTrue(lab.requests.stream().anyMatch(request -> request.startsWith(app + " bytes=")), "the second try asks only for the rest: " + lab.requests);
        }
    }

    @Test
    void aNewVersionThatCannotStartIsTakenOutAndNotOfferedAgain() throws Exception {
        try (Lab lab = new Lab("rollback")) {
            lab.install(lab.build(new Lab.Recipe("1.0.0").withLauncher()));
            lab.publish(lab.build(new Lab.Recipe("2.0.0").withLauncher().behavior("crash")));

            Lab.Run run = lab.run();

            // The new app dies at once without ever reaching the stub, so the old version is put back and started.
            List<String> lines = lab.lines();
            assertEquals("1.0.0", lab.installedVersion(), lines + "\n" + run.log());
            assertTrue(run.log().contains("went back to version 1.0.0"), run.log());
            assertFalse(Files.exists(lab.install.resolve("old")));

            // The next run sees the same published version and leaves it alone.
            lab.requests.clear();
            lab.run();
            assertEquals("1.0.0", lab.installedVersion());
            assertTrue(lab.requests.stream().noneMatch(request -> request.contains(".zip")), "not downloaded again: " + lab.requests);
        }
    }

    @Test
    void thePolicyBackgroundDownloadsWithoutBeingAsked() throws Exception {
        try (Lab lab = new Lab("background")) {
            lab.install(lab.build(new Lab.Recipe("1.0.0").policy("background").behavior("await-ready")));
            lab.publish(lab.build(new Lab.Recipe("2.0.0").policy("background")));

            Lab.Run run = lab.run();
            assertEquals(0, run.exitCode(), run.log());
            assertTrue(has(lab.lines(), "app 1.0.0", "background=true", "ready=2.0.0"), lab.lines() + "\n" + run.log());

            // Nothing was swapped while the app ran; the next start is the new version.
            assertEquals("2.0.0", lab.installedVersion(), "swapped in when the app closed");
            lab.run();
            assertTrue(has(lab.lines(), "app 2.0.0"), lab.lines().toString());
        }
    }

    @Test
    void aSecondWindowKeepsTheSwapWaitingUntilEveryWindowIsClosed() throws Exception {
        try (Lab lab = new Lab("two-windows")) {
            lab.install(lab.build(new Lab.Recipe("1.0.0").behavior("hold")));
            lab.publish(lab.build(new Lab.Recipe("2.0.0").behavior("exit")));
            var gate = lab.dir.resolve("gate-1");
            // Window one stays open. A second start downloads (via a launcher-less app asking) and must not swap under it.
            ProcessBuilder first = lab.command(Map.of("SAMPLE_GATE", gate.toString()));
            Process open = first.start();
            try {
                waitFor(() -> lab.lines().stream().anyMatch(line -> line.contains("holding")));

                // Put a downloaded release in place by hand, as a background download would have.
                io.github.b077as.jpack.tests.Lab.Run second = lab.run(Map.of("SAMPLE_BEHAVIOR", "update"));
                assertEquals(0, second.exitCode(), second.log());
                assertEquals("1.0.0", lab.installedVersion(), "window one is still open, so nothing is swapped: " + second.log());
                assertTrue(second.log().contains("another window of the app is open"), second.log());
            } finally {
                Files.writeString(gate, "go");
                lab.finish(open, first);
            }
            // With every window closed the swap happens.
            assertEquals("2.0.0", lab.installedVersion(), "swapped in when the last window closed");
        }
    }

    @Test
    void aMissingRuntimeIsReportedInTheLogAndTheStubFails() throws Exception {
        try (Lab lab = new Lab("broken")) {
            lab.install(lab.build(new Lab.Recipe("1.0.0").withoutUpdates()));
            io.github.b077as.jpack.build.Packager.deleteTree(lab.install.resolve("runtime"));

            Lab.Run run = lab.run();

            assertEquals(126, run.exitCode(), run.log());
            assertTrue(Files.readString(lab.install.resolve("jpack.log")).contains("runtime is missing"));
        }
    }

    private static void waitFor(ThrowingCondition condition) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.test()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting");
    }

    @FunctionalInterface
    private interface ThrowingCondition {
        boolean test() throws IOException;
    }
}
