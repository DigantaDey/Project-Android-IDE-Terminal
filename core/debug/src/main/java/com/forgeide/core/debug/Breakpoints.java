package com.forgeide.core.debug;

import com.forgeide.core.util.*;
import java.util.*;

/** Persistent zero-based breakpoints with optional conditions; adapter verification is separate. */
public final class Breakpoints {

    public static final class Point {

        public final String file, condition;
        public final int line;

        public Point(String file, int line, String condition) {
            if (line < 0) throw new IllegalArgumentException();
            this.file = file;
            this.line = line;
            this.condition = condition == null ? "" : condition;
        }
    }

    private final List<Point> points = new ArrayList<>();

    public synchronized boolean toggle(String file, int line) {
        for (Point p : new ArrayList<>(points))
            if (p.file.equals(file) && p.line == line) {
                points.remove(p);
                return false;
            }
        points.add(new Point(file, line, ""));
        return true;
    }

    public synchronized void condition(String file, int line, String value) {
        points.removeIf(p -> p.file.equals(file) && p.line == line);
        points.add(new Point(file, line, value));
    }

    public synchronized List<Point> all() {
        return new ArrayList<>(points);
    }

    public synchronized String serialize() {
        List<Object> out = new ArrayList<>();
        for (Point p : points) out.add(Map.of("file", p.file, "line", p.line, "condition", p.condition));
        return Json.write(out);
    }

    public synchronized void restore(String json) {
        List<Point> next = new ArrayList<>();
        for (Object o : Json.parseArray(json)) {
            if (!(o instanceof Map)) continue;
            JsonObject j = new JsonObject((Map<String, Object>) o);
            next.add(new Point(j.getString("file", ""), j.getInt("line", 0), j.getString("condition", "")));
        }
        points.clear();
        points.addAll(next);
    }
}
