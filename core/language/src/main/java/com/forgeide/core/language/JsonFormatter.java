package com.forgeide.core.language;

import com.forgeide.core.util.Json;

/** Whitespace-only JSON layout formatter: string escapes and number lexemes are preserved exactly. */
public final class JsonFormatter {

    public static String format(String source, int size) {
        Json.parse(source);
        String unit = " ".repeat(Math.max(1, Math.min(8, size)));
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (c == '"') {
                out.append(c);
                while (++i < source.length()) {
                    char s = source.charAt(i);
                    out.append(s);
                    if (s == '\\' && i + 1 < source.length()) out.append(source.charAt(++i));
                    else if (s == '"') break;
                }
                continue;
            }
            if (c == '{' || c == '[') {
                out.append(c);
                int j = i + 1;
                while (j < source.length() && Character.isWhitespace(source.charAt(j))) j++;
                if (j < source.length() && (source.charAt(j) == '}' || source.charAt(j) == ']')) {
                    out.append(source.charAt(j));
                    i = j;
                } else {
                    depth++;
                    out.append('\n').append(unit.repeat(depth));
                }
            } else if (c == '}' || c == ']') {
                depth--;
                out.append('\n').append(unit.repeat(Math.max(0, depth))).append(c);
            } else if (c == ',') out.append(",\n").append(unit.repeat(depth));
            else if (c == ':') out.append(": ");
            else out.append(c);
        }
        return out.append('\n').toString();
    }
}
