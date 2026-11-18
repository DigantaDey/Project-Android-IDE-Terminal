package com.forgeide.app.nativebridge;

import com.forgeide.app.ForgeApplication;
import com.forgeide.core.toolchain.RunConfiguration;
import com.forgeide.core.util.CancellationToken;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Retains one remote PTY and scrollback over Activity recreation, not over Android process death. */
public final class NativeTerminalState {

    final ForgeApplication app;
    public final TerminalSession terminal;
    public final NativeRuntimeClient.Run run;
    volatile TerminalView view;
    boolean control;
    boolean ready;

    public NativeTerminalState(ForgeApplication app) {
        this.app = app;
        TerminalCallbacks callback = new TerminalCallbacks() {
            @Override
            public void onTextChanged(TerminalSession session) {
                TerminalView current = view;
                if (current != null) current.onScreenUpdated();
            }
        };
        terminal = new TerminalSession(
            new TerminalSession.Transport() {
                @Override
                public void write(byte[] bytes) {
                    if (ready) app.nativeRuntime.write(run, bytes);
                }

                @Override
                public void resize(int rows, int columns) {
                    if (ready) app.nativeRuntime.resize(run, rows, columns);
                }

                @Override
                public void stop() {
                    app.nativeRuntime.stop(run);
                }
            },
            callback
        );
        terminal.feed(
            ("Forge Native Bash · separate companion UID\r\nPreparing a disposable project copy. Type exit to discard it.\r\nExport changes before exit; no automatic writes to the IDE.\r\nNetwork " +
                (app.settings.bool("native.network") ? "ON" : "OFF") +
                " · npm install scripts " +
                (app.settings.bool("native.installScripts") ? "ON" : "OFF") +
                "\r\n").getBytes(StandardCharsets.UTF_8)
        );
        run = app.nativeRuntime.start(
            app.workspace,
            "bash",
            "shellscript",
            new RunConfiguration(
                "Native Bash",
                "bash",
                "",
                "",
                List.of(),
                Map.of(),
                "",
                List.of(),
                List.of(),
                List.of()
            ),
            60000,
            1024 * 1024,
            true,
            new CancellationToken(),
            bytes -> app.main.post(() -> terminal.feed(bytes)),
            info ->
                app.main.post(() -> {
                    ready = true;
                    TerminalView current = view;
                    if (current != null) {
                        current.updateSize();
                        current.onScreenUpdated();
                    }
                })
        );
        run.result.whenComplete((exit, error) ->
            app.main.post(() -> {
                ready = false;
                terminal.finished(
                    exit == null ? 1 : exit,
                    error == null ? "" : String.valueOf(error.getMessage())
                );
            })
        );
    }
}
