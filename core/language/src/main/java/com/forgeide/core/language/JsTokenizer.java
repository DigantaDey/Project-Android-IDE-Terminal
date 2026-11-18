package com.forgeide.core.language;

import java.util.List;
import java.util.Set;

/**
 * JavaScript/TypeScript tokenizer: extends the C-style base with template
 * literals (with ${} nesting) and a best-effort regex-literal heuristic.
 */
public class JsTokenizer implements Tokenizer {

    private final Set<String> keywords;
    private final Set<String> builtins;

    public JsTokenizer(Set<String> keywords, Set<String> builtins) {
        this.keywords = keywords;
        this.builtins = builtins;
    }

    @Override
    public List<Token> tokenize(String text) {
        Scanner s = new Scanner(text);
        char prevSignificant = 0;
        while (!s.atEnd()) {
            char c = s.peek();
            if (c == '/' && s.peek(1) == '/') {
                s.mark(TokenType.COMMENT);
                s.readToEol();
                s.flush();
                continue;
            }
            if (c == '/' && s.peek(1) == '*') {
                boolean doc = s.peek(2) == '*';
                s.mark(doc ? TokenType.DOC_COMMENT : TokenType.COMMENT);
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
                prevSignificant = c;
                continue;
            }
            if (c == '`') {
                s.mark(TokenType.STRING);
                readTemplateLiteral(s);
                s.flush();
                prevSignificant = '`';
                continue;
            }
            // Regex literal: '/' that is not a comment and follows an operator/opening context
            if (c == '/' && isRegexContext(prevSignificant)) {
                int save = s.i;
                if (readRegex(s)) {
                    s.emit(save, s.i, TokenType.REGEX);
                    s.flush();
                    prevSignificant = '/';
                    continue;
                }
                s.i = save;
            }
            if (Scanner.isDigit(c) || (c == '.' && Scanner.isDigit(s.peek(1)))) {
                s.mark(TokenType.NUMBER);
                s.readNumber();
                s.flush();
                prevSignificant = '1';
                continue;
            }
            if (Scanner.isWordStart(c)) {
                String word = s.readWord();
                s.emit(s.i - word.length(), s.i, Scanner.classifyWord(word, keywords, builtins));
                prevSignificant = 'a';
                continue;
            }
            if (CLikeTokenizer.isOperator(c) || c == ';' || c == ',' || c == '.') {
                s.mark(TokenType.OPERATOR);
                s.advance();
                s.flush();
                prevSignificant = c == ';' || c == ',' ? ';' : 'o';
                continue;
            }
            s.mark(TokenType.PUNCTUATION);
            s.advance();
            s.flush();
            prevSignificant = c;
        }
        return s.finish();
    }

    private static boolean isRegexContext(char prev) {
        // After these, a '/' starts a regex rather than a division
        return (
            prev == 0 ||
            prev == 'o' ||
            prev == ';' ||
            prev == '(' ||
            prev == '{' ||
            prev == '[' ||
            prev == '!'
        );
    }

    /** Reads /pattern/flags. Returns false if it doesn't terminate as a regex. */
    private static boolean readRegex(Scanner s) {
        s.advance(); // leading /
        boolean inClass = false;
        while (!s.atEnd()) {
            char c = s.peek();
            if (c == '\\') {
                s.advance();
                if (!s.atEnd()) {
                    s.advance();
                }
                continue;
            }
            if (c == '\n') {
                return false;
            }
            if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            } else if (c == '/' && !inClass) {
                s.advance();
                while (!s.atEnd() && Character.isLetter(s.peek())) {
                    s.advance(); // flags
                }
                return true;
            }
            s.advance();
        }
        return false;
    }

    private static void readTemplateLiteral(Scanner s) {
        s.advance(); // backtick
        int braceDepth = 0;
        while (!s.atEnd()) {
            char c = s.advance();
            if (c == '\\') {
                if (!s.atEnd()) {
                    s.advance();
                }
                continue;
            }
            if (c == '`' && braceDepth == 0) {
                return;
            }
            if (c == '$' && s.peek() == '{') {
                braceDepth++;
                s.advance();
            } else if (c == '}' && braceDepth > 0) {
                braceDepth--;
            }
        }
    }
}
