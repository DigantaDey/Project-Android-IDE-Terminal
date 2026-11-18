package com.forgeide.core.language;

import java.util.List;
import java.util.Set;

/**
 * Python tokenizer: comments, single/double/triple strings with prefix
 * modifiers (r, b, f, u), decorators, numbers, keywords and builtins.
 */
public class PythonTokenizer implements Tokenizer {

    private final Set<String> keywords;
    private final Set<String> builtins;

    public PythonTokenizer(Set<String> keywords, Set<String> builtins) {
        this.keywords = keywords;
        this.builtins = builtins;
    }

    @Override
    public List<Token> tokenize(String text) {
        Scanner s = new Scanner(text);
        while (!s.atEnd()) {
            char c = s.peek();
            if (c == '#') {
                s.mark(TokenType.COMMENT);
                s.readToEol();
                s.flush();
                continue;
            }
            if (Character.isWhitespace(c)) {
                s.mark(TokenType.WHITESPACE);
                while (!s.atEnd() && Character.isWhitespace(s.peek())) {
                    s.advance();
                }
                s.flush();
                continue;
            }
            if (c == '@' && Scanner.isWordStart(s.peek(1))) {
                s.mark(TokenType.ANNOTATION);
                s.advance();
                s.readWord();
                while (s.peek() == '.' && Scanner.isWordStart(s.peek(1))) {
                    s.advance();
                    s.readWord();
                }
                s.flush();
                continue;
            }
            if (c == '"' || c == '\'') {
                s.mark(TokenType.STRING);
                readPythonString(s);
                s.flush();
                continue;
            }
            if (Scanner.isDigit(c)) {
                s.mark(TokenType.NUMBER);
                s.readNumber();
                s.flush();
                continue;
            }
            if (Scanner.isWordStart(c)) {
                int save = s.i;
                String word = s.readWord();
                char next = s.peek();
                if ((next == '"' || next == '\'') && isStringPrefix(word)) {
                    // String with prefix (r"...", f'...'): token spans prefix + string
                    s.i = save;
                    s.mark(TokenType.STRING);
                    s.readWord();
                    readPythonString(s);
                    s.flush();
                    continue;
                }
                s.emit(s.i - word.length(), s.i, Scanner.classifyWord(word, keywords, builtins));
                continue;
            }
            if (CLikeTokenizer.isOperator(c)) {
                s.mark(TokenType.OPERATOR);
                s.advance();
                s.flush();
                continue;
            }
            s.mark(TokenType.PUNCTUATION);
            s.advance();
            s.flush();
        }
        return s.finish();
    }

    private static boolean isStringPrefix(String word) {
        String w = word.toLowerCase();
        return w.equals("r") || w.equals("b") || w.equals("f") || w.equals("u")
                || w.equals("rb") || w.equals("br") || w.equals("rf") || w.equals("fr");
    }

    private static void readPythonString(Scanner s) {
        char quote = s.peek();
        boolean triple = s.peek(1) == quote && s.peek(2) == quote;
        if (triple) {
            s.advance();
            s.advance();
            s.advance();
            while (!s.atEnd()) {
                char c = s.advance();
                if (c == '\\') {
                    if (!s.atEnd()) {
                        s.advance();
                    }
                    continue;
                }
                if (c == quote && s.peek() == quote && s.peek(1) == quote) {
                    s.advance();
                    s.advance();
                    return;
                }
            }
        } else {
            s.readString(quote, false);
        }
    }
}
