package com.forgeide.core.toolchain;

import com.forgeide.core.util.*;
import java.util.*;

/** Per-project launch data. Commands are argv arrays, never concatenated into a host shell. */
public final class RunConfiguration {

    public final String name, toolchain, file, workingDirectory, stdin;
    public final List<String> arguments, buildCommand, runCommand, debugCommand;
    public final Map<String, String> environment;

    public RunConfiguration(
        String name,
        String toolchain,
        String file,
        String workingDirectory,
        List<String> arguments,
        Map<String, String> environment,
        String stdin,
        List<String> build,
        List<String> run,
        List<String> debug
    ) {
        this.name = name;
        this.toolchain = toolchain;
        this.file = file;
        this.workingDirectory = workingDirectory;
        this.arguments = List.copyOf(arguments);
        this.environment = Map.copyOf(environment);
        this.stdin = stdin;
        buildCommand = List.copyOf(build);
        runCommand = List.copyOf(run);
        debugCommand = List.copyOf(debug);
    }

    public static RunConfiguration parse(String json) {
        JsonObject j = JsonObject.parse(json);
        Map<String, String> env = new LinkedHashMap<>();
        j
            .getObject("environment")
            .raw()
            .forEach((k, v) -> env.put(k, String.valueOf(v)));
        return new RunConfiguration(
            j.getString("name", "Current file"),
            j.getString("toolchain", "javascript"),
            j.getString("file", "main.js"),
            j.getString("workingDirectory", ""),
            j.getStringList("arguments"),
            env,
            j.getString("stdin", ""),
            j.getStringList("buildCommand"),
            j.getStringList("runCommand"),
            j.getStringList("debugCommand")
        );
    }

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("toolchain", toolchain);
        m.put("file", file);
        m.put("workingDirectory", workingDirectory);
        m.put("arguments", arguments);
        m.put("environment", environment);
        m.put("stdin", stdin);
        m.put("buildCommand", buildCommand);
        m.put("runCommand", runCommand);
        m.put("debugCommand", debugCommand);
        return Json.writePretty(m, 2);
    }
}
