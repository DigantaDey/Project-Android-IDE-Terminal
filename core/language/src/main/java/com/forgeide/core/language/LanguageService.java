package com.forgeide.core.language;

import com.forgeide.core.editor.FoldingRegion;
import com.forgeide.core.util.CancellationToken;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Providers can be in-process or LSP-backed; never run analysis on the UI thread. */
public interface LanguageService {
    final class Symbol {

        public final String name, kind;
        public final int offset, line;

        public Symbol(String name, String kind, int offset, int line) {
            this.name = name;
            this.kind = kind;
            this.offset = offset;
            this.line = line;
        }

        @Override
        public String toString() {
            return name + "  ·  " + kind + "  :" + (line + 1);
        }
    }

    List<String> complete(Language language, String text, int offset, CancellationToken cancel);
    List<Symbol> symbols(Language language, String text, CancellationToken cancel);
    List<Diagnostic> diagnose(Language language, String file, String text, CancellationToken cancel);
    List<FoldingRegion> folds(Language language, String text, CancellationToken cancel);
}
