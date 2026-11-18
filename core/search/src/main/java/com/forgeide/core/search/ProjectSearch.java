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
        Pattern pattern = new FindReplaceEngine(new Document()).compile(query);
        int changed = 0;
        for (Map.Entry<String, List<Hit>> group : grouped.entrySet()) {
            cancel.throwIfCancelled();
            String file = group.getKey();
            String text = w.read(file);
            List<Hit> hits = new ArrayList<>(group.getValue());
            hits.sort(Comparator.comparingInt(hit -> hit.start));
            String digest = AtomicFiles.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder next = new StringBuilder(text.length());
            int cursor = 0,
                currentLineStart = -1,
                lineEnd = -1;
            Matcher matcher = null;
            boolean found = false;
            for (Hit hit : hits) {
                cancel.throwIfCancelled();
                if (
                    !digest.equals(hit.digest) ||
                    hit.start < cursor ||
                    hit.start < 0 ||
                    hit.end < hit.start ||
                    hit.end > text.length()
                ) {
                    throw new IOException("Search results are stale or overlap: " + file);
                }
                // Reuse a forward matcher per original line. Starting a new matcher at each hit
                // breaks \G and repeatedly copies/scans long prefixes. Never match edited text.
                int lineStart = currentLineStart;
                if (matcher == null || hit.start > lineEnd) {
                    lineStart = hit.start == 0 ? 0 : text.lastIndexOf('\n', hit.start - 1) + 1;
                    currentLineStart = lineStart;
                    lineEnd = text.indexOf('\n', hit.start);
                    if (lineEnd < 0) lineEnd = text.length();
                    matcher = pattern.matcher(new BoundedText(text.substring(lineStart, lineEnd), 300));
                    found = matcher.find();
                }
                while (found && matcher.start() < hit.start - lineStart) found = matcher.find();
                if (
                    !found ||
                    hit.end > lineEnd ||
                    matcher.start() != hit.start - lineStart ||
                    matcher.end() != hit.end - lineStart
                ) {
                    throw new IOException("Search result no longer matches the query: " + file);
                }
                append(next, text, cursor, hit.start);
                String value = query.regex
                    ? FindReplaceEngine.replacementValue(matcher, replacement, true)
                    : replacement;
                append(next, value, 0, value.length());
                cursor = hit.end;
                found = matcher.find();
            }
            append(next, text, cursor, text.length());
            cancel.throwIfCancelled();
            // Construct/validate the entire per-file replacement before backups or writes.
            if (backup != null) backup.accept(file);
            cancel.throwIfCancelled();
            w.writeChecked(file, next.toString(), digest);
            changed += hits.size();
        }
        return changed;
    }

    private static void append(StringBuilder result, String value, int start, int end) throws IOException {
        if ((long) result.length() + end - start > Workspace.MAX_TEXT_BYTES) {
            throw new IOException("Project replacement exceeds the 8 Mi-character safety limit");
        }
        result.append(value, start, end);
    }
}
