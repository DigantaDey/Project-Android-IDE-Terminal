package com.forgeide.core.settings;

import com.forgeide.core.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Validated global settings with explicit per-project overrides. Credentials never belong here. */
public final class Settings {
    private final Map<String, Object> global = new LinkedHashMap<>(), project = new LinkedHashMap<>();
    public Settings() {
        global.put("theme", "system"); global.put("fontFamily", "monospace"); global.put("syntaxTheme", "warm"); global.put("fontSize", 14L); global.put("tabSize", 4L);
        global.put("useTabs", false); global.put("wordWrap", false); global.put("minimap", false);
        global.put("requireHardLimits", false);
        global.put("autoClose", true); global.put("autosave", true); global.put("autosaveSeconds", 15L);
        global.put("uiScale", 100L); global.put("executionSeconds", 10L); global.put("outputKiB", 256L);
    }
    public synchronized Object get(String key) { return project.containsKey(key) ? project.get(key) : global.get(key); }
    public int integer(String key) { return ((Number) get(key)).intValue(); }
    public boolean bool(String key) { return Boolean.TRUE.equals(get(key)); }
    public String string(String key) { return String.valueOf(get(key)); }
    public synchronized void set(String key, Object value, boolean perProject) {
        if (!global.containsKey(key)) throw new IllegalArgumentException("Unknown setting: " + key);
        Object def = global.get(key);
        if (def instanceof Number) {
            if (!(value instanceof Number)) throw new IllegalArgumentException("Expected number");
            double numeric = ((Number) value).doubleValue();
            if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)) throw new IllegalArgumentException("Expected a finite whole number");
            int n = ((Number) value).intValue(), min = 1, max = 600;
            switch (key) { case "fontSize": min = 9; max = 32; break; case "tabSize": max = 8; break; case "uiScale": min = 80; max = 150; break; case "executionSeconds": max = 60; break; case "outputKiB": min = 16; max = 1024; break; }
            if (n < min || n > max) throw new IllegalArgumentException(key + " must be " + min + "–" + max);
            value = (long)n;
        } else if (def instanceof Boolean && !(value instanceof Boolean)) throw new IllegalArgumentException("Expected boolean");
        else if (key.equals("theme") && !List.of("system", "dark", "light").contains(value)) throw new IllegalArgumentException("Unknown theme");
        if (key.equals("fontFamily") && !List.of("monospace", "sans-serif", "serif").contains(value)) throw new IllegalArgumentException("Unknown font family");
        if (key.equals("syntaxTheme") && !List.of("warm", "cool", "monochrome").contains(value)) throw new IllegalArgumentException("Unknown syntax theme");
        (perProject ? project : global).put(key, value);
    }
    public synchronized void load(Path file, boolean perProject) throws IOException {
        if (perProject) project.clear(); if (!Files.exists(file)) return;
        if (Files.size(file) > 65536) throw new IOException("Settings file too large");
        Map<String, Object> values;
        try { values = Json.parseObject(com.forgeide.core.util.AtomicFiles.readUtf8(file)); } catch (RuntimeException e) { throw new IOException("Settings are invalid; original file has not been overwritten", e); }
        for (Map.Entry<String, Object> e : values.entrySet()) if (global.containsKey(e.getKey())) {
            try { set(e.getKey(), e.getValue(), perProject); } catch (IllegalArgumentException ex) { throw new IOException("Invalid setting " + e.getKey(), ex); }
        }
    }
    public synchronized void save(Path file, boolean perProject) throws IOException { AtomicFiles.write(file, Json.writePretty(perProject ? project : global, 2)); }
    public synchronized Map<String, Object> snapshot() { Map<String, Object> m = new LinkedHashMap<>(global); m.putAll(project); return m; }
}
