package com.forgeide.core.util;

/** Thread-safe, fixed-allocation UTF-16 tail buffer; tiny writes never copy the entire transcript. */
public final class BoundedLog {

    private final char[] buffer;
    private int start, length;

    public BoundedLog(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Positive capacity required");
        buffer = new char[capacity];
    }

    public synchronized void append(String text) {
        int from = Math.max(0, text.length() - buffer.length),
            count = text.length() - from;
        if (count == 0) return;
        if (count == buffer.length) {
            start = 0;
            length = 0;
        }
        int excess = Math.max(0, length + count - buffer.length);
        start = (start + excess) % buffer.length;
        length -= excess;
        int end = (start + length) % buffer.length,
            first = Math.min(count, buffer.length - end);
        text.getChars(from, from + first, buffer, end);
        if (first < count) text.getChars(from + first, text.length(), buffer, 0);
        length += count;
        // A truncated tail must not begin with the second half of a valid pair.
        if (length > 0 && Character.isLowSurrogate(buffer[start])) {
            start = (start + 1) % buffer.length;
            length--;
        }
    }

    public synchronized void clear() {
        start = 0;
        length = 0;
    }

    public synchronized void set(String text) {
        clear();
        append(text);
    }

    public synchronized boolean isEmpty() {
        return length == 0;
    }

    @Override
    public synchronized String toString() {
        int first = Math.min(length, buffer.length - start);
        if (first == length) return new String(buffer, start, length);
        return new String(buffer, start, first) + new String(buffer, 0, length - first);
    }
}
