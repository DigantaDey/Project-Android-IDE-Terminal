package com.forgeide.core.recovery;

import com.forgeide.core.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Atomic, checksummed recovery journals stored OUTSIDE projects and inaccessible to user code. */
public final class RecoveryStore {
    public static final class Entry {
        public final String project, path, text, diskDigest; public final long timestamp;
        Entry(JsonObject j) { project = j.getString("project", ""); path = j.getString("path", ""); text = j.getString("text", ""); diskDigest = j.getString("diskDigest", ""); timestamp = j.getLong("timestamp", 0); }
    }
    private final Path directory;
    public RecoveryStore(Path directory) throws IOException { Files.createDirectories(directory); this.directory = directory; }
    private Path file(String project, String path) { return directory.resolve(AtomicFiles.sha256((project + "\0" + path).getBytes(StandardCharsets.UTF_8)) + ".json"); }
    public synchronized void save(String project, String path, String text, String diskDigest) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>(); m.put("project", project); m.put("path", path); m.put("text", text); m.put("diskDigest", diskDigest);
        m.put("checksum", AtomicFiles.sha256(text.getBytes(StandardCharsets.UTF_8))); m.put("timestamp", System.currentTimeMillis());
        AtomicFiles.write(file(project, path), Json.write(m));
    }
    public synchronized void discard(String project, String path) throws IOException { Files.deleteIfExists(file(project, path)); }
    public synchronized List<Entry> recover() throws IOException {
        List<Entry> result = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path p : files) {
                if (Files.size(p) > 64L * 1024 * 1024) throw new IOException("Recovery journal exceeds safety limit: " + p.getFileName());
                try { JsonObject j = JsonObject.parse(com.forgeide.core.util.AtomicFiles.readUtf8(p)); Entry e = new Entry(j);
                    if (!AtomicFiles.sha256(e.text.getBytes(StandardCharsets.UTF_8)).equals(j.getString("checksum", ""))) throw new IOException("Recovery checksum mismatch: " + p.getFileName());
                    result.add(e);
                } catch (RuntimeException ex) { throw new IOException("Damaged recovery journal retained: " + p.getFileName(), ex); }
            }
        }
        result.sort(Comparator.comparingLong(e -> -e.timestamp)); return result;
    }
}
