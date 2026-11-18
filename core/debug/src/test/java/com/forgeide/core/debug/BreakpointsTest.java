package com.forgeide.core.debug;

import static org.junit.Assert.*;

import org.junit.Test;

public class BreakpointsTest {

    @Test
    public void conditionalPointsPersist() {
        Breakpoints b = new Breakpoints();
        assertTrue(b.toggle("a.js", 3));
        b.condition("a.js", 3, "x > 2");
        Breakpoints restored = new Breakpoints();
        restored.restore(b.serialize());
        assertEquals(1, restored.all().size());
        assertEquals("x > 2", restored.all().get(0).condition);
        assertFalse(restored.toggle("a.js", 3));
        assertTrue(restored.all().isEmpty());
    }

    @Test
    public void fileAndFolderRenamesPreserveConditionsWithoutPrefixCollisions() {
        Breakpoints points = new Breakpoints();
        points.condition("src/main.js", 4, "count > 1");
        points.toggle("src/nested/other.js", 1);
        points.toggle("src-copy/keep.js", 2);
        points.relocate("src", "lib");
        assertEquals("lib/main.js", points.all().get(0).file);
        assertEquals("count > 1", points.all().get(0).condition);
        assertEquals("lib/nested/other.js", points.all().get(1).file);
        assertEquals("src-copy/keep.js", points.all().get(2).file);
        points.relocate("lib/main.js", "lib/renamed.js");
        assertEquals("lib/renamed.js", points.all().get(0).file);
        assertEquals(4, points.all().get(0).line);
    }
}
