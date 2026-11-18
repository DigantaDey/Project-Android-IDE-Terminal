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

    public static final CancellationToken NONE = new CancellationToken();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            listeners.forEach(Runnable::run);
        }
    }

    public void onCancel(Runnable runnable) {
        if (cancelled.get()) {
            runnable.run();
        } else {
            listeners.add(runnable);
        }
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
