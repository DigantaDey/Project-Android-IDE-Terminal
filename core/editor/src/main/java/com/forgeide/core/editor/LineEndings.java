package com.forgeide.core.editor;

/** Line ending detection and normalization. */
public enum LineEndings {
    LF("\n"),
    CRLF("\r\n"),
    CR("\r");

    public final String value;

    LineEndings(String value) {
        this.value = value;
    }

    public static LineEndings detect(String text) {
        int crlf = 0;
        int lf = 0;
        int cr = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    crlf++;
                    i++;
                } else {
                    cr++;
                }
            } else if (c == '\n') {
                lf++;
            }
        }
        if (crlf >= lf && crlf >= cr && crlf > 0) {
            return CRLF;
        }
        if (cr > lf) {
            return CR;
        }
        return LF;
    }

    public static String normalize(String text, LineEndings target) {
        String lf = text.replace("\r\n", "\n").replace('\r', '\n');
        if (target == LF) {
            return lf;
        }
        return lf.replace("\n", target.value);
    }
}
