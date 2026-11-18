package com.forgeide.core.runtime;

import com.forgeide.core.util.*;
import java.util.*;
import java.util.function.Consumer;
import org.mozilla.javascript.*;
import org.mozilla.javascript.debug.*;

/** Real Rhino debugger: conditional breakpoints, stepping, activation scopes, stack and watch evaluation. */
public final class ScriptDebugger implements Debugger {

    private final Map<Integer, String> breakpoints;
    private final Consumer<String> events;
    private final Deque<Frame> stack = new ArrayDeque<>();
    private CancellationToken cancel;
    private long deadline;
    private boolean paused,
        pauseRequested = true,
        done,
        evaluating;
    private String command = "continue",
        watch = "";
    private int stepDepth = Integer.MAX_VALUE;
    private String step = "";

    public ScriptDebugger(Map<Integer, String> breakpoints, Consumer<String> events) {
        this.breakpoints = new HashMap<>(breakpoints);
        this.events = events;
    }

    public void attach(CancellationToken token, long deadline) {
        cancel = token;
        this.deadline = deadline;
        token.onCancel(() -> control("continue", ""));
    }

    public synchronized void control(String action, String expression) {
        if (action.equals("pause")) {
            pauseRequested = true;
            return;
        }
        command = action;
        watch = expression == null ? "" : expression;
        paused = false;
        notifyAll();
    }

    public synchronized void finished() {
        done = true;
        paused = false;
        notifyAll();
    }

    @Override
    public void handleCompilationDone(Context cx, DebuggableScript script, String source) {}

    @Override
    public DebugFrame getFrame(Context cx, DebuggableScript script) {
        return evaluating ? null : new Frame(script);
    }

    private static String value(Object o) {
        if (o == Scriptable.NOT_FOUND) return "<not defined>";
        if (o == Undefined.instance) return "undefined";
        if (o == null) return "null";
        if (o instanceof Scriptable) return "[object]";
        String s = o instanceof Number || o instanceof Boolean || o instanceof CharSequence
            ? Context.toString(o)
            : String.valueOf(o);
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private Object evaluate(Context cx, String expression, Frame frame) {
        evaluating = true;
        try {
            return cx.evaluateString(frame.scope, expression, "<watch>", 1, null);
        } finally {
            evaluating = false;
        }
    }

    private static String bounded(String text, int length) {
        return text == null ? "<script>" : text.substring(0, Math.min(text.length(), length));
    }

    private String snapshot(String reason) {
        boolean previous = evaluating;
        evaluating = true;
        try {
            return snapshotValues(reason);
        } finally {
            evaluating = previous;
        }
    }

    private String snapshotValues(String reason) {
        List<Object> frames = new ArrayList<>();
        int count = 0,
            budget = 12000;
        for (Frame f : stack) {
            if (++count > 32 || budget <= 0) break;
            Map<String, Object> variables = new LinkedHashMap<>();
            int n = 0;
            for (Object key : f.scope.getIds()) {
                if (++n > 32 || budget <= 0) break;
                Object v;
                try {
                    v = key instanceof Number
                        ? f.scope.get(((Number) key).intValue(), f.scope)
                        : f.scope.get(key.toString(), f.scope);
                } catch (RuntimeException unavailable) {
                    v = "<unavailable>";
                }
                String name = bounded(key.toString(), 80),
                    display = value(v);
                budget -= name.length() + display.length() + 24;
                variables.put(name, display);
            }
            frames.add(
                Map.of(
                    "name",
                    (f.script.getFunctionName() == null || f.script.getFunctionName().isEmpty())
                        ? "<main>"
                        : bounded(f.script.getFunctionName(), 80),
                    "file",
                    bounded(f.script.getSourceName(), 200),
                    "line",
                    f.line,
                    "variables",
                    variables
                )
            );
        }
        return Json.write(Map.of("event", "stopped", "reason", reason, "stack", frames));
    }

    private final class Frame implements DebugFrame {

        final DebuggableScript script;
        Scriptable scope;
        int line;

        Frame(DebuggableScript script) {
            this.script = script;
        }

        @Override
        public void onEnter(Context cx, Scriptable activation, Scriptable thisObject, Object[] args) {
            scope = activation;
            stack.push(this);
        }

        @Override
        public void onLineChange(Context cx, int line) {
            this.line = line;
            if (evaluating) return;
            boolean hit = breakpoints.containsKey(line);
            if (hit && !breakpoints.get(line).isBlank()) try {
                hit = Context.toBoolean(evaluate(cx, breakpoints.get(line), this));
            } catch (RuntimeException e) {
                events.accept(
                    Json.write(Map.of("event", "conditionError", "message", String.valueOf(e.getMessage())))
                );
                hit = false;
            }
            boolean stepHit =
                step.equals("into") ||
                (step.equals("over") && stack.size() <= stepDepth) ||
                (step.equals("out") && stack.size() < stepDepth);
            if (!hit && !pauseRequested && !stepHit) return;
            synchronized (ScriptDebugger.this) {
                pauseRequested = false;
                step = "";
                while (!done && !cancel.isCancelled()) {
                    paused = true;
                    events.accept(snapshot(hit ? "breakpoint" : "step"));
                    while (paused && !cancel.isCancelled() && System.nanoTime() < deadline) try {
                        ScriptDebugger.this.wait(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new JavaScriptRuntime.Stopped("Debugger interrupted");
                    }
                    if (System.nanoTime() >= deadline) throw new JavaScriptRuntime.Stopped(
                        "Debug session time limit exceeded"
                    );
                    if (command.equals("evaluate")) {
                        String result;
                        try {
                            result = value(evaluate(cx, watch, this));
                        } catch (RuntimeException e) {
                            result = String.valueOf(e.getMessage());
                        }
                        events.accept(
                            Json.write(Map.of("event", "evaluation", "expression", watch, "value", result))
                        );
                        continue;
                    }
                    stepDepth = stack.size();
                    if (List.of("into", "over", "out").contains(command)) step = command;
                    events.accept(Json.write(Map.of("event", "continued")));
                    break;
                }
            }
        }

        @Override
        public void onExceptionThrown(Context cx, Throwable ex) {}

        @Override
        public void onExit(Context cx, boolean byThrow, Object result) {
            stack.remove(this);
        }

        @Override
        public void onDebuggerStatement(Context cx) {
            pauseRequested = true;
            onLineChange(cx, line);
        }
    }
}
