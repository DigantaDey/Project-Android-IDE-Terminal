package com.forgeide.core.editor;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class EditorMiscTest {

    @Test
    public void bracketMatcherFindsPairs() {
        String text = "f(a, {b: [1, 2]})";
        int open = text.indexOf('{');
        int close = BracketMatcher.findMatch(text, open);
        assertEquals(text.indexOf('}'), close);
        assertEquals(text.indexOf('('), BracketMatcher.findMatch(text, text.lastIndexOf(')')));
    }

    @Test
    public void bracketMatcherSkipsStringsAndComments() {
        String text = "f(\"}\") // }\n{ x }";
        int open = text.indexOf('{');
        int close = BracketMatcher.findMatch(text, open);
        assertEquals(text.lastIndexOf('}'), close);
    }

    @Test
    public void bracketMatcherReturnsMinusOneWhenNoMatch() {
        assertEquals(-1, BracketMatcher.findMatch("f(", 1));
        assertEquals(-1, BracketMatcher.findMatch("abc", 0));
        assertEquals(-1, BracketMatcher.findMatch("abc", -1));
    }

    @Test
    public void foldingVisibilityAndRowMapping() {
        FoldingModel model = new FoldingModel();
        model.setRegions(Arrays.asList(
                new FoldingRegion(1, 4, "braces"),
                new FoldingRegion(2, 3, "braces")));
        assertTrue(model.fold(1));
        assertTrue(model.isLineVisible(1));
        assertFalse(model.isLineVisible(2));
        assertFalse(model.isLineVisible(4));
        assertTrue(model.isLineVisible(5));
        assertEquals(0, model.visibleRowToLine(0, 10));
        assertEquals(1, model.visibleRowToLine(1, 10));
        assertEquals(5, model.visibleRowToLine(2, 10));
        model.toggle(1);
        assertTrue(model.isLineVisible(2));
    }

    @Test
    public void foldAllOnlyFoldsTopLevel() {
        FoldingModel model = new FoldingModel();
        model.setRegions(Arrays.asList(
                new FoldingRegion(0, 9, "braces"),
                new FoldingRegion(2, 4, "braces"),
                new FoldingRegion(11, 13, "braces")));
        model.foldAll();
        assertTrue(model.isFolded(0));
        assertFalse(model.isFolded(2)); // nested
        assertTrue(model.isFolded(11));
    }

    @Test
    public void invalidRegionsAreDropped() {
        FoldingModel model = new FoldingModel();
        model.setRegions(List.of(new FoldingRegion(1, 3, "braces")));
        model.fold(1);
        model.setRegions(List.of(new FoldingRegion(5, 8, "braces")));
        assertFalse(model.isFolded(1)); // stale fold dropped
    }

    @Test
    public void wordWrapBreaksAtWhitespace() {
        List<Integer> breaks = WordWrap.wrapLine("the quick brown fox jumps", 10);
        assertFalse(breaks.isEmpty());
        // Reconstruct visual rows
        String line = "the quick brown fox jumps";
        int start = 0;
        StringBuilder rows = new StringBuilder();
        for (int b : breaks) {
            rows.append(line, start, b).append('|');
            start = b;
            while (start < line.length() && line.charAt(start) == ' ') {
                start++;
            }
        }
        rows.append(line.substring(start));
        assertEquals("the quick|brown fox|jumps", rows.toString());
    }

    @Test
    public void wordWrapHardBreaksLongWords() {
        List<Integer> breaks = WordWrap.wrapLine("abcdefghij", 4);
        assertEquals(Arrays.asList(4, 8), breaks);
    }

    @Test
    public void lineEndingDetection() {
        assertEquals(LineEndings.LF, LineEndings.detect("a\nb\nc"));
        assertEquals(LineEndings.CRLF, LineEndings.detect("a\r\nb\r\n"));
        assertEquals(LineEndings.CR, LineEndings.detect("a\rb\r"));
        assertEquals(LineEndings.LF, LineEndings.detect("no newlines"));
        assertEquals("a\r\nb", LineEndings.normalize("a\nb", LineEndings.CRLF));
        assertEquals("a\nb", LineEndings.normalize("a\r\nb", LineEndings.LF));
    }

    @Test
    public void editOperationsMoveLine() {
        Document doc = new Document("one\ntwo\nthree");
        assertTrue(EditOperations.moveLineDown(doc, 0));
        assertEquals("two\none\nthree", doc.text());
        assertTrue(EditOperations.moveLineUp(doc, 1));
        assertEquals("one\ntwo\nthree", doc.text());
        assertFalse(EditOperations.moveLineUp(doc, 0));
        assertFalse(EditOperations.moveLineDown(doc, 2));
    }

    @Test
    public void editOperationsDuplicateAndDelete() {
        Document doc = new Document("one\ntwo");
        EditOperations.duplicateLine(doc, 0);
        assertEquals("one\none\ntwo", doc.text());
        EditOperations.deleteLine(doc, 1);
        assertEquals("one\ntwo", doc.text());
    }

    @Test
    public void editOperationsJoinLines() {
        Document doc = new Document("foo(\n    bar)");
        assertTrue(EditOperations.joinLines(doc, 0));
        assertEquals("foo( bar)", doc.text());
    }

    @Test
    public void toggleCommentIdempotent() {
        Document doc = new Document("int a;\nint b;");
        EditOperations.toggleComment(doc, 0, 1, "//");
        assertEquals("// int a;\n// int b;", doc.text());
        EditOperations.toggleComment(doc, 0, 1, "//");
        assertEquals("int a;\nint b;", doc.text());
    }
}
