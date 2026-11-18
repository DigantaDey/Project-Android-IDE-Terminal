package com.forgeide.core.language;

import java.util.List;
import java.util.Locale;

/**
 * Tokenizer for HTML and XML. Tags, attributes, entities, comments, doctypes,
 * CDATA and processing instructions are handled. HTML mode additionally
 * delegates {@code <script>} and {@code <style>} bodies to the JS/CSS
 * tokenizers so embedded code is highlighted properly.
 */
public class MarkupTokenizer implements Tokenizer {

    private final boolean htmlMode;
    private final JsTokenizer jsTokenizer;
    private final CssTokenizer cssTokenizer;

    /** @param htmlMode true for HTML (embedded script/style), false for strict XML */
    public MarkupTokenizer(boolean htmlMode, JsTokenizer jsTokenizer, CssTokenizer cssTokenizer) {
        this.htmlMode = htmlMode;
        this.jsTokenizer = jsTokenizer;
        this.cssTokenizer = cssTokenizer;
    }

    @Override
    public List<Token> tokenize(String text) {
        Scanner s = new Scanner(text);
        while (!s.atEnd()) {
            char c = s.peek();
            if (c == '<') {
                if (s.peek(1) == '!' && s.peek(2) == '-' && s.peek(3) == '-') {
                    s.mark(TokenType.COMMENT);
                    readUntil(s, "-->");
                    s.flush();
                    continue;
                }
                if (s.peek(1) == '!' && startsWithIgnoreCase(s, "<![CDATA[")) {
                    s.mark(TokenType.STRING);
                    readToEndOf(s, "]]>");
                    s.flush();
                    continue;
                }
                if (s.peek(1) == '!') {
                    s.mark(TokenType.PREPROCESSOR); // DOCTYPE
                    readToEndOf(s, ">");
                    s.flush();
                    continue;
                }
                if (s.peek(1) == '?') {
                    s.mark(TokenType.PREPROCESSOR); // <?xml ... ?>
                    readToEndOf(s, "?>");
                    s.flush();
                    continue;
                }
                // Tag
                tokenizeTag(s);
                continue;
            }
            if (c == '&') {
                s.mark(TokenType.CONSTANT);
                readToEndOf(s, ";");
                s.flush();
                continue;
            }
            // Text content
            s.mark(TokenType.PLAIN);
            while (!s.atEnd() && s.peek() != '<' && s.peek() != '&') {
                s.advance();
            }
            s.flush();
        }
        return s.finish();
    }

    private void tokenizeTag(Scanner s) {
        int tagStart = s.i;
        s.mark(TokenType.TAG);
        s.advance(); // <
        if (s.peek() == '/') {
            s.advance();
        }
        while (!s.atEnd() && (Scanner.isWordChar(s.peek()) || s.peek() == '-' || s.peek() == ':')) {
            s.advance();
        }
        String tagName = s.text.substring(tagStart, s.i).toLowerCase(Locale.US);
        s.flush();
        // Attributes
        while (!s.atEnd() && s.peek() != '>') {
            char c = s.peek();
            if (Character.isWhitespace(c)) {
                s.mark(TokenType.WHITESPACE);
                while (!s.atEnd() && Character.isWhitespace(s.peek()) && s.peek() != '>') {
                    s.advance();
                }
                s.flush();
                continue;
            }
            if (c == '/' && s.peek(1) == '>') {
                break;
            }
            if (c == '=') {
                s.mark(TokenType.OPERATOR);
                s.advance();
                s.flush();
                continue;
            }
            if (c == '"' || c == '\'') {
                s.mark(TokenType.VALUE);
                s.readString(c, false);
                s.flush();
                continue;
            }
            int before = s.i;
            s.mark(TokenType.ATTRIBUTE);
            while (
                !s.atEnd() &&
                !Character.isWhitespace(s.peek()) &&
                s.peek() != '=' &&
                s.peek() != '>' &&
                s.peek() != '/'
            ) {
                s.advance();
            }
            if (s.i == before) s.advance();
            s.flush();
        }
        if (!s.atEnd() && s.peek() == '/') {
            s.mark(TokenType.TAG);
            s.advance();
            s.flush();
        }
        if (!s.atEnd() && s.peek() == '>') {
            s.mark(TokenType.TAG);
            s.advance();
            s.flush();
        }
        // Embedded language bodies
        if (htmlMode) {
            String open = tagName.startsWith("</") ? "" : tagName.substring(1);
            if ("script".equals(open) || "style".equals(open)) {
                String closer = "</" + open;
                int closeIdx = indexOfIgnoreCase(s.text, closer, s.i);
                int bodyEnd = closeIdx < 0 ? s.n : closeIdx;
                if (bodyEnd > s.i) {
                    String body = s.text.substring(s.i, bodyEnd);
                    Tokenizer inner = "script".equals(open) ? jsTokenizer : cssTokenizer;
                    if (inner != null) {
                        List<Token> tokens = inner.tokenize(body);
                        for (Token t : tokens) {
                            s.out.add(new Token(t.start + s.i, t.length, t.type));
                        }
                    } else {
                        s.out.add(new Token(s.i, bodyEnd - s.i, TokenType.PLAIN));
                    }
                    s.i = bodyEnd;
                }
            }
        }
    }

    private static void readUntil(Scanner s, String terminator) {
        readToEndOf(s, terminator);
    }

    private static void readToEndOf(Scanner s, String terminator) {
        int idx = s.text.indexOf(terminator, s.i);
        if (idx < 0) {
            s.i = s.n;
        } else {
            s.i = idx + terminator.length();
        }
    }

    private static boolean startsWithIgnoreCase(Scanner s, String what) {
        if (s.i + what.length() > s.n) {
            return false;
        }
        for (int j = 0; j < what.length(); j++) {
            if (Character.toLowerCase(s.text.charAt(s.i + j)) != Character.toLowerCase(what.charAt(j))) {
                return false;
            }
        }
        return true;
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        String h = haystack.toLowerCase(Locale.US);
        String n2 = needle.toLowerCase(Locale.US);
        return h.indexOf(n2, from);
    }
}
