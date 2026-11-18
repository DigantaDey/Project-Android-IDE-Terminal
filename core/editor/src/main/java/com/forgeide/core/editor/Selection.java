package com.forgeide.core.editor;

/** Caret/selection state. A collapsed selection (anchor == caret) is just a caret. */
public final class Selection {
    private int anchor;
    private int caret;

    public Selection() {
        this(0, 0);
    }

    public Selection(int anchor, int caret) {
        this.anchor = Math.max(0, anchor);
        this.caret = Math.max(0, caret);
    }

    public static Selection caret(int offset) {
        return new Selection(offset, offset);
    }

    public int anchor() {
        return anchor;
    }

    public int caret() {
        return caret;
    }

    public boolean hasSelection() {
        return anchor != caret;
    }

    public int start() {
        return Math.min(anchor, caret);
    }

    public int end() {
        return Math.max(anchor, caret);
    }

    public int length() {
        return end() - start();
    }

    public Range range() {
        return new Range(start(), end());
    }

    public Selection withCaret(int newCaret) {
        return new Selection(anchor, newCaret);
    }

    public Selection collapseToCaret() {
        return new Selection(caret, caret);
    }

    public Selection collapseToStart() {
        return new Selection(start(), start());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Selection)) {
            return false;
        }
        Selection other = (Selection) o;
        return anchor == other.anchor && caret == other.caret;
    }

    @Override
    public int hashCode() {
        return 31 * anchor + caret;
    }

    @Override
    public String toString() {
        return "Selection[" + start() + ".." + end() + " caret=" + caret + "]";
    }
}
