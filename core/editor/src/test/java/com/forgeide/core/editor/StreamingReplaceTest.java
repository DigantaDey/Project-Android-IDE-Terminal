package com.forgeide.core.editor;

import static org.junit.Assert.*;

import com.forgeide.core.util.BoundedText;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.Test;

public class StreamingReplaceTest {

    @Test
    public void replacementTemplatesMatchJavaRegexGrammar() {
        String source = "ab a ab",
            regex = "(?<word>a)(b)?";
        for (String template : List.of("$0|$1|$2", "$10", "${word}", "\\$1", "$02", "$0001", "\\\\$0")) {
            Document document = new Document(source);
            new FindReplaceEngine(document).replaceAll(
                new FindReplaceEngine.Query(regex, true, true, false),
                template,
                null
            );
            assertEquals(
                template,
                Pattern.compile(regex).matcher(source).replaceAll(template),
                document.text()
            );
        }
    }

    @Test(timeout = 3000)
    public void repeatedRegexReplacementStreamsWithoutQuadraticPrefixCopies() {
        Document document = new Document("x ".repeat(50000));
        FindReplaceEngine engine = new FindReplaceEngine(document);
        assertEquals(
            50000,
            engine.replaceAll(new FindReplaceEngine.Query("(x)", true, true, false), "$1$1", null)
        );
        assertEquals("xx ".repeat(50000), document.text());
    }

    @Test
    public void contiguousAnchorKeepsOriginalMatcherContext() {
        Document document = new Document("abc def");
        assertEquals(
            3,
            new FindReplaceEngine(document).replaceAll(
                new FindReplaceEngine.Query("\\G\\w", true, true, false),
                "_",
                null
            )
        );
        assertEquals("___ def", document.text());
    }

    @Test
    public void oversizedCaptureExpansionIsRejectedBeforeMutation() {
        String source = "a".repeat(131072);
        Document document = new Document(source);
        try {
            new FindReplaceEngine(document).replaceAll(
                new FindReplaceEngine.Query("(.+)", true, true, false),
                "$1".repeat(100),
                null
            );
            fail();
        } catch (IllegalArgumentException expected) {}
        assertEquals(source, document.text());
    }

    @Test
    public void enumerationIsBoundedButCountDoesNotAllocateEveryMatch() {
        FindReplaceEngine engine = new FindReplaceEngine(new Document("x".repeat(20001)));
        assertEquals(20001, engine.count(FindReplaceEngine.Query.literal("x")));
        assertEquals(3, engine.findAll(FindReplaceEngine.Query.literal("x"), 3).size());
        try {
            engine.findAll(FindReplaceEngine.Query.literal("x"), Integer.MAX_VALUE);
            fail();
        } catch (BoundedText.LimitExceeded expected) {}
    }

    @Test
    public void staleLiteralReplacementNeverOverwritesDifferentText() {
        Document document = new Document("foo");
        FindReplaceEngine engine = new FindReplaceEngine(document);
        FindReplaceEngine.Query query = FindReplaceEngine.Query.literal("foo");
        FindReplaceEngine.Match match = engine.findNext(query, 0, false);
        document.replace(0, 3, "bar");
        try {
            engine.replace(query, match, "new");
            fail();
        } catch (IllegalStateException expected) {}
        assertEquals("bar", document.text());
    }
}
