package com.forgeide.core.language;

import java.util.List;

/**
 * A full-text lexer. Implementations must return tokens that tile the input
 * completely (every offset belongs to exactly one token) and run in O(n).
 * The editor re-tokenizes visible text on change; results may be cached by
 * document version.
 */
public interface Tokenizer {
    List<Token> tokenize(String text);
}
