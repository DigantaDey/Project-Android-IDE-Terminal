package com.forgeide.app.nativebridge;

import android.util.Log;
import android.view.*;
import com.termux.terminal.*;
import com.termux.view.TerminalViewClient;

/** Small defaults shared by the native terminal UI; clipboard access is explicit in its toolbar. */
class TerminalCallbacks implements TerminalSessionClient, TerminalViewClient {

    @Override
    public void onTextChanged(TerminalSession session) {}

    @Override
    public void onTitleChanged(TerminalSession session) {}

    @Override
    public void onSessionFinished(TerminalSession session) {}

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {}

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {}

    @Override
    public void onBell(TerminalSession session) {}

    @Override
    public void onColorsChanged(TerminalSession session) {
        onTextChanged(session);
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {}

    @Override
    public Integer getTerminalCursorStyle() {
        return TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
    }

    @Override
    public float onScale(float scale) {
        return 1;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {}

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return false;
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return true;
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    @Override
    public boolean isTerminalViewSelected() {
        return true;
    }

    @Override
    public void copyModeChanged(boolean copyMode) {}

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event, TerminalSession session) {
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }

    @Override
    public boolean readControlKey() {
        return false;
    }

    @Override
    public boolean readAltKey() {
        return false;
    }

    @Override
    public boolean readShiftKey() {
        return false;
    }

    @Override
    public boolean readFnKey() {
        return false;
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    @Override
    public void onEmulatorSet() {}

    @Override
    public void logError(String tag, String message) {
        Log.e(tag, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        Log.w(tag, message);
    }

    @Override
    public void logInfo(String tag, String message) {
        Log.i(tag, message);
    }

    @Override
    public void logDebug(String tag, String message) {
        Log.d(tag, message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        Log.v(tag, message);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Log.e(tag, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        Log.e(tag, "Terminal", e);
    }
}
