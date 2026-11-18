package com.forgeide.core.util;

import static org.junit.Assert.*;

import org.junit.Test;

public final class LiteralStreamRewriterTest {

    @Test
    public void companionPathsStayClickableAcrossEveryChunkBoundary() {
        String root = "/data/user/0/com.forgex/cache/sessions/uuid/project/";
        String source = root + "src/🐍.c:3:5: error: broken\r\n" + root + "include/lib.h:4:1: note: here\r\n";
        for (int split = 0; split <= source.length(); split++) {
            LiteralStreamRewriter stream = new LiteralStreamRewriter(root, "");
            String result =
                stream.append(source.substring(0, split)) +
                stream.append(source.substring(split)) +
                stream.finish();
            assertEquals("split " + split, source.replace(root, ""), result);
        }
        LiteralStreamRewriter stream = new LiteralStreamRewriter(root, "");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < source.length(); i++) result.append(stream.append(source.substring(i, i + 1)));
        result.append(stream.finish());
        assertEquals(source.replace(root, ""), result.toString());
    }

    @Test
    public void greedyFullMatchesWinOverOverlappingPartialSuffixes() {
        for (String source : new String[] { "aaaaaa", "aaaabaaa", "zzabababab", "aabaaabaab", "ababa" }) {
            for (String pattern : new String[] { "aaa", "aba", "aab", "z", "not-present" }) {
                LiteralStreamRewriter stream = new LiteralStreamRewriter(pattern, "_");
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < source.length(); i++) result.append(
                    stream.append(source.substring(i, i + 1))
                );
                result.append(stream.finish());
                assertEquals(source + " / " + pattern, source.replace(pattern, "_"), result.toString());
            }
        }
    }

    @Test
    public void partialTailIsFlushedButOrdinaryOutputIsImmediate() {
        LiteralStreamRewriter stream = new LiteralStreamRewriter("/copy/project/", "");
        assertEquals("hello\n", stream.append("hello\n"));
        assertEquals("", stream.append("/copy/proj"));
        assertEquals("/copy/proj", stream.finish());
        assertEquals("", stream.finish());
        assertEquals("world", stream.append("world"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyPatternIsNotABoundedStreamingReplacement() {
        new LiteralStreamRewriter("", "");
    }
}
