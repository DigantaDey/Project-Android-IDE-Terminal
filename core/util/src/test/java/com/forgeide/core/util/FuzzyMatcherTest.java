package com.forgeide.core.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class FuzzyMatcherTest {

    @Test
    public void emptyPatternMatches() {
        assertNotNull(FuzzyMatcher.match("", "anything"));
    }

    @Test
    public void subsequenceRequired() {
        assertNull(FuzzyMatcher.match("xyz", "hello world"));
        assertNotNull(FuzzyMatcher.match("hlo", "hello"));
    }

    @Test
    public void caseInsensitive() {
        assertNotNull(FuzzyMatcher.match("MCP", "myCoolProject"));
    }

    @Test
    public void wordBoundaryScoresHigher() {
        FuzzyMatcher.Match boundary = FuzzyMatcher.match("fb", "FooBar");
        FuzzyMatcher.Match middle = FuzzyMatcher.match("fb", "zfozbo");
        assertNotNull(boundary);
        assertNotNull(middle);
        assertTrue(boundary.score > middle.score);
    }

    @Test
    public void consecutiveScoresHigher() {
        FuzzyMatcher.Match consecutive = FuzzyMatcher.match("abc", "abcdef");
        FuzzyMatcher.Match scattered = FuzzyMatcher.match("abc", "axxxbxxxc");
        assertTrue(consecutive.score > scattered.score);
    }

    @Test
    public void matchRangesAreSane() {
        FuzzyMatcher.Match m = FuzzyMatcher.match("sv", "SaveFile");
        assertNotNull(m);
        assertFalse(m.ranges.isEmpty());
        for (int[] r : m.ranges) {
            assertEquals(2, r.length);
            assertTrue(r[0] < r[1]);
        }
    }
}
