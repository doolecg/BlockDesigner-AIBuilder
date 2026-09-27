package io.blockdesigner.aibuilder.llm;

import java.util.List;

/** One message of a conversation: who said it, the text, and any pictures (base64) attached to it. */
public record ChatMessage(Role role, String text, List<Image> images) {
    public enum Role { SYSTEM, USER, ASSISTANT }

    /** A picture as sent to a model: {@code image/png} or {@code image/jpeg}, base64 without line breaks. */
    public record Image(String mimeType, String base64) {
    }

    public ChatMessage {
        images = images == null ? List.of() : List.copyOf(images);
        text = text == null ? "" : text;
    }

    public static ChatMessage system(String text) {
        return new ChatMessage(Role.SYSTEM, text, List.of());
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text, List.of());
    }

    public static ChatMessage user(String text, List<Image> images) {
        return new ChatMessage(Role.USER, text, images);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage(Role.ASSISTANT, text, List.of());
    }
}
