package com.forgeide.app.nativebridge;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.forgeide.app.ForgeApplication;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

/** Native ANSI terminal view. All shell execution remains in the separate runtime application. */
public final class NativeTerminalActivity extends Activity {

    public static final int TERMINAL_ID = 2001;
    private static final int EXPORT = 81;
    private ForgeApplication app;
    private NativeTerminalState state;
    private TerminalView terminal;
    private int font = 14;

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        app = (ForgeApplication) getApplication();
        if (app.settings.bool("requireHardLimits") || !app.nativeRuntime.available("bash")) {
            new AlertDialog.Builder(this)
                .setTitle("Native Bash unavailable")
                .setMessage(
                    app.settings.bool("requireHardLimits")
                        ? "Hard CPU/memory quotas are not supported by the native companion. Strict policy refuses execution."
                        : app.nativeRuntime.description("bash")
                )
                .setPositiveButton("Back", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
            return;
        }
        try {
            if (app.nativeTerminal == null || app.nativeTerminal.run.result.isDone()) app.nativeTerminal =
                new NativeTerminalState(app);
            state = app.nativeTerminal;
        } catch (RuntimeException error) {
            new AlertDialog.Builder(this)
                .setMessage(error.getMessage())
                .setPositiveButton("Back", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
            return;
        }
        getWindow().setStatusBarColor(Color.rgb(17, 19, 24));
        getWindow().setNavigationBarColor(Color.rgb(17, 19, 24));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(17, 19, 24));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom()
            );
            return insets;
        });
        LinearLayout bar = new LinearLayout(this);
        addButton(bar, "‹", "Close native terminal", this::onBackPressed);
        TextView title = new TextView(this);
        title.setText("Native Bash · project copy");
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(title, new LinearLayout.LayoutParams(0, 48, 1));
        addButton(bar, "⋮", "Native terminal actions", this::actions);
        root.addView(bar);
        terminal = new TerminalView(this, null);
        terminal.setId(TERMINAL_ID);
        terminal.setContentDescription("Native Bash PTY");
        terminal.setTerminalViewClient(
            new TerminalCallbacks() {
                @Override
                public boolean readControlKey() {
                    boolean value = state.control;
                    state.control = false;
                    return value;
                }

                @Override
                public void onSingleTapUp(MotionEvent e) {
                    terminal.requestFocus();
                    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(
                        terminal,
                        InputMethodManager.SHOW_IMPLICIT
                    );
                }

                @Override
                public float onScale(float scale) {
                    if (scale > 1.1f) font = Math.min(28, font + 1);
                    else if (scale < 0.9f) font = Math.max(9, font - 1);
                    terminal.setTextSize(Math.round(font * getResources().getDisplayMetrics().scaledDensity));
                    return 1;
                }
            }
        );
        terminal.setTextSize(Math.round(font * getResources().getDisplayMetrics().scaledDensity));
        terminal.setTypeface(Typeface.MONOSPACE);
        terminal.attachSession(state.terminal);
        state.view = terminal;
        root.addView(terminal, new LinearLayout.LayoutParams(-1, 0, 1));
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        LinearLayout keys = new LinearLayout(this);
        addButton(keys, "Esc", "Escape", () -> state.terminal.write("\u001b"));
        addButton(keys, "Tab", "Tab", () -> state.terminal.write("\t"));
        addButton(keys, "Ctrl", "Control modifier", () -> {
            state.control = !state.control;
            Toast.makeText(this, state.control ? "Ctrl: next key" : "Ctrl off", Toast.LENGTH_SHORT).show();
        });
        addButton(keys, "^C", "Interrupt foreground command", () -> state.terminal.write("\u0003"));
        addButton(keys, "↑", "History previous", () -> state.terminal.write("\u001b[A"));
        addButton(keys, "↓", "History next", () -> state.terminal.write("\u001b[B"));
        addButton(keys, "←", "Cursor left", () -> state.terminal.write("\u001b[D"));
        addButton(keys, "→", "Cursor right", () -> state.terminal.write("\u001b[C"));
        scroll.addView(keys);
        root.addView(scroll);
        setContentView(root);
        terminal.requestFocus();
    }

    private void addButton(LinearLayout parent, String text, String description, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setContentDescription(description);
        button.setOnClickListener(v -> action.run());
        parent.addView(button);
    }

    private void actions() {
        new AlertDialog.Builder(this)
            .setTitle("Native terminal")
            .setItems(
                new String[] {
                    "Paste clipboard",
                    "Copy transcript",
                    "Export project copy as ZIP…",
                    "Clear screen",
                    "Stop all native processes",
                },
                (d, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (which == 0 && clipboard.hasPrimaryClip()) {
                        CharSequence value = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
                        if (value.length() > 65536) {
                            Toast.makeText(this, "Paste limit is 64 KiB", Toast.LENGTH_LONG).show();
                            return;
                        }
                        // Bracketed paste keeps embedded newlines out of immediate shell execution where supported.
                        state.terminal.getEmulator().paste(value.toString());
                    } else if (which == 1) clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            "Native terminal",
                            state.terminal.getEmulator().getScreen().getTranscriptText()
                        )
                    );
                    else if (which == 2) startActivityForResult(
                        new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .setType("application/zip")
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .putExtra(Intent.EXTRA_TITLE, "native-project-copy.zip"),
                        EXPORT
                    );
                    else if (which == 3) state.terminal.reset();
                    else if (which == 4) app.nativeRuntime.stop(state.run);
                }
            )
            .show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (
            request == EXPORT &&
            result == RESULT_OK &&
            data != null &&
            data.getData() != null &&
            state != null
        ) {
            Uri uri = data.getData();
            app.nativeRuntime
                .exportProject(state.run, uri)
                .whenComplete((unused, error) ->
                    runOnUiThread(() ->
                        Toast.makeText(
                            this,
                            error == null
                                ? "Native project copy exported"
                                : "Export failed; destination may be incomplete: " + error.getMessage(),
                            Toast.LENGTH_LONG
                        ).show()
                    )
                );
        }
    }

    @Override
    public void onBackPressed() {
        if (state == null || state.run.result.isDone()) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Stop native Bash?")
            .setMessage(
                "This stops its native jobs and discards the project copy, including installed npm dependencies. Export any changes first; the IDE originals are unchanged."
            )
            .setNegativeButton("Keep terminal", null)
            .setNeutralButton("Export…", (d, w) ->
                startActivityForResult(
                    new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .setType("application/zip")
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_TITLE, "native-project-copy.zip"),
                    EXPORT
                )
            )
            .setPositiveButton("Stop & discard", (d, w) -> {
                app.nativeRuntime.stop(state.run);
                finish();
            })
            .show();
    }

    @Override
    protected void onDestroy() {
        if (state != null) {
            if (state.view == terminal) state.view = null;
            if (isFinishing()) {
                app.nativeRuntime.stop(state.run);
                if (app.nativeTerminal == state) app.nativeTerminal = null;
            }
        }
        super.onDestroy();
    }
}
