package com.forgeide.core.editor;

import org.junit.Test;

import static org.junit.Assert.*;

public class UndoRedoManagerTest {

    private Document doc;
    private UndoRedoManager undo;

    private void setup(String initial) {
        doc = new Document();
        doc.setText(initial);
        undo = new UndoRedoManager(doc);
        undo.clear();
    }

    @Test
    public void basicUndoRedo() {
        setup("");
        doc.insert(0, "hello");
        assertTrue(undo.canUndo());
        undo.undo();
        assertEquals("", doc.text());
        assertFalse(undo.canUndo());
        undo.redo();
        assertEquals("hello", doc.text());
    }

    @Test
    public void consecutiveTypingCoalesces() {
        setup("");
        doc.insert(0, "a");
        doc.insert(1, "b");
        doc.insert(2, "c");
        undo.undo();
        assertEquals("", doc.text());
    }

    @Test
    public void newlineBreaksCoalescing() {
        setup("");
        doc.insert(0, "a");
        doc.insert(1, "\n");
        doc.insert(2, "b");
        undo.undo();
        assertEquals("a\n", doc.text());
        undo.undo();
        assertEquals("a", doc.text());
        undo.undo();
        assertEquals("", doc.text());
    }

    @Test
    public void backspaceRunCoalesces() {
        setup("hello");
        doc.delete(4, 1);
        doc.delete(3, 1);
        doc.delete(2, 1);
        undo.undo();
        assertEquals("hello", doc.text());
    }

    @Test
    public void breakCoalescingSeparatesSteps() {
        setup("");
        doc.insert(0, "a");
        undo.breakCoalescing();
        doc.insert(1, "b");
        undo.undo();
        assertEquals("a", doc.text());
    }

    @Test
    public void compoundUndoIsAtomic() {
        setup("x=1; x=2; x=3;");
        undo.beginCompound();
        int idx = 0;
        while (true) {
            int i = doc.text().indexOf("x", idx < 0 ? 0 : 0);
            if (i < 0) {
                break;
            }
            doc.replace(i, 1, "y");
            idx = i;
        }
        undo.endCompound();
        assertEquals("y=1; y=2; y=3;", doc.text());
        undo.undo();
        assertEquals("x=1; x=2; x=3;", doc.text());
        undo.redo();
        assertEquals("y=1; y=2; y=3;", doc.text());
    }

    @Test
    public void newEditClearsRedo() {
        setup("");
        doc.insert(0, "a");
        undo.undo();
        doc.insert(0, "b");
        assertFalse(undo.canRedo());
    }

    @Test
    public void multilineChangeUndo() {
        setup("startEND");
        doc.insert(5, " one\ntwo\nthree ");
        assertEquals(3, doc.lineCount());
        undo.undo();
        assertEquals("startEND", doc.text());
    }
}
