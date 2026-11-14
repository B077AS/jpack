package io.github.b077as.jpack;

import io.github.b077as.jpack.internal.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The connection from an app (or its launcher) to the jpack stub that started it.
 *
 * <p>The stub is the small native program that sits next to the app: it downloads updates, swaps the files while no Java
 * process is running, and starts the app. This class asks it to do those things and reports the progress, so the app
 * decides what the user sees:
 *
 * <pre>{@code
 * if (Jpack.isAvailable()) {
 *     Jpack jpack = Jpack.connect();
 *     jpack.check().thenAccept(update -> {
 *         if (update.available()) {
 *             jpack.download((received, total) -> showProgress(received, total))
 *                  .thenRun(() -> showRestartButton());   // the button calls jpack.restartAfterExit() and exits
 *         }
 *     });
 * }
 * }</pre>
 *
 * <p>A launcher does the same and then hands over: {@code jpack.startAfterExit("app"); System.exit(0);}. The stub waits
 * for the launcher to exit, swaps in what was downloaded (a new Java runtime included) and starts the app.
 *
 * <p>When nothing was downloaded, a launcher can stay on screen until the app's window is up, so there is no moment with
 * nothing to look at:
 *
 * <pre>{@code
 * if (jpack.startNow("app").get(30, SECONDS).isPresent()) {      // the app is starting next to the launcher
 *     try {
 *         jpack.whenShown("app").get(60, SECONDS);                // the app called notifyShown()
 *     } catch (ExecutionException e) {
 *         showError(e.getCause().getMessage());                   // it ended before its window appeared
 *         return;
 *     } catch (TimeoutException e) {
 *         // an app that never says so: close anyway
 *     }
 * } else {
 *     showStatus("Starting...");                                   // an update is swapped in once the launcher has exited
 * }
 * System.exit(0);
 * }</pre>
 *
 * <p>The app says when its window is on screen: {@code jpack.notifyShown()}.
 *
 * <p>Listeners and the futures' follow-up steps run on this connection's own thread. A UI must hop to its UI thread
 * ({@code Platform.runLater}, {@code Application.dispatch}, ...) before touching a window.
 *
 * <p>When the app was not started by a stub (from the IDE, with {@code java -jar}), {@link #isAvailable()} is false.
 */
public final class Jpack implements AutoCloseable {

    private static final int TIMEOUT_SECONDS = 15;

    private final Socket socket;
    private final OutputStream out;
    private final AtomicLong ids = new AtomicLong(1);
    private final Map<Long, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final List<ProgressListener> progressListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> readyListeners = new CopyOnWriteArrayList<>();
    private final Set<String> features;

    /** Entries that said their window is on screen, entries that exited before that (with their exit code), and who waits. */
    private final Object shownLock = new Object();
    private final Set<String> shownEntries = new HashSet<>();
    private final Map<String, Integer> endedBeforeShown = new HashMap<>();
    private final Map<String, List<CompletableFuture<Void>>> shownWaiters = new HashMap<>();
    private JpackException gone;

    private final String appName;
    private final String appVersion;
    private final String entry;
    private final boolean canUpdate;
    private final boolean updatesInBackground;
    private volatile String downloadedVersion;
    private volatile boolean closed;

    private Jpack(Socket socket, Map<String, Object> hello) throws IOException {
        this.socket = socket;
        this.out = socket.getOutputStream();
        this.appName = text(hello.get("name"));
        this.appVersion = text(hello.get("version"));
        this.entry = text(hello.get("entry"));
        this.canUpdate = Boolean.TRUE.equals(hello.get("canUpdate"));
        this.updatesInBackground = "background".equals(hello.get("policy"));
        this.downloadedVersion = (String) hello.get("staged");
        Set<String> understood = new HashSet<>();
        if (hello.get("features") instanceof List) {
            for (Object feature : (List<?>) hello.get("features")) {
                understood.add(String.valueOf(feature));
            }
        }
        this.features = Set.copyOf(understood);
    }

    /** True when this program was started by a jpack stub, so {@link #connect()} can work. */
    public static boolean isAvailable() {
        return System.getenv("JPACK_PORT") != null && System.getenv("JPACK_TOKEN") != null;
    }

    /**
     * Connects to the stub that started this program.
     *
     * @throws JpackException when this program was not started by a stub, or the stub does not answer
     */
    public static Jpack connect() {
        String port = System.getenv("JPACK_PORT");
        String token = System.getenv("JPACK_TOKEN");
        if (port == null || token == null) {
            throw new JpackException("This program was not started by jpack, so there is nothing to connect to.");
        }
        Socket socket = null;
        try {
            // The stub listens on IPv4 loopback only; getLoopbackAddress() may answer ::1.
            socket = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), Integer.parseInt(port.trim()));
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(TIMEOUT_SECONDS * 1000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            Map<String, Object> hello = new LinkedHashMap<>();
            hello.put("id", 0);
            hello.put("cmd", "hello");
            hello.put("token", token);
            socket.getOutputStream().write((Json.write(hello) + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            String line = in.readLine();
            if (line == null) {
                throw new JpackException("The jpack stub closed the connection.");
            }
            Map<String, Object> answer = Json.parseObject(line);
            if (!Boolean.TRUE.equals(answer.get("ok"))) {
                throw new JpackException("The jpack stub refused the connection: " + answer.get("error"));
            }
            socket.setSoTimeout(0);
            Jpack jpack = new Jpack(socket, answer);
            // A platform thread on purpose: on Windows a virtual thread reading a socket next to another one writing it
            // can miss its wakeup.
            Thread reader = new Thread(() -> jpack.read(in), "jpack");
            reader.setDaemon(true);
            reader.start();
            return jpack;
        } catch (IOException | RuntimeException e) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // nothing more to do
                }
            }
            if (e instanceof JpackException) {
                throw (JpackException) e;
            }
            throw new JpackException("The jpack stub could not be reached: " + e.getMessage(), e);
        }
    }

    /** The app's name, as the installed release states it. */
    public String appName() {
        return appName;
    }

    /** The version that is installed (and running). */
    public String appVersion() {
        return appVersion;
    }

    /** The name of the launch entry this program was started as, such as {@code app} or {@code launcher}. */
    public String entry() {
        return entry;
    }

    /**
     * False when this installation cannot update itself: it has no update address, or its folder cannot be written to
     * (installed for all users, or on read-only media).
     */
    public boolean canUpdate() {
        return canUpdate;
    }

    /** True when the stub looks for updates by itself while the app runs; listen with {@link #onReady} for the result. */
    public boolean updatesInBackground() {
        return updatesInBackground;
    }

    /** The version that is downloaded and will be used after this program has exited, if there is one. */
    public Optional<String> downloadedVersion() {
        return Optional.ofNullable(downloadedVersion);
    }

    /** Asks whether a newer release is published. Downloads nothing. */
    public CompletableFuture<UpdateCheck> check() {
        return request("check", null).thenApply(answer -> new UpdateCheck(
                Boolean.TRUE.equals(answer.get("available")),
                text(answer.get("version")),
                answer.get("size") instanceof Number ? ((Number) answer.get("size")).longValue() : 0,
                Boolean.TRUE.equals(answer.get("staged"))));
    }

    /**
     * Downloads the newest release, if there is a newer one. Completes with its version once everything is downloaded
     * and verified, or with an empty value when the installed release is the newest. Nothing changes for the running
     * program: the files are swapped after it has exited.
     */
    public CompletableFuture<Optional<String>> download() {
        return request("download", null).thenApply(answer -> {
            String version = (String) answer.get("version");
            if (version != null) {
                downloadedVersion = version;
            }
            return Optional.ofNullable(version);
        });
    }

    /** Like {@link #download()}, reporting the progress of this download to {@code listener}. */
    public CompletableFuture<Optional<String>> download(ProgressListener listener) {
        progressListeners.add(listener);
        return download().whenComplete((version, failure) -> progressListeners.remove(listener));
    }

    /**
     * Reports the progress of every download, including the ones the stub starts by itself in the background.
     *
     * @return call {@code run()} on it to stop listening
     */
    public Runnable onProgress(ProgressListener listener) {
        progressListeners.add(listener);
        return () -> progressListeners.remove(listener);
    }

    /**
     * Called with the version each time a release has been downloaded and waits for this program to exit, including
     * downloads the stub started by itself. The moment to offer "restart to update".
     *
     * @return call {@code run()} on it to stop listening
     */
    public Runnable onReady(Consumer<String> listener) {
        readyListeners.add(listener);
        return () -> readyListeners.remove(listener);
    }

    /**
     * Tells the stub what to start once this program has exited: a launcher names the app's entry and exits. A
     * downloaded release is swapped in first, so the entry may be one that only the new release has.
     */
    public void startAfterExit(String entry) {
        if (entry == null || entry.isBlank()) {
            throw new IllegalArgumentException("an entry name is needed");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entry", entry);
        await(request("next", fields));
    }

    /** Tells the stub to start this program again once it has exited, with a downloaded release swapped in first. */
    public void restartAfterExit() {
        startAfterExit(entry);
    }

    /** Takes back {@link #startAfterExit} / {@link #restartAfterExit}: when this program exits, nothing else starts. */
    public void cancelStartAfterExit() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entry", null);
        await(request("next", fields));
    }

    /**
     * Starts {@code entry} now, next to this program: a launcher hands over to the app without leaving a moment with no
     * window on screen. Wait for the app with {@link #whenShown}, then exit.
     *
     * <p>When a downloaded release waits to be swapped in, the entry cannot start yet (the files are swapped while no
     * Java program runs). Then this does what {@link #startAfterExit} does, and the future completes empty: exit, and the
     * stub swaps the update in and starts the entry. A stub older than this library is treated the same way.
     *
     * @return completes with the started program's process id, or empty when it starts after this program has exited
     */
    public CompletableFuture<OptionalLong> startNow(String entry) {
        if (entry == null || entry.isBlank()) {
            throw new IllegalArgumentException("an entry name is needed");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entry", entry);
        if (!features.contains("start")) {
            return request("next", fields).thenApply(answer -> OptionalLong.empty());
        }
        synchronized (shownLock) {
            // A second try after a failed start begins afresh.
            shownEntries.remove(entry);
            endedBeforeShown.remove(entry);
        }
        return request("start", fields).thenApply(answer -> Boolean.TRUE.equals(answer.get("started")) && answer.get("pid") instanceof Number
                ? OptionalLong.of(((Number) answer.get("pid")).longValue())
                : OptionalLong.empty());
    }

    /**
     * Tells the stub that this program's window is on screen. A launcher that handed over with {@link #startNow} is
     * waiting for exactly this to close. Call it once the window is visible (after {@code show()}); calling it more than
     * once is harmless, and with a stub that does not know it, nothing happens.
     */
    public CompletableFuture<Void> notifyShown() {
        if (!features.contains("shown")) {
            return CompletableFuture.completedFuture(null);
        }
        return request("shown", null).thenApply(answer -> null);
    }

    /**
     * Completes when the program started as {@code entry} has said its window is on screen ({@link #notifyShown}), or
     * fails with a {@link JpackException} when it exits before that. Use it with a timeout: an app that never calls
     * {@code notifyShown} never completes it.
     */
    public CompletableFuture<Void> whenShown(String entry) {
        CompletableFuture<Void> shown = new CompletableFuture<>();
        synchronized (shownLock) {
            if (shownEntries.contains(entry)) {
                shown.complete(null);
            } else if (endedBeforeShown.containsKey(entry)) {
                shown.completeExceptionally(endedEarly(entry, endedBeforeShown.get(entry)));
            } else if (gone != null) {
                shown.completeExceptionally(gone);
            } else {
                shownWaiters.computeIfAbsent(entry, name -> new ArrayList<>()).add(shown);
            }
        }
        return shown;
    }

    private static JpackException endedEarly(String entry, int code) {
        return new JpackException("'" + entry + "' ended with exit code " + code + " before its window appeared.");
    }

    /** Closes the connection. The stub keeps running, and so does a download it has started. */
    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing more to do
        }
    }

    private CompletableFuture<Map<String, Object>> request(String command, Map<String, Object> fields) {
        long id = ids.getAndIncrement();
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id);
        message.put("cmd", command);
        if (fields != null) {
            message.putAll(fields);
        }
        CompletableFuture<Map<String, Object>> answer = new CompletableFuture<>();
        pending.put(id, answer);
        try {
            byte[] line = (Json.write(message) + "\n").getBytes(StandardCharsets.UTF_8);
            synchronized (out) {
                out.write(line);
                out.flush();
            }
        } catch (IOException e) {
            pending.remove(id);
            answer.completeExceptionally(new JpackException("The connection to the jpack stub is gone: " + e.getMessage(), e));
        }
        return answer;
    }

    private static void await(CompletableFuture<?> answer) {
        try {
            answer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof JpackException) {
                throw (JpackException) e.getCause();
            }
            throw new JpackException(String.valueOf(e.getCause()), e.getCause());
        } catch (TimeoutException e) {
            throw new JpackException("The jpack stub did not answer.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JpackException("Interrupted while waiting for the jpack stub.", e);
        }
    }

    private void read(BufferedReader in) {
        String ending = "The connection to the jpack stub was closed.";
        try {
            String line;
            while ((line = in.readLine()) != null) {
                Map<String, Object> message;
                try {
                    message = Json.parseObject(line);
                } catch (IllegalArgumentException e) {
                    continue; // a line this version does not understand
                }
                if (message.get("re") instanceof Number) {
                    answer(message);
                } else if (message.get("event") instanceof String) {
                    event(message);
                }
            }
        } catch (IOException e) {
            if (!closed) {
                ending = "The connection to the jpack stub broke: " + e.getMessage();
            }
        }
        JpackException ended = new JpackException(ending);
        for (CompletableFuture<Map<String, Object>> waiting : pending.values()) {
            waiting.completeExceptionally(ended);
        }
        pending.clear();
        List<CompletableFuture<Void>> waiting = new ArrayList<>();
        synchronized (shownLock) {
            gone = ended;
            shownWaiters.values().forEach(waiting::addAll);
            shownWaiters.clear();
        }
        waiting.forEach(future -> future.completeExceptionally(ended));
    }

    private void answer(Map<String, Object> message) {
        CompletableFuture<Map<String, Object>> waiting = pending.remove(((Number) message.get("re")).longValue());
        if (waiting == null) {
            return;
        }
        if (Boolean.TRUE.equals(message.get("ok"))) {
            waiting.complete(message);
        } else {
            waiting.completeExceptionally(new JpackException(text(message.get("error"))));
        }
    }

    private void event(Map<String, Object> message) {
        String event = (String) message.get("event");
        if ("progress".equals(event) && message.get("received") instanceof Number && message.get("total") instanceof Number) {
            long received = ((Number) message.get("received")).longValue();
            long total = ((Number) message.get("total")).longValue();
            for (ProgressListener listener : progressListeners) {
                call(() -> listener.progress(received, total));
            }
        } else if ("ready".equals(event) && message.get("version") instanceof String) {
            String version = (String) message.get("version");
            downloadedVersion = version;
            for (Consumer<String> listener : readyListeners) {
                call(() -> listener.accept(version));
            }
        } else if ("shown".equals(event) && message.get("entry") instanceof String) {
            String shownEntry = (String) message.get("entry");
            List<CompletableFuture<Void>> waiting;
            synchronized (shownLock) {
                shownEntries.add(shownEntry);
                waiting = shownWaiters.remove(shownEntry);
            }
            if (waiting != null) {
                waiting.forEach(future -> future.complete(null));
            }
        } else if ("exited".equals(event) && message.get("entry") instanceof String && !Boolean.TRUE.equals(message.get("shown"))) {
            String endedEntry = (String) message.get("entry");
            int code = message.get("code") instanceof Number ? ((Number) message.get("code")).intValue() : -1;
            List<CompletableFuture<Void>> waiting;
            synchronized (shownLock) {
                if (shownEntries.contains(endedEntry)) {
                    return;
                }
                endedBeforeShown.put(endedEntry, code);
                waiting = shownWaiters.remove(endedEntry);
            }
            if (waiting != null) {
                waiting.forEach(future -> future.completeExceptionally(endedEarly(endedEntry, code)));
            }
        }
    }

    /** A listener that throws must not end the connection for everyone else. */
    private static void call(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException e) {
            Thread thread = Thread.currentThread();
            thread.getUncaughtExceptionHandler().uncaughtException(thread, e);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
