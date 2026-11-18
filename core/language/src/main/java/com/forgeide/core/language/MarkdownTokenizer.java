package com.forgeide.core.language;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Linear, line-oriented Markdown highlighting. Unmatched delimiters remain plain text. */
public final class MarkdownTokenizer implements Tokenizer {

    private static final Pattern INLINE = Pattern.compile(
        "(`+)[^`\\n]*`+|\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__|\\*[^*\\n]+\\*|_[^_\\n]+_|!?\\[[^\\]\\n]*\\]\\([^\\)\\n]*\\)"
    );

    @Override
    public List<Token> tokenize(String text) {
        List<Token> out = new ArrayList<>();
        String fence = null;
        for (int start = 0; start < text.length(); ) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline + 1;
            String line = text.substring(start, end);
            String trim = line.stripLeading();
            TokenType block = null;
            if (trim.startsWith("```") || trim.startsWith("~~~")) {
                String marker = trim.substring(0, 3);
                if (fence == null) fence = marker;
                else if (fence.equals(marker)) fence = null;
                block = TokenType.CODE;
            } else if (fence != null) block = TokenType.CODE;
            else if (
                trim.matches("#{1,6}(?:[ \\t].*|\\s*)") || trim.trim().matches("(?:={3,}|-{3,})")
            ) block = TokenType.HEADING;
            else if (trim.startsWith(">")) block = TokenType.QUOTE;
            if (block != null) out.add(new Token(start, end - start, block));
            else {
                Matcher m = INLINE.matcher(line);
                int pos = 0;
                while (m.find()) {
                    if (m.start() > pos) out.add(new Token(start + pos, m.start() - pos, TokenType.PLAIN));
                    String part = m.group();
                    TokenType type = part.startsWith("`")
                        ? TokenType.CODE
                        : part.startsWith("[") || part.startsWith("![")
                            ? TokenType.LINK
                            : part.startsWith("**") || part.startsWith("__")
                                ? TokenType.STRONG
                                : TokenType.EMPHASIS;
                    out.add(new Token(start + m.start(), m.end() - m.start(), type));
                    pos = m.end();
                }
                if (pos < line.length()) out.add(
                    new Token(start + pos, line.length() - pos, TokenType.PLAIN)
                );
            }
            start = end;
        }
        return out;
    }
}
