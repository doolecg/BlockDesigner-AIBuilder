package io.blockdesigner.aibuilder.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Reads a server-sent event stream: {@code event:} and {@code data:} lines, an event per blank line. */
final class Sse {
    @FunctionalInterface
    interface Handler {
        /** @return false to stop reading */
        boolean event(String event, String data) throws IOException;
    }

    private Sse() {
    }

    static void read(InputStream in, Handler handler, Cancel cancel) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String event = null;
            StringBuilder data = new StringBuilder();
            String line;
            try {
                while ((line = r.readLine()) != null) {
                    cancel.check();
                    if (line.isEmpty()) {
                        if (!data.isEmpty() || event != null) {
                            if (!handler.event(event == null ? "message" : event, data.toString())) return;
                        }
                        event = null;
                        data.setLength(0);
                        continue;
                    }
                    if (line.startsWith(":")) continue;
                    int colon = line.indexOf(':');
                    String field = colon < 0 ? line : line.substring(0, colon);
                    String value = colon < 0 ? "" : line.substring(colon + 1);
                    if (value.startsWith(" ")) value = value.substring(1);
                    if (field.equals("event")) event = value;
                    else if (field.equals("data")) {
                        if (!data.isEmpty()) data.append('\n');
                        data.append(value);
                    }
                }
                if (!data.isEmpty()) handler.event(event == null ? "message" : event, data.toString());
            } catch (IOException e) {
                if (cancel.cancelled()) throw new Cancel.CancelledException();
                throw e;
            }
            cancel.check();
        }
    }
}
