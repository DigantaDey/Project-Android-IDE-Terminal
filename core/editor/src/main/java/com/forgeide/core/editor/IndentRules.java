package com.forgeide.core.editor;

import java.util.regex.Pattern;

/**
 * Language-driven indentation rules. Patterns are matched against the trimmed
 * content of the previous / current line.
 */
public final class IndentRules {

    /** A line ending with this (e.g. '{', '(', '[', ':') deepens the next line. */
    public final Pattern increaseIndent;
    /** A line starting with this (e.g. '}', ')', ']', 'else', 'except') dedents itself. */
    public final Pattern decreaseIndent;
    /** A line matching this dedents the line after next (e.g. 'return', 'break' in Python). */
    public final Pattern dedentNext;
    /** Whether unclosed brackets on a line deepen indentation (C-style). */
    public final boolean bracketCounting;

    public IndentRules(
        Pattern increaseIndent,
        Pattern decreaseIndent,
        Pattern dedentNext,
        boolean bracketCounting
    ) {
        this.increaseIndent = increaseIndent;
        this.decreaseIndent = decreaseIndent;
        this.dedentNext = dedentNext;
        this.bracketCounting = bracketCounting;
    }

    /** C/Java/JS-style brace language rules. */
    public static IndentRules braces() {
        return new IndentRules(Pattern.compile(".*[{(:]\\s*$"), Pattern.compile("^[}\\])].*"), null, false);
    }

    /** Python rules: ':' deepens, return/break/pass/raise dedent the following line. */
    public static IndentRules python() {
        return new IndentRules(
            Pattern.compile(".*:\\s*(#.*)?$"),
            Pattern.compile("^(return|break|pass|raise|continue|else|elif|except|finally)\\b.*"),
            Pattern.compile("^\\s*(return|break|pass|raise|continue)\\b.*"),
            false
        );
    }

    /** No smart indentation: just copy the previous line's indent. */
    public static IndentRules none() {
        return new IndentRules(null, null, null, false);
    }
}
