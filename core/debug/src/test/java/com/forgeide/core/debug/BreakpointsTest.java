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
}
