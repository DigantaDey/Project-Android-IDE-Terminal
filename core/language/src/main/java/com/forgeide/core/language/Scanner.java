package com.forgeide.core.language;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Hand-written scanner helper shared by the bundled tokenizers. Guarantees the
 * tiling invariant: every byte of input belongs to exactly one token.
 */
final class Scanner {

    final String text;
    final int n;
    int i;
    final List<Token> out = new ArrayList<>();
    private int tokenStart;
    private TokenType pending;

    Scanner(String text) {
        this.text = text == null ? "" : text;
        this.n = this.text.length();
    }

    boolean atEnd() {
        return i >= n;
    }

    char peek() {
        return i < n ? text.charAt(i) : '\0';
    }

    char peek(int ahead) {
        return i + ahead < n ? text.charAt(i + ahead) : '\0';
    }

    char advance() {
        return i < n ? text.charAt(i++) : '\0';
    }

    /** Begins a new token at the current position. */
    void mark(TokenType type) {
        flush();
        tokenStart = i;
        pending = type;
    }

    /** Emits the pending token up to the current position. */
    void flush() {
        if (pending != null && i > tokenStart) {
            out.add(new Token(tokenStart, i - tokenStart, pending));
        }
        pending = null;
        tokenStart = i;
    }

    /** Immediately emits a token covering [from, to). */
    void emit(int from, int to, TokenType type) {
        flush();
        if (to > from) {
            out.add(new Token(from, to - from, type));
        }
        tokenStart = i;
    }

    /** Finishes and validates the token stream. */
    List<Token> finish() {
        flush();
        if (n == 0) {
            return out;
        }
        if (out.isEmpty()) {
            out.add(new Token(0, n, TokenType.PLAIN));
        }
        return out;
    }

    boolean isAtLineStart() {
        if (i == 0) {
            return true;
        }
        // only whitespace between newline and here
        for (int j = i - 1; j >= 0 && text.charAt(j) != '\n'; j--) {
            char c = text.charAt(j);
            if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** Reads a run of word characters and returns the word. */
    String readWord() {
        int start = i;
        while (!atEnd() && isWordChar(peek())) {
            i++;
        }
        return text.substring(start, i);
    }

    /** Reads a CSS identifier (word chars plus '-'). */
    String readCssWord() {
        int start = i;
        while (!atEnd() && (isWordChar(peek()) || peek() == '-')) {
            i++;
        }
        return text.substring(start, i);
    }

    /** Reads a quoted string handling escapes; leaves scanner after closing quote (or EOL/EOF). */
    void readString(char quote, boolean multiline) {
        advance(); // opening quote
        while (!atEnd()) {
            char c = advance();
            if (c == '\\') {
                if (!atEnd()) {
                    advance();
                }
                continue;
            }
            if (c == quote) {
                return;
            }
            if (c == '\n' && !multiline) {
                return;
            }
        }
    }

    void readToEol() {
        while (!atEnd() && peek() != '\n') {
            i++;
        }
    }

    void readNumber() {
        // 0x hex, 0b binary, 0o octal, floats, exponents, suffixes
        if (peek() == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            i += 2;
            while (
                !atEnd() &&
                (isDigit(peek()) ||
                    (peek() >= 'a' && peek() <= 'f') ||
                    (peek() >= 'A' && peek() <= 'F') ||
                    peek() == '_' ||
                    peek() == '\'')
            ) {
                i++;
            }
        } else if (peek() == '0' && (peek(1) == 'b' || peek(1) == 'B')) {
            i += 2;
            while (!atEnd() && (peek() == '0' || peek() == '1' || peek() == '_' || peek() == '\'')) {
                i++;
            }
        } else if (peek() == '0' && (peek(1) == 'o' || peek(1) == 'O')) {
            i += 2;
            while (!atEnd() && isDigit(peek())) {
                i++;
            }
        } else {
            while (!atEnd() && (isDigit(peek()) || peek() == '_' || peek() == '\'')) {
                i++;
            }
            if (peek() == '.' && isDigit(peek(1))) {
                i++;
                while (!atEnd() && isDigit(peek())) {
                    i++;
                }
            }
            if (peek() == 'e' || peek() == 'E') {
                int save = i;
                i++;
                if (peek() == '+' || peek() == '-') {
                    i++;
                }
                if (isDigit(peek())) {
                    while (!atEnd() && isDigit(peek())) {
                        i++;
                    }
                } else {
                    i = save;
                }
            }
        }
        // type suffixes (f, l, u, n, m...)
        while (
            !atEnd() &&
            (peek() == 'f' ||
                peek() == 'F' ||
                peek() == 'l' ||
                peek() == 'L' ||
                peek() == 'u' ||
                peek() == 'U' ||
                peek() == 'n')
        ) {
            i++;
        }
    }

    /** Classifies a word: keyword, builtin, constant or identifier. */
    static TokenType classifyWord(String word, Set<String> keywords, Set<String> builtins) {
        if (keywords != null && keywords.contains(word)) {
            return TokenType.KEYWORD;
        }
        if (builtins != null && builtins.contains(word)) {
            return TokenType.BUILTIN;
        }
        if (
            word.equals(word.toUpperCase()) && word.length() > 2 && word.chars().anyMatch(Character::isLetter)
        ) {
            return TokenType.CONSTANT; // SCREAMING_CASE heuristic
        }
        if (!word.isEmpty() && Character.isUpperCase(word.charAt(0))) return TokenType.TYPE;
        return TokenType.IDENTIFIER;
    }
}
