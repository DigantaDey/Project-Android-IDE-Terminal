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
            for (String path : w.files(20000)) {
                zip.putNextEntry(new ZipEntry(path));
                Files.copy(w.resolve(path), zip);
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
                if (e.isDirectory()) Files.createDirectories(p);
                else {
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
