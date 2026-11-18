package com.forgeide.core.util;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** Bounded asynchronous LSP / DAP transport. Owns streams, never the host process. */
public final class ProtocolClient implements AutoCloseable {

    private final InputStream input;
    private final OutputStream output;
    private final boolean dap;
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r ->
        daemon(r, "protocol-timeouts")
    );
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(128),
        r -> daemon(r, "protocol-writer")
    );
    private final Consumer<JsonObject> events;
    private final Function<JsonObject, Object> serverRequests;
    private volatile boolean closed;
    public final CompletableFuture<Void> disconnected = new CompletableFuture<>();

    private static Thread daemon(Runnable r, String name) {
        Thread thread = new Thread(r, name);
        thread.setDaemon(true);
        return thread;
    }

    public ProtocolClient(InputStream input, OutputStream output, boolean dap, Consumer<JsonObject> events) {
        this(input, output, dap, events, null);
    }

    public ProtocolClient(
        InputStream input,
        OutputStream output,
        boolean dap,
        Consumer<JsonObject> events,
        Function<JsonObject, Object> serverRequests
    ) {
        this.input = input;
        this.output = output;
        this.dap = dap;
        this.events = events;
        this.serverRequests = serverRequests;
        daemon(this::readLoop, dap ? "dap-reader" : "lsp-reader").start();
    }

    public CompletableFuture<JsonObject> request(String method, Map<String, ?> params) {
        return request(method, params, 30000);
    }

    public CompletableFuture<JsonObject> request(String method, Map<String, ?> params, long timeoutMillis) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        long id = sequence.incrementAndGet();
        synchronized (this) {
            if (closed || pending.size() >= 64) {
                future.completeExceptionally(
                    new IOException(closed ? "Protocol disconnected" : "Too many pending protocol requests")
                );
                return future;
            }
            pending.put(id, future);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (dap) {
            m.put("seq", id);
            m.put("type", "request");
            m.put("command", method);
            m.put("arguments", params);
        } else {
            m.put("jsonrpc", "2.0");
            m.put("id", id);
            m.put("method", method);
            m.put("params", params);
        }
        try {
            ScheduledFuture<?> timeout = deadlines.schedule(
                () -> {
                    CompletableFuture<JsonObject> p = pending.remove(id);
                    if (p != null) p.completeExceptionally(new TimeoutException(method + " timed out"));
                },
                Math.max(1, timeoutMillis),
                TimeUnit.MILLISECONDS
            );
            future.whenComplete((v, e) -> {
                pending.remove(id);
                timeout.cancel(false);
                if (!dap && (future.isCancelled() || e instanceof TimeoutException)) try {
                    notify("$/cancelRequest", Map.of("id", id));
                } catch (IOException ignored) {}
            });
            enqueue(m, future);
        } catch (IOException | RejectedExecutionException error) {
            pending.remove(id);
            future.completeExceptionally(error);
        }
        return future;
    }

    private void enqueue(Map<String, ?> message, CompletableFuture<?> request) throws IOException {
        if (closed) throw new IOException("Protocol disconnected");
        try {
            writer.execute(() -> {
                if (closed || (request != null && request.isDone())) return;
                try {
                    FramedJson.write(output, message);
                } catch (IOException error) {
                    terminate(error);
                }
            });
        } catch (RejectedExecutionException full) {
            // Never silently drop a didChange or reorder a request against it.
            IOException failure = new IOException("Protocol write queue is full or closed", full);
            terminate(failure);
            throw failure;
        }
    }

    public void notify(String method, Map<String, ?> params) throws IOException {
        if (dap) throw new IllegalStateException("DAP uses requests/events");
        enqueue(Map.of("jsonrpc", "2.0", "method", method, "params", params), null);
    }

    private void respond(JsonObject request) throws IOException {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("jsonrpc", "2.0");
        reply.put("id", request.raw().get("id"));
        try {
            if (serverRequests == null) throw new UnsupportedOperationException();
            reply.put("result", serverRequests.apply(request));
        } catch (UnsupportedOperationException unsupported) {
            reply.put("error", Map.of("code", -32601, "message", "Unsupported client method"));
        } catch (RuntimeException error) {
            reply.put("error", Map.of("code", -32603, "message", "Client request failed"));
        }
        enqueue(reply, null);
    }

    private void readLoop() {
        Throwable failure = new EOFException("Protocol peer closed");
        try {
            Map<String, Object> raw;
            while (!closed && (raw = FramedJson.read(input)) != null) {
                JsonObject m = new JsonObject(raw);
                boolean response = dap
                    ? m.getString("type", "").equals("response")
                    : m.has("id") && !m.has("method");
                if (response) {
                    CompletableFuture<JsonObject> f = pending.remove(
                        m.getLong(dap ? "request_seq" : "id", -1)
                    );
                    if (f != null) {
                        if (
                            m.has("error") || (dap && !m.getBoolean("success", false))
                        ) f.completeExceptionally(
                            new IOException(
                                dap
                                    ? m.getString("message", "Adapter request failed")
                                    : m.getObject("error").getString("message", "Server request failed")
                            )
                        );
                        else f.complete(m);
                    }
                } else if (!dap && m.has("id") && m.has("method")) respond(m);
                else if (events != null) events.accept(m);
            }
        } catch (Throwable error) {
            failure = error;
        } finally {
            terminate(failure);
        }
    }

    private void terminate(Throwable failure) {
        List<CompletableFuture<JsonObject>> outstanding;
        synchronized (this) {
            if (closed) return;
            closed = true;
            outstanding = new ArrayList<>(pending.values());
            pending.clear();
        }
        deadlines.shutdownNow();
        writer.shutdownNow();
        for (CompletableFuture<JsonObject> future : outstanding)
            future.completeExceptionally(failure == null ? new IOException("Protocol closed") : failure);
        try {
            input.close();
        } catch (IOException ignored) {}
        try {
            output.close();
        } catch (IOException ignored) {}
        if (failure == null) disconnected.complete(null);
        else disconnected.completeExceptionally(failure);
    }

    @Override
    public void close() throws IOException {
        terminate(null);
    }
}
