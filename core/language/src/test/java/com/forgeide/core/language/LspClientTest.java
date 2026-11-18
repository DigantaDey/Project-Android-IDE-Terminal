package com.forgeide.core.language;

import static org.junit.Assert.*;

import com.forgeide.core.util.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public class LspClientTest {

    @Test(timeout = 8000)
    public void initializeHandlesServerConfigurationRequestsAndNegotiatesFeaturesBeforeDocumentTraffic()
        throws Exception {
        PipedInputStream serverInput = new PipedInputStream(65536),
            hostInput = new PipedInputStream(65536);
        PipedOutputStream hostOutput = new PipedOutputStream(serverInput),
            serverOutput = new PipedOutputStream(hostInput);
        try (LspClient client = new LspClient(hostInput, hostOutput, event -> {})) {
            CompletableFuture<JsonObject> ready = client.initialize(
                "file:///copy/",
                Map.of("disableAutomaticTypingAcquisition", true),
                Map.of("python", Map.of("analysis", Map.of("typeCheckingMode", "basic")))
            );
            JsonObject initialize = new JsonObject(FramedJson.read(serverInput));
            assertTrue(initialize.getObject("params").raw().containsKey("processId"));
            assertNull(initialize.getObject("params").raw().get("processId"));
            assertFalse(
                initialize
                    .getObject("params")
                    .getObject("capabilities")
                    .getObject("textDocument")
                    .getObject("completion")
                    .getObject("completionItem")
                    .getBoolean("snippetSupport", true)
            );
            FramedJson.write(
                serverOutput,
                Map.of(
                    "jsonrpc",
                    "2.0",
                    "id",
                    "config",
                    "method",
                    "workspace/configuration",
                    "params",
                    Map.of(
                        "items",
                        List.of(Map.of("section", "python.analysis"), Map.of("section", "missing"))
                    )
                )
            );
            JsonObject configuration = new JsonObject(FramedJson.read(serverInput));
            assertEquals("config", configuration.getString("id", ""));
            assertEquals(
                "basic",
                LspResults.object(configuration.getArray("result").get(0)).getString("typeCheckingMode", "")
            );
            assertNull(configuration.getArray("result").get(1));
            FramedJson.write(
                serverOutput,
                Map.of(
                    "jsonrpc",
                    "2.0",
                    "id",
                    initialize.raw().get("id"),
                    "result",
                    Map.of(
                        "capabilities",
                        Map.of(
                            "hoverProvider",
                            true,
                            "completionProvider",
                            Map.of(),
                            "documentFormattingProvider",
                            false
                        )
                    )
                )
            );
            ready.get(2, TimeUnit.SECONDS);
            assertEquals("initialized", new JsonObject(FramedJson.read(serverInput)).getString("method", ""));
            assertEquals(
                "workspace/didChangeConfiguration",
                new JsonObject(FramedJson.read(serverInput)).getString("method", "")
            );
            assertTrue(client.supports("hover"));
            assertTrue(client.supports("completion"));
            assertFalse(client.supports("definition"));
            assertFalse(client.supports("formatting"));
            client.open("file:///copy/main.py", "python", 1, "value = 42");
            client.change("file:///copy/main.py", 2, "value = 'unsaved'");
            assertEquals(
                "textDocument/didOpen",
                new JsonObject(FramedJson.read(serverInput)).getString("method", "")
            );
            JsonObject changed = new JsonObject(FramedJson.read(serverInput));
            assertEquals("textDocument/didChange", changed.getString("method", ""));
            assertEquals(2, changed.getObject("params").getObject("textDocument").getInt("version", 0));
            assertEquals(
                "value = 'unsaved'",
                LspResults.object(changed.getObject("params").getArray("contentChanges").get(0)).getString(
                    "text",
                    ""
                )
            );
        } finally {
            serverInput.close();
            serverOutput.close();
        }
    }

    @Test(timeout = 5000)
    public void serverCannotExecuteClientCommandsApplyEditsOrRegisterLiveFileWatchers() throws Exception {
        PipedInputStream serverInput = new PipedInputStream(65536),
            hostInput = new PipedInputStream(65536);
        PipedOutputStream hostOutput = new PipedOutputStream(serverInput),
            serverOutput = new PipedOutputStream(hostInput);
        try (LspClient client = new LspClient(hostInput, hostOutput, event -> {})) {
            for (String method : List.of(
                "workspace/applyEdit",
                "client/registerCapability",
                "workspace/executeClientCommand"
            )) {
                FramedJson.write(
                    serverOutput,
                    Map.of("jsonrpc", "2.0", "id", method, "method", method, "params", Map.of())
                );
                JsonObject response = new JsonObject(FramedJson.read(serverInput));
                if (method.equals("workspace/applyEdit")) assertFalse(
                    response.getObject("result").getBoolean("applied", true)
                );
                else assertEquals(-32601, response.getObject("error").getInt("code", 0));
            }
        } finally {
            serverInput.close();
            serverOutput.close();
        }
    }
}
