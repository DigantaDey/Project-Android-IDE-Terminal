package com.forgeide.core.editor;

import com.forgeide.core.util.TextUtils;
import java.util.regex.Pattern;

/**
 * Computes and applies indentation according to {@link IndentRules} and the
 * user's tab/spaces configuration.
 */
public final class IndentationEngine {

    public final int tabSize;
    public final boolean useTabs;

    public IndentationEngine(int tabSize, boolean useTabs) {
        this.tabSize = Math.max(1, tabSize);
        this.useTabs = useTabs;
    }

    public String indentString(int columns) {
        return TextUtils.indentString(columns, tabSize, useTabs);
    }

    /**
     * Computes the indentation for a new line inserted after {@code lineIndex}.
     *
     * @param rules   language rules (may be {@link IndentRules#none()})
     * @param prevLine the full text of the previous line ("" if inserting at line 0)
     * @return the indent prefix to insert
     */
    public String indentForNewLine(IndentRules rules, String prevLine) {
        int base = TextUtils.leadingWhitespaceColumns(prevLine, tabSize);
        String trimmed = prevLine.trim();
        if (
            rules != null &&
            rules.increaseIndent != null &&
            !trimmed.isEmpty() &&
            matches(rules.increaseIndent, trimmed)
        ) {
            base += tabSize;
        }
        if (rules != null && rules.dedentNext != null && matches(rules.dedentNext, trimmed)) {
            base = Math.max(0, base - tabSize);
        }
        return indentString(base);
    }

    /**
     * Adjusts the indentation of a freshly typed line that begins with a
     * dedent trigger (e.g. the user typed '}' or 'else').
     */
    public String adjustForDedentTrigger(IndentRules rules, String currentLineContent) {
        String trimmed = currentLineContent.trim();
        if (rules == null || rules.decreaseIndent == null || trimmed.isEmpty()) {
            return null;
        }
        if (!matches(rules.decreaseIndent, trimmed)) {
            return null;
        }
        int current = TextUtils.leadingWhitespaceColumns(currentLineContent, tabSize);
        if (current < tabSize) {
            return null;
        }
        String newIndent = indentString(current - tabSize);
        return newIndent + trimmed;
    }

    /** Indents a range of lines by one level. Returns {firstOffset, insertedColumns}. */
    public void indentLines(Document document, int startLine, int endLineInclusive) {
        String unit = indentString(tabSize);
        for (int line = startLine; line <= endLineInclusive && line < document.lineCount(); line++) {
            if (document.lineLength(line) == 0) {
                continue; // don't add stray indentation to blank lines
            }
            document.insert(document.offsetAt(line, 0), unit);
        }
    }

    /** Dedents a range of lines by up to one level. */
    public void dedentLines(Document document, int startLine, int endLineInclusive) {
        for (int line = startLine; line <= endLineInclusive && line < document.lineCount(); line++) {
            String content = document.line(line);
            int remove = 0;
            if (content.startsWith("\t")) {
                remove = 1;
            } else {
                int spaces = Math.min(tabSize, content.length());
                while (remove < spaces && content.charAt(remove) == ' ') {
                    remove++;
                }
            }
            if (remove > 0) {
                document.delete(document.offsetAt(line, 0), remove);
            }
        }
    }

    private static boolean matches(Pattern pattern, String text) {
        return pattern.matcher(text).matches();
    }
}
