package com.forgeide.core.util;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class Utf8ChunksTest {

    @Test
    public void everyByteBoundaryPreservesUnicode() {
        String value = "Hello বাংলা 🐍 🧑‍💻\r\n";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        Utf8Chunks decoder = new Utf8Chunks();
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(decoder.append(new byte[] { b }));
        result.append(decoder.finish());
        assertEquals(value, result.toString());
        assertEquals("", decoder.finish());
    }

    @Test
    public void incompleteAndMalformedOutputCannotBreakUi() {
        Utf8Chunks decoder = new Utf8Chunks();
        assertEquals("", decoder.append(new byte[] { (byte) 0xf0 }));
        assertEquals("�", decoder.finish());
        assertEquals("", decoder.append(new byte[] { 1 }));
    }
}
