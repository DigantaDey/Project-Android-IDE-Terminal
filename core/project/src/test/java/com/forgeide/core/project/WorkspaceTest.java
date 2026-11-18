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

    @Test
    public void boundedStreamImportIsPublishedOnlyOnSuccess() throws Exception {
        Workspace workspace = ws();
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        assertEquals(5, workspace.importFile("src/import.txt", new ByteArrayInputStream(bytes), 5));
        assertEquals("hello", workspace.read("src/import.txt"));
        try {
            workspace.importFile("too-big.txt", new ByteArrayInputStream(bytes), 4);
            fail();
        } catch (IOException expected) {}
        assertFalse(Files.exists(workspace.resolve("too-big.txt")));
        assertEquals(java.util.List.of("src"), workspace.list(""));
    }

    @Test
    public void failedImportCannotDeleteAnExistingDestination() throws Exception {
        Workspace workspace = ws();
        workspace.write("original.txt", "precious");
        try {
            workspace.importFile("original.txt", new ByteArrayInputStream(new byte[] { 1 }), 1);
            fail();
        } catch (FileAlreadyExistsException expected) {}
        assertEquals("precious", workspace.read("original.txt"));
    }

    @Test
    public void providerFailureLeavesNoPartialFileOrTemporaryArtifact() throws Exception {
        Workspace workspace = ws();
        InputStream broken = new InputStream() {
            int read;

            @Override
            public int read() throws IOException {
                if (read++ == 0) return 65;
                throw new IOException("Provider disconnected");
            }
        };
        try {
            workspace.importFile("new.txt", broken, 100);
            fail();
        } catch (IOException expected) {}
        try (java.util.stream.Stream<Path> files = Files.list(workspace.root())) {
            assertEquals(0, files.count());
        }
    }

    @Test
    public void destinationCreatedDuringImportIsNeverOverwritten() throws Exception {
        Workspace workspace = ws();
        InputStream racing = new InputStream() {
            @Override
            public int read() throws IOException {
                workspace.write("late.txt", "preserve");
                return -1;
            }
        };
        try {
            workspace.importFile("late.txt", racing, 100);
            fail();
        } catch (FileAlreadyExistsException expected) {}
        assertEquals("preserve", workspace.read("late.txt"));
        assertEquals(1, workspace.list("").size());
    }

    @Test
    public void externalOversizedSaveConflictIsRejectedBeforeReadingTheWholeFile() throws Exception {
        Workspace workspace = ws();
        try (
            java.nio.channels.SeekableByteChannel file = Files.newByteChannel(
                workspace.resolve("grown.txt"),
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW
            )
        ) {
            file.position(Workspace.MAX_TEXT_BYTES);
            file.write(java.nio.ByteBuffer.wrap(new byte[] { 1 }));
        }
        try {
            workspace.writeChecked("grown.txt", "unsaved", "old-digest");
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("size limit"));
        }
        assertEquals(Workspace.MAX_TEXT_BYTES + 1, Files.size(workspace.resolve("grown.txt")));
    }
}
