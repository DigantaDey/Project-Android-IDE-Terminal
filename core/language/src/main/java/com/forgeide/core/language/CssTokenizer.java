package com.forgeide.core.language;

import java.util.List;

/**
 * CSS tokenizer: comments, at-rules, selectors, properties, values,
 * strings, numbers with units, hex colors.
 */
public class CssTokenizer implements Tokenizer {

    @Override
    public List<Token> tokenize(String text) {
        Scanner s = new Scanner(text);
        boolean inBlock = false; // inside { ... }
        while (!s.atEnd()) {
            char c = s.peek();
            if (c == '/' && s.peek(1) == '*') {
                s.mark(TokenType.COMMENT);
                s.advance();
                s.advance();
                while (!s.atEnd()) {
                    if (s.peek() == '*' && s.peek(1) == '/') {
                        s.advance();
                        s.advance();
                        break;
                    }
                    s.advance();
                }
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
            if (c == '"' || c == '\'') {
                s.mark(TokenType.STRING);
                s.readString(c, false);
                s.flush();
                continue;
            }
            if (c == '@') {
                s.mark(TokenType.PREPROCESSOR);
                s.advance();
                s.readWord();
                s.flush();
                continue;
            }
            if (c == '#' && isHexStart(s)) {
                s.mark(TokenType.CONSTANT);
                s.advance();
                while (!s.atEnd() && isHex(s.peek())) {
                    s.advance();
                }
                s.flush();
                continue;
            }
            if (c == '#' || c == '.' || c == '*' || c == ':' || c == '[') {
                if (!inBlock && (c == '#' || c == '.' || c == '*' || c == ':' || c == '[')) {
                    // Selector part
                    s.mark(TokenType.SELECTOR);
                    s.advance();
                    if (c == '[') {
                        while (!s.atEnd() && s.peek() != ']') {
                            s.advance();
                        }
                        if (!s.atEnd()) {
                            s.advance();
                        }
                    } else {
                        while (!s.atEnd() && (Scanner.isWordChar(s.peek()) || s.peek() == '-')) {
                            s.advance();
                        }
                    }
                    s.flush();
                    continue;
                }
            }
            if (Scanner.isDigit(c)) {
                s.mark(TokenType.NUMBER);
                s.advance();
                while (!s.atEnd() && (Scanner.isDigit(s.peek()) || s.peek() == '.')) {
                    s.advance();
                }
                // unit
                while (!s.atEnd() && (Character.isLetter(s.peek()) || s.peek() == '%')) {
                    s.advance();
                }
                s.flush();
                continue;
            }
            if (Scanner.isWordStart(c) || c == '-') {
                int save = s.i;
                String word = s.readCssWord();
                // property if followed (after spaces) by ':'; value/selector otherwise
                int j = s.i;
                while (j < s.n && (s.text.charAt(j) == ' ' || s.text.charAt(j) == '\t')) {
                    j++;
                }
                boolean property = inBlock && j < s.n && s.text.charAt(j) == ':';
                if (property) {
                    s.emit(save, s.i, TokenType.PROPERTY);
                } else if (inBlock) {
                    s.emit(save, s.i, isKnownValue(word) ? TokenType.CONSTANT : TokenType.VALUE);
                } else {
                    s.emit(save, s.i, TokenType.SELECTOR);
                }
                continue;
            }
            if (c == '{') {
                inBlock = true;
                s.mark(TokenType.PUNCTUATION);
                s.advance();
                s.flush();
                continue;
            }
            if (c == '}') {
                inBlock = false;
                s.mark(TokenType.PUNCTUATION);
                s.advance();
                s.flush();
                continue;
            }
            if (c == ':' || c == ';' || c == ',') {
                s.mark(TokenType.PUNCTUATION);
                s.advance();
                s.flush();
                continue;
            }
            s.mark(TokenType.PLAIN);
            s.advance();
            s.flush();
        }
        return s.finish();
    }

    private static boolean isHexStart(Scanner s) {
        char c = s.peek(1);
        return isHex(c);
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isKnownValue(String word) {
        switch (word) {
            case "auto":
            case "none":
            case "inherit":
            case "initial":
            case "unset":
            case "solid":
            case "absolute":
            case "relative":
            case "fixed":
            case "block":
            case "inline":
            case "flex":
            case "grid":
            case "center":
            case "left":
            case "right":
            case "bold":
            case "normal":
            case "italic":
            case "hidden":
            case "visible":
            case "transparent":
            case "important":
                return true;
            default:
                return false;
        }
    }
}
