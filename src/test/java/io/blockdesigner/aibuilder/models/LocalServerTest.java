package io.blockdesigner.aibuilder.models;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalServerTest {
    @TempDir
    Path dir;

    /** Stands in for llama-server: alive until destroyed. */
    static final class FakeProcess extends Process {
        volatile boolean alive = true;
        final Runnable onDestroy;

        FakeProcess(Runnable onDestroy) {
            this.onDestroy = onDestroy;
        }

        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        public int waitFor() {
            return 0;
        }

        public boolean waitFor(long t, java.util.concurrent.TimeUnit u) {
            return !alive;
        }

        public int exitValue() {
            if (alive) throw new IllegalThreadStateException();
            return 0;
        }

        public boolean isAlive() {
            return alive;
        }

        public void destroy() {
            alive = false;
            onDestroy.run();
        }

        public java.util.stream.Stream<ProcessHandle> descendants() {
            return java.util.stream.Stream.empty();
        }
    }

    private LocalServer.Launch launch() {
        return new LocalServer.Launch(dir.resolve("engine/llama-server.exe"), dir.resolve("m.gguf"), dir.resolve("mmproj.gguf"), 16384, true, dir.resolve("server.log"));
    }

    @Test
    void theCommandLineRunsOnLocalhostWithTheProjector() {
        List<String> c = LocalServer.command(launch(), 5555);
        assertThat(c).startsWith(dir.resolve("engine/llama-server.exe").toString(), "-m", dir.resolve("m.gguf").toString(),
                "--mmproj", dir.resolve("mmproj.gguf").toString());
        assertThat(String.join(" ", c)).contains("--jinja -c 16384 -ngl 99 --host 127.0.0.1 --port 5555");
        var cpu = new LocalServer.Launch(dir.resolve("s.exe"), dir.resolve("m.gguf"), null, 8192, false, dir.resolve("l"));
        assertThat(LocalServer.command(cpu, 1)).doesNotContain("--mmproj").contains("0");
    }

    @Test
    void startsWaitsForHealthAndStops() throws Exception {
        List<String> seenCommand = new ArrayList<>();
        AtomicInteger health = new AtomicInteger();
        HttpServer[] fake = new HttpServer[1];
        LocalServer.Starter starter = (command, log) -> {
            seenCommand.addAll(command);
            int port = Integer.parseInt(command.getLast());
            HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            s.createContext("/health", ex -> {
                int n = health.incrementAndGet();
                byte[] body = (n < 3 ? "{\"status\":\"loading model\"}" : "{\"status\":\"ok\"}").getBytes();
                ex.sendResponseHeaders(n < 3 ? 503 : 200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            s.start();
            fake[0] = s;
            return new FakeProcess(() -> s.stop(0));
        };
        LocalServer server = new LocalServer(Downloader.defaultClient(), starter);
        List<LocalServer.Status> statuses = new ArrayList<>();
        server.onStatus(statuses::add);
        server.start(launch(), Duration.ofSeconds(20));
        assertThat(server.status()).isEqualTo(LocalServer.Status.READY);
        assertThat(health.get()).isGreaterThanOrEqualTo(3);
        assertThat(server.baseUrl().toString()).isEqualTo("http://127.0.0.1:" + seenCommand.getLast() + "/v1");
        assertThat(server.running(launch())).isTrue();
        server.start(launch(), Duration.ofSeconds(1));   // already running: nothing to do
        assertThat(seenCommand.stream().filter(s -> s.equals("--port")).count()).isEqualTo(1);
        server.stop();
        assertThat(server.status()).isEqualTo(LocalServer.Status.STOPPED);
        assertThat(statuses).containsExactly(LocalServer.Status.STARTING, LocalServer.Status.READY, LocalServer.Status.STOPPED);
    }

    @Test
    void aProcessThatDiesReportsTheLogTail() throws Exception {
        Files.writeString(dir.resolve("server.log"), "loading\nerror: failed to load model 'm.gguf'\n");
        LocalServer.Starter starter = (command, log) -> {
            FakeProcess p = new FakeProcess(() -> { });
            p.alive = false;
            return p;
        };
        LocalServer server = new LocalServer(Downloader.defaultClient(), starter);
        assertThatThrownBy(() -> server.start(launch(), Duration.ofSeconds(5)))
                .isInstanceOf(IOException.class).hasMessageContaining("stopped while starting").hasMessageContaining("failed to load model");
        assertThat(server.status()).isEqualTo(LocalServer.Status.FAILED);
        assertThat(server.problem()).contains("failed to load model");
    }
}
