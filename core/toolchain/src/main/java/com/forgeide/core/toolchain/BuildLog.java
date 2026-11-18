package com.forgeide.core.toolchain;

import com.forgeide.core.language.Diagnostic;
import java.util.*;
import java.util.regex.*;

/** Bounded build history and GCC/Clang/javac/Python-style source diagnostics. */
public final class BuildLog {
    private static final Pattern SOURCE = Pattern.compile("^(.+?):(\\d+)(?::(\\d+))?:\\s*(?:(fatal error|error|warning|note):)?\\s*(.*)$");
    private final Deque<String> runs = new ArrayDeque<>();
    public synchronized void add(String output) { runs.addFirst(output.length() > 262144 ? output.substring(output.length() - 262144) : output); while (runs.size() > 20) runs.removeLast(); }
    public synchronized List<String> history() { return new ArrayList<>(runs); }
    public static List<Diagnostic> diagnostics(String output) {
        List<Diagnostic> result = new ArrayList<>();
        for (String line : output.split("\n")) {
            Matcher m = SOURCE.matcher(line); if (!m.matches()) continue;
            try { result.add(new Diagnostic(m.group(1), Integer.parseInt(m.group(2)) - 1, m.group(3) == null ? 0 : Integer.parseInt(m.group(3)) - 1, "warning".equals(m.group(4)) ? Diagnostic.Severity.WARNING : "note".equals(m.group(4)) ? Diagnostic.Severity.INFO : Diagnostic.Severity.ERROR, m.group(5), "compiler")); } catch (NumberFormatException ignored) { }
        } return result;
    }
}
