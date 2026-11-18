package com.forgeide.core.editor;

/**
 * Higher-level editing operations used by commands and shortcuts:
 * line duplication, moving, joining, comment toggling.
 */
public final class EditOperations {

    private EditOperations() {
    }

    /** Duplicates the given line below itself. */
    public static void duplicateLine(Document document, int line) {
        String content = document.line(line);
        int insertAt = document.offsetAt(line, 0) + document.lineLength(line);
        document.insert(insertAt, "\n" + content);
    }

    public static boolean moveLineUp(Document document, int line) {
        if (line <= 0 || line >= document.lineCount()) {
            return false;
        }
        String content = document.line(line);
        int lineStart = document.offsetAt(line, 0);
        int lineEnd = lineStart + document.lineLength(line);
        // Remove the line and its preceding newline
        document.delete(lineStart - 1, document.lineLength(line) + 1);
        int prevStart = document.offsetAt(line - 1, 0);
        document.insert(prevStart, content + "\n");
        return true;
    }

    public static boolean moveLineDown(Document document, int line) {
        if (line < 0 || line >= document.lineCount() - 1) {
            return false;
        }
        String content = document.line(line);
        // Remove the line and its trailing newline; the next line shifts up to `line`.
        document.delete(document.offsetAt(line, 0), document.lineLength(line) + 1);
        // Insert the removed content after the line that took its place.
        int insertAt = document.offsetAt(line, 0) + document.lineLength(line);
        document.insert(insertAt, "\n" + content);
        return true;
    }

    /** Joins the given line with the next one, collapsing whitespace between. */
    public static boolean joinLines(Document document, int line) {
        if (line < 0 || line >= document.lineCount() - 1) {
            return false;
        }
        int endOfLine = document.offsetAt(line, 0) + document.lineLength(line);
        String next = document.line(line + 1);
        int trimmedLeading = next.length() - next.stripLeading().length();
        document.replace(endOfLine, 1 + trimmedLeading, " ");
        return true;
    }

    /** Deletes a whole line including its line separator. */
    public static void deleteLine(Document document, int line) {
        int start = document.offsetAt(line, 0);
        if (line == document.lineCount() - 1 && line > 0) {
            document.delete(start - 1, document.lineLength(line) + 1);
        } else {
            document.delete(start, Math.min(document.lineLength(line) + 1, document.length() - start));
        }
    }

    /**
     * Toggles a line comment prefix on a range of lines. If every non-blank
     * line is commented, all are uncommented; otherwise all are commented.
     */
    public static void toggleComment(Document document, int startLine, int endLine, String prefix) {
        boolean allCommented = true;
        for (int i = startLine; i <= endLine && i < document.lineCount(); i++) {
            String line = document.line(i);
            if (!line.trim().isEmpty() && !line.trim().startsWith(prefix)) {
                allCommented = false;
                break;
            }
        }
        if (allCommented) {
            for (int i = startLine; i <= endLine && i < document.lineCount(); i++) {
                String line = document.line(i);
                int idx = line.indexOf(prefix);
                if (idx >= 0) {
                    int offset = document.offsetAt(i, idx);
                    int len = prefix.length();
                    if (idx + len < line.length() && line.charAt(idx + len) == ' ') {
                        len++;
                    }
                    document.delete(offset, len);
                }
            }
        } else {
            int minIndent = Integer.MAX_VALUE;
            for (int i = startLine; i <= endLine && i < document.lineCount(); i++) {
                String line = document.line(i);
                if (!line.trim().isEmpty()) {
                    int indent = line.length() - line.stripLeading().length();
                    minIndent = Math.min(minIndent, indent);
                }
            }
            if (minIndent == Integer.MAX_VALUE) {
                return;
            }
            for (int i = startLine; i <= endLine && i < document.lineCount(); i++) {
                String line = document.line(i);
                if (line.trim().isEmpty()) {
                    continue;
                }
                document.insert(document.offsetAt(i, Math.min(minIndent, line.length())), prefix + " ");
            }
        }
    }
}
