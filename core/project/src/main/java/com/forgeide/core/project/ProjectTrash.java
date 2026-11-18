package com.forgeide.core.project;

import com.forgeide.core.util.Json;
import com.forgeide.core.util.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/** Recoverable deletes. Metadata is a sibling of content, never named after the user's file. */
public final class ProjectTrash {

    public static final class Entry {

        public final String id, path;

        Entry(String id, String path) {
            this.id = id;
            this.path = path;
        }

        @Override
        public String toString() {
            return path;
        }
    }

    private final Workspace workspace;

    public ProjectTrash(Workspace workspace) {
        this.workspace = workspace;
    }

    public Entry move(String path) throws IOException {
        String normalized = workspace.relative(workspace.resolve(path));
        if (
            normalized.isEmpty() ||
            normalized.equals(".forge-trash") ||
            normalized.startsWith(".forge-trash/")
        ) throw new IOException("Cannot trash the project root or the trash store");
        String id = UUID.randomUUID().toString(),
            base = ".forge-trash/" + id;
        workspace.mkdir(base);
        // Commit metadata before removing anything from its original location.
        workspace.write(base + "/entry.json", Json.write(Map.of("path", normalized)));
        workspace.move(normalized, base + "/content");
        return new Entry(id, normalized);
    }

    public List<Entry> list() throws IOException {
        List<Entry> result = new ArrayList<>();
        if (!Files.exists(workspace.resolve(".forge-trash"))) return result;
        for (String base : workspace.list(".forge-trash")) {
            if (
                !workspace.isDirectory(base) ||
                !Files.exists(workspace.resolve(base + "/entry.json")) ||
                !Files.exists(workspace.resolve(base + "/content"))
            ) continue;
            String path;
            try {
                path = JsonObject.parse(workspace.read(base + "/entry.json")).getString("path", "");
            } catch (RuntimeException e) {
                throw new IOException("Damaged trash entry retained", e);
            }
            workspace.resolve(path);
            result.add(new Entry(base.substring(base.lastIndexOf('/') + 1), path));
        }
        return result;
    }

    public void restore(Entry entry) throws IOException {
        if (!entry.id.matches("[0-9a-f-]{36}")) throw new IOException("Invalid trash entry");
        if (Files.exists(workspace.resolve(entry.path))) throw new IOException(
            "A file already exists at " + entry.path + ". Rename it before restoring."
        );
        String base = ".forge-trash/" + entry.id;
        java.nio.file.Path parent = workspace.resolve(entry.path).getParent();
        Files.createDirectories(parent);
        workspace.move(base + "/content", entry.path);
        workspace.delete(base);
    }
}
