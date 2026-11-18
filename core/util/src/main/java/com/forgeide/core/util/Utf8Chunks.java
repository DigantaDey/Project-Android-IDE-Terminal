package com.forgeide.core.util;

import java.nio.*;
import java.nio.charset.*;

/** Incremental decoding for arbitrary PTY byte boundaries, including split multibyte characters. */
public final class Utf8Chunks {

    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private byte[] pending = new byte[0];
    private boolean finished;

    public synchronized String append(byte[] bytes) {
        return decode(bytes, false);
    }

    public synchronized String finish() {
        return finished ? "" : decode(new byte[0], true);
    }

    private String decode(byte[] bytes, boolean end) {
        if (finished) return "";
        byte[] all = new byte[pending.length + bytes.length];
        System.arraycopy(pending, 0, all, 0, pending.length);
        System.arraycopy(bytes, 0, all, pending.length, bytes.length);
        ByteBuffer input = ByteBuffer.wrap(all);
        CharBuffer output = CharBuffer.allocate(all.length + 2);
        CoderResult result = decoder.decode(input, output, end);
        if (result.isError() || result.isOverflow()) throw new IllegalStateException(
            "UTF-8 decoder invariant"
        );
        pending = new byte[input.remaining()];
        input.get(pending);
        if (end) {
            decoder.flush(output);
            finished = true;
        }
        output.flip();
        return output.toString();
    }
}
