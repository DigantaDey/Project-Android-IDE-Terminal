package com.forgeide.core.editor;

/** A foldable region of lines. endLine is inclusive; the first line stays visible. */
public final class FoldingRegion {
    public final int startLine;
    public final int endLine;
    public final String kind; // "braces", "indent", "comment", "region", "tag", "heading"

    public FoldingRegion(int startLine, int endLine, String kind) {
        if (endLine <= startLine) {
            throw new IllegalArgumentException("Fold region must span at least 2 lines");
        }
        this.startLine = startLine;
        this.endLine = endLine;
        this.kind = kind;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FoldingRegion)) {
            return false;
        }
        FoldingRegion other = (FoldingRegion) o;
        return startLine == other.startLine && endLine == other.endLine;
    }

    @Override
    public int hashCode() {
        return 31 * startLine + endLine;
    }

    @Override
    public String toString() {
        return "Fold[" + (startLine + 1) + ".." + (endLine + 1) + " " + kind + "]";
    }
}
