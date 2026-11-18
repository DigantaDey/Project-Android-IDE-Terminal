package com.forgeide.core.terminal;

import com.forgeide.core.project.*;
import com.forgeide.core.util.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * A real project-scoped virtual shell. Never invokes /system/bin/sh or ProcessBuilder.
 * File commands run against Workspace; runtime/git commands go to explicitly registered providers.
 * It is not a PTY. History/output may be persisted; a killed Android process is not restorable.
 */
public final class TerminalSession {

    public interface External {
        Result execute(
            List<String> argv,
            String cwd,
            Map<String, String> environment,
            CancellationToken cancel
        ) throws Exception;
    }

    public static final class Result {

        public final String stdout, stderr;
        public final int exitCode;

        public Result(String stdout, String stderr, int exitCode) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }

        public static Result ok(String text) {
            return new Result(text, "", 0);
        }
    }

    public final String id;
    private final Workspace workspace;
    private String cwd = "";
    private final Map<String, String> env = new LinkedHashMap<>();
    private final List<String> history = new ArrayList<>();
    private final StringBuilder output = new StringBuilder();
    private final Map<String, External> providers = new HashMap<>();

    public TerminalSession(String id, Workspace workspace) {
        this.id = id;
        this.workspace = workspace;
        env.put("HOME", "/project");
        env.put("TERM", "forge-virtual");
        env.put("PWD", "/project");
    }

    public synchronized void register(String command, External provider) {
        providers.put(command, provider);
    }

    public synchronized String cwd() {
        return cwd;
    }

    public synchronized List<String> history() {
        return new ArrayList<>(history);
    }

    public synchronized String output() {
        return output.toString();
    }

    public synchronized void append(String text) {
        output.append(text);
        if (output.length() > 262144) output.delete(0, output.length() - 262144);
    }

    public synchronized void clear() {
        output.setLength(0);
    }

    private String path(String value) throws IOException {
        if (value.startsWith("/project/")) value = value.substring(9);
        else if (value.equals("/project") || value.equals("~")) value = "";
        else if (value.startsWith("/")) throw new IOException("Host absolute paths are not accessible");
        else if (!cwd.isEmpty()) value = cwd + "/" + value;
        return workspace.relative(workspace.resolve(value));
    }

    private static void args(List<String> argv, int n) {
        if (argv.size() < n + 1) throw new IllegalArgumentException("Missing argument; type help");
    }

    private static String bounded(String s) {
        return s.length() > 262144 ? s.substring(0, 262144) + "\n[output truncated]\n" : s;
    }

    public static String redactCommand(String command) {
        return command.matches("(?is).*(token|password|secret|authorization)\\s*=.*")
            ? "[credential assignment redacted]"
            : command;
    }

    public synchronized Result execute(String command, CancellationToken cancel) {
        if (command.length() > 16384) return new Result("", "Command exceeds 16 KiB\n", 2);
        // History is project-local. Never keep commands that appear to contain a credential assignment.
        if (!command.matches("(?is).*(token|password|secret|authorization)\\s*=.*")) {
            history.add(command);
            if (history.size() > 500) history.remove(0);
        }
        try {
            cancel.throwIfCancelled();
            List<String> a = ShellWords.parse(command, env);
            if (a.isEmpty()) return Result.ok("");
            String cmd = a.get(0);
            String value = a.size() > 1 ? String.join(" ", a.subList(1, a.size())) : "";
            switch (cmd) {
                case "help":
                    return Result.ok(
                        "Forge project shell • no host privileges\nhelp pwd cd ls cat echo touch mkdir cp mv rm find head tail grep wc env export unset clear history\nrun / build / git are provided by registered isolated or trusted IDE services.\nNo arbitrary native executables, PTY, pipelines or background OS processes.\n"
                    );
                case "pwd":
                    return Result.ok("/project" + (cwd.isEmpty() ? "" : "/" + cwd) + "\n");
                case "cd": {
                    String target = a.size() == 1 ? "" : path(a.get(1));
                    if (!workspace.isDirectory(target)) throw new IOException("Not a directory");
                    cwd = target;
                    env.put("PWD", "/project/" + cwd);
                    return Result.ok("");
                }
                case "ls": {
                    StringBuilder b = new StringBuilder();
                    for (String p : workspace.list(a.size() == 1 ? cwd : path(a.get(1)))) {
                        cancel.throwIfCancelled();
                        b
                            .append(p.substring(p.lastIndexOf('/') + 1))
                            .append(workspace.isDirectory(p) ? "/" : "")
                            .append('\n');
                    }
                    return Result.ok(b.toString());
                }
                case "echo":
                    return Result.ok(value + "\n");
                case "cat":
                    args(a, 1);
                    return Result.ok(bounded(workspace.read(path(a.get(1)))));
                case "touch":
                    args(a, 1);
                    {
                        String p = path(a.get(1));
                        if (!Files.exists(workspace.resolve(p))) workspace.create(p);
                        return Result.ok("");
                    }
                case "mkdir":
                    args(a, 1);
                    workspace.mkdir(path(a.get(a.size() - 1)));
                    return Result.ok("");
                case "cp":
                    args(a, 2);
                    workspace.duplicate(path(a.get(1)), path(a.get(2)));
                    return Result.ok("");
                case "mv":
                    args(a, 2);
                    workspace.move(path(a.get(1)), path(a.get(2)));
                    return Result.ok("");
                case "rm":
                    args(a, 1);
                    {
                        String p = path(a.get(1));
                        if (workspace.isDirectory(p)) throw new IOException(
                            "Use the explorer's recoverable delete for directories"
                        );
                        workspace.delete(p);
                        return Result.ok("");
                    }
                case "find":
                    return Result.ok(String.join("\n", workspace.files(10000)) + "\n");
                case "head":
                case "tail":
                    args(a, 1);
                    {
                        String[] lines = workspace.read(path(a.get(1))).split("\n", -1);
                        int from = cmd.equals("head") ? 0 : Math.max(0, lines.length - 10),
                            to = cmd.equals("head") ? Math.min(10, lines.length) : lines.length;
                        return Result.ok(String.join("\n", Arrays.copyOfRange(lines, from, to)) + "\n");
                    }
                case "grep":
                    args(a, 2);
                    {
                        StringBuilder b = new StringBuilder();
                        for (String line : workspace.read(path(a.get(2))).split("\n")) {
                            cancel.throwIfCancelled();
                            if (line.contains(a.get(1))) {
                                b.append(line).append('\n');
                                if (b.length() > 262144) break;
                            }
                        }
                        return Result.ok(bounded(b.toString()));
                    }
                case "wc":
                    args(a, 1);
                    {
                        String text = workspace.read(path(a.get(1)));
                        return Result.ok(
                            text.split("\n", -1).length +
                                " lines · " +
                                (text.isBlank() ? 0 : text.trim().split("\\s+").length) +
                                " words · " +
                                text.length() +
                                " characters\n"
                        );
                    }
                case "env": {
                    StringBuilder b = new StringBuilder();
                    env.forEach((k, v) -> b.append(k).append('=').append(v).append('\n'));
                    return Result.ok(b.toString());
                }
                case "export":
                    args(a, 1);
                    {
                        String assignment = a.get(1);
                        int eq = assignment.indexOf('=');
                        if (eq < 1) throw new IllegalArgumentException("Use export NAME=value");
                        String key = assignment.substring(0, eq);
                        if (!key.matches("[A-Za-z_][A-Za-z_0-9]*")) throw new IllegalArgumentException(
                            "Invalid variable name"
                        );
                        if (env.size() > 100) throw new IllegalArgumentException(
                            "Environment variable limit"
                        );
                        env.put(key, assignment.substring(eq + 1));
                        return Result.ok("");
                    }
                case "unset":
                    env.remove(value);
                    return Result.ok("");
                case "clear":
                    clear();
                    return Result.ok("");
                case "history":
                    return Result.ok(String.join("\n", history) + "\n");
                default: {
                    External provider = providers.get(cmd);
                    if (provider == null) return new Result(
                        "",
                        cmd + ": not installed. This shell never falls back to the Android host.\n",
                        127
                    );
                    return provider.execute(a, cwd, new LinkedHashMap<>(env), cancel);
                }
            }
        } catch (CancellationToken.CancellationException e) {
            return new Result("", "Cancelled\n", 130);
        } catch (Exception e) {
            return new Result("", bounded(String.valueOf(e.getMessage())) + "\n", 1);
        }
    }

    public synchronized String serialize() {
        return Json.write(Map.of("id", id, "cwd", cwd, "history", history, "output", output.toString()));
    }

    public synchronized void restore(String json) throws IOException {
        JsonObject j = JsonObject.parse(json);
        String dir = j.getString("cwd", "");
        if (workspace.isDirectory(dir)) cwd = dir;
        history.clear();
        List<String> h = j.getStringList("history");
        history.addAll(h.subList(Math.max(0, h.size() - 500), h.size()));
        clear();
        append(j.getString("output", ""));
        env.put("PWD", "/project/" + cwd);
    }
}
