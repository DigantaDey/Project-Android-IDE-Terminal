package com.forgeide.core.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class BoundedLogTest {

    @Test
    public void rollingTailMatchesAReference() {
        BoundedLog log = new BoundedLog(17);
        String reference = "";
        for (int i = 0; i < 1000; i++) {
            String part = "" + i;
            log.append(part);
            reference += part;
            if (reference.length() > 17) reference = reference.substring(reference.length() - 17);
            assertEquals(reference, log.toString());
        }
        log.clear();
        assertTrue(log.isEmpty());
        log.set("short");
        assertEquals("short", log.toString());
    }

    @Test
    public void truncationDoesNotSplitSurrogatePairs() {
        BoundedLog log = new BoundedLog(4);
        log.append("😀abc");
        assertEquals("abc", log.toString());
        log.set("😀😀");
        assertEquals("😀😀", log.toString());
        log.append("x");
        assertEquals("😀x", log.toString());
    }
}
