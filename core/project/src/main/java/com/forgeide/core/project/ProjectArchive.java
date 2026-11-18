package com.forgeide.core.project;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** ZIP backup/import with traversal, duplicate-entry, depth and zip-bomb protection. */
public final class ProjectArchive {

    public static final long MAX_BYTES = 256L * 1024 * 1024;

    public static void exportZip(Workspace w, OutputStream destination) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            Deque<String> directories = new ArrayDeque<>();
            directories.add("");
            int entries = 0;
            long bytes = 0;
            while (!directories.isEmpty()) {
                String directory = directories.removeFirst();
                for (String path : w.list(directory)) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Export interrupted");
                    boolean folder = w.isDirectory(path);
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    if (folder && Workspace.ignoredDirectory(name)) continue;
                    if (++entries > 20000 || path.split("/").length > 64) throw new IOException(
                        "Project exceeds archive entry/depth limits; export smaller subprojects"
                    );
                    if (folder) {
                        zip.putNextEntry(new ZipEntry(path + "/"));
                        zip.closeEntry();
                        directories.addLast(path);
                    } else {
                        Path source = w.resolve(path);
                        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) continue;
                        bytes += Files.size(source);
                        if (bytes > MAX_BYTES) throw new IOException(
                            "Project exceeds the 256 MiB backup limit"
                        );
                        zip.putNextEntry(new ZipEntry(path));
                        Files.copy(source, zip);
                        zip.closeEntry();
                    }
                }
            }
            if (entries == 0) {
                // Android 8 cannot finish a truly entryless ZIP.
                zip.putNextEntry(new ZipEntry("./"));
                zip.closeEntry();
            }
        }
    }

    /** Import into a NEW empty project; a failure never modifies an existing project. */
    public static void importZip(Workspace w, InputStream source) throws IOException {
        if (!w.list("").isEmpty()) throw new IOException("Import requires an empty project");
        long total = 0;
        int count = 0;
        Set<String> seen = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry e;
            byte[] buffer = new byte[16384];
            while ((e = zip.getNextEntry()) != null) {
                if (++count > 20000 || e.getName().split("/").length > 64) throw new IOException(
                    "Archive contains too many entries or excessive nesting"
                );
                Path p = w.resolve(e.getName());
                if (!seen.add(p.toString())) throw new IOException("Duplicate archive entry");
                if (e.isDirectory()) {
                    // closeEntry() otherwise inflates ignored directory data without
                    // counting it against MAX_BYTES. Real directory entries are empty.
                    if (zip.read() != -1) throw new IOException("Archive directory contains file data");
                    Files.createDirectories(p);
                } else {
                    Files.createDirectories(p.getParent());
                    try (OutputStream out = Files.newOutputStream(p, StandardOpenOption.CREATE_NEW)) {
                        int n;
                        while ((n = zip.read(buffer)) != -1) {
                            total += n;
                            if (total > MAX_BYTES) throw new IOException(
                                "Archive exceeds 256 MiB extracted limit"
                            );
                            out.write(buffer, 0, n);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }
}
