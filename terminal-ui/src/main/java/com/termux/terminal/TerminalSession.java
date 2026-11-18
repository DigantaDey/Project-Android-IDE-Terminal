package com.termux.terminal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Forge's remote-only adapter for the upstream terminal view. Never forks or execs in the IDE UID. */
public final class TerminalSession extends TerminalOutput {

    public interface Transport {
        void write(byte[] bytes);
        void resize(int rows, int columns);
        void stop();
    }

    public final String mHandle = UUID.randomUUID().toString();
    public String mSessionName = "Native Bash";
    private final Transport transport;
    private TerminalSessionClient client;
    private final TerminalEmulator emulator;
    private boolean running = true;
    private int exitStatus;

    public TerminalSession(Transport transport, TerminalSessionClient client) {
        this.transport = transport;
        this.client = client;
        emulator = new TerminalEmulator(this, 80, 24, 8, 16, 5000, client);
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        this.client = client;
        emulator.updateTerminalSessionClient(client);
    }

    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        emulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        if (running) transport.resize(rows, columns);
    }

    public void feed(byte[] bytes) {
        emulator.append(bytes, bytes.length);
        client.onTextChanged(this);
    }

    public void finished(int status, String message) {
        if (!running) return;
        running = false;
        exitStatus = status;
        feed(
            ("\r\n[exit " + status + (message.isEmpty() ? "" : ": " + message) + "]\r\n").getBytes(
                StandardCharsets.UTF_8
            )
        );
        client.onSessionFinished(this);
    }

    public String getTitle() {
        return emulator.getTitle();
    }

    public TerminalEmulator getEmulator() {
        return emulator;
    }

    @Override
    public void write(byte[] data, int offset, int count) {
        if (running && count > 0) for (int at = offset; at < offset + count; at += 8192) transport.write(
            Arrays.copyOfRange(data, at, Math.min(offset + count, at + 8192))
        );
    }

    public void writeCodePoint(boolean escape, int codePoint) {
        if (escape) write("\u001b");
        write(new String(Character.toChars(codePoint)));
    }

    public void reset() {
        emulator.reset();
        client.onTextChanged(this);
    }

    public void finishIfRunning() {
        if (running) transport.stop();
    }

    public boolean isRunning() {
        return running;
    }

    public int getExitStatus() {
        return exitStatus;
    }

    public int getPid() {
        return 0;
    }

    public String getCwd() {
        return null;
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        client.onTitleChanged(this);
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        /* OSC 52 is not user clipboard consent. */
    }

    @Override
    public void onPasteTextFromClipboard() {
        /* Only the explicit Paste control may read clipboard. */
    }

    @Override
    public void onBell() {
        client.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        client.onColorsChanged(this);
    }
}
