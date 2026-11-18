package com.forgeide.core.language;

import java.util.*;

/** SQLite statement boundaries without splitting semicolons inside quoted strings or comments. */
public final class SqlStatements {

    public static List<String> split(String sql) {
        List<String> out = new ArrayList<>();
        int start = 0;
        char quote = 0;
        boolean line = false,
            block = false,
            trigger = false;
        int blockDepth = 0;
        List<String> prefix = new ArrayList<>();
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i),
                next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (line) {
                if (c == '\n') line = false;
                continue;
            }
            if (block) {
                if (c == '*' && next == '/') {
                    block = false;
                    i++;
                }
                continue;
            }
            if (quote != 0) {
                if (c == quote) {
                    if (next == quote && quote != ']') i++;
                    else quote = 0;
                }
                continue;
            }
            if (Character.isLetter(c)) {
                int from = i;
                while (
                    i + 1 < sql.length() &&
                    (Character.isLetterOrDigit(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')
                ) i++;
                String word = sql.substring(from, i + 1).toUpperCase(Locale.ROOT);
                if (prefix.size() < 4) prefix.add(word);
                if (
                    word.equals("TRIGGER") &&
                    !prefix.isEmpty() &&
                    prefix.get(0).equals("CREATE") &&
                    prefix.size() <= 3
                ) trigger = true;
                if (trigger && (word.equals("BEGIN") || word.equals("CASE"))) blockDepth++;
                else if (trigger && word.equals("END") && blockDepth > 0) blockDepth--;
                continue;
            }
            if (c == '-' && next == '-') {
                line = true;
                i++;
            } else if (c == '/' && next == '*') {
                block = true;
                i++;
            } else if (c == '\'' || c == '"' || c == '`' || c == '[') quote = c == '[' ? ']' : c;
            else if (c == ';' && (!trigger || blockDepth == 0)) {
                String statement = sql.substring(start, i).trim();
                if (!keyword(statement).isEmpty()) out.add(statement);
                start = i + 1;
                trigger = false;
                blockDepth = 0;
                prefix.clear();
                if (out.size() > 2000) throw new IllegalArgumentException(
                    "Maximum 2000 SQL statements per run"
                );
            }
        }
        if (quote != 0 || block) throw new IllegalArgumentException("Unclosed SQL quote or comment");
        String tail = sql.substring(start).trim();
        if (!keyword(tail).isEmpty()) out.add(tail);
        return out;
    }

    public static String keyword(String sql) {
        List<Token> tokens = new ScriptTokenizer(Set.of(), true).tokenize(sql);
        for (Token t : tokens)
            if (t.type != TokenType.COMMENT && t.type != TokenType.WHITESPACE) return sql
                .substring(t.start, t.end())
                .toUpperCase(Locale.ROOT);
        return "";
    }
}
