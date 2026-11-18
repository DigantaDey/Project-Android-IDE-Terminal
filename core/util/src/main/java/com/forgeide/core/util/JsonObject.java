package com.forgeide.core.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Typed, forgiving accessor over a parsed JSON object. Used for settings files,
 * run configurations, DAP messages and GitHub API responses so callers never
 * have to sprinkle casts and null checks everywhere.
 */
public final class JsonObject {

    private final Map<String, Object> map;

    public JsonObject(Map<String, Object> map) {
        this.map = map == null ? Collections.emptyMap() : map;
    }

    public static JsonObject parse(String text) {
        return new JsonObject(Json.parseObject(text));
    }

    public static JsonObject empty() {
        return new JsonObject(Collections.emptyMap());
    }

    public Map<String, Object> raw() {
        return map;
    }

    public boolean has(String key) {
        return map.containsKey(key) && map.get(key) != null;
    }

    public String getString(String key, String fallback) {
        Object v = map.get(key);
        if (v == null) {
            return fallback;
        }
        return v instanceof String ? (String) v : String.valueOf(v);
    }

    public long getLong(String key, long fallback) {
        Object v = map.get(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong((String) v);
            } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    public int getInt(String key, int fallback) {
        return (int) getLong(key, fallback);
    }

    public double getDouble(String key, double fallback) {
        Object v = map.get(key);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble((String) v);
            } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    public boolean getBoolean(String key, boolean fallback) {
        Object v = map.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            return Boolean.parseBoolean((String) v);
        }
        return fallback;
    }

    public JsonObject getObject(String key) {
        Object v = map.get(key);
        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            return new JsonObject(m);
        }
        return empty();
    }

    public List<Object> getArray(String key) {
        Object v = map.get(key);
        if (v instanceof List) {
            return (List<Object>) v;
        }
        return Collections.emptyList();
    }

    public List<String> getStringList(String key) {
        List<String> out = new ArrayList<>();
        for (Object o : getArray(key)) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    public String toJson() {
        return Json.write(map);
    }
}
