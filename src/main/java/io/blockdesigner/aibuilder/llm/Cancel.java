package io.blockdesigner.aibuilder.llm;

import java.io.Closeable;
import java.io.IOException;

/** Stops a request in flight: closes the response stream being read, and tells loops to give up. */
public final class Cancel {
    public static final class CancelledException extends IOException {
        public CancelledException() {
            super("Stopped");
        }
    }

    private volatile boolean cancelled;
    private volatile Closeable current;

    public void cancel() {
        cancelled = true;
        Closeable c = current;
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // closing is best effort
            }
        }
    }

    public boolean cancelled() {
        return cancelled;
    }

    public void check() throws CancelledException {
        if (cancelled) throw new CancelledException();
    }

    /** The stream to close on cancel (null clears it). */
    public void watch(Closeable c) throws CancelledException {
        current = c;
        if (cancelled && c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // closing is best effort
            }
            throw new CancelledException();
        }
    }
}
