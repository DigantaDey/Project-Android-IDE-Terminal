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

    @Test(timeout = 8000)
    public void cancellationAndTimeoutAreSentToTheServerAndDoNotLeakPendingRequests() throws Exception {
        PipedInputStream serverInput = new PipedInputStream(65536),
            hostInput = new PipedInputStream(65536);
        PipedOutputStream hostOutput = new PipedOutputStream(serverInput),
            serverOutput = new PipedOutputStream(hostInput);
        try (ProtocolClient client = new ProtocolClient(hostInput, hostOutput, false, event -> {})) {
            CompletableFuture<JsonObject> cancelled = client.request("completion", Map.of());
            JsonObject request = new JsonObject(FramedJson.read(serverInput));
            cancelled.cancel(true);
            JsonObject cancel = new JsonObject(FramedJson.read(serverInput));
            assertEquals("$/cancelRequest", cancel.getString("method", ""));
            assertEquals(request.getLong("id", -1), cancel.getObject("params").getLong("id", -2));
            CompletableFuture<JsonObject> timed = client.request("hover", Map.of(), 100);
            request = new JsonObject(FramedJson.read(serverInput));
            try {
                timed.get(2, TimeUnit.SECONDS);
                fail();
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof TimeoutException);
            }
            cancel = new JsonObject(FramedJson.read(serverInput));
            assertEquals("$/cancelRequest", cancel.getString("method", ""));
            assertEquals(request.getLong("id", -1), cancel.getObject("params").getLong("id", -2));
            // Late responses cannot complete a different request.
            FramedJson.write(
                serverOutput,
                Map.of("jsonrpc", "2.0", "id", request.raw().get("id"), "result", Map.of())
            );
            CompletableFuture<JsonObject> finalRequest = client.request("symbols", Map.of());
            JsonObject last = new JsonObject(FramedJson.read(serverInput));
            FramedJson.write(
                serverOutput,
                Map.of("jsonrpc", "2.0", "id", last.raw().get("id"), "result", Map.of("ok", true))
            );
            assertTrue(finalRequest.get(2, TimeUnit.SECONDS).getObject("result").getBoolean("ok", false));
        } finally {
            serverInput.close();
            serverOutput.close();
        }
    }

    @Test(timeout = 5000)
    public void peerDisconnectFailsAllOutstandingRequestsAndFurtherRequestsFailImmediately()
        throws Exception {
        PipedInputStream serverInput = new PipedInputStream(65536),
            hostInput = new PipedInputStream(65536);
        PipedOutputStream hostOutput = new PipedOutputStream(serverInput),
            serverOutput = new PipedOutputStream(hostInput);
        try (ProtocolClient client = new ProtocolClient(hostInput, hostOutput, false, event -> {})) {
            CompletableFuture<JsonObject> pending = client.request("completion", Map.of());
            FramedJson.read(serverInput);
            serverOutput.close();
            try {
                pending.get(2, TimeUnit.SECONDS);
                fail();
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof EOFException);
            }
            assertTrue(client.request("hover", Map.of()).isCompletedExceptionally());
        } finally {
            serverInput.close();
            serverOutput.close();
        }
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
