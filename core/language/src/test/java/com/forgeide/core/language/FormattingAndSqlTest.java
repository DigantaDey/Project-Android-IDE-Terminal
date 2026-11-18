package com.forgeide.core.language;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class FormattingAndSqlTest {

    @Test
    public void jsonFormattingPreservesNumberAndStringLexemes() {
        String text = "{\"precision\":0.12345678901234567890123,\"escaped\":\"\\u0061\",\"power\":1.20e+12}";
        String formatted = JsonFormatter.format(text, 2);
        assertEquals(text, formatted.replaceAll("\\s+", ""));
        assertTrue(formatted.contains("\n  \"precision\""));
    }

    @Test
    public void jsonFormattingKeepsWhitespaceInsideStrings() {
        String source = "{ \"message\": \"two  spaces\", \"empty\": [] }";
        String output = JsonFormatter.format(source, 2);
        assertTrue(output.contains("two  spaces"));
        assertTrue(output.contains("\"empty\": []"));
    }

    @Test
    public void sqlSplitRespectsQuotesAndComments() {
        assertEquals(
            List.of("SELECT 'a;b'", "-- ignore ;\nSELECT \"a;b\"", "/* ; */ SELECT 2"),
            SqlStatements.split("SELECT 'a;b'; -- ignore ;\nSELECT \"a;b\"; /* ; */ SELECT 2;")
        );
    }

    @Test
    public void sqlTriggerBodyIsOneStatement() {
        assertEquals(
            2,
            SqlStatements.split(
                "CREATE TRIGGER test AFTER INSERT ON sample BEGIN INSERT INTO log VALUES(CASE WHEN 1 THEN 2 ELSE 3 END); UPDATE log SET x=4; END; SELECT 1;"
            ).size()
        );
    }

    @Test
    public void sqlKeywordIgnoresComments() {
        assertEquals("ATTACH", SqlStatements.keyword("-- test\n/* test */ ATTACH 'secret' AS other"));
    }
}
