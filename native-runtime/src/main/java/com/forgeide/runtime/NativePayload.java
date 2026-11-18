package com.forgeide.runtime;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import com.forgeide.core.toolchain.NativeTools;
import com.forgeide.core.util.Json;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Installs only APK-contained, catalog-hashed payloads; no network or package maintainer scripts. */
final class NativePayload {

    private final Context context;
    private final Map<String, Object> catalog;
    final String architecture;
    final Path prefix;

    NativePayload(Context context) throws IOException {
        this.context = context;
        architecture = Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")
            ? "aarch64"
            : Arrays.asList(Build.SUPPORTED_ABIS).contains("x86_64")
                ? "x86_64"
                : "unsupported";
        prefix = context.getFilesDir().toPath().resolve("usr");
        try (InputStream input = context.getAssets().open("runtime-catalog.json")) {
            catalog = Json.parseObject(
                new String(read(input, 65536), java.nio.charset.StandardCharsets.UTF_8)
            );
        }
    }

    String id() {
        return String.valueOf(catalog.get("id"));
    }

    boolean supported() {
        return !architecture.equals("unsupported");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> details() {
        return (Map<String, Object>) ((Map<?, ?>) catalog.get("architectures")).get(architecture);
    }

    static byte[] read(InputStream input, int max) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (bytes.size() + n > max) throw new IOException("Runtime metadata too large");
            bytes.write(buffer, 0, n);
        }
        return bytes.toByteArray();
    }

    static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder();
        for (byte value : bytes) b.append(String.format(Locale.ROOT, "%02x", value & 255));
        return b.toString();
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Path inside(Path root, String name) throws IOException {
        try {
            NativeTools.relativePath(name, false);
        } catch (IllegalArgumentException bad) {
            throw new IOException("Invalid runtime payload path", bad);
        }
        Path path = root.resolve(name);
        if (!path.normalize().startsWith(root)) throw new IOException("Runtime payload escapes root");
        return path;
    }

    static void delete(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
            for (Path item : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                Files.deleteIfExists(item);
        }
    }

    @SuppressWarnings("unchecked")
    synchronized void ensure() throws Exception {
        if (!supported()) throw new IOException("Native runtime supports arm64-v8a and x86_64 only");
        // Android may bind-mount /data/data and /data/user/0 rather than symlinking them.
        // Compare inode identity, not canonical path text; secondary users still fail closed.
        if (
            !Files.isSameFile(
                new File("/data/data/" + NativeTools.PACKAGE + "/files").toPath(),
                context.getFilesDir().toPath()
            )
        ) throw new IOException(
            "This compatibility runtime requires Android's primary user; secondary/work-profile paths are unsupported"
        );
        Path marker = prefix.resolve(".forge-bundle-id");
        if (
            !Files.isSymbolicLink(prefix) &&
            Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) &&
            Files.size(marker) == 64 &&
            com.forgeide.core.util.AtomicFiles.readUtf8(marker).equals(id())
        ) return;
        Path stage = context.getFilesDir().toPath().resolve("usr.installing");
        delete(stage);
        Files.createDirectories(stage);
        long expected = ((Number) details().get("unpackedBytes")).longValue();
        if (stage.toFile().getUsableSpace() < expected + 200L * 1024 * 1024) throw new IOException(
            "Not enough storage to unpack native tools; need " + ((expected / 1048576) + 200) + " MiB free"
        );
        MessageDigest archiveHash = digest();
        Map<String, String> hashes = new HashMap<>();
        Map<String, Long> sizes = new HashMap<>();
        Map<String, Object> manifest = null;
        long unpacked = 0;
        int count = 0;
        try {
            try (
                InputStream raw = context.getAssets().open("runtime-" + architecture + ".zip");
                DigestInputStream hashed = new DigestInputStream(raw, archiveHash);
                ZipInputStream zip = new ZipInputStream(hashed)
            ) {
                ZipEntry entry;
                byte[] buffer = new byte[32768];
                while ((entry = zip.getNextEntry()) != null) {
                    if (++count > 80000 || Thread.currentThread().isInterrupted()) throw new IOException(
                        "Runtime install cancelled or too many files"
                    );
                    String name = entry.getName();
                    if (name.equals("manifest.json")) {
                        manifest = Json.parseObject(
                            new String(read(zip, 16 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8)
                        );
                        continue;
                    }
                    if (name.equals("package-lock.json")) {
                        read(zip, 128 * 1024);
                        continue;
                    }
                    if (!name.startsWith("files/") || entry.isDirectory()) throw new IOException(
                        "Invalid runtime payload entry"
                    );
                    name = name.substring(6);
                    Path target = inside(stage, name);
                    Files.createDirectories(target.getParent());
                    MessageDigest hash = digest();
                    long size = 0;
                    try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                        int n;
                        while ((n = zip.read(buffer)) != -1) {
                            unpacked += n;
                            size += n;
                            if (
                                unpacked > expected || unpacked > 2L * 1024 * 1024 * 1024
                            ) throw new IOException("Runtime payload exceeds catalog size");
                            hash.update(buffer, 0, n);
                            output.write(buffer, 0, n);
                        }
                    }
                    hashes.put(name, hex(hash.digest()));
                    sizes.put(name, size);
                }
                // ZipInputStream stops at the central directory. Hash the complete archive too.
                while (hashed.read(buffer) != -1) {}
            }
            if (
                !hex(archiveHash.digest()).equals(details().get("sha256")) ||
                manifest == null ||
                !id().equals(manifest.get("id"))
            ) throw new IOException("Bundled runtime checksum mismatch");
            List<Map<String, Object>> files = (List<Map<String, Object>>) manifest.get("files");
            if (files.size() != hashes.size() || unpacked != expected) throw new IOException(
                "Incomplete runtime payload"
            );
            for (Map<String, Object> file : files) {
                String name = (String) file.get("path");
                if (
                    !Objects.equals(file.get("sha256"), hashes.remove(name)) ||
                    ((Number) file.get("size")).longValue() != sizes.get(name)
                ) throw new IOException("Runtime file checksum mismatch");
                Os.chmod(inside(stage, name).toString(), ((Number) file.get("mode")).intValue());
            }
            for (Map<String, Object> link : (List<Map<String, Object>>) manifest.get("links")) {
                Path path = inside(stage, (String) link.get("path"));
                String target = (String) link.get("target");
                inside(stage, target);
                Files.createDirectories(path.getParent());
                // Relative links survive the final atomic prefix rename.
                Os.symlink(path.getParent().relativize(stage.resolve(target)).toString(), path.toString());
            }
            for (String dir : List.of("tmp", "var/tmp", "var/run", "etc"))
                Files.createDirectories(stage.resolve(dir));
            com.forgeide.core.util.AtomicFiles.write(stage.resolve(".forge-bundle-id"), id());
            delete(prefix);
            Files.move(stage, prefix);
        } catch (Exception error) {
            delete(stage);
            throw error;
        }
    }
}
