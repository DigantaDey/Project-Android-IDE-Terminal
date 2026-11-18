package com.forgeide.core.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** LSP/DAP Content-Length framing. UTF-8 byte counts, bounded headers and messages, no line-based JSON assumptions. */
public final class FramedJson {
    public static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;
    private FramedJson() {}
    public static Map<String,Object> read(InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream(); int state = 0;
        while (state < 4) {
            int b = input.read(); if (b == -1) { if (header.size() == 0) return null; throw new EOFException("Incomplete protocol header"); }
            header.write(b); if (header.size() > 8192) throw new IOException("Protocol header exceeds 8 KiB");
            state = (state == 0 || state == 2) ? (b == '\r' ? state + 1 : 0) : (b == '\n' ? state + 1 : b == '\r' ? 1 : 0);
        }
        int length = -1;
        for (String line : header.toString(StandardCharsets.US_ASCII.name()).split("\r\n")) {
            int colon = line.indexOf(':'); if (colon < 0) continue;
            if (line.substring(0,colon).equalsIgnoreCase("Content-Length")) {
                if (length >= 0) throw new IOException("Duplicate Content-Length");
                try { length = Integer.parseInt(line.substring(colon+1).trim()); } catch (NumberFormatException e) { throw new IOException("Invalid Content-Length", e); }
            }
        }
        if (length < 0 || length > MAX_MESSAGE_BYTES) throw new IOException("Missing or oversized Content-Length");
        byte[] data = new byte[length]; int offset = 0;
        while (offset < length) { int n = input.read(data, offset, length-offset); if (n == -1) throw new EOFException("Incomplete protocol body"); offset += n; }
        try { return Json.parseObject(new String(data, StandardCharsets.UTF_8)); } catch (RuntimeException e) { throw new IOException("Invalid protocol JSON",e); }
    }
    public static void write(OutputStream output, Map<String,?> message) throws IOException {
        byte[] body = Json.write(message).getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_MESSAGE_BYTES) throw new IOException("Protocol message too large");
        synchronized (output) { output.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); output.write(body); output.flush(); }
    }
}
