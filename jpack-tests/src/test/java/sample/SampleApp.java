package sample;

import io.github.b077as.jpack.Jpack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The app the end-to-end tests install and update. It writes one line about itself to the file named by
 * {@code SAMPLE_OUT} and then does what its release (or the {@code SAMPLE_BEHAVIOR} variable) tells it to.
 */
public final class SampleApp {

    private SampleApp() {
    }

    public static void main(String[] args) throws Exception {
        String version = resource("/sample-version.txt");
        String behavior = System.getenv("SAMPLE_BEHAVIOR") != null ? System.getenv("SAMPLE_BEHAVIOR") : resource("/sample-behavior.txt");
        StringBuilder line = new StringBuilder("app " + version)
                .append(" logging=").append(ModuleLayer.boot().findModule("java.logging").isPresent())
                .append(" args=").append(String.join(",", args))
                .append(" installed=").append(System.getenv("JPACK_VERSION"));
        int code = 0;
        switch (behavior) {
            case "exit" -> {
            }
            case "crash" -> code = 3;
            case "code7" -> code = 7;
            case "update" -> {
                try (Jpack jpack = Jpack.connect()) {
                    line.append(" download=").append(download(jpack));
                }
            }
            case "restart-when-updated" -> {
                try (Jpack jpack = Jpack.connect()) {
                    String downloaded = download(jpack);
                    line.append(" download=").append(downloaded);
                    if (!downloaded.equals("none") && !downloaded.startsWith("error")) {
                        jpack.restartAfterExit();
                        line.append(" restarting");
                    }
                }
            }
            case "await-ready" -> {
                try (Jpack jpack = Jpack.connect()) {
                    CountDownLatch ready = new CountDownLatch(1);
                    jpack.onReady(downloaded -> ready.countDown());
                    boolean arrived = jpack.downloadedVersion().isPresent() || ready.await(120, TimeUnit.SECONDS);
                    line.append(" background=").append(jpack.updatesInBackground());
                    line.append(" ready=").append(arrived ? jpack.downloadedVersion().orElse("?") : "never");
                }
            }
            case "hold" -> {
                // Stays open until the test creates the gate file, like a window the user has not closed yet.
                Path gate = Path.of(System.getenv("SAMPLE_GATE"));
                write(line + " holding");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
                while (!Files.exists(gate) && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                line.append(" released");
            }
            default -> line.append(" unknown-behavior=").append(behavior);
        }
        write(line.toString());
        System.exit(code);
    }

    private static String download(Jpack jpack) throws InterruptedException {
        try {
            Optional<String> version = jpack.download().get(120, TimeUnit.SECONDS);
            return version.orElse("none");
        } catch (ExecutionException e) {
            return "error:" + e.getCause().getMessage();
        } catch (java.util.concurrent.TimeoutException e) {
            return "error:timeout";
        }
    }

    static String resource(String name) throws IOException {
        try (InputStream in = SampleApp.class.getResourceAsStream(name)) {
            return in == null ? "?" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    static void write(String line) throws IOException {
        Files.writeString(Path.of(System.getenv("SAMPLE_OUT")), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
