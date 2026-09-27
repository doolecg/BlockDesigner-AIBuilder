package io.blockdesigner.aibuilder.llm;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** A model to talk to. Replies stream: {@code onText} gets each piece as it arrives. */
public interface ChatClient {
    /**
     * Sends the conversation (system message first, if any) and returns the whole reply. Blocks: call it off the
     * UI thread.
     *
     * @throws Cancel.CancelledException when {@code cancel} is triggered
     */
    String chat(List<ChatMessage> messages, Consumer<String> onText, Cancel cancel) throws IOException;

    /** Whether pictures can be sent to it. */
    boolean vision();

    /** A short name for the chat ("Gemma 4 E4B on this PC", "Claude claude-opus-5-5"). */
    String describe();
}
