package io.blockdesigner.aibuilder.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The OpenAI chat completions API, streamed. It serves the built-in model (llama.cpp's server), Ollama
 * ({@code http://localhost:11434/v1}), LM Studio, OpenAI and other compatible services.
 */
public final class OpenAiCompatibleClient implements ChatClient {
    private final HttpClient http;
    private final URI base;
    private final String apiKey;
    private final String model;
    private final boolean vision;
    private final String name;
    private final Double temperature;

    /**
     * @param base        the API base, e.g. {@code https://api.openai.com/v1} (a bare host gets {@code /v1})
     * @param apiKey      sent as a bearer token, or null
     * @param model       the model name; blank lets the server choose (llama.cpp serves one model)
     * @param temperature null to leave it to the server (OpenAI's reasoning models accept only the default)
     */
    public OpenAiCompatibleClient(HttpClient http, URI base, String apiKey, String model, boolean vision, String name, Double temperature) {
        this.http = http;
        this.base = normalize(base);
        this.apiKey = apiKey;
        this.model = model == null ? "" : model.strip();
        this.vision = vision;
        this.name = name;
        this.temperature = temperature;
    }

    /** Trims a trailing slash or {@code /chat/completions}; a URL with no path gets {@code /v1}. */
    static URI normalize(URI base) {
        String s = base.toString().strip();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.endsWith("/chat/completions")) s = s.substring(0, s.length() - "/chat/completions".length());
        URI u = URI.create(s);
        if (u.getPath() == null || u.getPath().isEmpty()) s = s + "/v1";
        return URI.create(s);
    }

    public URI base() {
        return base;
    }

    @Override
    public boolean vision() {
        return vision;
    }

    @Override
    public String describe() {
        return name;
    }

    String body(List<ChatMessage> messages) {
        ObjectNode root = Http.JSON.createObjectNode();
        root.put("model", model.isEmpty() ? "default" : model);
        root.put("stream", true);
        if (temperature != null) root.put("temperature", temperature);
        ArrayNode msgs = root.putArray("messages");
        for (ChatMessage m : messages) {
            ObjectNode o = msgs.addObject();
            o.put("role", m.role().name().toLowerCase(java.util.Locale.ROOT));
            if (m.images().isEmpty() || !vision) {
                o.put("content", m.text());
            } else {
                ArrayNode parts = o.putArray("content");
                parts.addObject().put("type", "text").put("text", m.text());
                for (ChatMessage.Image img : m.images()) {
                    parts.addObject().put("type", "image_url").putObject("image_url")
                            .put("url", "data:" + img.mimeType() + ";base64," + img.base64());
                }
            }
        }
        return root.toString();
    }

    @Override
    public String chat(List<ChatMessage> messages, Consumer<String> onText, Cancel cancel) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "text/event-stream");
        if (apiKey != null && !apiKey.isBlank()) headers.put("Authorization", "Bearer " + apiKey.strip());
        HttpResponse<InputStream> resp = Http.post(http, URI.create(base + "/chat/completions"), headers, body(messages), Duration.ofMinutes(15), cancel);
        if (resp.statusCode() / 100 != 2) throw Http.error(name, resp);
        StringBuilder reply = new StringBuilder();
        String type = resp.headers().firstValue("Content-Type").orElse("");
        try {
            if (type.startsWith("application/json")) {
                // A server that ignored "stream": one JSON answer.
                JsonNode n = Http.JSON.readTree(resp.body());
                String text = n.path("choices").path(0).path("message").path("content").asText("");
                onText.accept(text);
                return text;
            }
            Sse.read(resp.body(), (event, data) -> {
                if (data.equals("[DONE]")) return false;
                JsonNode n = Http.JSON.readTree(data);
                if (n.has("error")) {
                    JsonNode e = n.path("error");
                    throw new IOException(name + ": " + (e.isTextual() ? e.asText() : e.path("message").asText("error")));
                }
                JsonNode delta = n.path("choices").path(0).path("delta");
                String piece = delta.path("content").isTextual() ? delta.path("content").asText() : "";
                if (!piece.isEmpty()) {
                    reply.append(piece);
                    onText.accept(piece);
                }
                return true;
            }, cancel);
        } finally {
            cancel.watch(null);
        }
        return reply.toString();
    }
}
