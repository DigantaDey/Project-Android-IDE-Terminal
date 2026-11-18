package com.forgeide.core.project;

import static org.junit.Assert.*;

import com.forgeide.core.util.AtomicFiles;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class WorkspaceTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Workspace ws() throws IOException {
        return new Workspace(temp.newFolder().toPath());
    }

    @Test
    public void fileLifecycle() throws Exception {
        Workspace w = ws();
        w.mkdir("src");
        w.create("src/a.txt");
        w.write("src/a.txt", "hello");
        w.duplicate("src/a.txt", "src/b.txt");
        w.move("src/b.txt", "src/c.txt");
        assertEquals("hello", w.read("src/c.txt"));
        assertEquals(2, w.files(10).size());
        w.delete("src");
        assertTrue(w.list("").isEmpty());
    }

    @Test
    public void directoryDuplicateIsConfinedAndPreservesEmptyDirectories() throws Exception {
        Workspace w = ws();
        w.write("src/nested/main.js", "print(42)");
        w.mkdir("src/empty");
        w.duplicate("src", "copy");
        assertEquals("print(42)", w.read("copy/nested/main.js"));
        assertTrue(w.isDirectory("copy/empty"));
        try {
            w.duplicate("src", "src/nested/copy");
            fail();
        } catch (IOException expected) {}
        try {
            w.duplicate("src", "copy");
            fail();
        } catch (IOException expected) {}
        assertEquals("print(42)", w.read("copy/nested/main.js"));
    }

    @Test
    public void directoryDuplicateNeverFollowsLinksOrLeavesPartialCopies() throws Exception {
        Workspace w = ws();
        w.mkdir("src");
        Files.createSymbolicLink(w.resolve("src").resolve("link"), temp.newFile().toPath());
        try {
            w.duplicate("src", "copy");
            fail();
        } catch (IOException expected) {}
        assertFalse(Files.exists(w.resolve("copy")));
        assertEquals(1, w.list("").size());
    }

    @Test
    public void traversalAndAbsolutePathsDenied() throws Exception {
        Workspace w = ws();
        for (String p : new String[] { "../outside", "/tmp/host", "dir/../../outside", "a\\..\\host" })
            try {
                w.resolve(p);
                fail(p);
            } catch (IOException expected) {}
    }

    @Test
    public void symlinksDenied() throws Exception {
        Workspace w = ws();
        Path outside = temp.newFile().toPath();
        Files.createSymbolicLink(w.root().resolve("link"), outside);
        try {
            w.read("link");
            fail();
        } catch (IOException expected) {}
    }

    @Test
    public void checkedSaveProtectsExternalEdits() throws Exception {
        Workspace w = ws();
        w.write("a", "old");
        String digest = AtomicFiles.sha256("old".getBytes(StandardCharsets.UTF_8));
        w.write("a", "external");
        try {
            w.writeChecked("a", "new", digest);
            fail();
        } catch (IOException expected) {}
        assertEquals("external", w.read("a"));
    }

    @Test
    public void binaryAndNonUtf8Rejected() throws Exception {
        Workspace w = ws();
        for (byte[] bytes : new byte[][] { { 0, 1 }, { (byte) 0xff } }) {
            Files.write(w.resolve("a"), bytes);
            try {
                w.read("a");
                fail();
            } catch (IOException expected) {}
        }
    }

    @Test
    public void zipRoundTrip() throws Exception {
        Workspace a = ws(),
            b = ws();
        a.write("src/main.js", "print('hello');");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProjectArchive.exportZip(a, out);
        ProjectArchive.importZip(b, new ByteArrayInputStream(out.toByteArray()));
        assertEquals(a.read("src/main.js"), b.read("src/main.js"));
    }

    @Test
    public void zipPreservesEmptyFoldersAndExcludesPrivateStores() throws Exception {
        Workspace a = ws(),
            b = ws();
        a.mkdir("src/empty");
        a.write(".git/config", "private metadata");
        a.write(".forge-trash/hidden", "deleted");
        a.write(".forge/settings.json", "{}");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ProjectArchive.exportZip(a, bytes);
        ProjectArchive.importZip(b, new ByteArrayInputStream(bytes.toByteArray()));
        assertTrue(b.isDirectory("src/empty"));
        assertFalse(Files.exists(b.resolve(".git")));
        assertFalse(Files.exists(b.resolve(".forge-trash")));
        assertEquals("{}", b.read(".forge/settings.json"));
    }

    @Test
    public void zipSlipRejected() throws Exception {
        Workspace w = ws();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("../escape"));
            z.write(1);
            z.closeEntry();
        }
        try {
            ProjectArchive.importZip(w, new ByteArrayInputStream(out.toByteArray()));
            fail();
        } catch (IOException expected) {}
        assertFalse(Files.exists(w.root().getParent().resolve("escape")));
    }

    @Test
    public void cannotDeleteRoot() throws Exception {
        try {
            ws().delete("");
            fail();
        } catch (IOException expected) {}
    }
}
