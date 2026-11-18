package com.forgeide.core.language;

import com.forgeide.core.editor.AutoCloseEngine;
import com.forgeide.core.editor.IndentRules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Static definition of a programming language: identity, file association,
 * comment/bracket/indent conventions and keyword sets. Languages are
 * registered with {@link LanguageRegistry}; new languages can be added by
 * plugins without touching the editor core.
 */
public final class Language {
    private final String id;
    private final String displayName;
    private final Set<String> extensions;
    private final Set<String> fileNames;
    private final String lineComment;
    private final String blockCommentStart;
    private final String blockCommentEnd;
    private final IndentRules indentRules;
    private final AutoCloseEngine autoClose;
    private final Set<String> keywords;
    private final boolean wordWrapPreferred;

    private Language(Builder b) {
        this.id = b.id;
        this.displayName = b.displayName;
        this.extensions = Collections.unmodifiableSet(b.extensions);
        this.fileNames = Collections.unmodifiableSet(b.fileNames);
        this.lineComment = b.lineComment;
        this.blockCommentStart = b.blockCommentStart;
        this.blockCommentEnd = b.blockCommentEnd;
        this.indentRules = b.indentRules;
        this.autoClose = b.autoClose;
        this.keywords = Collections.unmodifiableSet(b.keywords);
        this.wordWrapPreferred = b.wordWrapPreferred;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Set<String> extensions() {
        return extensions;
    }

    public Set<String> fileNames() {
        return fileNames;
    }

    public String lineComment() {
        return lineComment;
    }

    public String blockCommentStart() {
        return blockCommentStart;
    }

    public String blockCommentEnd() {
        return blockCommentEnd;
    }

    public IndentRules indentRules() {
        return indentRules;
    }

    public AutoCloseEngine autoClose() {
        return autoClose;
    }

    public Set<String> keywords() {
        return keywords;
    }

    /** Markdown and other prose formats prefer word wrap on by default. */
    public boolean wordWrapPreferred() {
        return wordWrapPreferred;
    }

    /** Extracts the word at an offset in text (letters, digits, _, $). */
    public static String wordAt(String text, int offset) {
        if (offset < 0 || offset > text.length()) {
            return "";
        }
        int start = offset;
        int end = offset;
        while (start > 0 && isWordChar(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && isWordChar(text.charAt(end))) {
            end++;
        }
        return text.substring(start, end);
    }

    public static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    public static final class Builder {
        private final String id;
        private final String displayName;
        private final Set<String> extensions = new LinkedHashSet<>();
        private final Set<String> fileNames = new LinkedHashSet<>();
        private String lineComment;
        private String blockCommentStart;
        private String blockCommentEnd;
        private IndentRules indentRules = IndentRules.none();
        private AutoCloseEngine autoClose = AutoCloseEngine.cStyle();
        private final Set<String> keywords = new LinkedHashSet<>();
        private boolean wordWrapPreferred;

        public Builder(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public Builder extensions(String... exts) {
            extensions.addAll(Arrays.asList(exts));
            return this;
        }

        public Builder fileNames(String... names) {
            fileNames.addAll(Arrays.asList(names));
            return this;
        }

        public Builder lineComment(String prefix) {
            this.lineComment = prefix;
            return this;
        }

        public Builder blockComment(String start, String end) {
            this.blockCommentStart = start;
            this.blockCommentEnd = end;
            return this;
        }

        public Builder indentRules(IndentRules rules) {
            this.indentRules = rules;
            return this;
        }

        public Builder autoClose(AutoCloseEngine engine) {
            this.autoClose = engine;
            return this;
        }

        public Builder keywords(String... words) {
            keywords.addAll(Arrays.asList(words));
            return this;
        }

        public Builder keywordsFrom(String spaceSeparated) {
            for (String w : spaceSeparated.trim().split("\\s+")) {
                keywords.add(w);
            }
            return this;
        }

        public Builder wordWrapPreferred(boolean preferred) {
            this.wordWrapPreferred = preferred;
            return this;
        }

        public Language build() {
            if (id == null || displayName == null) {
                throw new IllegalStateException("Language id and displayName are required");
            }
            return new Language(this);
        }
    }
}
