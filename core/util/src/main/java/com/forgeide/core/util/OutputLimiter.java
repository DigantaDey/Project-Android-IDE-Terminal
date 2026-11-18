package com.forgeide.core.util;

import java.util.function.Consumer;

/** UTF-8 byte budget, with no split surrogate pairs and no unbounded callback queues. */
public final class OutputLimiter {

    public static final class LimitExceeded extends RuntimeException {

        public LimitExceeded() {
            super("Output limit exceeded");
        }
    }

    private int remaining;
    private final Consumer<String> consumer;

    public OutputLimiter(int bytes, Consumer<String> consumer) {
        remaining = bytes;
        this.consumer = consumer;
    }

    public synchronized void accept(String text) {
        int end = 0,
            used = 0;
        while (end < text.length()) {
            int cp = text.codePointAt(end);
            int size = cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
            if (used + size > remaining) break;
            used += size;
            end += Character.charCount(cp);
        }
        if (end > 0) {
            consumer.accept(text.substring(0, end));
            remaining -= used;
        }
        if (end < text.length()) throw new LimitExceeded();
    }
}
