package com.forgeide.core.language;

/** A lexical token: (start, length, type) over the source text. */
public final class Token {
    public final int start;
    public final int length;
    public final TokenType type;

    public Token(int start, int length, TokenType type) {
        if (start < 0 || length < 0) {
            throw new IllegalArgumentException("Invalid token " + start + "+" + length);
        }
        this.start = start;
        this.length = length;
        this.type = type;
    }

    public int end() {
        return start + length;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Token)) {
            return false;
        }
        Token other = (Token) o;
        return start == other.start && length == other.length && type == other.type;
    }

    @Override
    public int hashCode() {
        int h = start;
        h = 31 * h + length;
        h = 31 * h + type.hashCode();
        return h;
    }

    @Override
    public String toString() {
        return type + "[" + start + ".." + end() + "]";
    }
}
