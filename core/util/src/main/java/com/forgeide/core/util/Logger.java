package com.forgeide.core.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tiny logging facade. Core modules never depend on Android's Log; the app
 * installs a sink that forwards to Logcat and a ring buffer visible in the
 * in-app Logs panel. Tests install an in-memory sink.
 */
public final class Logger {

    public enum Level {
        VERBOSE,
        DEBUG,
        INFO,
        WARN,
        ERROR,
    }

    public interface Sink {
        void log(Level level, String tag, String message, Throwable error);
    }

    public static final class Entry {

        public final long timestamp;
        public final Level level;
        public final String tag;
        public final String message;

        Entry(long timestamp, Level level, String tag, String message) {
            this.timestamp = timestamp;
            this.level = level;
            this.tag = tag;
            this.message = message;
        }

        public String format() {
            String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(timestamp));
            return time + " " + level.name().charAt(0) + "/" + tag + ": " + message;
        }
    }

    /** Ring-buffer sink that retains the most recent entries (default 2000). */
    public static final class RingBufferSink implements Sink {

        private final int capacity;
        private final List<Entry> entries = new CopyOnWriteArrayList<>();
        private volatile Level minLevel = Level.DEBUG;

        public RingBufferSink(int capacity) {
            this.capacity = Math.max(16, capacity);
        }

        public void setMinLevel(Level minLevel) {
            this.minLevel = minLevel;
        }

        @Override
        public void log(Level level, String tag, String message, Throwable error) {
            if (level.ordinal() < minLevel.ordinal()) {
                return;
            }
            if (entries.size() >= capacity) {
                entries.remove(0);
            }
            String full = error == null ? message : message + "\n" + error;
            entries.add(new Entry(System.currentTimeMillis(), level, tag, full));
        }

        public List<Entry> entries() {
            return entries;
        }

        public void clear() {
            entries.clear();
        }
    }

    private static volatile Sink sink = new RingBufferSink(2000);

    private Logger() {}

    public static void setSink(Sink newSink) {
        sink = newSink == null ? new RingBufferSink(2000) : newSink;
    }

    public static Sink getSink() {
        return sink;
    }

    public static void v(String tag, String message) {
        log(Level.VERBOSE, tag, message, null);
    }

    public static void d(String tag, String message) {
        log(Level.DEBUG, tag, message, null);
    }

    public static void i(String tag, String message) {
        log(Level.INFO, tag, message, null);
    }

    public static void w(String tag, String message) {
        log(Level.WARN, tag, message, null);
    }

    public static void e(String tag, String message) {
        log(Level.ERROR, tag, message, null);
    }

    public static void e(String tag, String message, Throwable error) {
        log(Level.ERROR, tag, message, error);
    }

    public static void log(Level level, String tag, String message, Throwable error) {
        try {
            sink.log(level, tag, message, error);
        } catch (Throwable ignored) {
            // Logging must never crash the app.
        }
    }
}
