package com.forgeide.core.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe listener list with safe iteration (listeners may remove
 * themselves while an event is being delivered).
 */
public final class Listeners<T> {
    private final List<T> listeners = new CopyOnWriteArrayList<>();

    public void add(T listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void remove(T listener) {
        listeners.remove(listener);
    }

    public void clear() {
        listeners.clear();
    }

    public int size() {
        return listeners.size();
    }

    /** Iterates a stable snapshot. */
    public void forEach(Dispatch<T> dispatch) {
        for (T listener : listeners) {
            dispatch.dispatch(listener);
        }
    }

    public interface Dispatch<T> {
        void dispatch(T listener);
    }
}
