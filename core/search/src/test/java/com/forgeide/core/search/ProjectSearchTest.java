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

    @Test
    public void contextualRegexKeepsUnselectedMatchesInItsOriginalLineContext() throws Exception {
        Workspace w = new Workspace(tmp.newFolder().toPath());
        w.write("a", "abc\ndef");
        ProjectSearch search = new ProjectSearch();
        FindReplaceEngine.Query query = new FindReplaceEngine.Query("\\G[a-z]", true, true, false);
        List<ProjectSearch.Hit> hits = search.search(w, query, 100, new CancellationToken());
        assertEquals(6, hits.size());
        assertEquals(
            3,
            search.replace(
                w,
                query,
                "<$0>",
                List.of(hits.get(0), hits.get(2), hits.get(4)),
                new CancellationToken(),
                null
            )
        );
        assertEquals("<a>b<c>\nd<e>f", w.read("a"));
    }

    @Test
    public void projectTemplatesResolveNamedGroupsAndLookbehindWithoutCopyingPrefixes() throws Exception {
        Workspace w = new Workspace(tmp.newFolder().toPath());
        w.write("a", "n:one n:two\nn:three");
        ProjectSearch search = new ProjectSearch();
        FindReplaceEngine.Query query = new FindReplaceEngine.Query("(?<=:)(?<word>\\w+)", true, true, false);
        List<ProjectSearch.Hit> hits = search.search(w, query, 100, new CancellationToken());
        assertEquals(3, search.replace(w, query, "${word}-$0", hits, new CancellationToken(), null));
        assertEquals("n:one-one n:two-two\nn:three-three", w.read("a"));
    }

    @Test
    public void onlyThePreviewedPrefixChanges() throws Exception {
        Workspace w = new Workspace(tmp.newFolder().toPath());
        w.write("a", "foo foo foo");
        ProjectSearch search = new ProjectSearch();
        FindReplaceEngine.Query query = FindReplaceEngine.Query.literal("foo");
        List<ProjectSearch.Hit> hits = search.search(w, query, 2, new CancellationToken());
        assertEquals(2, search.replace(w, query, "bar", hits, new CancellationToken(), null));
        assertEquals("bar bar foo", w.read("a"));
    }

    @Test
    public void oversizedProjectReplacementFailsBeforeBackupsOrPartialEdits() throws Exception {
        Workspace w = new Workspace(tmp.newFolder().toPath());
        w.write("a", "x x x");
        ProjectSearch search = new ProjectSearch();
        FindReplaceEngine.Query query = FindReplaceEngine.Query.literal("x");
        List<ProjectSearch.Hit> hits = search.search(w, query, 10, new CancellationToken());
        java.util.concurrent.atomic.AtomicInteger backups = new java.util.concurrent.atomic.AtomicInteger();
        try {
            search.replace(w, query, "r".repeat(3 * 1024 * 1024), hits, new CancellationToken(), file ->
                backups.incrementAndGet()
            );
            fail();
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("safety limit"));
        }
        assertEquals(0, backups.get());
        assertEquals("x x x", w.read("a"));
    }

    @Test
    public void invalidTemplateDoesNotCreateABackupOrMutateTheFile() throws Exception {
        Workspace w = new Workspace(tmp.newFolder().toPath());
        w.write("a", "x x");
        ProjectSearch search = new ProjectSearch();
        FindReplaceEngine.Query query = new FindReplaceEngine.Query("(x)", true, true, false);
        List<ProjectSearch.Hit> hits = search.search(w, query, 10, new CancellationToken());
        java.util.concurrent.atomic.AtomicInteger backups = new java.util.concurrent.atomic.AtomicInteger();
        try {
            search.replace(w, query, "${absent}", hits, new CancellationToken(), file ->
                backups.incrementAndGet()
            );
            fail();
        } catch (IllegalArgumentException expected) {}
        assertEquals(0, backups.get());
        assertEquals("x x", w.read("a"));
    }

    @Test
    public void queryMustStillMatchEveryPreviewedRange() throws Exception {
        Workspace w = new Workspace(tmp.newFolder().toPath());
        w.write("a", "foo bar");
        ProjectSearch search = new ProjectSearch();
        List<ProjectSearch.Hit> hits = search.search(
            w,
            FindReplaceEngine.Query.literal("foo"),
            10,
            new CancellationToken()
        );
        try {
            search.replace(
                w,
                FindReplaceEngine.Query.literal("bar"),
                "bad",
                hits,
                new CancellationToken(),
                null
            );
            fail();
        } catch (java.io.IOException expected) {}
        assertEquals("foo bar", w.read("a"));
    }

    @Test
    public void cancellationDuringBackupIsRecheckedBeforePublishingChanges() throws Exception {
        Workspace w = new Workspace(tmp.newFolder().toPath());
        w.write("a", "foo");
        ProjectSearch search = new ProjectSearch();
        FindReplaceEngine.Query query = FindReplaceEngine.Query.literal("foo");
        List<ProjectSearch.Hit> hits = search.search(w, query, 10, new CancellationToken());
        CancellationToken cancel = new CancellationToken();
        try {
            search.replace(w, query, "bar", hits, cancel, file -> cancel.cancel());
            fail();
        } catch (CancellationToken.CancellationException expected) {}
        assertEquals("foo", w.read("a"));
    }
}
