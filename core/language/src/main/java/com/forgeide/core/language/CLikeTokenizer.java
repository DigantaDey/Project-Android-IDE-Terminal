package com.forgeide.core.language;

import java.util.List;
import java.util.Set;

/**
 * Tokenizer for C-style languages (C, C++, Java, and as a base for others):
 * line/block comments, strings, chars, numbers, preprocessor lines, annotations.
 */
public class CLikeTokenizer implements Tokenizer {

    private final Set<String> keywords;
    private final Set<String> builtins;
    private final boolean preprocessor;
    private final boolean annotations;

    public CLikeTokenizer(Set<String> keywords, Set<String> builtins,
                          boolean preprocessor, boolean annotations) {
        this.keywords = keywords;
        this.builtins = builtins;
        this.preprocessor = preprocessor;
        this.annotations = annotations;
    }

    @Override
    public List<Token> tokenize(String text) {
        Scanner s = new Scanner(text);
        boolean docComment = false;
        while (!s.atEnd()) {
            char c = s.peek();
            // Comments
            if (c == '/' && s.peek(1) == '/') {
                s.mark(TokenType.COMMENT);
                s.readToEol();
                s.flush();
                continue;
            }
            if (c == '/' && s.peek(1) == '*') {
                docComment = s.peek(2) == '*';
                s.mark(docComment ? TokenType.DOC_COMMENT : TokenType.COMMENT);
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
            // Preprocessor directive at line start
            if (preprocessor && c == '#' && s.isAtLineStart()) {
                s.mark(TokenType.PREPROCESSOR);
                s.readToEol();
                // line continuation
                while (!s.atEnd() && s.peek() == '\\' && (s.peek(1) == '\n')) {
                    s.advance();
                    s.advance();
                    s.readToEol();
                }
                s.flush();
                continue;
            }
            // Annotation
            if (annotations && c == '@' && Scanner.isWordStart(s.peek(1))) {
                s.mark(TokenType.ANNOTATION);
                s.advance();
                s.readWord();
                s.flush();
                continue;
            }
            // Whitespace
            if (Character.isWhitespace(c)) {
                s.mark(TokenType.WHITESPACE);
                while (!s.atEnd() && Character.isWhitespace(s.peek())) {
                    s.advance();
                }
                s.flush();
                continue;
            }
            // Strings and chars
            if (c == '"' || c == '\'') {
                s.mark(c == '"' ? TokenType.STRING : TokenType.CHAR);
                s.readString(c, false);
                s.flush();
                continue;
            }
            // Numbers
            if (Scanner.isDigit(c) || (c == '.' && Scanner.isDigit(s.peek(1)))) {
                s.mark(TokenType.NUMBER);
                s.readNumber();
                s.flush();
                continue;
            }
            // Words
            if (Scanner.isWordStart(c)) {
                String word = s.readWord();
                s.emit(s.i - word.length(), s.i, Scanner.classifyWord(word, keywords, builtins));
                continue;
            }
            // Operators / punctuation
            if (isOperator(c)) {
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

    static boolean isOperator(char c) {
        return "+-*/%=!<>&|^~?:".indexOf(c) >= 0;
    }
}
