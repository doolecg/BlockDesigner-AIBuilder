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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Claude through Anthropic's Messages API, streamed, with pictures as image blocks. Raw HTTP on purpose: the plugin
 * ships as one small jar with no bundled libraries (BlockDesigner provides Jackson), so it uses Java's own client.
 */
public final class AnthropicClient implements ChatClient {
    public static final String DEFAULT_MODEL = "claude-opus-5-5";
    static final String VERSION = "2023-06-01";
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final HttpClient http;
    private final URI base;
    private final String apiKey;
    private final String model;

    public AnthropicClient(HttpClient http, URI base, String apiKey, String model) {
        this.http = http;
        String b = base.toString();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = URI.create(b);
        this.apiKey = apiKey;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
    }

    public static URI defaultBase() {
        return URI.create("https://api.anthropic.com");
    }

    @Override
    public boolean vision() {
        return true;
    }

    @Override
    public String describe() {
        return "Claude (" + model + ")";
    }

    /** Server-side fallback on a declined request is offered for these models; others are sent without it. */
    static boolean fallbackSupported(String model) {
        return model.startsWith("claude-opus-5") || model.startsWith("claude-fable-5");
    }

    String body(List<ChatMessage> messages, boolean withFallback) {
        ObjectNode root = Http.JSON.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", 32000);
        root.put("stream", true);
        if (withFallback) root.put("fallbacks", "default");
        StringBuilder system = new StringBuilder();
        // The API wants user and assistant turns alternating, starting with the user.
        List<ChatMessage> turns = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (m.role() == ChatMessage.Role.SYSTEM) {
                if (!system.isEmpty()) system.append("\n\n");
                system.append(m.text());
            } else {
                turns.add(m);
            }
        }
        if (!system.isEmpty()) root.put("system", system.toString());
        ArrayNode msgs = root.putArray("messages");
        ObjectNode current = null;
        ChatMessage.Role currentRole = null;
        for (ChatMessage m : turns) {
            if (current == null && m.role() == ChatMessage.Role.ASSISTANT) continue;   // must start with the user
            if (m.role() != currentRole) {
                current = msgs.addObject();
                current.put("role", m.role() == ChatMessage.Role.USER ? "user" : "assistant");
                current.putArray("content");
                currentRole = m.role();
            }
            ArrayNode content = (ArrayNode) current.get("content");
            for (ChatMessage.Image img : m.images()) {
                ObjectNode block = content.addObject().put("type", "image");
                block.putObject("source").put("type", "base64").put("media_type", img.mimeType()).put("data", img.base64());
            }
            String text = m.text().isBlank() ? "(no text)" : m.text();
            // A reply must not end with trailing whitespace when it is the last turn; trim to be safe.
            content.addObject().put("type", "text").put("text", m.role() == ChatMessage.Role.ASSISTANT ? text.stripTrailing() : text);
        }
        return root.toString();
    }

    @Override
    public String chat(List<ChatMessage> messages, Consumer<String> onText, Cancel cancel) throws IOException {
        if (apiKey == null || apiKey.isBlank()) throw new IOException("Add your Anthropic API key on the Models page (Advanced)");
        boolean fallback = fallbackSupported(model);
        HttpResponse<InputStream> resp = send(messages, fallback, cancel);
        if (resp.statusCode() == 400 && fallback) {
            IOException e = Http.error("Claude", resp);
            if (!e.getMessage().contains("fallback")) throw e;
            resp = send(messages, false, cancel);   // this account or model doesn't take fallbacks: plain request
        }
        if (resp.statusCode() / 100 != 2) throw Http.error("Claude", resp);
        StringBuilder reply = new StringBuilder();
        String[] stop = {null};
        try {
            Sse.read(resp.body(), (event, data) -> {
                JsonNode n = Http.JSON.readTree(data);
                String type = n.path("type").asText(event);
                switch (type) {
                    case "content_block_delta" -> {
                        JsonNode d = n.path("delta");
                        if ("text_delta".equals(d.path("type").asText())) {
                            String piece = d.path("text").asText("");
                            reply.append(piece);
                            onText.accept(piece);
                        }
                    }
                    case "message_delta" -> {
                        String s = n.path("delta").path("stop_reason").asText(null);
                        if (s != null) stop[0] = s;
                    }
                    case "error" -> throw new IOException("Claude: " + n.path("error").path("message").asText("error"));
                    case "message_stop" -> {
                        return false;
                    }
                    default -> {
                    }
                }
                return true;
            }, cancel);
        } finally {
            cancel.watch(null);
        }
        if ("refusal".equals(stop[0])) {
            throw new IOException("Claude declined this request. Try rewording it.");
        }
        if ("max_tokens".equals(stop[0]) && reply.isEmpty()) throw new IOException("Claude ran out of room before answering");
        return reply.toString();
    }

    private HttpResponse<InputStream> send(List<ChatMessage> messages, boolean fallback, Cancel cancel) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", apiKey.strip());
        headers.put("anthropic-version", VERSION);
        headers.put("Accept", "text/event-stream");
        if (fallback) headers.put("anthropic-beta", FALLBACK_BETA);
        return Http.post(http, URI.create(base + "/v1/messages"), headers, body(messages, fallback), Duration.ofMinutes(15), cancel);
    }
}
