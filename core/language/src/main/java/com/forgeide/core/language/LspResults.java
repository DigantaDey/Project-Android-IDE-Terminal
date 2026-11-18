package com.forgeide.core.language;

import com.forgeide.core.util.JsonObject;
import java.io.IOException;
import java.util.*;

/** Bounded LSP result decoding and validated UTF-16 edits. Server output is untrusted input. */
public final class LspResults {

    private LspResults() {}

    public static final int MAX_TEXT = 512 * 1024;

    @SuppressWarnings("unchecked")
    public static JsonObject object(Object value) {
        return value instanceof Map ? new JsonObject((Map<String, Object>) value) : JsonObject.empty();
    }

    private static int coordinate(JsonObject position, String key) {
        Object n = position.raw().get(key);
        if (!(n instanceof Number)) return -1;
        long value = ((Number) n).longValue();
        return value >= 0 && value <= MAX_TEXT && value == ((Number) n).doubleValue() ? (int) value : -1;
    }

    public static int offset(String source, JsonObject position) {
        int line = coordinate(position, "line"),
            column = coordinate(position, "character");
        if (line < 0 || column < 0) return -1;
        int start = 0;
        for (int n = 0; n < line; n++) {
            while (
                start < source.length() && source.charAt(start) != '\r' && source.charAt(start) != '\n'
            ) start++;
            if (start == source.length()) return -1;
            char end = source.charAt(start++);
            if (end == '\r' && start < source.length() && source.charAt(start) == '\n') start++;
        }
        int end = start;
        while (end < source.length() && source.charAt(end) != '\r' && source.charAt(end) != '\n') end++;
        if (column > end - start) return -1;
        int index = start + column;
        if (
            index > 0 &&
            index < source.length() &&
            Character.isHighSurrogate(source.charAt(index - 1)) &&
            Character.isLowSurrogate(source.charAt(index))
        ) return -1;
        return index;
    }

    public static final class Completion {

        public final String label, detail, text;
        public final int start, end;
        public int selection;

        Completion(String label, String detail, String text, int start, int end) {
            this.label = label;
            this.detail = detail;
            this.text = text;
            this.start = start;
            this.end = end;
            this.selection = start + text.length();
        }
    }

    public static List<Completion> completions(
        JsonObject response,
        String source,
        int cursor,
        int prefixStart
    ) {
        List<Completion> result = new ArrayList<>();
        if (
            source.length() > MAX_TEXT || prefixStart < 0 || prefixStart > cursor || cursor > source.length()
        ) return result;
        Object raw = response.raw().get("result");
        List<?> items = raw instanceof List ? (List<?>) raw : object(raw).getArray("items");
        int examined = 0;
        for (Object value : items) {
            if (++examined > 2000 || result.size() >= 100) break;
            JsonObject item = object(value);
            // No execution of commands, lazy resolution or partial auto-import edits.
            // VS Code CSS sends its harmless re-trigger command even when not advertised.
            // Ignore that one command; never execute it or any arbitrary server command.
            if (
                !item.getArray("additionalTextEdits").isEmpty() ||
                (item.has("command") &&
                    !item
                        .getObject("command")
                        .getString("command", "")
                        .equals("editor.action.triggerSuggest"))
            ) continue;
            String label = item.getString("label", "");
            if (label.isEmpty() || label.length() > 512) continue;
            int start = prefixStart,
                end = cursor;
            String text = item.getString("insertText", label);
            if (item.has("textEdit")) {
                JsonObject edit = item.getObject("textEdit");
                JsonObject range = edit.getObject("range");
                if (!range.has("start")) range = edit.getObject("replace");
                start = offset(source, range.getObject("start"));
                end = offset(source, range.getObject("end"));
                text = edit.getString("newText", "");
            }
            if (
                start < 0 ||
                end < start ||
                start > cursor ||
                end < cursor ||
                text.length() > 65536 ||
                text.indexOf('\0') >= 0
            ) continue;
            int marker = -1;
            if (item.getInt("insertTextFormat", 1) == 2) {
                // Not an interactive snippet engine: only a single final-cursor $0
                // can be losslessly converted to plain text. Reject all tabstops,
                // variables, choices and placeholder expressions.
                marker = text.indexOf("$0");
                if (marker >= 0) text = text.substring(0, marker) + text.substring(marker + 2);
                if (text.indexOf('$') >= 0 || text.indexOf('\\') >= 0) continue;
            } else if (item.getInt("insertTextFormat", 1) != 1) continue;
            String detail = item.getString("detail", "");
            Completion completion = new Completion(
                label,
                detail.substring(0, Math.min(1024, detail.length())),
                text,
                start,
                end
            );
            if (marker >= 0) completion.selection = start + marker;
            result.add(completion);
        }
        return result;
    }

    public static final class Location {

        public final String file, label;
        public final int line, column;

        Location(String file, String label, int line, int column) {
            this.file = file;
            this.label = label;
            this.line = line;
            this.column = column;
        }

        @Override
        public String toString() {
            return (label.isEmpty() ? "" : label + " · ") + file + ":" + (line + 1) + ":" + (column + 1);
        }
    }

    private static void location(
        Object value,
        LspProjectUris uris,
        String fallback,
        String label,
        List<Location> output
    ) {
        JsonObject item = object(value);
        String file = item.has("uri") || item.has("targetUri")
            ? uris.relative(item.getString("uri", item.getString("targetUri", "")))
            : fallback;
        JsonObject range = item.has("targetSelectionRange")
            ? item.getObject("targetSelectionRange")
            : item.has("selectionRange")
                ? item.getObject("selectionRange")
                : item.getObject("range");
        JsonObject position = range.getObject("start");
        int line = coordinate(position, "line"),
            column = coordinate(position, "character");
        if (
            file != null && LspProjectUris.allowed(file) && line >= 0 && column >= 0 && output.size() < 500
        ) output.add(new Location(file, label, line, column));
    }

    public static List<Location> locations(JsonObject response, LspProjectUris uris) {
        List<Location> result = new ArrayList<>();
        Object raw = response.raw().get("result");
        if (raw instanceof List) for (Object item : (List<?>) raw) {
            location(item, uris, null, "", result);
            if (result.size() >= 500) break;
        }
        else location(raw, uris, null, "", result);
        return result;
    }

    public static List<Location> symbols(JsonObject response, LspProjectUris uris, String file) {
        List<Location> result = new ArrayList<>();
        symbols(response.getArray("result"), uris, file, result, 0);
        return result;
    }

    private static void symbols(
        List<?> items,
        LspProjectUris uris,
        String file,
        List<Location> result,
        int depth
    ) {
        if (depth > 32) return;
        int count = 0;
        for (Object raw : items) {
            if (result.size() >= 500 || ++count > 2000) break;
            JsonObject item = object(raw);
            String label = item.getString("name", "");
            label = label.substring(0, Math.min(512, label.length()));
            location(item.has("location") ? item.raw().get("location") : raw, uris, file, label, result);
            symbols(item.getArray("children"), uris, file, result, depth + 1);
        }
    }

    public static String hover(JsonObject response) {
        StringBuilder out = new StringBuilder();
        hover(response.getObject("result").raw().get("contents"), out, 0);
        return out.substring(0, Math.min(16384, out.length()));
    }

    private static void hover(Object value, StringBuilder out, int depth) {
        if (depth > 8 || out.length() >= 16384) return;
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                hover(item, out, depth + 1);
                if (out.length() >= 16384) break;
            }
        } else {
            String text = value instanceof String ? (String) value : object(value).getString("value", "");
            if (out.length() > 0) out.append('\n');
            out.append(text, 0, Math.min(text.length(), 16384 - Math.min(out.length(), 16384)));
        }
    }

    public static List<Diagnostic> diagnostics(JsonObject params, LspProjectUris uris, String server) {
        List<Diagnostic> result = new ArrayList<>();
        String file = uris.relative(params.getString("uri", ""));
        if (file == null) return result;
        int count = 0;
        for (Object raw : params.getArray("diagnostics")) {
            if (++count > 500) break;
            JsonObject item = object(raw),
                position = item.getObject("range").getObject("start");
            int line = coordinate(position, "line"),
                column = coordinate(position, "character");
            if (line < 0 || column < 0) continue;
            String text = item.getString("message", "");
            int severity = item.getInt("severity", 1);
            result.add(
                new Diagnostic(
                    file,
                    line,
                    column,
                    severity == 1
                        ? Diagnostic.Severity.ERROR
                        : severity == 2
                            ? Diagnostic.Severity.WARNING
                            : Diagnostic.Severity.INFO,
                    text.substring(0, Math.min(8192, text.length())),
                    "LSP · " + server
                )
            );
        }
        return result;
    }

    /** All-or-nothing single-document formatting; rejects invalid/overlapping or surrogate-splitting ranges. */
    public static String applyEdits(String source, JsonObject response) throws IOException {
        List<Completion> edits = new ArrayList<>();
        if (source.length() > MAX_TEXT || response.getArray("result").size() > 2000) throw new IOException(
            "LSP edit limit exceeded"
        );
        long size = source.length();
        for (Object raw : response.getArray("result")) {
            JsonObject edit = object(raw),
                range = edit.getObject("range");
            int start = offset(source, range.getObject("start")),
                end = offset(source, range.getObject("end"));
            String text = edit.getString("newText", "");
            if (
                !edit.has("newText") || start < 0 || end < start || text.indexOf('\0') >= 0
            ) throw new IOException("Invalid LSP edit range");
            size += text.length() - (end - start);
            if (size > MAX_TEXT) throw new IOException("LSP formatted document exceeds 512 KiB");
            edits.add(new Completion("", "", text, start, end));
        }
        edits.sort(Comparator.comparingInt((Completion item) -> item.start).reversed());
        int previous = source.length() + 1;
        StringBuilder result = new StringBuilder(source);
        for (Completion edit : edits) {
            if (edit.end > previous || edit.start == previous) throw new IOException("Overlapping LSP edits");
            result.replace(edit.start, edit.end, edit.text);
            previous = edit.start;
        }
        return result.toString();
    }
}
