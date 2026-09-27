package io.blockdesigner.aibuilder.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.blockdesigner.aibuilder.FakeHttp;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ChatMessage> CONVERSATION = List.of(
            ChatMessage.system("You build."),
            ChatMessage.user("Build a hut", List.of(new ChatMessage.Image("image/png", "iVBORw0KGgo="))));

    private static String json(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String openAiStream(String... pieces) {
        StringBuilder sb = new StringBuilder();
        sb.append("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n");
        for (String p : pieces) {
            sb.append("data: {\"choices\":[{\"delta\":{\"content\":").append(json(p)).append("}}]}\n\n");
        }
        sb.append(": keep-alive comment\n\ndata: [DONE]\n\n");
        return sb.toString();
    }

    @Test
    void openAiCompatibleStreamsAndSendsImagesAndTheKey() throws Exception {
        try (FakeHttp http = new FakeHttp()) {
            http.text("/v1/chat/completions", 200, "text/event-stream", openAiStream("Plan: a hut.\n", "```commands\n/set stone\n```"));
            OpenAiCompatibleClient c = new OpenAiCompatibleClient(Http.client(), http.uri(""), "sk-test", "gemma", true, "Test", 0.2);
            List<String> pieces = new ArrayList<>();
            String reply = c.chat(CONVERSATION, pieces::add, new Cancel());
            assertThat(reply).isEqualTo("Plan: a hut.\n```commands\n/set stone\n```");
            assertThat(pieces).hasSize(2);
            assertThat(http.headers.getFirst().getFirst("Authorization")).isEqualTo("Bearer sk-test");
            JsonNode body = JSON.readTree(http.bodies.getFirst());
            assertThat(body.path("model").asText()).isEqualTo("gemma");
            assertThat(body.path("stream").asBoolean()).isTrue();
            assertThat(body.path("messages").path(0).path("content").asText()).isEqualTo("You build.");
            JsonNode parts = body.path("messages").path(1).path("content");
            assertThat(parts.path(0).path("text").asText()).isEqualTo("Build a hut");
            assertThat(parts.path(1).path("image_url").path("url").asText()).isEqualTo("data:image/png;base64,iVBORw0KGgo=");
        }
    }

    @Test
    void imagesAreLeftOutForATextOnlyModelAndTheBaseIsNormalised() throws Exception {
        try (FakeHttp http = new FakeHttp()) {
            http.text("/v1/chat/completions", 200, "text/event-stream", openAiStream("ok"));
            OpenAiCompatibleClient c = new OpenAiCompatibleClient(Http.client(), URI.create(http.uri("/v1/chat/completions/") + ""), null, "", false, "T", null);
            c.chat(CONVERSATION, s -> { }, new Cancel());
            JsonNode body = JSON.readTree(http.bodies.getFirst());
            assertThat(body.path("messages").path(1).path("content").isTextual()).isTrue();
            assertThat(body.has("temperature")).isFalse();
            assertThat(http.headers.getFirst().containsKey("Authorization")).isFalse();
        }
        assertThat(OpenAiCompatibleClient.normalize(URI.create("http://localhost:11434")).toString()).isEqualTo("http://localhost:11434/v1");
        assertThat(OpenAiCompatibleClient.normalize(URI.create("https://api.openai.com/v1/")).toString()).isEqualTo("https://api.openai.com/v1");
    }

    @Test
    void aNonStreamingJsonAnswerIsAccepted() throws Exception {
        try (FakeHttp http = new FakeHttp()) {
            http.text("/v1/chat/completions", 200, "application/json", "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}");
            var c = new OpenAiCompatibleClient(Http.client(), http.uri("/v1"), null, "m", false, "T", null);
            assertThat(c.chat(CONVERSATION, s -> { }, new Cancel())).isEqualTo("hello");
        }
    }

    @Test
    void httpErrorsBecomeReadableMessages() throws Exception {
        try (FakeHttp http = new FakeHttp()) {
            http.text("/v1/chat/completions", 401, "application/json", "{\"error\":{\"message\":\"Incorrect API key provided\"}}");
            var c = new OpenAiCompatibleClient(Http.client(), http.uri("/v1"), "bad", "m", false, "OpenAI", null);
            assertThatThrownBy(() -> c.chat(CONVERSATION, s -> { }, new Cancel()))
                    .hasMessageContaining("OpenAI: HTTP 401").hasMessageContaining("Incorrect API key").hasMessageContaining("check the API key");
        }
        var nowhere = new OpenAiCompatibleClient(Http.client(), URI.create("http://127.0.0.1:1/v1"), null, "m", false, "Local", null);
        assertThatThrownBy(() -> nowhere.chat(CONVERSATION, s -> { }, new Cancel())).isInstanceOf(IOException.class);
    }

    @Test
    void cancelStopsAStreamInFlight() throws Exception {
        try (FakeHttp http = new FakeHttp()) {
            http.handle("/v1/chat/completions", ex -> {
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0);
                try (OutputStream out = ex.getResponseBody()) {
                    for (int i = 0; i < 200; i++) {
                        out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        Thread.sleep(50);
                    }
                } catch (IOException | InterruptedException ignored) {
                    // the client went away
                }
            });
            var c = new OpenAiCompatibleClient(Http.client(), http.uri("/v1"), null, "m", false, "T", null);
            Cancel cancel = new Cancel();
            List<String> got = new java.util.concurrent.CopyOnWriteArrayList<>();
            CompletableFuture<Throwable> f = CompletableFuture.supplyAsync(() -> {
                try {
                    c.chat(CONVERSATION, got::add, cancel);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            while (got.size() < 3) Thread.sleep(10);
            cancel.cancel();
            assertThat(f.get(10, TimeUnit.SECONDS)).isInstanceOf(Cancel.CancelledException.class);
        }
    }

    private static String anthropicStream(String... pieces) {
        StringBuilder sb = new StringBuilder();
        sb.append("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"content\":[]}}\n\n");
        sb.append("event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n\n");
        sb.append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"\"}}\n\n");
        sb.append("event: ping\ndata: {\"type\":\"ping\"}\n\n");
        for (String p : pieces) {
            sb.append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":")
                    .append(json(p)).append("}}\n\n");
        }
        sb.append("event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n");
        sb.append("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n");
        return sb.toString();
    }

    @Test
    void anthropicStreamsTextAndSendsTheRightShape() throws Exception {
        try (FakeHttp http = new FakeHttp()) {
            http.text("/v1/messages", 200, "text/event-stream", anthropicStream("A hut.", "\n```commands\n/set stone\n```"));
            AnthropicClient c = new AnthropicClient(Http.client(), http.uri("/"), "sk-ant-test", null);
            List<ChatMessage> convo = new ArrayList<>(CONVERSATION);
            convo.add(ChatMessage.assistant("Done.  "));
            convo.add(ChatMessage.user("Bigger"));
            convo.add(ChatMessage.user("please"));
            String reply = c.chat(convo, s -> { }, new Cancel());
            assertThat(reply).isEqualTo("A hut.\n```commands\n/set stone\n```");
            var h = http.headers.getFirst();
            assertThat(h.getFirst("x-api-key")).isEqualTo("sk-ant-test");
            assertThat(h.getFirst("anthropic-version")).isEqualTo("2023-06-01");
            assertThat(h.getFirst("anthropic-beta")).isEqualTo("server-side-fallback-2026-07-01");
            JsonNode body = JSON.readTree(http.bodies.getFirst());
            assertThat(body.path("model").asText()).isEqualTo("claude-opus-5-5");
            assertThat(body.path("fallbacks").asText()).isEqualTo("default");
            assertThat(body.path("system").asText()).isEqualTo("You build.");
            JsonNode msgs = body.path("messages");
            assertThat(msgs).hasSize(3);   // the two trailing user messages are merged into one turn
            assertThat(msgs.path(0).path("content").path(0).path("type").asText()).isEqualTo("image");
            assertThat(msgs.path(0).path("content").path(0).path("source").path("media_type").asText()).isEqualTo("image/png");
            assertThat(msgs.path(1).path("content").path(0).path("text").asText()).isEqualTo("Done.");
            assertThat(msgs.path(2).path("content")).hasSize(2);
        }
    }

    @Test
    void anthropicWithoutFallbackSupportForOtherModelsAndRetriesWithoutIt() throws Exception {
        try (FakeHttp http = new FakeHttp()) {
            http.text("/v1/messages", 200, "text/event-stream", anthropicStream("hi"));
            new AnthropicClient(Http.client(), http.uri(""), "k", "claude-haiku-4-5").chat(CONVERSATION, s -> { }, new Cancel());
            assertThat(http.headers.getFirst().containsKey("anthropic-beta")).isFalse();
            assertThat(JSON.readTree(http.bodies.getFirst()).has("fallbacks")).isFalse();
        }
        try (FakeHttp http = new FakeHttp()) {
            int[] calls = {0};
            http.handle("/v1/messages", ex -> {
                boolean first = calls[0]++ == 0;
                byte[] b = (first ? "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"fallbacks: not available\"}}"
                        : anthropicStream("ok")).getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(first ? 400 : 200, b.length);
                ex.getResponseBody().write(b);
            });
            String r = new AnthropicClient(Http.client(), http.uri(""), "k", "claude-opus-5-5").chat(CONVERSATION, s -> { }, new Cancel());
            assertThat(r).isEqualTo("ok");
            assertThat(calls[0]).isEqualTo(2);
        }
    }

    @Test
    void anthropicErrorsAndRefusals() throws Exception {
        try (FakeHttp http = new FakeHttp()) {
            http.text("/v1/messages", 200, "text/event-stream",
                    "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"}}\n\n");
            var c = new AnthropicClient(Http.client(), http.uri(""), "k", "claude-opus-5-5");
            assertThatThrownBy(() -> c.chat(CONVERSATION, s -> { }, new Cancel())).hasMessageContaining("declined");
        }
        try (FakeHttp http = new FakeHttp()) {
            http.text("/v1/messages", 200, "text/event-stream",
                    "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n");
            var c = new AnthropicClient(Http.client(), http.uri(""), "k", "claude-opus-5-5");
            assertThatThrownBy(() -> c.chat(CONVERSATION, s -> { }, new Cancel())).hasMessageContaining("Overloaded");
        }
        assertThatThrownBy(() -> new AnthropicClient(Http.client(), AnthropicClient.defaultBase(), "", null).chat(CONVERSATION, s -> { }, new Cancel()))
                .hasMessageContaining("API key");
    }
}
