package com.forgeide.core.language;

import com.forgeide.core.util.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Real JSON-RPC LSP, UTF-16 positions, explicitly negotiated features, no client-side commands. */
public final class LspClient implements AutoCloseable {

    private final ProtocolClient rpc;
    private volatile JsonObject capabilities = JsonObject.empty();
    private volatile Map<String, Object> configuration = Map.of();
    private volatile String rootUri = "";

    public LspClient(InputStream in, OutputStream out, Consumer<JsonObject> events) {
        rpc = new ProtocolClient(in, out, false, events, this::serverRequest);
    }

    public CompletableFuture<Void> disconnected() {
        return rpc.disconnected;
    }

    private Object serverRequest(JsonObject message) {
        switch (message.getString("method", "")) {
            case "workspace/configuration": {
                List<Object> result = new ArrayList<>();
                for (Object raw : message.getObject("params").getArray("items")) {
                    JsonObject item = object(raw);
                    Object value = configuration;
                    String section = item.getString("section", "");
                    if (!section.isEmpty()) for (String part : section.split("\\."))
                        value = value instanceof Map ? ((Map<?, ?>) value).get(part) : null;
                    result.add(value);
                }
                return result;
            }
            case "workspace/workspaceFolders":
                return List.of(Map.of("uri", rootUri, "name", "Forge project copy"));
            case "window/workDoneProgress/create":
                return null;
            case "window/showMessageRequest":
                return null;
            case "workspace/applyEdit":
                return Map.of(
                    "applied",
                    false,
                    "failureReason",
                    "Server-initiated project edits are not permitted"
                );
            default:
                throw new UnsupportedOperationException();
        }
    }

    @SuppressWarnings("unchecked")
    private static JsonObject object(Object value) {
        return value instanceof Map ? new JsonObject((Map<String, Object>) value) : JsonObject.empty();
    }

    public CompletableFuture<JsonObject> initialize(String rootUri) {
        return initialize(rootUri, Map.of(), Map.of());
    }

    public CompletableFuture<JsonObject> initialize(
        String rootUri,
        Map<String, Object> options,
        Map<String, Object> configuration
    ) {
        this.rootUri = rootUri;
        this.configuration = configuration;
        Map<String, Object> params = new LinkedHashMap<>();
        // null: the server lives in another UID and must not watch an invented PID 0.
        params.put("processId", null);
        params.put("clientInfo", Map.of("name", "Forge Android", "version", "0.3.0"));
        params.put("rootUri", rootUri);
        params.put("workspaceFolders", List.of(Map.of("uri", rootUri, "name", "Forge project copy")));
        params.put("initializationOptions", options);
        params.put(
            "capabilities",
            Map.of(
                "general",
                Map.of("positionEncodings", List.of("utf-16")),
                "workspace",
                Map.of("configuration", true, "workspaceFolders", true, "applyEdit", false),
                "textDocument",
                Map.of(
                    "completion",
                    Map.of("completionItem", Map.of("snippetSupport", false, "insertReplaceSupport", false)),
                    "hover",
                    Map.of("contentFormat", List.of("plaintext", "markdown")),
                    "definition",
                    Map.of("linkSupport", true),
                    "documentSymbol",
                    Map.of("hierarchicalDocumentSymbolSupport", true),
                    "publishDiagnostics",
                    Map.of("versionSupport", true)
                )
            )
        );
        return rpc
            .request("initialize", params)
            .thenApply(response -> {
                capabilities = response.getObject("result").getObject("capabilities");
                if (
                    !capabilities.getString("positionEncoding", "utf-16").equalsIgnoreCase("utf-16")
                ) throw new CompletionException(
                    new IOException("Server selected unsupported position encoding")
                );
                try {
                    rpc.notify("initialized", Map.of());
                    rpc.notify("workspace/didChangeConfiguration", Map.of("settings", configuration));
                } catch (IOException error) {
                    throw new CompletionException(error);
                }
                return response;
            });
    }

    public JsonObject capabilities() {
        return capabilities;
    }

    public boolean supports(String method) {
        String key = method.equals("documentSymbol")
            ? "documentSymbolProvider"
            : method.equals("formatting")
                ? "documentFormattingProvider"
                : method + "Provider";
        Object value = capabilities.raw().get(key);
        return Boolean.TRUE.equals(value) || value instanceof Map;
    }

    public void open(String uri, String language, int version, String text) throws IOException {
        rpc.notify(
            "textDocument/didOpen",
            Map.of(
                "textDocument",
                Map.of("uri", uri, "languageId", language, "version", version, "text", text)
            )
        );
    }

    public void change(String uri, int version, String text) throws IOException {
        rpc.notify(
            "textDocument/didChange",
            Map.of(
                "textDocument",
                Map.of("uri", uri, "version", version),
                "contentChanges",
                List.of(Map.of("text", text))
            )
        );
    }

    public void closeDocument(String uri) throws IOException {
        rpc.notify("textDocument/didClose", Map.of("textDocument", Map.of("uri", uri)));
    }

    public CompletableFuture<JsonObject> at(String method, String uri, int line, int column) {
        return rpc.request(
            "textDocument/" + method,
            Map.of("textDocument", Map.of("uri", uri), "position", Map.of("line", line, "character", column))
        );
    }

    public CompletableFuture<JsonObject> references(String uri, int line, int column) {
        return rpc.request(
            "textDocument/references",
            Map.of(
                "textDocument",
                Map.of("uri", uri),
                "position",
                Map.of("line", line, "character", column),
                "context",
                Map.of("includeDeclaration", true)
            )
        );
    }

    public CompletableFuture<JsonObject> symbols(String uri) {
        return rpc.request("textDocument/documentSymbol", Map.of("textDocument", Map.of("uri", uri)));
    }

    public CompletableFuture<JsonObject> format(String uri, int tabSize, boolean spaces) {
        return rpc.request(
            "textDocument/formatting",
            Map.of(
                "textDocument",
                Map.of("uri", uri),
                "options",
                Map.of("tabSize", tabSize, "insertSpaces", spaces)
            )
        );
    }

    @Override
    public void close() throws IOException {
        rpc.close();
    }
}
