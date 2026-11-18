package com.forgeide.core.util;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public class ProtocolClientTest {

    @Test(timeout = 5000)
    public void lspResponsesAndNotificationsAreCorrelated() throws Exception {
        exchange(false);
    }

    @Test(timeout = 5000)
    public void dapResponseSequenceIsCorrelated() throws Exception {
        exchange(true);
    }

    private void exchange(boolean dap) throws Exception {
        PipedInputStream peerInput = new PipedInputStream(16384);
        PipedOutputStream hostOutput = new PipedOutputStream(peerInput);
        PipedInputStream hostInput = new PipedInputStream(16384);
        PipedOutputStream peerOutput = new PipedOutputStream(hostInput);
        BlockingQueue<JsonObject> events = new LinkedBlockingQueue<>();
        try (ProtocolClient client = new ProtocolClient(hostInput, hostOutput, dap, events::add)) {
            CompletableFuture<JsonObject> response = client.request("initialize", Map.of());
            Map<String, Object> request = FramedJson.read(peerInput);
            assertEquals("initialize", request.get(dap ? "command" : "method"));
            if (dap) FramedJson.write(
                peerOutput,
                Map.of(
                    "seq",
                    2,
                    "request_seq",
                    request.get("seq"),
                    "type",
                    "response",
                    "success",
                    true,
                    "body",
                    Map.of("ready", true)
                )
            );
            else FramedJson.write(
                peerOutput,
                Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", Map.of("ready", true))
            );
            assertTrue(
                response
                    .get(2, TimeUnit.SECONDS)
                    .getObject(dap ? "body" : "result")
                    .getBoolean("ready", false)
            );
            if (dap) FramedJson.write(peerOutput, Map.of("type", "event", "event", "stopped"));
            else FramedJson.write(
                peerOutput,
                Map.of("jsonrpc", "2.0", "method", "textDocument/publishDiagnostics", "params", Map.of())
            );
            assertNotNull(events.poll(2, TimeUnit.SECONDS));
            CompletableFuture<JsonObject> pending = client.request("never-respond", Map.of());
            client.close();
            assertTrue(pending.isCompletedExceptionally());
        } finally {
            peerInput.close();
            peerOutput.close();
        }
    }
}
