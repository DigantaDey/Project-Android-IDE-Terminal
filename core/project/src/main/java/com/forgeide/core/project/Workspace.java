package com.forgeide.core.project;

import com.forgeide.core.util.AtomicFiles;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Project-scoped file authority. Traversal, absolute paths and symlinks are never followed. */
public final class Workspace {

    public static final long MAX_TEXT_BYTES = 8L * 1024 * 1024;
    private final Path root;

    public Workspace(Path root) throws IOException {
        Files.createDirectories(root);
        this.root = root.toRealPath();
    }

    public Path root() {
        return root;
    }

    public Path resolve(String relative) throws IOException {
        if (
            relative == null || relative.indexOf('\0') >= 0 || relative.indexOf('\\') >= 0
        ) throw new IOException("Invalid project path");
        Path child = Paths.get(relative);
        if (child.isAbsolute()) throw new IOException("Use a project-relative path");
        Path result = root.resolve(child).normalize();
        if (!result.startsWith(root)) throw new IOException("Path escapes the project");
        Path cursor = root;
        for (Path component : root.relativize(result)) {
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) throw new IOException(
                "Symbolic links are not permitted: " + relative
            );
        }
        return result;
    }

    public String relative(Path path) {
        return root.relativize(path).toString().replace(File.separatorChar, '/');
    }

    public List<String> list(String relative) throws IOException {
        Path directory = resolve(relative);
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path p : stream)
                if (
                    !Files.isSymbolicLink(p) && !p.getFileName().toString().startsWith(".forge-save-")
                ) entries.add(p);
        }
        entries.sort(
            Comparator.<Path, Boolean>comparing(p ->
                !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
            ).thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
        );
        List<String> out = new ArrayList<>();
        for (Path p : entries) out.add(relative(p));
        return out;
    }

    public boolean isDirectory(String path) throws IOException {
        return Files.isDirectory(resolve(path), LinkOption.NOFOLLOW_LINKS);
    }

    public String read(String path) throws IOException {
        Path p = resolve(path);
        if (Files.size(p) > MAX_TEXT_BYTES) throw new IOException(
            "File exceeds the 8 MiB editable limit; open a smaller portion externally"
        );
        byte[] bytes = Files.readAllBytes(p);
        if (bytes.length > MAX_TEXT_BYTES) throw new IOException("File grew beyond the editable limit");
        for (byte b : bytes) if (b == 0) throw new IOException("Binary file cannot be edited as text");
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("This file is not UTF-8. Convert its encoding before editing.", e);
        }
    }

    public synchronized void write(String path, String text) throws IOException {
        Path p = resolve(path);
        if (p.equals(root)) throw new IOException("Cannot overwrite project root");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) throw new IOException("Text exceeds editable size limit");
        AtomicFiles.write(p, bytes);
    }

    /** Expected-digest write prevents silently overwriting an external modification. */
    public synchronized void writeChecked(String path, String text, String expectedDigest)
        throws IOException {
        Path p = resolve(path);
        if (Files.exists(p) && Files.size(p) > MAX_TEXT_BYTES) throw new IOException(
            "File changed on disk beyond the editable size limit. Your recovery copy is safe."
        );
        String actual = Files.exists(p) ? AtomicFiles.sha256(Files.readAllBytes(p)) : "";
        if (expectedDigest != null && !actual.equals(expectedDigest)) throw new IOException(
            "File changed on disk. Your recovery copy is safe; reload or save a copy."
        );
        write(path, text);
    }

    /** Stage a bounded provider stream; failures never truncate/delete an existing destination. */
    public synchronized long importFile(String path, InputStream input, long limit) throws IOException {
        if (input == null) throw new IOException("No document stream");
        if (limit < 0) throw new IOException("Import exceeds storage limit");
        Path destination = resolve(path);
        if (destination.equals(root) || Files.exists(destination)) throw new FileAlreadyExistsException(path);
        Files.createDirectories(destination.getParent());
        Path stage = Files.createTempFile(destination.getParent(), ".forge-save-import-", ".tmp");
        long total = 0;
        try {
            try (FileOutputStream output = new FileOutputStream(stage.toFile())) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException(
                        "Import interrupted"
                    );
                    if (count > limit - total) throw new IOException("Import exceeds storage limit");
                    output.write(buffer, 0, count);
                    total += count;
                }
                output.getFD().sync();
            }
            Files.move(stage, destination);
            return total;
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    public synchronized void mkdir(String path) throws IOException {
        Files.createDirectories(resolve(path));
    }

    public synchronized void create(String path) throws IOException {
        Path p = resolve(path);
        Files.createDirectories(p.getParent());
        Files.createFile(p);
    }

    public synchronized void move(String from, String to) throws IOException {
        Path a = resolve(from),
            b = resolve(to);
        if (a.equals(root) || b.equals(root)) throw new IOException("Cannot move project root");
        Files.move(a, b);
    }

    public synchronized void duplicate(String from, String to) throws IOException {
        Path source = resolve(from),
            destination = resolve(to);
        if (
            source.equals(root) || destination.equals(root) || destination.startsWith(source)
        ) throw new IOException("Cannot copy a project root or copy a folder into itself");
        if (Files.exists(destination)) throw new FileAlreadyExistsException(to);
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(source, destination);
            return;
        }
        Path stage = Files.createTempDirectory(root, ".forge-save-copy-");
        long[] bytes = { 0 };
        int[] entries = { 0 };
        try {
            Files.walkFileTree(
                source,
                EnumSet.noneOf(FileVisitOption.class),
                64,
                new SimpleFileVisitor<Path>() {
                    private Path target(Path path) {
                        return stage.resolve(source.relativize(path));
                    }

                    private void check() throws IOException {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Copy interrupted");
                        if (++entries[0] > 20000) throw new IOException("Folder copy exceeds 20,000 entries");
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs)
                        throws IOException {
                        check();
                        if (!path.equals(source)) Files.createDirectory(target(path));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
                        throws IOException {
                        check();
                        if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new IOException(
                            "Only regular project files may be copied"
                        );
                        bytes[0] += attrs.size();
                        if (bytes[0] > ProjectArchive.MAX_BYTES) throw new IOException(
                            "Folder copy exceeds 256 MiB"
                        );
                        Files.copy(path, target(path));
                        return FileVisitResult.CONTINUE;
                    }
                }
            );
            Files.move(stage, destination);
        } finally {
            if (Files.exists(stage)) delete(relative(stage));
        }
    }

    /** Destructive operations are explicit; the Android UI moves files to .forge-trash first. */
    public synchronized void delete(String path) throws IOException {
        Path p = resolve(path);
        if (p.equals(root)) throw new IOException("Cannot delete project root");
        Files.walkFileTree(
            p,
            new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    Files.delete(f);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            }
        );
    }

    public static boolean ignoredDirectory(String name) {
        return Set.of(
            ".git",
            ".forge-trash",
            "replace-backups",
            "node_modules",
            "build",
            ".gradle",
            ".venv"
        ).contains(name);
    }

    public List<String> files(int limit) throws IOException {
        List<String> out = new ArrayList<>();
        Files.walkFileTree(
            root,
            EnumSet.noneOf(FileVisitOption.class),
            64,
            new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                    String n = d.getFileName().toString();
                    return !d.equals(root) && ignoredDirectory(n)
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    if (out.size() >= limit) return FileVisitResult.TERMINATE;
                    if (a.isRegularFile() && !a.isSymbolicLink()) out.add(relative(f));
                    return FileVisitResult.CONTINUE;
                }
            }
        );
        return out;
    }
}
