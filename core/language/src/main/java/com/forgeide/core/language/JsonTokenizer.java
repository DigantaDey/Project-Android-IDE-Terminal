package com.forgeide.core.language;

import java.util.List;

/**
 * JSON tokenizer. Keys, strings, numbers, literals and punctuation are
 * classified; invalid characters are marked ERROR so the editor can flag
 * them inline. Authoritative error reporting comes from JsonDiagnostics.
 */
public class JsonTokenizer implements Tokenizer {

    @Override
    public List<Token> tokenize(String text) {
        Scanner s = new Scanner(text);
        while (!s.atEnd()) {
            char c = s.peek();
            if (Character.isWhitespace(c)) {
                s.mark(TokenType.WHITESPACE);
                while (!s.atEnd() && Character.isWhitespace(s.peek())) {
                    s.advance();
                }
                s.flush();
                continue;
            }
            if (c == '"') {
                int start = s.i;
                s.readString('"', false);
                // Key if followed by ':' (after whitespace)
                int j = s.i;
                while (j < s.n && (s.text.charAt(j) == ' ' || s.text.charAt(j) == '\t')) {
                    j++;
                }
                s.emit(
                    start,
                    s.i,
                    j < s.n && s.text.charAt(j) == ':' ? TokenType.PROPERTY : TokenType.STRING
                );
                continue;
            }
            if (Scanner.isDigit(c) || c == '-') {
                s.mark(TokenType.NUMBER);
                if (c == '-') {
                    s.advance();
                }
                s.readNumber();
                s.flush();
                continue;
            }
            if (Character.isLetter(c)) {
                int start = s.i;
                String word = s.readWord();
                switch (word) {
                    case "true":
                    case "false":
                        s.emit(start, s.i, TokenType.KEYWORD);
                        break;
                    case "null":
                        s.emit(start, s.i, TokenType.CONSTANT);
                        break;
                    default:
                        s.emit(start, s.i, TokenType.ERROR);
                        break;
                }
                continue;
            }
            if (c == '{' || c == '}' || c == '[' || c == ']') {
                s.mark(TokenType.PUNCTUATION);
                s.advance();
                s.flush();
                continue;
            }
            if (c == ':' || c == ',') {
                s.mark(TokenType.OPERATOR);
                s.advance();
                s.flush();
                continue;
            }
            s.mark(TokenType.ERROR);
            s.advance();
            s.flush();
        }
        return s.finish();
    }
}
