package com.forgeide.core.util;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation token passed to long-running operations
 * (search, indexing, builds). Cancellation is advisory: code checks
 * {@link #isCancelled()} or calls {@link #throwIfCancelled()}.
 */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Listeners<Runnable> listeners = new Listeners<>();

    public static final CancellationToken NONE = new CancellationToken(false);
    private final boolean cancellable;
    public CancellationToken() { this(true); }
    private CancellationToken(boolean cancellable) { this.cancellable = cancellable; }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        synchronized (this) { if (!cancellable || !cancelled.compareAndSet(false, true)) return; }
        listeners.forEach(r -> { try { r.run(); } catch (RuntimeException ignored) { /* still cancel remaining resources */ } });
        listeners.clear();
    }

    public void onCancel(Runnable runnable) {
        if (!cancellable) return;
        boolean fire;
        synchronized (this) { fire = cancelled.get(); if (!fire) listeners.add(runnable); }
        if (fire) runnable.run();
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException();
        }
    }

    /** Thrown by cooperative operations when cancelled. */
    public static final class CancellationException extends RuntimeException {
        public CancellationException() {
            super("Operation cancelled");
        }
    }
}
