package io.blockdesigner.aibuilder;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** A local HTTP server for tests: files with Range support, canned JSON, streamed text. */
public final class FakeHttp implements AutoCloseable {
    public final HttpServer server;
    public final List<String> requests = new CopyOnWriteArrayList<>();
    public final List<String> bodies = new CopyOnWriteArrayList<>();
    public final List<com.sun.net.httpserver.Headers> headers = new CopyOnWriteArrayList<>();

    public FakeHttp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    public void handle(String path, HttpHandler h) {
        server.createContext(path, ex -> {
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI() + (ex.getRequestHeaders().containsKey("Range")
                    ? " Range:" + ex.getRequestHeaders().getFirst("Range") : ""));
            headers.add(ex.getRequestHeaders());
            byte[] in = ex.getRequestBody().readAllBytes();
            bodies.add(new String(in, StandardCharsets.UTF_8));
            try {
                h.handle(ex);
            } finally {
                ex.close();
            }
        });
    }

    /**
     * Serves {@code data}, honouring Range when {@code ranges} is on; {@code slowBytesPerChunk} > 0 writes in small
     * chunks with a pause, so a download can be cancelled in the middle.
     */
    public void file(String path, byte[] data, boolean ranges, int slowBytesPerChunk) {
        handle(path, ex -> {
            String range = ex.getRequestHeaders().getFirst("Range");
            int start = 0;
            if (ranges && range != null && range.startsWith("bytes=")) {
                start = Integer.parseInt(range.substring(6, range.indexOf('-')));
                if (start >= data.length) {
                    ex.getResponseHeaders().add("Content-Range", "bytes */" + data.length);
                    ex.sendResponseHeaders(416, -1);
                    return;
                }
                ex.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + (data.length - 1) + "/" + data.length);
                ex.sendResponseHeaders(206, data.length - start);
            } else {
                ex.sendResponseHeaders(200, data.length);
            }
            try (OutputStream out = ex.getResponseBody()) {
                if (slowBytesPerChunk <= 0) {
                    out.write(data, start, data.length - start);
                } else {
                    for (int i = start; i < data.length; i += slowBytesPerChunk) {
                        out.write(data, i, Math.min(slowBytesPerChunk, data.length - i));
                        out.flush();
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            } catch (IOException ignored) {
                // the client went away (a cancelled download)
            }
        });
    }

    public void text(String path, int status, String contentType, String body) {
        handle(path, ex -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        });
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
