package com.forgeide.core.language;

/** Source positions are zero-based throughout the core. */
public final class Diagnostic {

    public enum Severity {
        ERROR,
        WARNING,
        INFO,
    }

    public final String file, message, source;
    public final int line, column;
    public final Severity severity;

    public Diagnostic(String file, int line, int column, Severity severity, String message, String source) {
        this.file = file;
        this.line = Math.max(0, line);
        this.column = Math.max(0, column);
        this.severity = severity;
        this.message = message;
        this.source = source;
    }

    @Override
    public String toString() {
        return file + ":" + (line + 1) + ":" + (column + 1) + ": " + severity + ": " + message;
    }
}
