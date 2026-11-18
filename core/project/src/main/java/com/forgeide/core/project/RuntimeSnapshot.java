package com.forgeide.core.project;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Explicit, bounded project copies. Never includes IDE metadata and never imports runtime writes. */
public final class RuntimeSnapshot {

    private RuntimeSnapshot() {}

    public static final long MAX_BYTES = 32L * 1024 * 1024;
    public static final int MAX_FILES = 2000;
    private static final Set<String> PRIVATE = Set.of(
        ".git",
        ".forge",
        ".forge-trash",
        ".forge-replace-backups",
        "replace-backups",
        ".gradle",
        ".idea"
    );

    private static boolean allowed(String path) {
        for (String part : path.split("/"))
            if (
                PRIVATE.contains(part) || part.equals("..") || part.equals(".") || part.isEmpty()
            ) return false;
        return (
            !path.startsWith("/") &&
            path.indexOf('\\') < 0 &&
            path.indexOf(':') < 0 &&
            path.indexOf('\0') < 0 &&
            path.length() <= 1024 &&
            path.split("/").length <= 32
        );
    }

    public static void write(Workspace workspace, OutputStream output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            Deque<String> pending = new ArrayDeque<>();
            pending.add("");
            long bytes = 0;
            int files = 0;
            byte[] buffer = new byte[16384];
            while (!pending.isEmpty()) for (String name : workspace.list(pending.removeFirst())) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Snapshot cancelled");
                if (!allowed(name)) continue;
                if (++files > MAX_FILES) throw new IOException("Native project copy exceeds 2,000 entries");
                if (workspace.isDirectory(name)) {
                    pending.addLast(name);
                    continue;
                }
                Path path = workspace.resolve(name);
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                zip.putNextEntry(new ZipEntry(name));
                try (InputStream input = Files.newInputStream(path)) {
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        bytes += n;
                        if (bytes > MAX_BYTES) throw new IOException(
                            "Native project copy exceeds 32 MiB; use a smaller project"
                        );
                        zip.write(buffer, 0, n);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    public static void read(Path emptyDirectory, InputStream input) throws IOException {
        if (Files.isSymbolicLink(emptyDirectory)) throw new IOException("Invalid runtime project root");
        Files.createDirectories(emptyDirectory);
        try (DirectoryStream<Path> contents = Files.newDirectoryStream(emptyDirectory)) {
            if (contents.iterator().hasNext()) throw new IOException("Snapshot destination must be empty");
        }
        Path archive = Files.createTempFile(emptyDirectory.getParent(), "native-incoming-", ".zip");
        try {
            try (OutputStream output = Files.newOutputStream(archive)) {
                byte[] buffer = new byte[16384];
                long compressed = 0;
                int n;
                while ((n = input.read(buffer)) != -1) {
                    compressed += n;
                    if (compressed > MAX_BYTES + 8 * 1024 * 1024) throw new IOException(
                        "Native snapshot archive exceeds transport limit"
                    );
                    output.write(buffer, 0, n);
                }
            }
            long total = 0;
            int count = 0;
            Set<String> seen = new HashSet<>();
            // ZipFile requires a complete central directory: a truncated pipe is not a valid copy.
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                byte[] buffer = new byte[16384];
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory()) name = name.substring(0, name.length() - 1);
                    if (!allowed(name) || ++count > MAX_FILES || !seen.add(name)) throw new IOException(
                        "Invalid or excessive native snapshot entry"
                    );
                    Path target = emptyDirectory.resolve(name);
                    if (entry.isDirectory()) Files.createDirectories(target);
                    else {
                        Files.createDirectories(target.getParent());
                        try (
                            InputStream source = zip.getInputStream(entry);
                            OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)
                        ) {
                            int n;
                            while ((n = source.read(buffer)) != -1) {
                                total += n;
                                if (total > MAX_BYTES) throw new IOException(
                                    "Native snapshot exceeds 32 MiB"
                                );
                                output.write(buffer, 0, n);
                            }
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }
}
