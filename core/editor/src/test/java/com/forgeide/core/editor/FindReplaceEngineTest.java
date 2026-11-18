package com.forgeide.core.editor;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class FindReplaceEngineTest {

    private FindReplaceEngine engineFor(String text) {
        return new FindReplaceEngine(new Document(text));
    }

    @Test
    public void literalCaseInsensitiveByDefault() {
        FindReplaceEngine e = engine("Hello HELLO hello");
        List<FindReplaceEngine.Match> matches = e.findAll(FindReplaceEngine.Query.literal("hello"), 100);
        assertEquals(3, matches.size());
        assertEquals(new Range(0, 5), matches.get(0).range);
        assertEquals(new Range(6, 11), matches.get(1).range);
    }

    private FindReplaceEngine engine(String text) {
        return new FindReplaceEngine(new Document(text));
    }

    @Test
    public void caseSensitiveQuery() {
        FindReplaceEngine e = engine("Hello hello");
        assertEquals(1, e.count(new FindReplaceEngine.Query("hello", false, true, false)));
    }

    @Test
    public void wholeWordQuery() {
        FindReplaceEngine e = engine("cat catch catcat cat");
        assertEquals(2, e.count(new FindReplaceEngine.Query("cat", false, false, true)));
        assertEquals(5, e.count(new FindReplaceEngine.Query("cat", false, false, false)));
    }

    @Test
    public void regexQueryWithGroups() {
        FindReplaceEngine e = engine("foo(12) foo(345)");
        List<FindReplaceEngine.Match> matches = e.findAll(
            new FindReplaceEngine.Query("foo\\((\\d+)\\)", true, true, false),
            10
        );
        assertEquals(2, matches.size());
        assertEquals("12", matches.get(0).groups[1]);
        assertEquals("345", matches.get(1).groups[1]);
    }

    @Test
    public void invalidRegexIsRejected() {
        FindReplaceEngine e = engine("anything");
        assertFalse(e.isValid(new FindReplaceEngine.Query("(unclosed", true, true, false)));
        assertEquals(0, e.count(new FindReplaceEngine.Query("(unclosed", true, true, false)));
    }

    @Test
    public void findNextWrapsAround() {
        FindReplaceEngine e = engine("a here, b here");
        FindReplaceEngine.Match m = e.findNext(FindReplaceEngine.Query.literal("here"), 14, true);
        assertNotNull(m);
        assertEquals(2, m.range.start);
    }

    @Test
    public void findPreviousWorks() {
        FindReplaceEngine e = engine("one two one two one");
        FindReplaceEngine.Match m = e.findPrevious(FindReplaceEngine.Query.literal("one"), 15, false);
        assertNotNull(m);
        assertEquals(8, m.range.start);
    }

    @Test
    public void replaceWithGroupReference() {
        Document doc = new Document("hello world");
        FindReplaceEngine e = new FindReplaceEngine(doc);
        List<FindReplaceEngine.Match> matches = e.findAll(
            new FindReplaceEngine.Query("(hello) (world)", true, true, false),
            10
        );
        e.replace(new FindReplaceEngine.Query("(hello) (world)", true, true, false), matches.get(0), "$2 $1");
        assertEquals("world hello", doc.text());
    }

    @Test
    public void replaceAllIsAtomicUndoStep() {
        Document doc = new Document("a.b.c.d");
        UndoRedoManager undo = new UndoRedoManager(doc);
        undo.clear();
        FindReplaceEngine e = new FindReplaceEngine(doc);
        int n = e.replaceAll(FindReplaceEngine.Query.literal("."), "->", undo);
        assertEquals(3, n);
        assertEquals("a->b->c->d", doc.text());
        undo.undo();
        assertEquals("a.b.c.d", doc.text());
    }

    @Test
    public void zeroWidthRegexDoesNotLoop() {
        FindReplaceEngine e = engine("abc");
        List<FindReplaceEngine.Match> matches = e.findAll(
            new FindReplaceEngine.Query("x*", true, true, false),
            100
        );
        assertTrue(matches.size() < 100);
    }
}
