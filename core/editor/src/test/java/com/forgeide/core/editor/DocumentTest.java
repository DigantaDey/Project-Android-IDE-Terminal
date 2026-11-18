package com.forgeide.core.editor;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class DocumentTest {

    @Test
    public void emptyDocumentHasOneLine() {
        Document d = new Document();
        assertEquals(1, d.lineCount());
        assertEquals(0, d.length());
        assertEquals("", d.text());
    }

    @Test
    public void setTextSplitsLines() {
        Document d = new Document("a\nbb\nccc");
        assertEquals(3, d.lineCount());
        assertEquals("bb", d.line(1));
        assertEquals(8, d.length());
    }

    @Test
    public void trailingNewlineCreatesEmptyLine() {
        Document d = new Document("a\n");
        assertEquals(2, d.lineCount());
        assertEquals("", d.line(1));
        assertEquals("a\n", d.text());
    }

    @Test
    public void insertIntoMiddleOfLine() {
        Document d = new Document("hello world");
        d.insert(5, ",");
        assertEquals("hello, world", d.text());
    }

    @Test
    public void insertMultilineSplitsLine() {
        Document d = new Document("startEND");
        d.insert(5, " one\ntwo\nthree ");
        assertEquals(3, d.lineCount());
        assertEquals("start one", d.line(0));
        assertEquals("two", d.line(1));
        assertEquals("three END", d.line(2));
    }

    @Test
    public void insertNormalizesCRLF() {
        Document d = new Document();
        d.insert(0, "a\r\nb\rc");
        assertEquals("a\nb\nc", d.text());
        assertEquals(3, d.lineCount());
    }

    @Test
    public void deleteAcrossLinesMerges() {
        Document d = new Document("abc\ndef\nghi");
        // delete from middle of line 0 to middle of line 2
        d.delete(1, 8);
        assertEquals(1, d.lineCount());
        assertEquals("ahi", d.text());
    }

    @Test
    public void replaceWorks() {
        Document d = new Document("the quick brown fox");
        d.replace(4, 5, "slow");
        assertEquals("the slow brown fox", d.text());
    }

    @Test
    public void positionMappingRoundTrips() {
        Document d = new Document("ab\ncde\n\nf");
        int[] expected = { 0, 3, 7, 8 };
        for (int line = 0; line < d.lineCount(); line++) {
            assertEquals(expected[line], d.offsetAt(line, 0));
        }
        assertEquals(new Position(1, 2), d.positionAt(5));
        assertEquals(new Position(3, 0), d.positionAt(8));
        assertEquals(new Position(2, 0), d.positionAt(7));
        assertEquals(new Position(0, 0), d.positionAt(0));
        assertEquals(new Position(3, 1), d.positionAt(9));
    }

    @Test
    public void offsetAtClampsColumnToLineLength() {
        Document d = new Document("abc");
        assertEquals(3, d.offsetAt(0, 100));
    }

    @Test
    public void changeEventsCarryInverseData() {
        Document d = new Document("hello");
        List<TextChange> changes = new ArrayList<>();
        d.addListener((doc, change) -> changes.add(change));
        d.insert(5, " world");
        d.delete(0, 2);
        assertEquals(2, changes.size());
        assertEquals("", changes.get(0).removedText);
        assertEquals(" world", changes.get(0).insertedText);
        assertEquals("he", changes.get(1).removedText);
        assertEquals(0, changes.get(1).offset);
    }

    @Test
    public void textRangeExtraction() {
        Document d = new Document("ab\ncd\nef");
        assertEquals("b\ncd\ne", d.text(new Range(1, 7)));
    }

    @Test
    public void largeFilePerformanceSmoke() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            sb.append("int value").append(i).append(" = computeSomething(x, y);\n");
        }
        Document d = new Document(sb.toString());
        assertEquals(20001, d.lineCount());
        long start = System.nanoTime();
        for (int i = 0; i < 2000; i++) {
            d.positionAt((int) (Math.random() * d.length()));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue("position mapping should be fast, took " + elapsedMs + "ms", elapsedMs < 1500);
    }
}
