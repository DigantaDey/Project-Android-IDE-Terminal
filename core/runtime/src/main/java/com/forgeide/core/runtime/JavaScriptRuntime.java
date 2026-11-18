package com.forgeide.core.runtime;

import com.forgeide.core.sandbox.SandboxPolicy;
import com.forgeide.core.util.CancellationToken;
import com.forgeide.core.util.OutputLimiter;
import java.util.*;
import java.util.function.Consumer;
import org.mozilla.javascript.*;

/**
 * Embedded interpreted JavaScript (Rhino ES6, not Node.js). Android MUST host this in an
 * isolatedProcess service. Safe scope + denied Java interop are defense-in-depth, not the OS boundary.
 */
public final class JavaScriptRuntime {

    public static final class Stopped extends Error {

        public Stopped(String reason) {
            super(reason);
        }
    }

    public static final class Result {

        public final int exitCode, line, column;
        public final String error;

        Result(int code, String error, int line, int column) {
            exitCode = code;
            this.error = error;
            this.line = line;
            this.column = column;
        }
    }

    public Result run(
        String source,
        String name,
        List<String> arguments,
        Map<String, String> environment,
        String stdin,
        SandboxPolicy policy,
        CancellationToken cancel,
        Consumer<String> output,
        ScriptDebugger debugger
    ) {
        return run(source, name, arguments, environment, stdin, policy, cancel, output, output, debugger);
    }

    public Result run(
        String source,
        String name,
        List<String> arguments,
        Map<String, String> environment,
        String stdin,
        SandboxPolicy policy,
        CancellationToken cancel,
        Consumer<String> output,
        Consumer<String> errors,
        ScriptDebugger debugger
    ) {
        if (source.length() > 512 * 1024) return new Result(
            2,
            "Script exceeds 512 KiB execution limit",
            0,
            0
        );
        long deadline = System.nanoTime() + policy.timeoutMillis * 1_000_000L;
        ContextFactory factory = new ContextFactory() {
            @Override
            protected Context makeContext() {
                Context cx = super.makeContext();
                cx.setOptimizationLevel(-1);
                cx.setLanguageVersion(Context.VERSION_ES6);
                cx.setInstructionObserverThreshold(1000);
                cx.setMaximumInterpreterStackDepth(256);
                cx.setClassShutter(className -> false);
                cx.getWrapFactory().setJavaPrimitiveWrap(false);
                return cx;
            }

            @Override
            protected void observeInstructionCount(Context cx, int count) {
                if (cancel.isCancelled() || Thread.currentThread().isInterrupted()) throw new Stopped(
                    "Execution cancelled"
                );
                if (System.nanoTime() > deadline) throw new Stopped("Execution time limit exceeded");
            }
        };
        OutputLimiter limiter = new OutputLimiter(policy.outputBytes, output);
        try {
            factory.call(cx -> {
                if (debugger != null) {
                    debugger.attach(cancel, deadline);
                    cx.setGeneratingDebug(true);
                    cx.setDebugger(debugger, null);
                }
                ScriptableObject scope = cx.initSafeStandardObjects(null, true);
                BaseFunction print = printer(limiter, output, policy.outputBytes);
                BaseFunction errorPrint = printer(limiter, errors, policy.outputBytes);
                ScriptableObject.putProperty(scope, "print", print);
                Scriptable console = cx.newObject(scope);
                for (String method : List.of("log", "info", "warn", "error", "debug"))
                    ScriptableObject.putProperty(
                        console,
                        method,
                        method.equals("warn") || method.equals("error") ? errorPrint : print
                    );
                ScriptableObject.putProperty(scope, "console", console);
                ScriptableObject.putProperty(
                    scope,
                    "arguments",
                    cx.newArray(scope, arguments.toArray(new Object[arguments.size()]))
                );
                Scriptable env = cx.newObject(scope);
                environment.forEach((key, value) -> ScriptableObject.putProperty(env, key, value));
                ScriptableObject.putProperty(scope, "env", env);
                String[] inputLines = stdin.isEmpty() ? new String[0] : stdin.split("\\r\\n|\\n|\\r", -1);
                if (inputLines.length > 0 && inputLines[inputLines.length - 1].isEmpty()) {
                    inputLines = Arrays.copyOf(inputLines, inputLines.length - 1);
                }
                final String[] lines = inputLines;
                int[] inputIndex = { 0 };
                ScriptableObject.putProperty(
                    scope,
                    "readLine",
                    new BaseFunction() {
                        @Override
                        public Object call(Context c, Scriptable s, Scriptable self, Object[] a) {
                            return inputIndex[0] < lines.length ? lines[inputIndex[0]++] : null;
                        }
                    }
                );
                cx.evaluateString(scope, source, name, 1, null);
                return null;
            });
            return new Result(0, "", 0, 0);
        } catch (Stopped e) {
            return new Result(cancel.isCancelled() ? 130 : 124, e.getMessage(), 0, 0);
        } catch (OutputLimiter.LimitExceeded e) {
            return new Result(124, e.getMessage(), 0, 0);
        } catch (RhinoException e) {
            return new Result(1, e.details(), e.lineNumber(), e.columnNumber());
        } catch (StackOverflowError e) {
            return new Result(1, "Interpreter stack limit exceeded", 0, 0);
        } finally {
            if (debugger != null) debugger.finished();
        }
    }

    private static BaseFunction printer(OutputLimiter limiter, Consumer<String> sink, int budget) {
        return new BaseFunction() {
            @Override
            public Object call(Context c, Scriptable scope, Scriptable self, Object[] args) {
                StringBuilder line = new StringBuilder();
                for (Object value : args) {
                    if (line.length() > 0) line.append(' ');
                    line.append(Context.toString(value));
                    if (line.length() > budget) break;
                }
                line.append('\n');
                limiter.acceptTo(line.toString(), sink);
                return Undefined.instance;
            }
        };
    }
}
