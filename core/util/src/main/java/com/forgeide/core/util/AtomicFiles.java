package com.forgeide.core.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;

/** Same-directory, fsynced temporary files + atomic rename. Originals survive interrupted writes. */
public final class AtomicFiles {
    private AtomicFiles() {}
    public static void write(Path target, String content) throws IOException { write(target, content.getBytes(StandardCharsets.UTF_8)); }
    public static void write(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(target.toAbsolutePath().getParent(), ".forge-save-", ".tmp");
        try {
            try (FileOutputStream stream = new FileOutputStream(tmp.toFile())) { stream.write(content); stream.getFD().sync(); }
            try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
    public static String sha256(byte[] data) {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(data); StringBuilder s = new StringBuilder(); for (byte b : digest) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return s.toString(); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
