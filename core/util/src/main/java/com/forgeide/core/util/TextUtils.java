package com.forgeide.core.util;

/** Common text helpers shared by editor, language and search modules. */
public final class TextUtils {

    private TextUtils() {
    }

    /** Counts leading whitespace columns, expanding tabs to the given width. */
    public static int leadingWhitespaceColumns(String line, int tabSize) {
        int col = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                col++;
            } else if (c == '\t') {
                col += tabSize - (col % tabSize);
            } else {
                break;
            }
        }
        return col;
    }

    /** Returns the leading whitespace prefix of the line. */
    public static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    /** Creates an indent string of the requested visual depth. */
    public static String indentString(int columns, int tabSize, boolean useTabs) {
        StringBuilder sb = new StringBuilder();
        if (useTabs && tabSize > 0) {
            while (columns >= tabSize) {
                sb.append('\t');
                columns -= tabSize;
            }
        }
        while (columns-- > 0) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Detects the dominant indentation style of a document.
     * Returns {indentSize, useTabsAsOneOrZero}.
     */
    public static int[] detectIndentation(String text, int fallbackSize) {
        int tabLines = 0;
        int spaceLines = 0;
        int[] histogram = new int[9]; // 1..8 spaces
        String[] lines = text.split("\n", -1);
        int prevSpaceDepth = -1;
        for (String line : lines) {
            if (line.isEmpty() || line.trim().isEmpty()) {
                continue;
            }
            if (line.charAt(0) == '\t') {
                tabLines++;
                continue;
            }
            int spaces = 0;
            while (spaces < line.length() && line.charAt(spaces) == ' ') {
                spaces++;
            }
            if (spaces > 0) {
                spaceLines++;
                if (prevSpaceDepth >= 0 && spaces > prevSpaceDepth) {
                    int delta = spaces - prevSpaceDepth;
                    if (delta >= 1 && delta <= 8) {
                        histogram[delta]++;
                    }
                }
                prevSpaceDepth = spaces;
            }
        }
        if (tabLines > spaceLines) {
            return new int[]{fallbackSize, 1};
        }
        int best = 4;
        int bestCount = 0;
        for (int d = 1; d <= 8; d++) {
            if (histogram[d] > bestCount) {
                bestCount = histogram[d];
                best = d;
            }
        }
        return new int[]{bestCount == 0 ? fallbackSize : best, 0};
    }

    /** Levenshtein edit distance, capped for performance on long strings. */
    public static int levenshtein(String a, String b) {
        if (a == null || b == null) {
            return Integer.MAX_VALUE;
        }
        if (a.length() > 512 || b.length() > 512) {
            return Integer.MAX_VALUE;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    /** True when the string looks like a valid file/directory name on all platforms. */
    public static boolean isValidFileName(String name) {
        if (name == null || name.isEmpty() || name.length() > 255) {
            return false;
        }
        if (name.equals(".") || name.equals("..")) {
            return false;
        }
        if (name.charAt(name.length() - 1) == ' ' || name.charAt(name.length() - 1) == '.') {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|') {
                return false;
            }
        }
        return true;
    }

    /** Counts occurrences of a character in a string. */
    public static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }

    /** Null-safe trim to a maximum length for display. */
    public static String ellipsize(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
