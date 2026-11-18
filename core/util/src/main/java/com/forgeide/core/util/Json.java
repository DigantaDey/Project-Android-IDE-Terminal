package com.forgeide.core.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON parser/serializer.
 *
 * <p>Numbers are returned as {@link Long} when integral and {@link Double} otherwise.
 * Objects preserve insertion order. The parser reports precise positions so it can
 * double as the engine behind the JSON linter in the language module.</p>
 */
public final class Json {

    private Json() {}

    /** A parse error carrying a precise source position. */
    public static final class JsonException extends RuntimeException {

        private final int offset;
        private final int line;
        private final int column;

        public JsonException(String message, int offset, int line, int column) {
            super(message + " (line " + line + ", column " + column + ")");
            this.offset = offset;
            this.line = line;
            this.column = column;
        }

        public int getOffset() {
            return offset;
        }

        public int getLine() {
            return line;
        }

        public int getColumn() {
            return column;
        }
    }

    public static Object parse(String text) throws JsonException {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.parseValue();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw p.error("Unexpected trailing content");
        }
        return value;
    }

    /** Parses a JSON document expected to be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws JsonException {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("Expected JSON object at top level", 0, 1, 1);
        }
        return (Map<String, Object>) v;
    }

    /** Parses a JSON document expected to be an array. */
    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String text) throws JsonException {
        Object v = parse(text);
        if (!(v instanceof List)) {
            throw new JsonException("Expected JSON array at top level", 0, 1, 1);
        }
        return (List<Object>) v;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, -1, 0);
        return sb.toString();
    }

    public static String writePretty(Object value, int indent) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, Math.max(0, indent), 0);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Parser
    // ------------------------------------------------------------------

    private static final class Parser {

        private final String text;
        private int pos;
        private int line = 1;
        private int col = 1;
        private int depth;

        Parser(String text) {
            this.text = text == null ? "" : text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        char peek() {
            return atEnd() ? '\0' : text.charAt(pos);
        }

        char advance() {
            char c = text.charAt(pos++);
            if (c == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
            return c;
        }

        JsonException error(String message) {
            return new JsonException(message, pos, line, col);
        }

        void skipWhitespace() {
            while (!atEnd()) {
                char c = peek();
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    advance();
                } else {
                    break;
                }
            }
        }

        Object parseValue() {
            if (++depth > 128) throw error("JSON nesting exceeds 128 levels");
            try {
                return parseValueInternal();
            } finally {
                depth--;
            }
        }

        private Object parseValueInternal() {
            if (atEnd()) {
                throw error("Unexpected end of input");
            }
            char c = peek();
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expectLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    expectLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    expectLiteral("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw error("Unexpected character '" + printable(c) + "'");
            }
        }

        private String printable(char c) {
            if (c < 0x20 || c == 0x7F) {
                return "\\u" + String.format("%04x", (int) c);
            }
            return Character.toString(c);
        }

        private void expectLiteral(String literal) {
            for (int i = 0; i < literal.length(); i++) {
                if (atEnd() || peek() != literal.charAt(i)) {
                    throw error("Invalid literal, expected '" + literal + "'");
                }
                advance();
            }
        }

        private Map<String, Object> parseObject() {
            advance(); // {
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                advance();
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("Expected string key in object");
                }
                String key = parseString();
                skipWhitespace();
                if (peek() != ':') {
                    throw error("Expected ':' after object key");
                }
                advance();
                skipWhitespace();
                Object value = parseValue();
                if (map.containsKey(key)) throw error("Duplicate object key: " + key);
                map.put(key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    advance();
                } else if (c == '}') {
                    advance();
                    return map;
                } else {
                    throw error("Expected ',' or '}' in object");
                }
            }
        }

        private List<Object> parseArray() {
            advance(); // [
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                advance();
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    advance();
                } else if (c == ']') {
                    advance();
                    return list;
                } else {
                    throw error("Expected ',' or ']' in array");
                }
            }
        }

        private String parseString() {
            advance(); // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("Unterminated string");
                }
                char c = advance();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw error("Unterminated escape sequence");
                    }
                    char esc = advance();
                    switch (esc) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            sb.append(parseUnicodeEscape());
                            break;
                        default:
                            throw error("Invalid escape character '\\" + esc + "'");
                    }
                } else if (c < 0x20) {
                    throw error("Unescaped control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        private String parseUnicodeEscape() {
            int first = readHex4();
            // Surrogate pair support
            if (
                first >= 0xD800 &&
                first <= 0xDBFF &&
                pos + 1 < text.length() &&
                text.charAt(pos) == '\\' &&
                text.charAt(pos + 1) == 'u'
            ) {
                advance();
                advance();
                int second = readHex4();
                if (second >= 0xDC00 && second <= 0xDFFF) {
                    int codePoint = 0x10000 + ((first - 0xD800) << 10) + (second - 0xDC00);
                    return new String(Character.toChars(codePoint));
                }
                throw error("Invalid low surrogate in \\u escape");
            }
            if (first >= 0xD800 && first <= 0xDFFF) {
                throw error("Unpaired surrogate in \\u escape");
            }
            return Character.toString((char) first);
        }

        private int readHex4() {
            int value = 0;
            for (int i = 0; i < 4; i++) {
                if (atEnd()) {
                    throw error("Truncated \\u escape");
                }
                char c = advance();
                int digit = Character.digit(c, 16);
                if (digit < 0) {
                    throw error("Invalid hex digit in \\u escape");
                }
                value = (value << 4) | digit;
            }
            return value;
        }

        private Number parseNumber() {
            int start = pos;
            boolean isDouble = false;
            if (peek() == '-') {
                advance();
            }
            if (atEnd()) {
                throw error("Truncated number");
            }
            if (peek() == '0') {
                advance();
            } else if (Character.isDigit(peek())) {
                while (!atEnd() && Character.isDigit(peek())) {
                    advance();
                }
            } else {
                throw error("Invalid number");
            }
            if (!atEnd() && peek() == '.') {
                isDouble = true;
                advance();
                if (atEnd() || !Character.isDigit(peek())) {
                    throw error("Expected digit after decimal point");
                }
                while (!atEnd() && Character.isDigit(peek())) {
                    advance();
                }
            }
            if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
                isDouble = true;
                advance();
                if (!atEnd() && (peek() == '+' || peek() == '-')) {
                    advance();
                }
                if (atEnd() || !Character.isDigit(peek())) {
                    throw error("Expected digit in exponent");
                }
                while (!atEnd() && Character.isDigit(peek())) {
                    advance();
                }
            }
            String raw = text.substring(start, pos);
            try {
                if (isDouble) {
                    double value = Double.parseDouble(raw);
                    if (!Double.isFinite(value)) throw error("Number is outside the supported finite range");
                    return value;
                }
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                throw error("Invalid number '" + raw + "'");
            }
        }
    }

    // ------------------------------------------------------------------
    // Serializer
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value, int indent, int level) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Number) {
            writeNumber(sb, (Number) value);
        } else if (value instanceof Map) {
            writeObject(sb, (Map<String, Object>) value, indent, level);
        } else if (value instanceof List) {
            writeArray(sb, (List<Object>) value, indent, level);
        } else if (value instanceof Enum) {
            writeString(sb, ((Enum<?>) value).name());
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeNumber(StringBuilder sb, Number number) {
        if (number instanceof Double || number instanceof Float) {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append(Long.toString((long) d));
            } else {
                sb.append(number.toString());
            }
        } else {
            sb.append(number.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<String, Object> map, int indent, int level) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (first) {
                first = false;
            } else {
                sb.append(',');
            }
            newline(sb, indent, level + 1);
            writeString(sb, e.getKey());
            sb.append(':');
            if (indent >= 0) {
                sb.append(' ');
            }
            writeValue(sb, e.getValue(), indent, level + 1);
        }
        newline(sb, indent, level);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<Object> list, int indent, int level) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        boolean first = true;
        for (Object v : list) {
            if (first) {
                first = false;
            } else {
                sb.append(',');
            }
            newline(sb, indent, level + 1);
            writeValue(sb, v, indent, level + 1);
        }
        newline(sb, indent, level);
        sb.append(']');
    }

    private static void newline(StringBuilder sb, int indent, int level) {
        if (indent < 0) {
            return;
        }
        sb.append('\n');
        for (int i = 0; i < indent * level; i++) {
            sb.append(' ');
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
