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
}
