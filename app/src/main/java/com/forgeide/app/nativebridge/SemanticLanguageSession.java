package com.forgeide.app.nativebridge;

import android.os.*;
import com.forgeide.app.ForgeApplication;
import com.forgeide.core.language.*;
import com.forgeide.core.project.Workspace;
import com.forgeide.core.toolchain.LanguageServers;
import com.forgeide.core.util.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Activity-owned offline LSP session. Only the foreground editor can keep a copy alive. */
public final class SemanticLanguageSession implements AutoCloseable {

    private final ForgeApplication app;
    private final LanguageRegistry languages = LanguageRegistry.builtins();
    private final Consumer<List<Diagnostic>> diagnostics;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r ->
        new Thread(r, "semantic-session")
    );
    private volatile State state;
    private volatile String status = "LSP off · lexical";
    private boolean foreground, stopped, disposed;
    private Workspace failedWorkspace;
    private String failedServer = "";

    private static final class Snapshot {

        final ForgeApplication.OpenDocument doc;
        final String path, text;
        final long revision;

        Snapshot(ForgeApplication.OpenDocument doc, String text, long revision) {
            this.doc = doc;
            this.path = doc.path;
            this.text = text;
            this.revision = revision;
        }
    }

    private static final class Sent {

        final Snapshot snapshot;
        final int version;

        Sent(Snapshot snapshot, int version) {
            this.snapshot = snapshot;
            this.version = version;
        }
    }

    private static final class State {

        final Workspace workspace;
        final String server;
        final CancellationToken cancel = new CancellationToken();
        final CompletableFuture<LspClient> ready = new CompletableFuture<>();
        final Map<String, Sent> sent = new ConcurrentHashMap<>();
        final Map<String, List<Diagnostic>> found = new ConcurrentHashMap<>();
        final AtomicBoolean syncing = new AtomicBoolean();
        volatile List<Snapshot> latest = List.of();
        volatile LspClient client;
        volatile LspProjectUris uris;
        volatile NativeRuntimeClient.Run run;
        final Set<CompletableFuture<?>> requests = ConcurrentHashMap.newKeySet();
        int version;

        State(Workspace workspace, String server) {
            this.workspace = workspace;
            this.server = server;
        }
    }

    public static final class Reply {

        public final JsonObject message;
        public final LspProjectUris uris;
        public final String server, source;
        private final State state;

        private Reply(JsonObject message, State state, String source) {
            this.state = state;
            this.message = message;
            this.uris = state.uris;
            this.server = state.server;
            this.source = source;
        }
    }

    public SemanticLanguageSession(ForgeApplication app, Consumer<List<Diagnostic>> diagnostics) {
        this.app = app;
        this.diagnostics = diagnostics;
    }

    public String status() {
        return status;
    }

    public boolean isCurrent(Reply reply) {
        return reply != null && alive(reply.state) && app.nativeRuntime.availableServer(reply.server);
    }

    private void status(String text) {
        if (!status.equals(text)) {
            status = text;
            app.changed();
        }
    }

    public static String languageId(LanguageRegistry registry, String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tsx")) return "typescriptreact";
        if (lower.endsWith(".jsx")) return "javascriptreact";
        if (lower.endsWith(".scss")) return "scss";
        if (lower.endsWith(".less")) return "less";
        if (lower.endsWith(".jsonc")) return "jsonc";
        return registry.forFile(path).id();
    }

    private String server(ForgeApplication.OpenDocument doc) {
        return LanguageServers.forLanguage(languageId(languages, doc.path));
    }

    public boolean readyFor(ForgeApplication.OpenDocument doc) {
        State s = state;
        return (
            s != null &&
            s.workspace == doc.owner &&
            s.server.equals(server(doc)) &&
            s.ready.isDone() &&
            !s.ready.isCompletedExceptionally() &&
            s.client != null &&
            app.nativeRuntime.availableServer(s.server) &&
            !s.cancel.isCancelled()
        );
    }

    public void resume(ForgeApplication.OpenDocument doc) {
        foreground = true;
        refresh(doc);
    }

    public void suspend() {
        foreground = false;
        stopCurrent();
    }

    public void halt() {
        stopped = true;
        stopCurrent();
        status("LSP stopped · restart to resume");
    }

    public void restart(ForgeApplication.OpenDocument doc) {
        stopped = false;
        failedWorkspace = null;
        failedServer = "";
        stopCurrent().whenComplete((v, e) -> app.main.post(() -> refresh(doc)));
    }

    /** Called on the main thread after debouncing edits, tab/project changes and consent changes. */
    public void refresh(ForgeApplication.OpenDocument current) {
        if (disposed || !foreground || stopped) return;
        String id = current == null ? "" : server(current);
        if (
            current == null ||
            current.owner != app.workspace ||
            current.content.length() > LspResults.MAX_TEXT ||
            !LspProjectUris.allowed(current.path) ||
            id.isEmpty() ||
            !app.nativeRuntime.availableServer(id)
        ) {
            stopCurrent();
            status(
                app.settings.bool("requireHardLimits")
                    ? "LSP refused · hard quotas unsupported"
                    : "LSP off / unavailable · lexical"
            );
            return;
        }
        if (app.nativeRuntime.hasUserSession()) {
            stopCurrent();
            status("LSP paused · native run / PTY");
            return;
        }
        if (failedWorkspace == app.workspace && failedServer.equals(id)) {
            status("LSP failed · restart server");
            return;
        }
        State s = state;
        if (s == null || s.workspace != app.workspace || !s.server.equals(id)) {
            CompletableFuture<?> previous = stopCurrent();
            s = new State(app.workspace, id);
            state = s;
            State next = s;
            status(id + " · starting offline");
            previous.whenComplete((v, e) -> app.main.post(() -> launch(next)));
        }
        List<Snapshot> latest = new ArrayList<>();
        for (ForgeApplication.OpenDocument doc : app.documents) {
            if (
                doc.owner == s.workspace &&
                server(doc).equals(id) &&
                doc.content.length() <= LspResults.MAX_TEXT &&
                LspProjectUris.allowed(doc.path)
            ) {
                long revision = doc.revision.get();
                Sent old = s.sent.get(doc.path);
                Snapshot snapshot = old != null &&
                    old.snapshot.doc == doc &&
                    old.snapshot.revision == revision
                    ? old.snapshot
                    : new Snapshot(doc, doc.content.toString(), revision);
                if (revision == doc.revision.get()) latest.add(snapshot);
            }
        }
        s.latest = List.copyOf(latest);
        publish(s); // Immediately remove diagnostics for changed/closed documents.
        scheduleSync(s);
    }

    private boolean alive(State s) {
        return state == s && !s.cancel.isCancelled() && s.workspace == app.workspace;
    }

    private void execute(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException ignored) {}
    }

    private void launch(State s) {
        if (!alive(s) || disposed || !foreground || !app.nativeRuntime.availableServer(s.server)) return;
        try {
            s.run = app.nativeRuntime.startServer(s.workspace, s.server, s.cancel, info ->
                execute(() -> connect(s, info))
            );
            s.run.result.whenComplete((exit, error) ->
                app.main.post(() -> {
                    if (!alive(s)) return;
                    if (error != null || (exit != null && exit != 130)) fail(
                        s,
                        error == null
                            ? new IOException("Server exited " + exit + ": " + s.run.message)
                            : error
                    );
                    else {
                        stopCurrent();
                        status("LSP paused · native run / PTY");
                    }
                })
            );
        } catch (Exception error) {
            fail(s, error);
        }
    }

    private void connect(State s, Bundle info) {
        if (!alive(s)) return; // The bridge owns and closes descriptors on cancellation.
        try {
            if (
                info.getBoolean("network", true) ||
                info.getInt("uid") == android.os.Process.myUid() ||
                !s.server.equals(info.getString("server"))
            ) throw new IOException("Invalid offline language-server identity");
            s.uris = new LspProjectUris(info.getString("project", ""));
            ParcelFileDescriptor input = info.getParcelable("stdin"),
                output = info.getParcelable("stdout"),
                error = info.getParcelable("stderr");
            if (input == null || output == null || error == null) throw new IOException(
                "Runtime has no raw language-server streams; install the matching APK pair"
            );
            String prefix = info.getString("prefix", "");
            if (
                !prefix.equals("/data/user/0/com.forgex/files/usr") &&
                !prefix.equals("/data/data/com.forgex/files/usr")
            ) throw new IOException("Unexpected native prefix");
            drainErrors(s, new ParcelFileDescriptor.AutoCloseInputStream(error));
            LspClient client = new LspClient(
                new ParcelFileDescriptor.AutoCloseInputStream(output),
                new ParcelFileDescriptor.AutoCloseOutputStream(input),
                event -> event(s, event)
            );
            s.client = client;
            client
                .disconnected()
                .whenComplete((v, failure) -> {
                    if (alive(s)) fail(
                        s,
                        failure == null ? new EOFException("Language server disconnected") : failure
                    );
                });
            client
                .initialize(
                    s.uris.rootUri(),
                    LanguageServers.initialization(s.server, prefix),
                    LanguageServers.configuration(s.server, prefix)
                )
                .whenComplete((response, failure) -> {
                    if (failure != null) {
                        fail(s, failure);
                        return;
                    }
                    if (!alive(s)) {
                        try {
                            client.close();
                        } catch (IOException ignored) {}
                        return;
                    }
                    s.ready.complete(client);
                    app.main.post(() -> {
                        if (alive(s)) status(s.server + " · semantic");
                    });
                    scheduleSync(s);
                });
        } catch (Exception failure) {
            fail(s, failure);
        }
    }

    private void drainErrors(State s, InputStream stream) {
        Thread thread = new Thread(
            () -> {
                BoundedLog errors = new BoundedLog(8192);
                Utf8Chunks utf8 = new Utf8Chunks();
                try (InputStream input = stream) {
                    byte[] bytes = new byte[4096];
                    int n;
                    while ((n = input.read(bytes)) != -1) errors.append(utf8.append(Arrays.copyOf(bytes, n)));
                    errors.append(utf8.finish());
                } catch (IOException ignored) {}
                if (alive(s) && !errors.toString().trim().isEmpty()) app.log(s.server + " stderr: " + errors);
            },
            "semantic-stderr"
        );
        thread.setDaemon(true);
        thread.start();
    }

    private void scheduleSync(State s) {
        if (
            !alive(s) ||
            !s.ready.isDone() ||
            s.ready.isCompletedExceptionally() ||
            !s.syncing.compareAndSet(false, true)
        ) return;
        execute(() -> {
            List<Snapshot> latest = s.latest;
            try {
                sync(s, latest);
            } catch (Exception failure) {
                fail(s, failure);
            } finally {
                s.syncing.set(false);
                if (latest != s.latest) scheduleSync(s);
            }
        });
    }

    private void sync(State s, List<Snapshot> latest) throws IOException {
        if (!alive(s)) throw new IOException("Semantic session changed");
        Set<String> open = new HashSet<>();
        for (Snapshot snapshot : latest) {
            if (
                snapshot.doc.owner != s.workspace ||
                !app.documents.contains(snapshot.doc) ||
                !snapshot.path.equals(snapshot.doc.path)
            ) continue;
            open.add(snapshot.path);
            send(s, snapshot);
        }
        for (String path : new ArrayList<>(s.sent.keySet()))
            if (!open.contains(path)) {
                s.client.closeDocument(s.uris.uri(path));
                s.sent.remove(path);
                s.found.remove(path);
            }
    }

    private void send(State s, Snapshot snapshot) throws IOException {
        if (
            snapshot.doc.owner != s.workspace ||
            !app.documents.contains(snapshot.doc) ||
            !snapshot.path.equals(snapshot.doc.path) ||
            snapshot.text.length() > LspResults.MAX_TEXT
        ) throw new IOException("Document closed or changed ownership");
        Sent old = s.sent.get(snapshot.path);
        if (
            old != null && old.snapshot.doc == snapshot.doc && old.snapshot.revision >= snapshot.revision
        ) return;
        Sent sent = new Sent(snapshot, ++s.version);
        s.sent.put(snapshot.path, sent);
        if (old == null) s.client.open(
            s.uris.uri(snapshot.path),
            languageId(languages, snapshot.path),
            sent.version,
            snapshot.text
        );
        else s.client.change(s.uris.uri(snapshot.path), sent.version, snapshot.text);
    }

    public CompletableFuture<Reply> request(
        ForgeApplication.OpenDocument doc,
        String text,
        long revision,
        String method,
        int line,
        int column
    ) {
        CompletableFuture<Reply> result = new CompletableFuture<>();
        State s = state;
        if (
            s == null ||
            !alive(s) ||
            s.workspace != doc.owner ||
            !s.server.equals(server(doc)) ||
            text.length() > LspResults.MAX_TEXT ||
            !app.nativeRuntime.availableServer(s.server)
        ) {
            result.completeExceptionally(
                new IOException(
                    "No active semantic provider. Enable its Settings switch, or restart the language server."
                )
            );
            return result;
        }
        s.requests.add(result);
        result.whenComplete((reply, error) -> s.requests.remove(result));
        s.ready.whenComplete((client, failure) -> {
            if (result.isDone()) return;
            if (failure != null) {
                result.completeExceptionally(failure);
                return;
            }
            execute(() -> {
                try {
                    if (result.isDone()) return;
                    if (
                        !alive(s) ||
                        doc.revision.get() != revision ||
                        !app.nativeRuntime.availableServer(s.server)
                    ) throw new IOException("Document or semantic session changed; request cancelled");
                    if (
                        !client.supports(method) || (method.equals("formatting") && s.server.equals("bashls"))
                    ) throw new IOException(
                        "This server does not provide " + method + "; no substitute is presented as semantic"
                    );
                    sync(s, s.latest);
                    send(s, new Snapshot(doc, text, revision));
                    String uri = s.uris.uri(doc.path);
                    CompletableFuture<JsonObject> rpc = method.equals("documentSymbol")
                        ? client.symbols(uri)
                        : method.equals("references")
                            ? client.references(uri, line, column)
                            : method.equals("formatting")
                                ? client.format(
                                      uri,
                                      app.settings.integer("tabSize"),
                                      !app.settings.bool("useTabs")
                                  )
                                : client.at(method, uri, line, column);
                    result.whenComplete((r, error) -> {
                        if (result.isCancelled()) rpc.cancel(true);
                    });
                    rpc.whenComplete((response, error) -> {
                        if (error != null) result.completeExceptionally(error);
                        else if (!alive(s) || doc.revision.get() != revision) result.completeExceptionally(
                            new IOException("Document or semantic session changed; stale result discarded")
                        );
                        else result.complete(new Reply(response, s, text));
                    });
                } catch (Exception error) {
                    result.completeExceptionally(error);
                }
            });
        });
        return result;
    }

    private void event(State s, JsonObject event) {
        if (!alive(s) || !event.getString("method", "").equals("textDocument/publishDiagnostics")) return;
        JsonObject params = event.getObject("params");
        String path = s.uris.relative(params.getString("uri", ""));
        Sent sent = path == null ? null : s.sent.get(path);
        if (
            sent == null ||
            sent.snapshot.doc.revision.get() != sent.snapshot.revision ||
            (params.has("version") && params.getInt("version", -1) != sent.version)
        ) return;
        s.found.put(path, LspResults.diagnostics(params, s.uris, s.server));
        publish(s);
    }

    private void publish(State s) {
        app.main.post(() -> {
            if (!alive(s)) return;
            List<Diagnostic> visible = new ArrayList<>();
            for (Map.Entry<String, List<Diagnostic>> entry : s.found.entrySet()) {
                Sent sent = s.sent.get(entry.getKey());
                if (
                    sent != null &&
                    sent.snapshot.doc.revision.get() == sent.snapshot.revision &&
                    app.documents.contains(sent.snapshot.doc)
                ) visible.addAll(entry.getValue());
                if (visible.size() >= 1000) break;
            }
            diagnostics.accept(visible.subList(0, Math.min(1000, visible.size())));
        });
    }

    private void fail(State s, Throwable error) {
        app.main.post(() -> {
            if (!alive(s)) return;
            if (s.run != null && s.run.isCancelled()) {
                stopCurrent();
                status("LSP paused · native run / PTY");
                return;
            }
            failedWorkspace = s.workspace;
            failedServer = s.server;
            app.log(
                "Semantic " +
                    s.server +
                    ": " +
                    String.valueOf(error == null ? "disconnected" : error.getMessage())
            );
            stopCurrent();
            status("LSP failed · restart server");
        });
    }

    private CompletableFuture<?> stopCurrent() {
        State old = state;
        state = null;
        if (old != null) diagnostics.accept(List.of());
        if (old == null) return CompletableFuture.completedFuture(null);
        old.cancel.cancel();
        for (CompletableFuture<?> request : old.requests) request.cancel(true);
        old.requests.clear();
        old.ready.completeExceptionally(new IOException("Semantic session stopped"));
        if (old.client != null) try {
            old.client.close();
        } catch (IOException ignored) {}
        return old.run == null ? CompletableFuture.completedFuture(null) : old.run.result;
    }

    @Override
    public void close() {
        disposed = true;
        foreground = false;
        stopCurrent();
        worker.shutdownNow();
    }
}
