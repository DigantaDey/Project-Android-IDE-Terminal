package com.forgeide.core.search;

import static org.junit.Assert.*;

import com.forgeide.core.editor.*;
import com.forgeide.core.project.*;
import com.forgeide.core.util.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class ProjectSearchTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void searchAndReplaceIsChecked() throws Exception {
        Workspace w = new Workspace(tmp.newFolder().toPath());
        w.write("a.js", "foo foo\nbar");
        ProjectSearch s = new ProjectSearch();
        FindReplaceEngine.Query q = FindReplaceEngine.Query.literal("foo");
        List<ProjectSearch.Hit> hits = s.search(w, q, 100, new CancellationToken());
        assertEquals(2, hits.size());
        assertEquals(4, hits.get(1).column);
        assertEquals(2, s.replace(w, q, "hello", hits, new CancellationToken(), null));
        assertEquals("hello hello\nbar", w.read("a.js"));
        try {
            s.replace(w, q, "oops", hits, new CancellationToken(), null);
            fail();
        } catch (java.io.IOException expected) {}
    }

    @Test
    public void cancellationAndLimit() throws Exception {
        Workspace w = new Workspace(tmp.newFolder().toPath());
        w.write("a", "a a a");
        ProjectSearch s = new ProjectSearch();
        assertEquals(1, s.search(w, FindReplaceEngine.Query.literal("a"), 1, new CancellationToken()).size());
        CancellationToken c = new CancellationToken();
        c.cancel();
        try {
            s.search(w, FindReplaceEngine.Query.literal("a"), 10, c);
            fail();
        } catch (CancellationToken.CancellationException expected) {}
    }
}
