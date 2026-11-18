package com.forgeide.core.project;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class RuntimeSnapshotTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void copiesProjectButNotIdeMetadataOrGit() throws Exception {
        Workspace project = new Workspace(tmp.newFolder().toPath());
        project.write("src/hello.py", "print('hello')");
        project.write(".git/config", "secret");
        project.write(".forge/run.json", "private");
        project.write("replace-backups/old", "private");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RuntimeSnapshot.write(project, bytes);
        Path copy = tmp.newFolder().toPath();
        RuntimeSnapshot.read(copy, new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals("print('hello')", Files.readString(copy.resolve("src/hello.py")));
        assertFalse(Files.exists(copy.resolve(".git")));
        assertFalse(Files.exists(copy.resolve(".forge")));
        assertFalse(Files.exists(copy.resolve("replace-backups")));
        Files.writeString(copy.resolve("src/hello.py"), "changed");
        assertEquals("print('hello')", project.read("src/hello.py"));
    }

    @Test
    public void refusesTraversalAndPrivateArchiveEntries() throws Exception {
        for (String name : new String[] {
            "../escape",
            "/absolute",
            ".git/config",
            ".forge/token",
            "a/../b",
            "a\\b",
        }) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(1);
                zip.closeEntry();
            }
            try {
                RuntimeSnapshot.read(tmp.newFolder().toPath(), new ByteArrayInputStream(bytes.toByteArray()));
                fail(name);
            } catch (IOException expected) {}
        }
    }

    @Test
    public void boundsExpansionAndRejectsNonEmptyDestinations() throws Exception {
        Path nonempty = tmp.newFolder().toPath();
        Files.writeString(nonempty.resolve("keep"), "yes");
        try {
            RuntimeSnapshot.read(nonempty, new ByteArrayInputStream(new byte[0]));
            fail();
        } catch (IOException expected) {}
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("bomb"));
            byte[] block = new byte[1024 * 1024];
            for (int i = 0; i < 33; i++) zip.write(block);
            zip.closeEntry();
        }
        try {
            RuntimeSnapshot.read(tmp.newFolder().toPath(), new ByteArrayInputStream(bytes.toByteArray()));
            fail();
        } catch (IOException expected) {}
    }

    @Test
    public void rejectsDuplicateEntriesThroughDifferentZipPaths() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("a"));
            zip.write(1);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("a/b"));
            zip.write(2);
            zip.closeEntry();
        }
        try {
            RuntimeSnapshot.read(tmp.newFolder().toPath(), new ByteArrayInputStream(bytes.toByteArray()));
            fail();
        } catch (IOException expected) {}
    }
}
