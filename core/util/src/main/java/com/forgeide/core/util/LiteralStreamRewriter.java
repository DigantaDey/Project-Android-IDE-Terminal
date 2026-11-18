package com.forgeide.core.util;

import java.util.Objects;

/** Literal replacement across arbitrary output chunks, retaining only a possible partial match. */
public final class LiteralStreamRewriter {

    private final String from;
    private final String to;
    private String pending = "";

    public LiteralStreamRewriter(String from, String to) {
        this.from = Objects.requireNonNull(from);
        this.to = Objects.requireNonNull(to);
        if (from.isEmpty()) throw new IllegalArgumentException("A stream pattern must not be empty");
    }

    public synchronized String append(String text) {
        String input = pending + text;
        StringBuilder output = new StringBuilder();
        int consumed = 0,
            match;
        while ((match = input.indexOf(from, consumed)) >= 0) {
            output.append(input, consumed, match).append(to);
            consumed = match + from.length();
        }
        int held = Math.min(from.length() - 1, input.length() - consumed);
        while (held > 0 && !input.regionMatches(input.length() - held, from, 0, held)) held--;
        int stable = input.length() - held;
        output.append(input, consumed, stable);
        pending = input.substring(stable);
        return output.toString();
    }

    public synchronized String finish() {
        String tail = pending;
        pending = "";
        return tail;
    }
}
