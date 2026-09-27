package io.blockdesigner.aibuilder.models;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Runs {@code llama-server.exe} in the background for the built-in model: on 127.0.0.1 at a free port, with no
 * window, logging to a file. It is ready once {@code /health} answers 200; it stops on {@link #stop()}, when the
 * plugin is disabled, when the JVM exits, and after an idle time if one is set.
 */
public final class LocalServer {
    public enum Status { STOPPED, STARTING, READY, FAILED }

    /**
     * @param mmproj the vision projector, or null for a text-only model
     * @param gpu    offload the model to the graphics card ({@code -ngl 99}); false for the CPU build
     */
    public record Launch(Path exe, Path model, Path mmproj, int contextSize, boolean gpu, Path log) {
    }

    /** Starts a process; replaced in tests. */
    @FunctionalInterface
    public interface Starter {
        Process start(List<String> command, Path log) throws IOException;
    }

    public static final Starter PROCESS = (command, log) -> {
        Files.createDirectories(log.toAbsolutePath().getParent());
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        pb.directory(Path.of(command.getFirst()).toAbsolutePath().getParent().toFile());
        return pb.start();   // Java starts Windows console programs without a window (CREATE_NO_WINDOW)
    };

    private final HttpClient http;
    private final Starter starter;
    private final List<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
    private volatile Status status = Status.STOPPED;
    private volatile String problem;
    private volatile Process process;
    private volatile int port;
    private volatile Launch launched;
    private volatile long lastUsed = System.currentTimeMillis();
    private Thread shutdownHook;

    public LocalServer(HttpClient http, Starter starter) {
        this.http = http;
        this.starter = starter;
    }

    static List<String> command(Launch l, int port) {
        List<String> c = new ArrayList<>(List.of(l.exe().toString(), "-m", l.model().toString()));
        if (l.mmproj() != null) c.addAll(List.of("--mmproj", l.mmproj().toString()));
        c.addAll(List.of("--jinja", "-c", Integer.toString(l.contextSize()), "-ngl", l.gpu() ? "99" : "0",
                "--host", "127.0.0.1", "--port", Integer.toString(port)));
        return c;
    }

    public static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    public Status status() {
        return status;
    }

    /** Why it failed, or null. */
    public String problem() {
        return problem;
    }

    public Launch launched() {
        return launched;
    }

    public void onStatus(Consumer<Status> listener) {
        listeners.add(listener);
    }

    private void set(Status s) {
        status = s;
        for (Consumer<Status> l : listeners) l.accept(s);
    }

    /** The OpenAI-compatible base URL while running. */
    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + port + "/v1");
    }

    public void touch() {
        lastUsed = System.currentTimeMillis();
    }

    public long idleMillis() {
        return System.currentTimeMillis() - lastUsed;
    }

    public boolean running(Launch l) {
        Process p = process;
        return status == Status.READY && p != null && p.isAlive() && l.equals(launched);
    }

    /**
     * Starts the server (stopping one with other settings first) and waits until it is ready. Call off the UI
     * thread: loading a model takes from seconds to a few minutes.
     */
    public synchronized void start(Launch l, Duration timeout) throws IOException {
        if (running(l)) return;
        stop();
        problem = null;
        set(Status.STARTING);
        try {
            port = freePort();
            Process p = starter.start(command(l, port), l.log());
            process = p;
            launched = l;
            registerHook();
            URI health = URI.create("http://127.0.0.1:" + port + "/health");
            if (!waitHealthy(http, health, timeout, p::isAlive)) {
                String tail = logTail(l.log(), 12);
                boolean died = !p.isAlive();
                stopProcess();
                throw new IOException((died ? "The model engine stopped while starting" : "The model engine didn't start in time")
                        + (tail.isBlank() ? "" : ":\n" + tail));
            }
            touch();
            set(Status.READY);
        } catch (IOException | RuntimeException e) {
            problem = e.getMessage();
            set(Status.FAILED);
            throw e;
        }
    }

    /** Polls {@code health} until it answers 200, the process dies, or time runs out. */
    static boolean waitHealthy(HttpClient http, URI health, Duration timeout, BooleanSupplier alive) {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            if (!alive.getAsBoolean()) return false;
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) return true;   // 503 while the model loads
            } catch (IOException ignored) {
                // not listening yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    static String logTail(Path log, int lines) {
        try {
            if (log == null || !Files.isRegularFile(log)) return "";
            List<String> all = Files.readAllLines(log, StandardCharsets.UTF_8);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    public synchronized void stop() {
        stopProcess();
        if (status != Status.STOPPED) set(Status.STOPPED);
    }

    private void stopProcess() {
        Process p = process;
        process = null;
        if (p != null && p.isAlive()) {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        unregisterHook();
    }

    private void registerHook() {
        if (shutdownHook != null) return;
        shutdownHook = new Thread(() -> {
            Process p = process;
            if (p != null) p.destroyForcibly();
        }, "ai-builder-engine-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // already shutting down
        }
    }

    private void unregisterHook() {
        if (shutdownHook == null) return;
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // shutting down
        }
        shutdownHook = null;
    }
}
