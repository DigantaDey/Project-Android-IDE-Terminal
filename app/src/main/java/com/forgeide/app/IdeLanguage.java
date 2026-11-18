package com.forgeide.app;

import android.os.Bundle;
import com.forgeide.core.editor.*;
import com.forgeide.core.language.*;
import com.forgeide.core.util.CancellationToken;
import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.analysis.*;
import io.github.rosemoe.sora.lang.completion.*;
import io.github.rosemoe.sora.lang.styling.*;
import io.github.rosemoe.sora.text.*;
import io.github.rosemoe.sora.widget.SymbolPairMatch;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import java.util.*;

/** Connects the independently tested language module to the native Sora editor. */
public final class IdeLanguage extends EmptyLanguage {

    private final com.forgeide.core.language.Language language;
    private final int tab;
    private final boolean tabs;
    private final LocalLanguageService service = new LocalLanguageService();
    private final SimpleAnalyzeManager<Void> analyzer;

    public IdeLanguage(
        LanguageRegistry registry,
        com.forgeide.core.language.Language language,
        int tab,
        boolean tabs
    ) {
        this.language = language;
        this.tab = tab;
        this.tabs = tabs;
        analyzer = new SimpleAnalyzeManager<Void>() {
            @Override
            protected Styles analyze(StringBuilder input, Delegate<Void> delegate) {
                // Large-file mode keeps the editor's indexed text/layout, but avoids whole-file lexical analysis.
                if (input.length() > 512 * 1024) {
                    MappedSpans.Builder plain = new MappedSpans.Builder();
                    plain.addIfNeeded(0, 0, EditorColorScheme.TEXT_NORMAL);
                    int lines = 0;
                    for (int i = 0; i < input.length(); i++) if (input.charAt(i) == '\n') lines++;
                    plain.determine(lines);
                    return new Styles(plain.build());
                }
                String source = input.toString();
                MappedSpans.Builder spans = new MappedSpans.Builder();
                int line = 0,
                    col = 0;
                for (Token t : registry.tokenizer(language.id()).tokenize(source)) {
                    if (delegate.isCancelled()) return null;
                    spans.addIfNeeded(line, col, color(t.type));
                    for (int i = t.start; i < t.end(); i++) {
                        if (source.charAt(i) == '\n') {
                            line++;
                            col = 0;
                            spans.addIfNeeded(line, col, color(t.type));
                        } else col++;
                    }
                }
                spans.determine(line);
                Styles result = new Styles(spans.build());
                for (com.forgeide.core.editor.FoldingRegion f : service.folds(
                    language,
                    source,
                    new CancellationToken()
                )) {
                    CodeBlock b = result.obtainNewBlock();
                    b.startLine = f.startLine;
                    b.startColumn = 0;
                    b.endLine = f.endLine;
                    b.endColumn = 0;
                    result.addCodeBlock(b);
                }
                result.finishBuilding();
                return result;
            }
        };
    }

    private static int color(TokenType type) {
        switch (type) {
            case KEYWORD:
            case PREPROCESSOR:
                return EditorColorScheme.KEYWORD;
            case STRING:
            case CHAR:
            case CODE:
            case VALUE:
                return EditorColorScheme.LITERAL;
            case COMMENT:
            case DOC_COMMENT:
            case QUOTE:
                return EditorColorScheme.COMMENT;
            case NUMBER:
            case CONSTANT:
                return EditorColorScheme.LITERAL;
            case TYPE:
            case TAG:
            case HEADING:
            case FUNCTION:
                return EditorColorScheme.FUNCTION_NAME;
            case ANNOTATION:
            case ATTRIBUTE:
            case PROPERTY:
            case SELECTOR:
                return EditorColorScheme.ANNOTATION;
            default:
                return EditorColorScheme.TEXT_NORMAL;
        }
    }

    @Override
    public AnalyzeManager getAnalyzeManager() {
        return analyzer;
    }

    @Override
    public void requireAutoComplete(
        ContentReference content,
        CharPosition pos,
        CompletionPublisher publisher,
        Bundle extras
    ) {
        String line = content.getLine(pos.line);
        int start = pos.column;
        while (start > 0 && com.forgeide.core.language.Language.isWordChar(line.charAt(start - 1))) start--;
        String prefix = line.substring(start, pos.column);
        if (prefix.isEmpty()) return;
        Set<String> values = new TreeSet<>(language.keywords());
        // A bounded neighbourhood gives useful document completion without a full-file scan per keypress.
        for (
            int i = Math.max(0, pos.line - 100);
            i < Math.min(content.getLineCount(), pos.line + 100);
            i++
        ) for (String word : content.getLine(i).split("[^\\p{L}\\p{N}_$]+"))
            if (!word.isEmpty()) values.add(word);
        int count = 0;
        for (String word : values)
            if (word.startsWith(prefix) && !word.equals(prefix)) {
                publisher.addItem(
                    new SimpleCompletionItem(
                        word,
                        "Lexical · " + language.displayName(),
                        prefix.length(),
                        word
                    )
                );
                if (++count >= 60) break;
            }
    }

    @Override
    public int getIndentAdvance(ContentReference text, int line, int column) {
        String before = text.getLine(line).substring(0, column);
        String next = new IndentationEngine(tab, tabs).indentForNewLine(language.indentRules(), before);
        return (
            com.forgeide.core.util.TextUtils.leadingWhitespaceColumns(next, tab) -
            com.forgeide.core.util.TextUtils.leadingWhitespaceColumns(before, tab)
        );
    }

    @Override
    public boolean useTab() {
        return tabs;
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return new SymbolPairMatch.DefaultSymbolPairs();
    }

    @Override
    public void destroy() {
        analyzer.destroy();
    }
}
