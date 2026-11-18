package com.forgeide.core.editor;

import static org.junit.Assert.*;

import java.util.Random;
import org.junit.Test;

public class DocumentFuzzTest {

    @Test
    public void randomEditsMatchReference() {
        Random r = new Random(8128);
        Document d = new Document();
        StringBuilder ref = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            int start = r.nextInt(ref.length() + 1),
                len = r.nextInt(ref.length() - start + 1);
            String add = i % 3 == 0 ? "x\ny" : "abc";
            d.replace(start, len, add);
            ref.replace(start, start + len, add);
            assertEquals(ref.toString(), d.text());
            for (int j = 0; j <= d.length(); j++) assertEquals(j, d.offsetAt(d.positionAt(j)));
        }
    }

    @Test
    public void setTextCanBeUndoneAndResetsIndex() {
        Document d = new Document("a\nb");
        UndoRedoManager u = new UndoRedoManager(d);
        d.offsetAt(1, 0);
        d.setText("x\r\ny");
        assertEquals("x\ny", d.text());
        u.undo();
        assertEquals("a\nb", d.text());
    }

    @Test
    public void deletingOnlyLineIsSafe() {
        Document d = new Document("only");
        EditOperations.deleteLine(d, 0);
        assertEquals("", d.text());
    }
}
