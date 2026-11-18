package com.forgeide.core.terminal;

import java.util.*;

/** POSIX-style quoting/escaping for a virtual shell. Deliberately no host-shell substitution. */
public final class ShellWords {
    public static List<String> parse(String input, Map<String, String> env) {
        List<String> out = new ArrayList<>(); StringBuilder word = new StringBuilder(); char quote = 0; boolean active = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && quote != '\'') { if (++i == input.length()) throw new IllegalArgumentException("Trailing escape"); word.append(input.charAt(i)); active = true; }
            else if ((c == '\'' || c == '"') && (quote == 0 || quote == c)) { quote = quote == 0 ? c : 0; active = true; }
            else if (c == '$' && quote != '\'') {
                int start = i + 1, end = start;
                if (start < input.length() && input.charAt(start) == '{') { start++; end = input.indexOf('}', start); if (end < 0) throw new IllegalArgumentException("Unclosed variable"); i = end; }
                else { while (end < input.length() && (Character.isLetterOrDigit(input.charAt(end)) || input.charAt(end) == '_')) end++; i = end - 1; }
                if (end == start) word.append('$'); else word.append(env.getOrDefault(input.substring(start, end), "")); active = true;
            } else if (quote == 0 && Character.isWhitespace(c)) { if (active) { out.add(word.toString()); word.setLength(0); active = false; } }
            else if (quote == 0 && "|&;<>".indexOf(c) >= 0) throw new IllegalArgumentException("Pipes, redirection and host process control require an installed isolated runtime provider");
            else { word.append(c); active = true; }
        }
        if (quote != 0) throw new IllegalArgumentException("Unclosed quote");
        if (active) out.add(word.toString()); return out;
    }
}
