package com.forgeide.core.editor;

/**
 * Bracket matching over raw text with basic awareness of strings and
 * line comments so brackets inside them are ignored.
 */
public final class BracketMatcher {

    private static final String OPEN = "([{<";
    private static final String CLOSE = ")]}>";

    /**
     * Finds the offset of the bracket matching the bracket at {@code offset},
     * or -1 if there is no bracket at the offset or no match.
     */
    public static int findMatch(String text, int offset) {
        if (offset < 0 || offset >= text.length()) {
            return -1;
        }
        char c = text.charAt(offset);
        int openIdx = OPEN.indexOf(c);
        int closeIdx = CLOSE.indexOf(c);
        if (openIdx < 0 && closeIdx < 0) {
            return -1;
        }
        boolean forward = openIdx >= 0;
        char open = forward ? c : OPEN.charAt(closeIdx);
        char close = forward ? CLOSE.charAt(openIdx) : c;

        int depth = 0;
        boolean inString = false;
        char stringChar = 0;
        boolean inLineComment = false;
        boolean escaped = false;

        if (forward) {
            for (int i = offset; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (inLineComment) {
                    if (ch == '\n') {
                        inLineComment = false;
                    }
                    continue;
                }
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (ch == '\\') {
                        escaped = true;
                    } else if (ch == stringChar) {
                        inString = false;
                    }
                    continue;
                }
                if (ch == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                    inLineComment = true;
                    continue;
                }
                if (ch == '"' || ch == '\'' || ch == '`') {
                    inString = true;
                    stringChar = ch;
                    continue;
                }
                if (ch == open) {
                    depth++;
                } else if (ch == close) {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        } else {
            // Backward scan: strings/comments are not reliably detectable
            // backward, so use plain nesting (good enough for highlighting).
            for (int i = offset; i >= 0; i--) {
                char ch = text.charAt(i);
                if (ch == close) {
                    depth++;
                } else if (ch == open) {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }
}
