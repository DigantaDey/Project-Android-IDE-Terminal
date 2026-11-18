package com.forgeide.core.editor;

import com.forgeide.core.util.BoundedText;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded literal/regex navigation and single-pass, atomic replacement over a document snapshot. */
public final class FindReplaceEngine {

    private static final int MAX_RESULTS = 20000,
        MAX_CAPTURE_CHARS = 262144,
        MAX_REPLACED_CHARS = 8 * 1024 * 1024;

    public static final class Query {

        public final String pattern;
        public final boolean regex, caseSensitive, wholeWord;

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

    public Pattern compile(Query query) {
        String expression = query.regex ? query.pattern : Pattern.quote(query.pattern);
        if (query.wholeWord) expression = "\\b(?:" + expression + ")\\b";
        Pattern pattern = Pattern.compile(
            expression,
            Pattern.MULTILINE | (query.caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
        );
        if (pattern.matcher("").groupCount() > 100) throw new IllegalArgumentException(
            "Regex capture groups are limited to 100"
        );
        return pattern;
    }

    public boolean isValid(Query query) {
        if (query.pattern.isEmpty()) return false;
        try {
            compile(query);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private Matcher matcher(Query query, String text) {
        return compile(query).matcher(new BoundedText(text, 500));
    }

    public Match findNext(Query query, int fromOffset, boolean wrap) {
        if (!isValid(query)) return null;
        String text = document.snapshot();
        Matcher matcher = matcher(query, text);
        if (fromOffset >= 0 && fromOffset <= text.length() && matcher.find(fromOffset)) return toMatch(
            matcher
        );
        return wrap && matcher.find(0) ? toMatch(matcher) : null;
    }

    public Match findPrevious(Query query, int fromOffset, boolean wrap) {
        if (!isValid(query)) return null;
        Matcher matcher = matcher(query, document.snapshot());
        Match previous = null,
            last = null;
        while (matcher.find()) {
            last = toMatch(matcher);
            if (matcher.start() < fromOffset) previous = last;
            else if (previous != null || !wrap) break;
        }
        return previous == null && wrap ? last : previous;
    }

    /** Enumerated metadata is capped. Counting and replacement stream instead of allocating every match. */
    public List<Match> findAll(Query query, int maxResults) {
        List<Match> result = new ArrayList<>();
        if (!isValid(query) || maxResults <= 0) return result;
        Matcher matcher = matcher(query, document.snapshot());
        int limit = Math.min(MAX_RESULTS, maxResults),
            captureChars = 0;
        while (result.size() < limit && matcher.find()) {
            Match match = toMatch(matcher);
            for (String group : match.groups) if (group != null) captureChars += group.length();
            if (captureChars > 4 * 1024 * 1024) throw new BoundedText.LimitExceeded();
            result.add(match);
        }
        if (
            maxResults > MAX_RESULTS && result.size() == MAX_RESULTS && matcher.find()
        ) throw new BoundedText.LimitExceeded();
        return result;
    }

    public int count(Query query) {
        if (!isValid(query)) return 0;
        Matcher matcher = matcher(query, document.snapshot());
        int count = 0;
        long deadline = System.nanoTime() + 500_000_000L;
        while (matcher.find()) {
            count++;
            checkDeadline(count, deadline);
        }
        return count;
    }

    public void replace(Query query, Match match, String replacement) {
        String source = document.snapshot();
        Matcher matcher = matcher(query, source);
        if (
            !matcher.find(match.range.start) ||
            matcher.start() != match.range.start ||
            matcher.end() != match.range.end
        ) throw new IllegalStateException("Search result is stale");
        StringBuilder resolved = new StringBuilder();
        expand(resolved, matcher, replacement, query.regex);
        document.replace(match.range.start, match.range.length(), resolved.toString());
    }

    /** Validate and construct the entire result before making one undoable edit. No quadratic rescans. */
    public int replaceAll(Query query, String replacement, UndoRedoManager undo) {
        if (!isValid(query)) return 0;
        String source = document.snapshot();
        Matcher matcher = matcher(query, source);
        StringBuilder result = new StringBuilder(Math.min(source.length(), MAX_REPLACED_CHARS));
        int cursor = 0,
            count = 0;
        long deadline = System.nanoTime() + 500_000_000L;
        while (matcher.find()) {
            append(result, source, cursor, matcher.start());
            expand(result, matcher, replacement, query.regex);
            cursor = matcher.end();
            count++;
            checkDeadline(count, deadline);
        }
        if (count == 0) return 0;
        append(result, source, cursor, source.length());
        if (!source.equals(document.snapshot())) throw new IllegalStateException(
            "Document changed during replacement"
        );
        if (undo != null) undo.beginCompound();
        try {
            document.replace(0, source.length(), result.toString());
        } finally {
            if (undo != null) undo.endCompound();
        }
        return count;
    }

    private static void checkDeadline(int count, long deadline) {
        if (
            (count & 1023) == 0 && (System.nanoTime() > deadline || Thread.currentThread().isInterrupted())
        ) throw new BoundedText.LimitExceeded();
    }

    private static void append(StringBuilder result, String value, int from, int to) {
        if ((long) result.length() + to - from > MAX_REPLACED_CHARS) throw new IllegalArgumentException(
            "Replacement exceeds the 8 Mi-character safety limit"
        );
        result.append(value, from, to);
    }

    private static void append(StringBuilder result, String value) {
        if (value != null) append(result, value, 0, value.length());
    }

    /** Resolve one Java-style template with the same pre-allocation limit used by document replace-all. */
    public static String replacementValue(Matcher matcher, String replacement, boolean regex) {
        StringBuilder value = new StringBuilder();
        expand(value, matcher, replacement, regex);
        return value.toString();
    }

    /** Java Matcher replacement grammar, appended with pre-allocation growth checks for each capture. */
    private static void expand(StringBuilder result, Matcher matcher, String replacement, boolean regex) {
        if (!regex) {
            append(result, replacement);
            return;
        }
        for (int index = 0; index < replacement.length(); ) {
            char c = replacement.charAt(index++);
            if (c == '\\') {
                if (index == replacement.length()) throw new IllegalArgumentException(
                    "Trailing replacement escape"
                );
                append(result, replacement, index, index + 1);
                index++;
            } else if (c == '$') {
                if (index == replacement.length()) throw new IllegalArgumentException(
                    "Missing capture group after dollar sign"
                );
                if (replacement.charAt(index) == '{') {
                    int end = replacement.indexOf('}', index + 1);
                    if (end < 0) throw new IllegalArgumentException("Unclosed named capture group");
                    append(result, matcher.group(replacement.substring(index + 1, end)));
                    index = end + 1;
                } else {
                    char digit = replacement.charAt(index);
                    if (digit < '0' || digit > '9') throw new IllegalArgumentException(
                        "Invalid replacement capture group"
                    );
                    int group = digit - '0';
                    index++;
                    if (group > matcher.groupCount()) throw new IndexOutOfBoundsException(
                        "No group " + group
                    );
                    while (index < replacement.length()) {
                        digit = replacement.charAt(index);
                        if (digit < '0' || digit > '9') break;
                        int next = group * 10 + digit - '0';
                        if (next > matcher.groupCount()) break;
                        group = next;
                        index++;
                    }
                    append(result, matcher.group(group));
                }
            } else append(result, replacement, index - 1, index);
        }
    }

    private static Match toMatch(Matcher matcher) {
        String[] groups = new String[matcher.groupCount() + 1];
        int chars = 0;
        for (int i = 0; i < groups.length; i++) {
            if (matcher.start(i) >= 0) chars += matcher.end(i) - matcher.start(i);
            if (chars > MAX_CAPTURE_CHARS) throw new BoundedText.LimitExceeded();
            groups[i] = matcher.group(i);
        }
        return new Match(new Range(matcher.start(), matcher.end()), groups);
    }
}
