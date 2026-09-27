package io.blockdesigner.aibuilder.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** POSTs JSON and hands back the streamed response, turning HTTP errors into readable messages. */
final class Http {
    static final ObjectMapper JSON = new ObjectMapper();

    private Http() {
    }

    static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    static HttpResponse<InputStream> post(HttpClient http, URI url, Map<String, String> headers, String body, Duration timeout, Cancel cancel)
            throws IOException {
        cancel.check();
        HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(b::header);
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Cancel.CancelledException();
        } catch (java.net.ConnectException e) {
            throw new IOException("Couldn't connect to " + url.getHost() + (url.getPort() > 0 ? ":" + url.getPort() : "") + ". Is it running?");
        }
        cancel.watch(resp.body());
        return resp;
    }

    /** A readable error for a failed response, from the usual {"error": {"message": …}} shapes. */
    static IOException error(String who, HttpResponse<InputStream> resp) {
        String body;
        try (InputStream in = resp.body()) {
            body = new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
        } catch (IOException e) {
            body = "";
        }
        String msg = body.strip();
        try {
            JsonNode n = JSON.readTree(body);
            JsonNode err = n.path("error");
            if (err.isTextual()) msg = err.asText();
            else if (err.has("message")) msg = err.path("message").asText();
            else if (n.has("message")) msg = n.path("message").asText();
        } catch (IOException ignored) {
            // not JSON: keep the text
        }
        if (msg.length() > 400) msg = msg.substring(0, 400) + "…";
        String hint = switch (resp.statusCode()) {
            case 401, 403 -> " (check the API key)";
            case 404 -> " (check the address and the model name)";
            case 429 -> " (rate limited: wait a little and try again)";
            default -> "";
        };
        return new IOException(who + ": HTTP " + resp.statusCode() + (msg.isEmpty() ? "" : ": " + msg) + hint);
    }
}
