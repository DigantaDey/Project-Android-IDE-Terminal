package com.forgeide.core.util;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class JsonTest {

    @Test
    public void parsesPrimitives() {
        assertEquals(Boolean.TRUE, Json.parse("true"));
        assertEquals(Boolean.FALSE, Json.parse("false"));
        assertNull(Json.parse("null"));
        assertEquals(42L, Json.parse("42"));
        assertEquals(-7L, Json.parse(" -7 "));
        assertEquals(3.5d, (Double) Json.parse("3.5"), 0.0001);
        assertEquals(1200.0d, (Double) Json.parse("1.2e3"), 0.0001);
        assertEquals("hello", Json.parse("\"hello\""));
    }

    @Test
    public void parsesObjectsPreservingOrder() {
        Object v = Json.parse("{\"b\": 1, \"a\": [1, 2, {\"c\": null}]}");
        assertTrue(v instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) v;
        assertArrayEquals(new String[]{"b", "a"}, map.keySet().toArray(new String[0]));
        @SuppressWarnings("unchecked")
        List<Object> arr = (List<Object>) map.get("a");
        assertEquals(3, arr.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) arr.get(2);
        assertTrue(nested.containsKey("c"));
        assertNull(nested.get("c"));
    }

    @Test
    public void parsesEscapesAndUnicode() {
        assertEquals("a\nb\t\"q\"\\u", Json.parse("\"a\\nb\\t\\\"q\\\"\\\\u\""));
        assertEquals("é", Json.parse("\"\\u00e9\""));
        // Surrogate pair: U+1F600
        assertEquals(new String(Character.toChars(0x1F600)), Json.parse("\"\\ud83d\\ude00\""));
    }

    @Test
    public void rejectsTrailingContent() {
        try {
            Json.parse("{} {}");
            fail("expected exception");
        } catch (Json.JsonException e) {
            assertTrue(e.getMessage().contains("trailing"));
        }
    }

    @Test
    public void rejectsInvalidDocuments() {
        String[] bad = {"{", "[1,", "{\"a\"}", "{\"a\":}", "{'x':1}", "\"unterminated", "01x", "{]",
                "\"bad\\xescape\"", "tru", "[1 2]", ""};
        for (String doc : bad) {
            try {
                Json.parse(doc);
                fail("expected parse failure for: " + doc);
            } catch (Json.JsonException expected) {
                assertTrue(eHasPosition(expected));
            }
        }
    }

    private static boolean eHasPosition(Json.JsonException e) {
        return e.getLine() >= 1 && e.getColumn() >= 1 && e.getOffset() >= 0;
    }

    @Test
    public void reportsAccuratePositions() {
        try {
            Json.parse("{\n  \"a\": 1,\n  \"b\": ,\n}");
            fail("expected exception");
        } catch (Json.JsonException e) {
            assertEquals(3, e.getLine());
            assertEquals(8, e.getColumn());
        }
    }

    @Test
    public void roundTrips() {
        String pretty = Json.writePretty(Json.parse("{\"name\":\"x\",\"tags\":[\"a\",\"b\"],\"n\":5,\"ok\":true}"), 2);
        Object reparsed = Json.parse(pretty);
        assertEquals(Json.parse("{\"name\":\"x\",\"tags\":[\"a\",\"b\"],\"n\":5,\"ok\":true}"), reparsed);
        assertTrue(pretty.contains("\n"));
        assertEquals("{\"a\":1}", Json.write(Json.parseObject("{\"a\":1}")));
    }

    @Test
    public void writesNonFiniteDoublesAsNull() {
        assertEquals("null", Json.write(Double.NaN));
        assertEquals("null", Json.write(Double.POSITIVE_INFINITY));
        assertEquals("2", Json.write(2.0)); // integral doubles print without .0
    }

    @Test
    public void jsonObjectAccessorIsForgiving() {
        JsonObject o = JsonObject.parse("{\"s\":\"v\",\"n\":3,\"d\":2.5,\"b\":true,"
                + "\"arr\":[\"a\",1],\"obj\":{\"x\":1},\"strNum\":\"42\"}");
        assertEquals("v", o.getString("s", "?"));
        assertEquals(3, o.getInt("n", 0));
        assertEquals(2.5, o.getDouble("d", 0), 0.0001);
        assertTrue(o.getBoolean("b", false));
        assertEquals(42L, o.getLong("strNum", 0));
        assertEquals(2, o.getArray("arr").size());
        assertEquals(1, o.getObject("obj").getInt("x", 0));
        assertEquals("fallback", o.getString("missing", "fallback"));
        assertFalse(o.getBoolean("missing", false));
        assertEquals(0, o.getArray("missing").size());
        assertEquals(2, o.getStringList("arr").size()); // "a" + "1"
    }
}
