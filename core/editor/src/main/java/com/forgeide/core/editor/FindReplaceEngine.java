package com.forgeide.core.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Find/replace engine over a {@link Document}. Supports literal and regex search,
 * case sensitivity, whole-word matching, wrap-around navigation and replace-all.
 */
public final class FindReplaceEngine {

    public static final class Query {
        public final String pattern;
        public final boolean regex;
        public final boolean caseSensitive;
        public final boolean wholeWord;

        public Query(String pattern, boolean regex, boolean caseSensitive, boolean wholeWord) {
            this.pattern = pattern == null ? "" : pattern;
            this.regex = regex;
            this.caseSensitive = caseSensitive;
            this.wholeWord = wholeWord;
        }

        public static Query literal(String pattern) {
            return new Query(pattern, false, false, false);
        }
    }

    /** A single match; {@code groups} holds regex capture groups (group 0 = full match). */
    public static final class Match {
        public final Range range;
        public final String[] groups;

        Match(Range range, String[] groups) {
            this.range = range;
            this.groups = groups;
        }
    }

    private final Document document;

    public FindReplaceEngine(Document document) {
        this.document = document;
    }

    /** Compiles the query into a Pattern, or throws PatternSyntaxException for bad regex. */
    public Pattern compile(Query query) {
        String pattern = query.regex ? query.pattern : Pattern.quote(query.pattern);
        if (query.wholeWord) {
            pattern = "\\b(?:" + pattern + ")\\b";
        }
        int flags = query.caseSensitive ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        flags |= Pattern.MULTILINE;
        return Pattern.compile(pattern, flags);
    }

    public boolean isValid(Query query) {
        if (query.pattern.isEmpty()) {
            return false;
        }
        try {
            compile(query);
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /** Finds the next match at or after {@code fromOffset}; wraps around if {@code wrap}. */
    public Match findNext(Query query, int fromOffset, boolean wrap) {
        if (!isValid(query)) {
            return null;
        }
        String text = document.snapshot();
        Matcher m = compile(query).matcher(text);
        if (fromOffset >= 0 && fromOffset <= text.length() && m.find(fromOffset)) {
            return toMatch(m);
        }
        if (wrap && m.find(0)) {
            return toMatch(m);
        }
        return null;
    }

    /** Finds the previous match before {@code fromOffset}; wraps around if {@code wrap}. */
    public Match findPrevious(Query query, int fromOffset, boolean wrap) {
        List<Match> all = findAll(query, Integer.MAX_VALUE);
        Match previous = null;
        for (Match match : all) {
            if (match.range.start < fromOffset) {
                previous = match;
            } else {
                break;
            }
        }
        if (previous == null && wrap && !all.isEmpty()) {
            return all.get(all.size() - 1);
        }
        return previous;
    }

    /** All matches, capped at {@code maxResults} to keep large files responsive. */
    public List<Match> findAll(Query query, int maxResults) {
        List<Match> out = new ArrayList<>();
        if (!isValid(query)) {
            return out;
        }
        String text = document.snapshot();
        Matcher m = compile(query).matcher(text);
        while (out.size() < maxResults && m.find()) {
            out.add(toMatch(m));
            if (m.end() == m.start()) {
                // Guard against zero-width regex loops
                if (m.end() >= text.length()) {
                    break;
                }
                m.region(m.end() + 1, text.length());
            }
        }
        return out;
    }

    public int count(Query query) {
        return findAll(query, Integer.MAX_VALUE).size();
    }

    /**
     * Replaces the current match range with the replacement template.
     * Regex group references ($1, ${name}) are supported for regex queries.
     */
    public void replace(Query query, Match match, String replacement) {
        String resolved = resolveReplacement(query, match, replacement);
        document.replace(match.range.start, match.range.length(), resolved);
    }

    /** Replaces every match atomically (single undo step). Returns the number replaced. */
    public int replaceAll(Query query, String replacement, UndoRedoManager undo) {
        List<Match> matches = findAll(query, Integer.MAX_VALUE);
        if (matches.isEmpty()) {
            return 0;
        }
        if (undo != null) {
            undo.beginCompound();
        }
        try {
            // Apply from the end so earlier offsets remain valid.
            for (int i = matches.size() - 1; i >= 0; i--) {
                replace(query, matches.get(i), replacement);
            }
        } finally {
            if (undo != null) {
                undo.endCompound();
            }
        }
        return matches.size();
    }

    static String resolveReplacement(Query query, Match match, String replacement) {
        if (!query.regex || replacement.indexOf('$') < 0) {
            return replacement;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c == '$' && i + 1 < replacement.length()) {
                char next = replacement.charAt(i + 1);
                if (Character.isDigit(next)) {
                    int group = next - '0';
                    if (group < match.groups.length && match.groups[group] != null) {
                        sb.append(match.groups[group]);
                        i++;
                        continue;
                    }
                } else if (next == '$') {
                    sb.append('$');
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static Match toMatch(Matcher m) {
        String[] groups = new String[m.groupCount() + 1];
        for (int i = 0; i <= m.groupCount(); i++) {
            groups[i] = m.group(i);
        }
        return new Match(new Range(m.start(), m.end()), groups);
    }
}
