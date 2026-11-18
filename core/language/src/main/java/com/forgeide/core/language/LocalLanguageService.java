package com.forgeide.core.language;

import com.forgeide.core.editor.*;
import com.forgeide.core.util.*;
import java.util.*;
import java.util.regex.*;

/** Offline lexical intelligence. Not advertised as type-aware; LSP providers add semantic capabilities. */
public final class LocalLanguageService implements LanguageService {
    private static final Pattern WORD = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*");
    private static final Pattern DECLARATION = Pattern.compile("(?m)^\\s*(?:(?:public|private|protected|static|export|async|final|abstract)\\s+)*(?:(class|interface|enum|record|def|function|fun)\\s+([\\w$]+)|(?:[\\w<>\\[\\]]+)\\s+([\\w$]+)\\s*\\([^;]*?\\)\\s*\\{)");
    @Override public List<String> complete(Language lang, String text, int offset, CancellationToken cancel) {
        int start = Math.min(offset, text.length()); while (start > 0 && Language.isWordChar(text.charAt(start - 1))) start--;
        String prefix = text.substring(start, Math.min(offset, text.length()));
        TreeSet<String> candidates = new TreeSet<>(lang.keywords()); Matcher m = WORD.matcher(text);
        while (m.find() && candidates.size() < 12000) { cancel.throwIfCancelled(); candidates.add(m.group()); }
        List<String> out = new ArrayList<>();
        for (String word : candidates) if (word.startsWith(prefix) && !word.equals(prefix)) { out.add(word); if (out.size() == 80) break; }
        return out;
    }
    @Override public List<Symbol> symbols(Language lang, String text, CancellationToken cancel) {
        List<Symbol> out = new ArrayList<>(); Matcher m = DECLARATION.matcher(text); int line = 0, cursor = 0;
        while (m.find()) { cancel.throwIfCancelled(); while (cursor < m.start()) if (text.charAt(cursor++) == '\n') line++;
            String name = m.group(2) != null ? m.group(2) : m.group(3); out.add(new Symbol(name, m.group(1) == null ? "function" : m.group(1), m.start(), line)); }
        return out;
    }
    @Override public List<Diagnostic> diagnose(Language lang, String file, String text, CancellationToken cancel) {
        cancel.throwIfCancelled();
        if (lang.id().equals("json")) try { Json.parse(text); } catch (Json.JsonException e) { return List.of(new Diagnostic(file, e.getLine() - 1, e.getColumn() - 1, Diagnostic.Severity.ERROR, e.getMessage(), "JSON")); }
        return List.of();
    }
    @Override public List<FoldingRegion> folds(Language lang, String text, CancellationToken cancel) {
        List<FoldingRegion> out = new ArrayList<>(); Deque<Integer> stack = new ArrayDeque<>(); int line = 0;
        LanguageRegistry registry = LanguageRegistry.builtins();
        for (Token t : registry.tokenizer(lang.id()).tokenize(text)) {
            cancel.throwIfCancelled();
            for (int i = t.start; i < t.end(); i++) {
                char c = text.charAt(i);
                boolean code = t.type != TokenType.STRING && t.type != TokenType.CHAR && t.type != TokenType.COMMENT && t.type != TokenType.DOC_COMMENT;
                if (code && c == '{') stack.push(line);
                if (code && c == '}' && !stack.isEmpty()) { int from = stack.pop(); if (line > from) out.add(new FoldingRegion(from, line, "braces")); }
                if (c == '\n') line++;
            }
        }
        if (lang.id().equals("python")) {
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) if (lines[i].stripTrailing().endsWith(":")) {
                int depth = TextUtils.leadingWhitespaceColumns(lines[i], 4), end = i;
                for (int j = i + 1; j < lines.length; j++) { if (!lines[j].isBlank() && TextUtils.leadingWhitespaceColumns(lines[j], 4) <= depth) break; if (!lines[j].isBlank()) end = j; }
                if (end > i) out.add(new FoldingRegion(i, end, "indent"));
            }
        }
        return out;
    }
    /** Lossless JSON formatting or trailing-whitespace cleanup; never pretend this is Prettier/Black. */
    public String format(Language lang, String text, int indent) {
        if (lang.id().equals("json")) return JsonFormatter.format(text, indent);
        return text.replaceAll("(?m)[ \\t]+$", "");
    }
}
