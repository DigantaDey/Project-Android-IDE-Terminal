package com.forgeide.core.search;

import com.forgeide.core.editor.*;
import com.forgeide.core.project.Workspace;
import com.forgeide.core.util.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;

/** Cancellable, bounded project search. Replacements are previewed and digest-checked before write. */
public final class ProjectSearch {

    public static final class Hit {

        public final String file, preview, digest;
        public final int line, column, start, end;

        public Hit(String file, String preview, String digest, int line, int column, int start, int end) {
            this.file = file;
            this.preview = preview;
            this.digest = digest;
            this.line = line;
            this.column = column;
            this.start = start;
            this.end = end;
        }

        @Override
        public String toString() {
            return file + ":" + (line + 1) + "  " + preview.trim();
        }
    }

    public List<Hit> search(
        Workspace workspace,
        FindReplaceEngine.Query query,
        int limit,
        CancellationToken cancel
    ) throws IOException {
        Pattern pattern = new FindReplaceEngine(new Document()).compile(query);
        List<Hit> out = new ArrayList<>();
        if (query.pattern.isEmpty()) return out;
        for (String file : workspace.files(30000)) {
            cancel.throwIfCancelled();
            if (out.size() >= limit) break;
            if (file.startsWith(".forge/")) continue;
            String text;
            try {
                if (Files.size(workspace.resolve(file)) > 2 * 1024 * 1024) continue;
                text = workspace.read(file);
            } catch (IOException e) {
                continue;
            }
            String digest = AtomicFiles.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // Line-scoped regex prevents a pathological expression from spanning an entire project file.
            int offset = 0,
                line = 0;
            for (String content : text.split("\n", -1)) {
                cancel.throwIfCancelled();
                if (out.size() >= limit) break;
                Matcher matcher = pattern.matcher(new BoundedText(content, 300));
                while (out.size() < limit && matcher.find()) {
                    cancel.throwIfCancelled();
                    out.add(
                        new Hit(
                            file,
                            content.length() > 300 ? content.substring(0, 300) : content,
                            digest,
                            line,
                            matcher.start(),
                            offset + matcher.start(),
                            offset + matcher.end()
                        )
                    );
                }
                offset += content.length() + 1;
                line++;
            }
        }
        return out;
    }

    public int replace(
        Workspace w,
        FindReplaceEngine.Query query,
        String replacement,
        List<Hit> preview,
        CancellationToken cancel,
        Consumer<String> backup
    ) throws IOException {
        Map<String, List<Hit>> grouped = new LinkedHashMap<>();
        for (Hit hit : preview) grouped.computeIfAbsent(hit.file, k -> new ArrayList<>()).add(hit);
        int changed = 0;
        for (Map.Entry<String, List<Hit>> group : grouped.entrySet()) {
            cancel.throwIfCancelled();
            String file = group.getKey();
            String text = w.read(file);
            List<Hit> hits = group.getValue();
            String digest = AtomicFiles.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!digest.equals(hits.get(0).digest)) throw new IOException(
                "Search results are stale: " + file
            );
            if (backup != null) backup.accept(file);
            StringBuilder next = new StringBuilder(text);
            hits.sort(Comparator.comparingInt((Hit h) -> h.start).reversed());
            Pattern p = new FindReplaceEngine(new Document()).compile(query);
            for (Hit h : hits) {
                String original = text.substring(h.start, h.end);
                String value = replacement;
                if (query.regex) {
                    int lineStart = text.lastIndexOf('\n', Math.max(0, h.start - 1)) + 1;
                    if (h.start == 0) lineStart = 0;
                    int lineEnd = text.indexOf('\n', h.end);
                    if (lineEnd < 0) lineEnd = text.length();
                    Matcher matcher = p.matcher(new BoundedText(text.substring(lineStart, lineEnd), 300));
                    if (
                        !matcher.find(h.start - lineStart) ||
                        matcher.start() != h.start - lineStart ||
                        matcher.end() != h.end - lineStart
                    ) throw new IOException("Search result is stale");
                    StringBuffer buffer = new StringBuffer();
                    matcher.appendReplacement(buffer, replacement);
                    value = buffer.substring(matcher.start());
                }
                next.replace(h.start, h.end, value);
                changed++;
            }
            w.writeChecked(file, next.toString(), digest);
        }
        return changed;
    }
}
