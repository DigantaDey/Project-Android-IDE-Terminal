package com.forgeide.core.language;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** SQL or POSIX shell lexical analysis; no commands are evaluated by the lexer. */
public final class ScriptTokenizer implements Tokenizer {

    private final Set<String> keywords;
    private final boolean sql;

    public ScriptTokenizer(Set<String> keywords, boolean sql) {
        this.keywords = keywords;
        this.sql = sql;
    }

    @Override
    public List<Token> tokenize(String text) {
        Scanner s = new Scanner(text);
        while (!s.atEnd()) {
            char c = s.peek();
            if ((!sql && c == '#') || (sql && c == '-' && s.peek(1) == '-')) {
                s.mark(TokenType.COMMENT);
                s.readToEol();
                s.flush();
            } else if (sql && c == '/' && s.peek(1) == '*') {
                s.mark(TokenType.COMMENT);
                int end = text.indexOf("*/", s.i + 2);
                s.i = end < 0 ? s.n : end + 2;
                s.flush();
            } else if (c == '\'' || c == '"' || c == '`') {
                s.mark(TokenType.STRING);
                s.readString(c, true);
                s.flush();
            } else if ((Character.isWhitespace(c) || (sql && c == '\ufeff'))) {
                s.mark(TokenType.WHITESPACE);
                while (
                    !s.atEnd() && (Character.isWhitespace(s.peek()) || (sql && s.peek() == '\ufeff'))
                ) s.advance();
                s.flush();
            } else if (!sql && c == '$') {
                s.mark(TokenType.VARIABLE);
                s.advance();
                if (s.peek() == '{') {
                    s.advance();
                    while (!s.atEnd() && s.peek() != '}') s.advance();
                    if (!s.atEnd()) s.advance();
                } else s.readWord();
                s.flush();
            } else if (Scanner.isDigit(c)) {
                s.mark(TokenType.NUMBER);
                s.readNumber();
                s.flush();
            } else if (Scanner.isWordStart(c)) {
                int start = s.i;
                String word = s.readWord();
                s.emit(
                    start,
                    s.i,
                    keywords.contains(sql ? word.toUpperCase(Locale.ROOT) : word)
                        ? TokenType.KEYWORD
                        : TokenType.IDENTIFIER
                );
            } else {
                s.mark(CLikeTokenizer.isOperator(c) ? TokenType.OPERATOR : TokenType.PUNCTUATION);
                s.advance();
                s.flush();
            }
        }
        return s.finish();
    }
}
