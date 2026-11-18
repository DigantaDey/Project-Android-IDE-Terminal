package com.forgeide.core.editor;

/** A zero-based (line, column) position in a document. */
public final class Position implements Comparable<Position> {

    public final int line;
    public final int column;

    public Position(int line, int column) {
        if (line < 0 || column < 0) {
            throw new IllegalArgumentException("Position must be non-negative: " + line + ":" + column);
        }
        this.line = line;
        this.column = column;
    }

    @Override
    public int compareTo(Position other) {
        if (line != other.line) {
            return Integer.compare(line, other.line);
        }
        return Integer.compare(column, other.column);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Position)) {
            return false;
        }
        Position other = (Position) o;
        return line == other.line && column == other.column;
    }

    @Override
    public int hashCode() {
        return 31 * line + column;
    }

    @Override
    public String toString() {
        return (line + 1) + ":" + (column + 1);
    }
}
