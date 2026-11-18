package com.forgeide.core.recovery;

import com.forgeide.core.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Atomic, checksummed recovery journals stored OUTSIDE projects and inaccessible to user code. */
public final class RecoveryStore {

    public static final class Entry {

        public final String project, path, text, diskDigest;
        public final long timestamp;

        Entry(JsonObject j) {
            project = j.getString("project", "");
            path = j.getString("path", "");
            text = j.getString("text", "");
            diskDigest = j.getString("diskDigest", "");
            timestamp = j.getLong("timestamp", 0);
        }
    }

    private final Path directory;

    public RecoveryStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        this.directory = directory;
    }

    private Path file(String project, String path) {
        return directory.resolve(
            AtomicFiles.sha256((project + "\0" + path).getBytes(StandardCharsets.UTF_8)) + ".json"
        );
    }

    public synchronized void save(String project, String path, String text, String diskDigest)
        throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("project", project);
        m.put("path", path);
        m.put("text", text);
        m.put("diskDigest", diskDigest);
        m.put("checksum", AtomicFiles.sha256(text.getBytes(StandardCharsets.UTF_8)));
        m.put("timestamp", System.currentTimeMillis());
        AtomicFiles.write(file(project, path), Json.write(m));
    }

    public synchronized void discard(String project, String path) throws IOException {
        Files.deleteIfExists(file(project, path));
    }

    public synchronized List<Entry> recover() throws IOException {
        return recover(null, Integer.MAX_VALUE, null);
    }

    /**
     * Recover a project's healthy journals without letting one damaged entry block the rest.
     * Rejected/overflow entries remain on disk and each rejection is reported to the caller.
     * A null warning handler preserves the strict, fail-fast API used by backup tooling.
     */
    public synchronized List<Entry> recover(
        String project,
        int limit,
        java.util.function.Consumer<String> warnings
    ) throws IOException {
        if (limit < 1) throw new IllegalArgumentException("Recovery limit must be positive");
        List<Entry> result = new ArrayList<>();
        int overflow = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path p : files) {
                try {
                    if (Files.isSymbolicLink(p) || Files.size(p) > 64L * 1024 * 1024) throw new IOException(
                        "Recovery journal exceeds safety limit or is a link: " + p.getFileName()
                    );
                    JsonObject j = JsonObject.parse(AtomicFiles.readUtf8(p));
                    Entry e = new Entry(j);
                    Path path = Paths.get(e.path);
                    if (
                        !TextUtils.isValidFileName(e.project) ||
                        e.path.isEmpty() ||
                        e.path.indexOf('\\') >= 0 ||
                        path.isAbsolute() ||
                        path.normalize().toString().isEmpty() ||
                        path.normalize().startsWith("..")
                    ) {
                        throw new IOException("Invalid recovery path retained: " + p.getFileName());
                    }
                    if (
                        !AtomicFiles.sha256(e.text.getBytes(StandardCharsets.UTF_8)).equals(
                            j.getString("checksum", "")
                        )
                    ) {
                        throw new IOException("Recovery checksum mismatch: " + p.getFileName());
                    }
                    if (project != null && !project.equals(e.project)) continue;
                    result.add(e);
                    if (result.size() > limit) {
                        // Retain only the newest bounded set in memory, never delete overflow journals.
                        result.sort(Comparator.comparingLong(entry -> -entry.timestamp));
                        result.remove(result.size() - 1);
                        overflow++;
                    }
                } catch (IOException | RuntimeException ex) {
                    IOException problem = ex instanceof IOException
                        ? (IOException) ex
                        : new IOException("Damaged recovery journal retained: " + p.getFileName(), ex);
                    if (warnings == null) throw problem;
                    warnings.accept(problem.getMessage());
                }
            }
        }
        if (overflow > 0 && warnings != null) warnings.accept(
            overflow +
                " additional recovery journals retained on disk; close tabs and reopen this project to recover them"
        );
        result.sort(Comparator.comparingLong(e -> -e.timestamp));
        return result;
    }
}
