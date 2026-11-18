package com.forgeide.core.editor;

import static org.junit.Assert.*;

import com.forgeide.core.util.BoundedText;
import org.junit.Test;

public class SearchSafetyTest {

    @Test
    public void zeroWidthAnchorsDoNotResetTheMatcherRegion() {
        FindReplaceEngine e = new FindReplaceEngine(new Document("abc\ndef"));
        assertEquals(2, e.count(new FindReplaceEngine.Query("^", true, true, false)));
    }

    @Test
    public void namedGroupsAndEscapedDollarsWork() {
        Document d = new Document("hello world");
        FindReplaceEngine e = new FindReplaceEngine(d);
        e.replaceAll(
            new FindReplaceEngine.Query("(?<first>hello) (world)", true, true, false),
            "$2 ${first} \\$",
            null
        );
        assertEquals("world hello $", d.text());
    }

    @Test
    public void invalidReplacementDoesNotPartiallyMutate() {
        Document d = new Document("x x x");
        try {
            new FindReplaceEngine(d).replaceAll(
                new FindReplaceEngine.Query("(x)", true, true, false),
                "$9",
                null
            );
            fail();
        } catch (IllegalArgumentException | IndexOutOfBoundsException expected) {}
        assertEquals("x x x", d.text());
    }

    @Test(timeout = 3000)
    public void catastrophicRegexIsBudgeted() {
        try {
            new FindReplaceEngine(new Document("a".repeat(100000) + "!")).count(
                new FindReplaceEngine.Query("(a+)+$", true, true, false)
            );
            fail();
        } catch (BoundedText.LimitExceeded expected) {}
    }
}
