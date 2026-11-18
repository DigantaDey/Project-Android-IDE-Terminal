package com.forgeide.core.editor;

/** An immutable range of text expressed in offsets into a document. */
public final class Range {

    public final int start;
    public final int end;

    public Range(int start, int end) {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Invalid range: " + start + ".." + end);
        }
        this.start = start;
        this.end = end;
    }

    public int length() {
        return end - start;
    }

    public boolean isEmpty() {
        return start == end;
    }

    public boolean contains(int offset) {
        return offset >= start && offset < end;
    }

    public boolean containsInclusive(int offset) {
        return offset >= start && offset <= end;
    }

    public boolean intersects(Range other) {
        return this.start < other.end && other.start < this.end;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Range)) {
            return false;
        }
        Range other = (Range) o;
        return start == other.start && end == other.end;
    }

    @Override
    public int hashCode() {
        return 31 * start + end;
    }

    @Override
    public String toString() {
        return "[" + start + ", " + end + ")";
    }
}
