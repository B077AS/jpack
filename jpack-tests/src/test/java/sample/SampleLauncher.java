package sample;

import io.github.b077as.jpack.Jpack;
import io.github.b077as.jpack.UpdateCheck;

import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The launcher the end-to-end tests use: it does what a real one does (look, download while following the progress, hand
 * over to the app), and writes what it saw to the file named by {@code SAMPLE_OUT} where a real one would draw a window.
 */
public final class SampleLauncher {

    private SampleLauncher() {
    }

    public static void main(String[] args) throws Exception {
        StringBuilder line = new StringBuilder("launcher " + SampleApp.resource("/sample-version.txt"));
        try (Jpack jpack = Jpack.connect()) {
            line.append(" entry=").append(jpack.entry());
            try {
                UpdateCheck update = jpack.check().get(60, TimeUnit.SECONDS);
                line.append(" available=").append(update.available()).append(" version=").append(update.version());
                if (update.available()) {
                    AtomicInteger events = new AtomicInteger();
                    AtomicLong received = new AtomicLong(-1);
                    AtomicLong total = new AtomicLong(-1);
                    AtomicLong backwards = new AtomicLong();
                    jpack.download((soFar, all) -> {
                        events.incrementAndGet();
                        if (soFar < received.get()) {
                            backwards.incrementAndGet();
                        }
                        received.set(soFar);
                        total.set(all);
                    }).get(120, TimeUnit.SECONDS);
                    line.append(" size=").append(update.downloadSize())
                            .append(" events=").append(events.get() > 0 ? "some" : "none")
                            .append(" final=").append(received.get() == total.get() ? "complete" : received.get() + "/" + total.get())
                            .append(" total=").append(total.get())
                            .append(" backwards=").append(backwards.get());
                }
            } catch (ExecutionException e) {
                line.append(" error=").append(e.getCause().getMessage());
            }
            if ("handoff".equals(System.getenv("SAMPLE_LAUNCHER"))) {
                // Hand over without exiting first, and stay until the app says its window is up.
                OptionalLong pid = jpack.startNow("app").get(60, TimeUnit.SECONDS);
                line.append(" started=").append(pid.isPresent());
                if (pid.isPresent()) {
                    try {
                        jpack.whenShown("app").get(60, TimeUnit.SECONDS);
                        line.append(" shown=yes");
                    } catch (ExecutionException e) {
                        line.append(" failed=").append(e.getCause().getMessage());
                    }
                }
            } else {
                jpack.startAfterExit("app");
            }
        }
        SampleApp.write(line.toString());
        System.exit(0);
    }
}
