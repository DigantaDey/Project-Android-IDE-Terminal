package com.forgeide.app.execution;

import android.content.*;
import android.os.*;
import com.forgeide.core.debug.Breakpoints;
import com.forgeide.core.toolchain.RunConfiguration;
import com.forgeide.core.sandbox.SandboxPolicy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A run owns its binding, pipe and deadline. Old death callbacks cannot terminate a newer run. */
public final class ExecutionClient {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ActiveRun active;

    private static final class ActiveRun {
        final CompletableFuture<Integer> result = new CompletableFuture<>();
        final AtomicBoolean done = new AtomicBoolean();
        volatile boolean cancelled;
        boolean bound;
        ServiceConnection connection;
        IExecutionService service;
        ParcelFileDescriptor writer;
        Runnable watchdog;
    }

    public ExecutionClient(Context context) { this.context = context.getApplicationContext(); }
    public synchronized boolean running() { return active != null; }

    public void control(String action, String expression) {
        ActiveRun run;
        synchronized (this) { run = active; }
        if (run == null || run.service == null) return;
        if (!List.of("continue", "pause", "over", "into", "out", "evaluate").contains(action)) return;
        if (expression != null && expression.length() > 2048) throw new IllegalArgumentException("Watch expression too long");
        try { run.service.control(action, expression); } catch (RemoteException ignored) { }
    }

    public void cancel() {
        ActiveRun run;
        synchronized (this) { run = active; }
        if (run == null) return;
        run.cancelled = true;
        try { if (run.service != null) run.service.cancel(); } catch (RemoteException ignored) { }
        finish(run, 130, null);
    }

    private void finish(ActiveRun run, int code, Throwable error) {
        main.post(() -> {
            if (!run.done.compareAndSet(false, true)) return;
            if (run.watchdog != null) main.removeCallbacks(run.watchdog);
            if (run.writer != null) try { run.writer.close(); } catch (IOException ignored) { }
            if (run.bound) {
                try { context.unbindService(run.connection); } catch (IllegalArgumentException ignored) { }
                run.bound = false;
            }
            run.service = null;
            synchronized (ExecutionClient.this) { if (active == run) active = null; }
            if (error == null) run.result.complete(run.cancelled ? 130 : code);
            else run.result.completeExceptionally(error);
        });
    }

    public synchronized CompletableFuture<Integer> run(String source, RunConfiguration config,
            SandboxPolicy policy, List<Breakpoints.Point> points, boolean debug,
            Consumer<String> output, Consumer<String> errors, Consumer<String> events) {
        if (active != null) throw new IllegalStateException("Stop the active execution first");
        policy.requireSupported(SandboxPolicy.safeDefault().required);
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 512 * 1024) throw new IllegalArgumentException("Execution source is limited to 512 KiB");
        Bundle b = new Bundle();
        b.putLong("timeout", policy.timeoutMillis); b.putInt("output", policy.outputBytes);
        b.putString("language", config.toolchain); b.putString("file", config.file); b.putString("stdin", config.stdin);
        b.putStringArray("arguments", config.arguments.toArray(new String[0])); b.putBoolean("debug", debug);
        Bundle env = new Bundle(); config.environment.forEach(env::putString); b.putBundle("environment", env);
        List<Breakpoints.Point> selected = new ArrayList<>();
        for (Breakpoints.Point p : points) if (p.file.equals(config.file)) selected.add(p);
        if (selected.size() > 1000) throw new IllegalArgumentException("Maximum 1000 breakpoints per script");
        int[] lines = new int[selected.size()]; String[] conditions = new String[selected.size()];
        for (int i = 0; i < lines.length; i++) { lines[i] = selected.get(i).line + 1; conditions[i] = selected.get(i).condition; }
        b.putIntArray("breakpoints", lines); b.putStringArray("conditions", conditions);
        Parcel size = Parcel.obtain();
        try { b.writeToParcel(size, 0); if (size.dataSize() > 128 * 1024) throw new IllegalArgumentException("Run configuration exceeds 128 KiB IPC limit"); }
        finally { size.recycle(); }
        ActiveRun run = new ActiveRun(); active = run;
        run.connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (run.done.get() || run.cancelled) { finish(run, 130, null); return; }
                run.service = IExecutionService.Stub.asInterface(binder);
                try {
                    binder.linkToDeath(() -> {
                        if (!run.done.get() && !run.cancelled) errors.accept("Sandbox terminated by watchdog, resource pressure, or Android.\n");
                        finish(run, 137, null);
                    }, 0);
                    ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe(); run.writer = pipe[1];
                    try {
                        run.service.run(pipe[0], b, new IExecutionCallback.Stub() {
                            @Override public void onOutput(String value) { if (!run.done.get()) output.accept(value); }
                            @Override public void onEvent(String json) { if (!run.done.get()) events.accept(json); }
                            @Override public void onFinished(int exit, String error, int line, int column) {
                                if (!error.isEmpty()) errors.accept(config.file + ":" + Math.max(1, line) + ":" + Math.max(1, column) + ": error: " + error + "\n");
                                finish(run, exit, null);
                            }
                        });
                    } finally { pipe[0].close(); }
                    CompletableFuture.runAsync(() -> {
                        try (OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { stream.write(bytes); }
                        catch (IOException error) { if (!run.cancelled) finish(run, 1, error); }
                    });
                } catch (Exception error) { finish(run, 1, error); }
            }
            @Override public void onServiceDisconnected(ComponentName name) { finish(run, 137, null); }
            @Override public void onNullBinding(ComponentName name) { finish(run, 1, new IOException("Runtime returned no binder")); }
            @Override public void onBindingDied(ComponentName name) { finish(run, 137, null); }
        };
        main.post(() -> {
            if (run.cancelled) { finish(run, 130, null); return; }
            try {
                run.bound = context.bindService(new Intent(context, ExecutionService.class), run.connection, Context.BIND_AUTO_CREATE);
                if (!run.bound) finish(run, 1, new IOException("Could not bind isolated runtime"));
            } catch (RuntimeException error) { finish(run, 1, error); }
        });
        run.watchdog = () -> {
            if (!run.done.get()) {
                errors.accept("Host execution deadline reached; sandbox stopped.\n");
                try { if (run.service != null) run.service.cancel(); } catch (RemoteException ignored) { }
                finish(run, 124, null);
            }
        };
        main.postDelayed(run.watchdog, policy.timeoutMillis + 2500);
        return run.result;
    }
}
