package com.forgeide.core.recovery;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class RecoveryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void journalsSurviveNewStoreInstance() throws Exception {
        Path dir = tmp.newFolder().toPath();
        RecoveryStore s = new RecoveryStore(dir);
        s.save("project", "a.js", "unsaved text", "digest");
        RecoveryStore.Entry e = new RecoveryStore(dir).recover().get(0);
        assertEquals("unsaved text", e.text);
        assertEquals("digest", e.diskDigest);
        s.discard("project", "a.js");
        assertTrue(s.recover().isEmpty());
    }

    @Test
    public void corruptionIsNotSilentlyDiscarded() throws Exception {
        Path dir = tmp.newFolder().toPath();
        RecoveryStore s = new RecoveryStore(dir);
        s.save("p", "a", "old", "x");
        Path file;
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            file = files.findFirst().get();
        }
        Files.writeString(file, Files.readString(file).replace("old", "BAD"));
        try {
            s.recover();
            fail();
        } catch (java.io.IOException expected) {}
        assertTrue(Files.exists(file));
    }

    @Test
    public void damagedJournalDoesNotBlockHealthyProjectRecovery() throws Exception {
        Path directory = tmp.newFolder().toPath();
        RecoveryStore store = new RecoveryStore(directory);
        store.save("p", "healthy.js", "precious edits", "disk");
        Files.writeString(directory.resolve("damaged.json"), "{broken");
        java.util.List<String> warnings = new java.util.ArrayList<>();
        java.util.List<RecoveryStore.Entry> entries = store.recover("p", 20, warnings::add);
        assertEquals(1, entries.size());
        assertEquals("precious edits", entries.get(0).text);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("retained"));
        assertTrue(Files.exists(directory.resolve("damaged.json")));
    }

    @Test
    public void projectFilterAndLimitRetainOverflowJournalsOnDisk() throws Exception {
        Path directory = tmp.newFolder().toPath();
        RecoveryStore store = new RecoveryStore(directory);
        store.save("p", "one.js", "one", "disk");
        store.save("p", "two.js", "two", "disk");
        store.save("other", "three.js", "other project", "disk");
        java.util.List<String> warnings = new java.util.ArrayList<>();
        java.util.List<RecoveryStore.Entry> entries = store.recover("p", 1, warnings::add);
        assertEquals(1, entries.size());
        assertEquals("p", entries.get(0).project);
        assertEquals(1, warnings.size());
        assertEquals(3, store.recover().size());
    }

    @Test
    public void invalidRecoveryPathsAreRetainedButNeverOpened() throws Exception {
        Path directory = tmp.newFolder().toPath();
        RecoveryStore store = new RecoveryStore(directory);
        for (String path : new String[] { "../outside", "/absolute", ".", "a\\b" })
            store.save("p", path, "edits", "disk");
        java.util.List<String> warnings = new java.util.ArrayList<>();
        assertTrue(store.recover("p", 20, warnings::add).isEmpty());
        assertEquals(4, warnings.size());
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertEquals(4, files.count());
        }
    }
}
