package com.forgeide.core.project;

import static org.junit.Assert.*;

import java.io.IOException;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class ProjectTrashTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void metadataLikeFilenamesNeverCollide() throws Exception {
        Workspace w = new Workspace(tmp.newFolder().toPath());
        ProjectTrash t = new ProjectTrash(w);
        for (String path : new String[] { "original.txt", "entry.json", "content" }) {
            w.write(path, "important code " + path);
            ProjectTrash.Entry e = t.move(path);
            assertEquals(1, t.list().size());
            t.restore(e);
            assertEquals("important code " + path, w.read(path));
            assertTrue(t.list().isEmpty());
        }
    }

    @Test
    public void foldersAndTheirContentsRestore() throws Exception {
        Workspace w = new Workspace(tmp.newFolder().toPath());
        w.write("src/nested/a.js", "print(1)");
        ProjectTrash t = new ProjectTrash(w);
        ProjectTrash.Entry e = t.move("src");
        assertFalse(java.nio.file.Files.exists(w.resolve("src")));
        t.restore(e);
        assertEquals("print(1)", w.read("src/nested/a.js"));
    }

    @Test
    public void restoreNeverOverwritesNewFile() throws Exception {
        Workspace w = new Workspace(tmp.newFolder().toPath());
        w.write("a", "old");
        ProjectTrash t = new ProjectTrash(w);
        ProjectTrash.Entry e = t.move("a");
        w.write("a", "new");
        try {
            t.restore(e);
            fail();
        } catch (IOException expected) {}
        assertEquals("new", w.read("a"));
        assertEquals(1, t.list().size());
    }
}
