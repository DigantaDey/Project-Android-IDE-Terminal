package com.forgeide.core.util;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;

public class FramedJsonTest {

    @Test
    public void utf8ByteLengthAndConsecutiveMessages() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FramedJson.write(out, Map.of("text", "বাংলা 😀"));
        FramedJson.write(out, Map.of("id", 2));
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        assertEquals("বাংলা 😀", FramedJson.read(in).get("text"));
        assertEquals(2L, FramedJson.read(in).get("id"));
        assertNull(FramedJson.read(in));
    }

    @Test
    public void malformedFramesRejected() throws Exception {
        for (String text : new String[] {
            "Content-Length: 99999999\r\n\r\n",
            "Content-Length: 5\r\nContent-Length: 5\r\n\r\n{}",
            "Content-Length: 12\r\n\r\n{}",
            "Broken\r\n\r\n",
        })
            try {
                FramedJson.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
                fail(text);
            } catch (IOException expected) {}
    }
}
