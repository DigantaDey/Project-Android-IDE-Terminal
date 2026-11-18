package com.forgeide.core.editor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Automatic bracket/quote closing engine. Configured per language with a set of
 * pairs; given the text around the caret it decides how a typed character should
 * be handled (insert pair, overtype the closing character, or plain insert),
 * and whether backspace should delete an empty pair.
 */
public final class AutoCloseEngine {

    public enum Action {
        /** Insert the character normally. */
        INSERT_SINGLE,
        /** Insert the opening and closing character, caret between them. */
        INSERT_PAIR,
        /** The closing character already exists at the caret: just move past it. */
        OVERTYPE,
        /** Typing the closing quote at a position before an identical quote should overtype. */
        OVERTYPE_QUOTE
    }

    private final Map<Character, Character> pairs;
    private final boolean closeQuotes;

    public AutoCloseEngine(Map<Character, Character> pairs, boolean closeQuotes) {
        this.pairs = Collections.unmodifiableMap(new LinkedHashMap<>(pairs));
        this.closeQuotes = closeQuotes;
    }

    public static AutoCloseEngine cStyle() {
        Map<Character, Character> pairs = new LinkedHashMap<>();
        pairs.put('(', ')');
        pairs.put('[', ']');
        pairs.put('{', '}');
        pairs.put('"', '"');
        pairs.put('\'', '\'');
        return new AutoCloseEngine(pairs, true);
    }

    public static AutoCloseEngine none() {
        return new AutoCloseEngine(Collections.emptyMap(), false);
    }

    public boolean isOpening(char c) {
        Character close = pairs.get(c);
        return close != null && close != c;
    }

    public boolean isClosing(char c) {
        return pairs.containsValue(c) && !isOpening(c);
    }

    public boolean isQuote(char c) {
        Character close = pairs.get(c);
        return close != null && close == c;
    }

    public Character closingFor(char open) {
        return pairs.get(open);
    }

    /**
     * Decides how to handle a typed character.
     *
     * @param c         the character typed
     * @param textBeforeCaret full text before the caret
     * @param textAfterCaret  full text after the caret (may be empty)
     */
    public Action onCharTyped(char c, String textBeforeCaret, String textAfterCaret) {
        char next = textAfterCaret.isEmpty() ? '\0' : textAfterCaret.charAt(0);
        char prev = textBeforeCaret.isEmpty() ? '\0' : textBeforeCaret.charAt(textBeforeCaret.length() - 1);

        if (isQuote(c) && closeQuotes) {
            if (next == c) {
                return Action.OVERTYPE_QUOTE;
            }
            // Only auto-close a quote when it looks like an opening position:
            // previous char is whitespace, an opening bracket, an operator, or nothing.
            if (prev == '\0' || Character.isWhitespace(prev) || isOpening(prev)
                    || "=(:,[!&|?;{}+-*/%^~<>".indexOf(prev) >= 0) {
                // And the following char must not be a word character.
                if (next == '\0' || !isWordChar(next)) {
                    return Action.INSERT_PAIR;
                }
            }
            return Action.INSERT_SINGLE;
        }

        if (isOpening(c)) {
            if (next == '\0' || Character.isWhitespace(next) || isClosing(next) || next == c) {
                return Action.INSERT_PAIR;
            }
            return Action.INSERT_SINGLE;
        }

        if (isClosing(c) && next == c) {
            // Overtype only when this doesn't unbalance: count stack depth of c
            // remaining in text before caret is zero.
            if (unclosedCount(c, textBeforeCaret) == 1) {
                return Action.OVERTYPE;
            }
        }
        return Action.INSERT_SINGLE;
    }

    /**
     * When the caret sits between an empty pair (e.g. "{}", "()") and the user
     * presses backspace, both characters should be deleted.
     *
     * @return number of extra characters after the caret to delete (0 or 1)
     */
    public int backspacePairDelete(String textBeforeCaret, String textAfterCaret) {
        if (textBeforeCaret.isEmpty() || textAfterCaret.isEmpty()) {
            return 0;
        }
        char prev = textBeforeCaret.charAt(textBeforeCaret.length() - 1);
        char next = textAfterCaret.charAt(0);
        Character expected = pairs.get(prev);
        if (expected != null && expected == next) {
            return 1;
        }
        return 0;
    }

    private int unclosedCount(char closing, String text) {
        char opening = '\0';
        for (Map.Entry<Character, Character> e : pairs.entrySet()) {
            if (e.getValue() == closing && !e.getKey().equals(e.getValue())) {
                opening = e.getKey();
                break;
            }
        }
        if (opening == '\0') {
            return 0;
        }
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == opening) {
                depth++;
            } else if (c == closing) {
                depth--;
            }
        }
        return Math.max(depth, 0);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
