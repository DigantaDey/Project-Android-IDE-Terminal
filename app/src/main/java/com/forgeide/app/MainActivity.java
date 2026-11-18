package com.forgeide.app;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.forgeide.app.git.*;
import com.forgeide.core.commands.CommandRegistry;
import com.forgeide.core.debug.Breakpoints;
import com.forgeide.core.editor.*;
import com.forgeide.core.git.*;
import com.forgeide.core.language.*;
import com.forgeide.core.project.*;
import com.forgeide.core.recovery.RecoveryStore;
import com.forgeide.core.sandbox.*;
import com.forgeide.core.search.*;
import com.forgeide.core.terminal.*;
import com.forgeide.core.toolchain.*;
import com.forgeide.core.util.*;
import io.github.rosemoe.sora.event.*;
import io.github.rosemoe.sora.lang.diagnostic.*;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Native adaptive IDE shell. All long-running disk/network/analysis work uses background executors. */
public final class MainActivity extends Activity {

    public static final int EDITOR_ID = 1001,
        RUN_ID = 1002,
        SAVE_ID = 1003,
        PALETTE_ID = 1004,
        SECONDARY_EDITOR_ID = 1005;
    private static final int IMPORT_TREE = 31,
        IMPORT_ZIP = 32,
        EXPORT_ZIP = 33,
        OPEN_FILE = 34,
        EXPORT_FILE = 35;
    private ForgeApplication app;
    private final LanguageRegistry languages = LanguageRegistry.builtins();
    private final LocalLanguageService localLanguage = new LocalLanguageService();
    private final CommandRegistry commands = new CommandRegistry();
    private final Set<String> expanded = new HashSet<>();
    private final List<Diagnostic> problems = new ArrayList<>();
    private List<TerminalSession> terminals = new ArrayList<>();
    private TerminalSession visibleTerminal;
    private final ExecutorService analysis = Executors.newSingleThreadExecutor();
    private CancellationToken taskCancel = new CancellationToken();
    private LinearLayout root, sidebar, treeRows, tabs, editorRow, panelBody, panel, terminalRoot;
    private FrameLayout editorFrame;
    private HorizontalScrollView tabScroll;
    private TextView status, breadcrumb, projectTitle, outputText, terminalText, debugText, busy;
    private EditText terminalInput;
    private CodeEditor primary, secondary, focused;
    private MiniMapView minimap;
    private ForgeApplication.OpenDocument primaryDoc, secondaryDoc;
    private boolean wide,
        sidebarVisible,
        panelVisible = true,
        destroyed;
    private String panelName = "Terminal",
        lastTerminalProject = "";
    private int terminalIndex;
    private int bg, surface, raised, border, text, muted, accent;
    private long analyzedRevision = -1;
    private ForgeApplication.OpenDocument analyzedDocument;
    private final Runnable observer = () -> refreshState();
    private final Runnable diagnosticTask = () -> analyzeCurrent();
    private final Runnable autosave = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            if (app.settings.bool("autosave")) app
                .saveAll()
                .exceptionally(e -> {
                    app.log("Autosave: " + message(e));
                    return null;
                });
            app.main.postDelayed(this, app.settings.integer("autosaveSeconds") * 1000L);
        }
    };

    @Override
    public void onCreate(Bundle saved) {
        app = (ForgeApplication) getApplication();
        boolean darkTheme =
            app.settings.string("theme").equals("dark") ||
            (app.settings.string("theme").equals("system") &&
                (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES);
        setTheme(darkTheme ? R.style.Theme_Forge : R.style.Theme_Forge_Light);
        super.onCreate(saved);
        if (!app.fatalError.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("Storage unavailable")
                .setMessage(app.fatalError)
                .setPositiveButton("Close", (d, w) -> finish())
                .show();
            return;
        }
        if (saved != null) pendingExportPath = saved.getString("pendingExportPath", "");
        chooseColors();
        wide = getResources().getConfiguration().screenWidthDp >= 840;
        sidebarVisible = saved == null ? wide : saved.getBoolean("sidebar", wide);
        makeLayout();
        registerCommands();
        app.observers.add(observer);
        refreshTree();
        rebuildTabs();
        ForgeApplication.OpenDocument doc = app.find(
            saved == null ? app.activePath : saved.getString("primary", app.activePath)
        );
        if (doc != null) display(doc, false);
        if (saved != null) {
            ForgeApplication.OpenDocument right = app.find(saved.getString("secondary", ""));
            if (right != null) display(right, true);
            if (!saved.getBoolean("focusRight", false)) focused = primary;
        }
        restoreTerminals();
        terminalIndex = saved == null ? 0 : Math.min(saved.getInt("terminal", 0), terminals.size() - 1);
        showPanel(saved == null ? "Terminal" : saved.getString("panel", "Terminal"));
        if (saved != null) togglePanel(saved.getBoolean("panelVisible", true));
        refreshState();
        app.main.postDelayed(autosave, app.settings.integer("autosaveSeconds") * 1000L);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            this::back
        );
        if (saved == null) handleIncoming(getIntent());
    }

    private void chooseColors() {
        boolean dark =
            app.settings.string("theme").equals("dark") ||
            (app.settings.string("theme").equals("system") &&
                (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES);
        bg = Color.parseColor(dark ? "#14161C" : "#F9F8F5");
        surface = Color.parseColor(dark ? "#1A1D24" : "#F0EFEB");
        raised = Color.parseColor(dark ? "#232731" : "#E6E5E1");
        border = Color.parseColor(dark ? "#2B303A" : "#D5D6D2");
        text = Color.parseColor(dark ? "#DBDFE8" : "#252B33");
        muted = Color.parseColor(dark ? "#8C96A7" : "#606A78");
        accent = Color.parseColor(dark ? "#EEAD7F" : "#9F4E26");
    }

    private int dp(float value) {
        return Math.round(
            value *
                getResources().getDisplayMetrics().density *
                (app == null ? 1f : app.settings.integer("uiScale") / 100f)
        );
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView label(String value, int size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(12), 0, dp(12), 0);
        return v;
    }

    private TextView action(String value, String description, Runnable run) {
        TextView b = label(value, 18, muted);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(description);
        b.setTooltipText(description);
        b.setFocusable(true);
        b.setBackground(shape(Color.TRANSPARENT, 8));
        b.setOnClickListener(v -> run.run());
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setMinWidth(dp(44));
        b.setMinHeight(dp(44));
        return b;
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private void line(LinearLayout parent) {
        View v = new View(this);
        v.setBackgroundColor(border);
        parent.addView(v, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void makeLayout() {
        getWindow().setStatusBarColor(surface);
        getWindow().setNavigationBarColor(surface);
        androidx.core.view.WindowInsetsControllerCompat bars =
            new androidx.core.view.WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(Color.luminance(bg) > 0.4);
        bars.setAppearanceLightNavigationBars(Color.luminance(bg) > 0.4);
        root = column();
        root.setBackgroundColor(bg);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom()
            );
            return insets;
        });
        setContentView(root);
        LinearLayout top = row();
        top.setBackgroundColor(surface);
        top.setPadding(dp(6), 0, dp(6), 0);
        TextView brand = label("F", 25, accent);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(brand, new LinearLayout.LayoutParams(dp(44), dp(52)));
        brand.setContentDescription("Forge IDE");
        projectTitle = label(app.project + "  ⌄", 16, text);
        projectTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        projectTitle.setOnClickListener(v -> projectMenu());
        top.addView(projectTitle, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView palette = action("⌕", "Command palette", () -> palette());
        palette.setId(PALETTE_ID);
        top.addView(palette);
        TextView save = action("↓", "Save all · Ctrl+S", () -> saveAll());
        save.setId(SAVE_ID);
        top.addView(save);
        TextView run = action("▶", "Run current file · F5", () -> startRun(false, null, null));
        run.setId(RUN_ID);
        run.setTextColor(accent);
        top.addView(run);
        top.addView(action("⋮", "More commands", this::mainMenu));
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(52)));
        line(root);
        LinearLayout workspace = row();
        workspace.setGravity(Gravity.TOP);
        root.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout rail = column();
        rail.setBackgroundColor(surface);
        for (String[] item : new String[][] {
            { "▱", "Files" },
            { "⌕", "Search" },
            { "⑂", "Git" },
            { ">_", "Terminal" },
            { "⊙", "Debugger" },
        }) {
            TextView a = action(item[0], item[1], () -> {
                if (item[1].equals("Files")) toggleSidebar();
                else if (item[1].equals("Search")) projectSearch();
                else showPanel(item[1]);
            });
            rail.addView(a, new LinearLayout.LayoutParams(dp(48), dp(52)));
        }
        rail.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        rail.addView(action("⚙", "Settings", this::settingsDialog));
        workspace.addView(rail, new LinearLayout.LayoutParams(dp(48), -1));
        sidebar = column();
        sidebar.setBackgroundColor(surface);
        sidebar.setVisibility(sidebarVisible ? View.VISIBLE : View.GONE);
        LinearLayout explorerHeader = row();
        TextView title = label("EXPLORER", 11, muted);
        title.setLetterSpacing(0.12f);
        explorerHeader.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        explorerHeader.addView(action("+", "New file or folder", () -> newItem("")));
        sidebar.addView(explorerHeader);
        ScrollView treeScroll = new ScrollView(this);
        treeRows = column();
        treeScroll.addView(treeRows);
        sidebar.addView(treeScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        workspace.addView(sidebar, new LinearLayout.LayoutParams(dp(wide ? 225 : 180), -1));
        LinearLayout center = column();
        workspace.addView(center, new LinearLayout.LayoutParams(0, -1, 1));
        tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setBackgroundColor(surface);
        tabs = row();
        tabScroll.addView(tabs);
        center.addView(tabScroll, new LinearLayout.LayoutParams(-1, dp(42)));
        breadcrumb = label("", 11, muted);
        center.addView(breadcrumb, new LinearLayout.LayoutParams(-1, dp(28)));
        editorFrame = new FrameLayout(this);
        editorRow = row();
        primary = createEditor();
        primary.setId(EDITOR_ID);
        focused = primary;
        editorRow.addView(primary, new LinearLayout.LayoutParams(0, -1, 1));
        minimap = new MiniMapView(this);
        minimap.attach(primary);
        editorRow.addView(minimap, new LinearLayout.LayoutParams(dp(44), -1));
        minimap.setVisibility(app.settings.bool("minimap") ? View.VISIBLE : View.GONE);
        editorFrame.addView(editorRow);
        center.addView(editorFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        HorizontalScrollView symbols = new HorizontalScrollView(this);
        symbols.setHorizontalScrollBarEnabled(false);
        LinearLayout symbolRow = row();
        symbols.setBackgroundColor(surface);
        for (String value : new String[] {
            "Tab",
            "←",
            "→",
            "{",
            "}",
            "[",
            "]",
            "(",
            ")",
            "<",
            ">",
            ";",
            ":",
            "/",
            "\\",
            "|",
            "&",
            "*",
            "#",
            "$",
            "@",
            "_",
            "=",
            "+",
            "-",
        }) {
            TextView key = action(value, "Insert " + value, () -> symbol(value));
            key.setTextSize(15);
            key.setTextColor(text);
            symbolRow.addView(key, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        symbols.addView(symbolRow);
        center.addView(symbols, new LinearLayout.LayoutParams(-1, dp(44)));
        panel = column();
        panel.setBackgroundColor(surface);
        View handle = new View(this);
        handle.setBackgroundColor(border);
        handle.setContentDescription("Resize output panel");
        final float[] drag = { 0, 0 };
        handle.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                drag[0] = event.getRawY();
                drag[1] = panel.getHeight();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                int h = (int) Math.max(
                    dp(120),
                    Math.min(center.getHeight() * 0.75f, drag[1] + drag[0] - event.getRawY())
                );
                LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) panel.getLayoutParams();
                p.height = h;
                panel.setLayoutParams(p);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                v.performClick();
                return true;
            }
            return true;
        });
        panel.addView(handle, new LinearLayout.LayoutParams(-1, dp(8)));
        HorizontalScrollView panelTabs = new HorizontalScrollView(this);
        panelTabs.setHorizontalScrollBarEnabled(false);
        LinearLayout pr = row();
        for (String name : new String[] {
            "Terminal",
            "Output",
            "Problems",
            "Debugger",
            "Search",
            "Git",
            "Logs",
        }) {
            TextView b = label(name, 12, muted);
            b.setMinHeight(dp(40));
            b.setOnClickListener(v -> showPanel(name));
            pr.addView(b);
        }
        pr.addView(action("×", "Hide panel", () -> togglePanel(false)));
        panelTabs.addView(pr);
        panel.addView(panelTabs);
        panelBody = column();
        panel.addView(panelBody, new LinearLayout.LayoutParams(-1, 0, 1));
        center.addView(panel, new LinearLayout.LayoutParams(-1, dp(wide ? 238 : 200)));
        Runnable sizePanel = () -> {
            int available = center.getHeight();
            if (available <= 0) return;
            boolean input = terminalInput != null && terminalInput.hasFocus();
            int height = Math.min(
                dp(wide ? 238 : 200),
                Math.max(dp(input ? 160 : 96), (int) (available * (input ? 0.68f : 0.36f)))
            );
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) panel.getLayoutParams();
            if (params.height != height) {
                params.height = height;
                panel.setLayoutParams(params);
            }
            breadcrumb.setVisibility(available < dp(330) ? View.GONE : View.VISIBLE);
        };
        center.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) sizePanel.run();
        });
        center.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> sizePanel.run());
        LinearLayout statusRow = row();
        statusRow.setBackgroundColor(surface);
        busy = label("●", 11, accent);
        statusRow.addView(busy);
        status = label("", 10, muted);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, dp(28), 1));
        statusRow.addView(action("■", "Stop execution or active task", this::stop));
        root.addView(statusRow, new LinearLayout.LayoutParams(-1, dp(32)));
    }

    private CodeEditor createEditor() {
        CodeEditor e = new CodeEditor(this);
        e.setTextSize(app.settings.integer("fontSize"));
        e.setTypefaceText(Typeface.create(app.settings.string("fontFamily"), Typeface.NORMAL));
        e.setTabWidth(app.settings.integer("tabSize"));
        e.setWordwrap(app.settings.bool("wordWrap"));
        e.setPinLineNumber(true);
        e.setHighlightCurrentLine(true);
        e.setLineSpacingMultiplier(1.2f);
        e.getProps().symbolPairAutoCompletion = app.settings.bool("autoClose");
        EditorColorScheme scheme = new EditorColorScheme();
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, bg);
        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, bg);
        scheme.setColor(EditorColorScheme.TEXT_NORMAL, text);
        scheme.setColor(EditorColorScheme.LINE_NUMBER, muted);
        scheme.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, accent);
        scheme.setColor(EditorColorScheme.CURRENT_LINE, raised);
        boolean dark = Color.luminance(bg) < 0.4;
        boolean cool = app.settings.string("syntaxTheme").equals("cool"),
            mono = app.settings.string("syntaxTheme").equals("monochrome");
        scheme.setColor(
            EditorColorScheme.KEYWORD,
            mono
                ? text
                : Color.parseColor(cool ? (dark ? "#89B4FA" : "#20579D") : (dark ? "#B18BCE" : "#70428E"))
        );
        scheme.setColor(
            EditorColorScheme.LITERAL,
            mono ? text : Color.parseColor(dark ? "#8CB989" : "#306344")
        );
        scheme.setColor(EditorColorScheme.COMMENT, muted);
        scheme.setColor(EditorColorScheme.FUNCTION_NAME, mono ? text : accent);
        scheme.setColor(
            EditorColorScheme.ANNOTATION,
            mono ? text : Color.parseColor(dark ? "#68AFC0" : "#1E687C")
        );
        e.setColorScheme(scheme);
        e.subscribeEvent(SelectionChangeEvent.class, (event, unsubscribe) -> {
            focused = e;
            ForgeApplication.OpenDocument doc = docFor(e);
            if (doc != null) {
                doc.line = event.getLeft().line;
                doc.column = event.getLeft().column;
            }
            refreshStatus();
            if (minimap != null) {
                minimap.attach(e);
                minimap.invalidate();
            }
        });
        e.subscribeEvent(ContentChangeEvent.class, (event, unsubscribe) -> {
            app.main.removeCallbacks(diagnosticTask);
            app.main.postDelayed(diagnosticTask, 450);
            if (minimap != null) minimap.invalidate();
        });
        return e;
    }

    private ForgeApplication.OpenDocument docFor(CodeEditor editor) {
        return editor == secondary ? secondaryDoc : primaryDoc;
    }

    private ForgeApplication.OpenDocument current() {
        return docFor(focused == null ? primary : focused);
    }

    private void display(ForgeApplication.OpenDocument doc, boolean right) {
        if (right && secondary == null) {
            secondary = createEditor();
            secondary.setId(SECONDARY_EDITOR_ID);
            editorRow.addView(secondary, new LinearLayout.LayoutParams(0, -1, 1));
        }
        CodeEditor e = right ? secondary : primary;
        if (right) secondaryDoc = doc;
        else primaryDoc = doc;
        e.setEditorLanguage(
            new IdeLanguage(
                languages,
                languages.forFile(doc.path),
                app.settings.integer("tabSize"),
                app.settings.bool("useTabs")
            )
        );
        e.setText(doc.content, true, null);
        focused = e;
        int line = Math.min(doc.line, e.getLineCount() - 1);
        e.setSelection(line, Math.min(doc.column, e.getText().getColumnCount(line)));
        app.activePath = doc.path;
        rebuildTabs();
        refreshStatus();
        app.main.post(diagnosticTask);
        if (!wide && sidebarVisible) toggleSidebar();
    }

    private void open(String path) {
        background("Opening…", () -> app.open(path), d -> display(d, false));
    }

    private void rebuildTabs() {
        tabs.removeAllViews();
        for (ForgeApplication.OpenDocument d : app.documents) {
            LinearLayout tab = row();
            boolean active = d == primaryDoc || d == secondaryDoc;
            tab.setBackground(shape(active ? raised : surface, 0));
            TextView name = label((d.dirty() ? "●  " : "") + fileName(d.path), 12, active ? text : muted);
            name.setMinHeight(dp(42));
            name.setOnClickListener(v -> display(d, false));
            name.setOnLongClickListener(v -> {
                fileMenu(d.path);
                return true;
            });
            tab.addView(name);
            TextView close = action("×", "Close " + d.path, () -> closeDocument(d));
            close.setTextSize(14);
            tab.addView(close, new LinearLayout.LayoutParams(dp(32), dp(42)));
            tabs.addView(tab);
        }
    }

    private void closeDocument(ForgeApplication.OpenDocument d) {
        if (d.dirty()) {
            new AlertDialog.Builder(this)
                .setTitle("Save changes to " + fileName(d.path) + "?")
                .setMessage(
                    "The recovery journal is kept until you save or explicitly discard these changes."
                )
                .setPositiveButton("Save", (x, w) ->
                    background(
                        "Saving…",
                        () -> {
                            app.save(d);
                            return d;
                        },
                        this::removeDocument
                    )
                )
                .setNeutralButton("Keep editing", null)
                .setNegativeButton("Discard", (x, w) ->
                    background(
                        "Discarding…",
                        () -> {
                            app.recovery.discard(d.project, d.path);
                            d.discardChanges();
                            return d;
                        },
                        this::removeDocument
                    )
                )
                .show();
        } else removeDocument(d);
    }

    private void removeDocument(ForgeApplication.OpenDocument d) {
        if (d.dirty()) {
            toast("Document changed while saving; it was kept open");
            return;
        }
        d.detach();
        app.documents.remove(d);
        if (d == secondaryDoc) split();
        if (d == primaryDoc) {
            primaryDoc = null;
            primary.setText("");
            if (!app.documents.isEmpty()) display(app.documents.get(0), false);
        }
        rebuildTabs();
        app.io.execute(() -> {
            try {
                app.persistSession();
            } catch (IOException e) {
                app.log(e.getMessage());
            }
        });
    }

    private void refreshState() {
        if (destroyed || status == null) return;
        refreshStatus();
        for (int i = 0; i < app.documents.size() && i < tabs.getChildCount(); i++) {
            ForgeApplication.OpenDocument d = app.documents.get(i);
            ((TextView) ((LinearLayout) tabs.getChildAt(i)).getChildAt(0)).setText(
                (d.dirty() ? "●  " : "") + fileName(d.path)
            );
        }
        if (outputText != null && panelName.equals("Output")) outputText.setText(app.output.toString());
        if (
            terminalText != null && visibleTerminal != null && panelName.equals("Terminal")
        ) terminalText.setText(visibleTerminal.output());
        if (debugText != null && panelName.equals("Debugger")) debugText.setText(debugDescription());
    }

    private void refreshStatus() {
        if (status == null) return;
        ForgeApplication.OpenDocument d = current();
        String language = d == null ? "No document" : languages.forFile(d.path).displayName();
        status.setText(
            (app.isRunning() ? "Running in isolated UID  ·  " : "") +
                language +
                "  ·  " +
                (d == null ? "" : ("Ln " + (d.line + 1) + ", Col " + (d.column + 1) + "  ·  ")) +
                "UTF-8  ·  " +
                (app.settings.bool("useTabs") ? "Tabs" : "Spaces: " + app.settings.integer("tabSize"))
        );
        breadcrumb.setText(
            d == null
                ? "Open a file from the explorer or command palette"
                : app.project +
                  "  ›  " +
                  d.path.replace("/", "  ›  ") +
                  (d.content.length() > 512 * 1024 ? "  ·  Large file mode" : "")
        );
        busy.setTextColor(app.isRunning() ? accent : muted);
    }

    private void toggleSidebar() {
        sidebarVisible = !sidebarVisible;
        sidebar.setVisibility(sidebarVisible ? View.VISIBLE : View.GONE);
    }

    private void togglePanel(boolean visible) {
        panelVisible = visible;
        panel.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void split() {
        if (secondary != null) {
            editorRow.removeView(secondary);
            secondary.release();
            secondary = null;
            secondaryDoc = null;
            focused = primary;
        } else if (primaryDoc != null) {
            display(primaryDoc, true);
            toast("Split view · tap either editor to focus");
        }
    }

    private void symbol(String value) {
        if (current() == null) return;
        focused.requestFocus();
        if (value.equals("←")) {
            int index = Math.max(0, focused.getCursor().getLeft() - 1);
            selectOffset(index, index);
        } else if (value.equals("→")) {
            int index = Math.min(focused.getText().length(), focused.getCursor().getRight() + 1);
            selectOffset(index, index);
        } else focused.commitText(
            value.equals("Tab")
                ? (app.settings.bool("useTabs") ? "\t" : " ".repeat(app.settings.integer("tabSize")))
                : value
        );
    }

    private void selectOffset(int start, int end) {
        var a = focused.getText().getIndexer().getCharPosition(start);
        var b = focused.getText().getIndexer().getCharPosition(end);
        focused.setSelectionRegion(a.line, a.column, b.line, b.column);
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String message(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void error(Throwable error) {
        String m = message(error);
        app.log(m);
        if (!destroyed) new AlertDialog.Builder(this)
            .setTitle("Operation could not finish")
            .setMessage(m)
            .setPositiveButton("OK", null)
            .show();
    }

    private <T> void background(String title, Callable<T> work, Consumer<T> done) {
        busy.setText("◌");
        status.setText(title);
        CompletableFuture.supplyAsync(
            () -> {
                try {
                    return work.call();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            },
            app.io
        ).whenComplete((value, error) ->
            app.main.post(() -> {
                if (destroyed) return;
                busy.setText("●");
                refreshStatus();
                if (error != null) error(error);
                else done.accept(value);
            })
        );
    }

    private void input(String title, String value, Consumer<String> done) {
        EditText field = new EditText(this);
        field.setText(value);
        field.setSingleLine(true);
        field.setSelectAllOnFocus(true);
        field.setPadding(dp(20), dp(12), dp(20), dp(12));
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("OK", (d, w) -> done.accept(field.getText().toString()))
            .show();
    }

    private void choose(String title, List<String> options, Consumer<Integer> selected) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options.toArray(new String[0]), (d, i) -> selected.accept(i))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void textDialog(String title, String text) {
        TextView view = label(text, 12, this.text);
        view.setTextIsSelectable(true);
        view.setTypeface(Typeface.MONOSPACE);
        view.setPadding(dp(18), dp(12), dp(18), dp(20));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Close", null).show();
    }

    // ---------- Projects and explorer ----------
    private void refreshTree() {
        Workspace workspace = app.workspace;
        Set<String> open = new HashSet<>(expanded);
        background(
            "Reading project…",
            () -> {
                List<String> paths = new ArrayList<>();
                collectTree(workspace, "", open, paths, 0);
                return paths;
            },
            paths -> {
                treeRows.removeAllViews();
                TextView name = label(app.project.toUpperCase(Locale.ROOT), 11, muted);
                treeRows.addView(name, new LinearLayout.LayoutParams(-1, dp(32)));
                for (String path : paths) {
                    boolean dir;
                    try {
                        dir = workspace.isDirectory(path);
                    } catch (IOException e) {
                        continue;
                    }
                    int depth = path.split("/").length - 1;
                    TextView item = label(
                        (dir ? (expanded.contains(path) ? "⌄  " : "›  ") : "   ") + fileName(path),
                        12,
                        dir ? text : muted
                    );
                    item.setPadding(dp(12 + depth * 14), 0, dp(6), 0);
                    item.setMinHeight(dp(44));
                    item.setOnClickListener(v -> {
                        if (dir) {
                            if (!expanded.add(path)) expanded.remove(path);
                            refreshTree();
                        } else open(path);
                    });
                    item.setOnLongClickListener(v -> {
                        if (dir) folderMenu(path);
                        else fileMenu(path);
                        return true;
                    });
                    treeRows.addView(item, new LinearLayout.LayoutParams(-1, dp(44)));
                }
            }
        );
    }

    private void collectTree(Workspace w, String dir, Set<String> open, List<String> result, int depth)
        throws IOException {
        if (depth > 30 || result.size() > 3000) return;
        for (String p : w.list(dir)) {
            if (fileName(p).equals(".git") || fileName(p).equals(".forge-trash")) continue;
            result.add(p);
            if (w.isDirectory(p) && open.contains(p)) collectTree(w, p, open, result, depth + 1);
        }
    }

    private void newItem(String directory) {
        choose("Create in " + (directory.isEmpty() ? app.project : directory), List.of("File", "Folder"), i ->
            input(i == 0 ? "New file name" : "New folder name", "", name -> {
                String path = directory.isEmpty() ? name : directory + "/" + name;
                background(
                    "Creating…",
                    () -> {
                        if (i == 0) app.workspace.create(path);
                        else app.workspace.mkdir(path);
                        return path;
                    },
                    p -> {
                        if (!directory.isEmpty()) expanded.add(directory);
                        refreshTree();
                        if (i == 0) open(p);
                    }
                );
            })
        );
    }

    private void fileMenu(String path) {
        choose(
            path,
            List.of(
                "Open in split editor",
                "Rename / move",
                "Duplicate",
                "Move to trash",
                "Export file",
                "Share file",
                "Git diff",
                "File history",
                "Resolve merge conflict"
            ),
            i -> {
                switch (i) {
                    case 0:
                        background("Opening…", () -> app.open(path), d -> display(d, true));
                        break;
                    case 1:
                        input("New project-relative path", path, next ->
                            fileChange(
                                () -> {
                                    app.workspace.move(path, next);
                                    return next;
                                },
                                p -> {
                                    for (ForgeApplication.OpenDocument d : app.documents)
                                        if (d.path.equals(path)) d.path = p;
                                    refreshTree();
                                    rebuildTabs();
                                }
                            )
                        );
                        break;
                    case 2:
                        input("Copy to", path + ".copy", next ->
                            fileChange(
                                () -> {
                                    app.workspace.duplicate(path, next);
                                    return next;
                                },
                                p -> refreshTree()
                            )
                        );
                        break;
                    case 3:
                        trashItem(path);
                        break;
                    case 4:
                        pendingExportPath = path;
                        startActivityForResult(
                            new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TITLE, fileName(path))
                                .addCategory(Intent.CATEGORY_OPENABLE),
                            EXPORT_FILE
                        );
                        break;
                    case 5:
                        fileChange(() -> path, this::share);
                        break;
                    case 6:
                        gitWork(
                            "Diff",
                            g -> g.diff(path),
                            v ->
                                textDialog(
                                    "Working-tree diff · " + path,
                                    v.isEmpty()
                                        ? "No tracked working-tree changes. New files appear in Git changes."
                                        : v
                                )
                        );
                        break;
                    case 7:
                        gitWork(
                            "File history",
                            g -> g.history(path),
                            v -> textDialog(path, String.join("\n\n", v))
                        );
                        break;
                    case 8:
                        resolveConflict(path);
                        break;
                }
            }
        );
    }

    private <T> void fileChange(Callable<T> operation, Consumer<T> done) {
        Workspace owner = app.workspace;
        app
            .saveAll()
            .whenComplete((v, e) ->
                app.main.post(() -> {
                    if (destroyed) return;
                    if (e != null) error(e);
                    else if (app.workspace != owner) toast("Project changed; operation cancelled");
                    else background(
                        "Updating project…",
                        () -> {
                            if (app.workspace != owner) throw new IOException(
                                "Project changed; operation cancelled"
                            );
                            return operation.call();
                        },
                        done
                    );
                })
            );
    }

    private void restoreTrash() {
        ProjectTrash trash = new ProjectTrash(app.workspace);
        background("Reading trash…", trash::list, entries -> {
            if (entries.isEmpty()) {
                toast("Project trash is empty");
                return;
            }
            choose(
                "Restore deleted item",
                entries.stream().map(Object::toString).collect(java.util.stream.Collectors.toList()),
                i ->
                    background(
                        "Restoring…",
                        () -> {
                            ProjectTrash.Entry entry = entries.get(i);
                            trash.restore(entry);
                            return entry.path;
                        },
                        path -> {
                            refreshTree();
                            try {
                                if (!app.workspace.isDirectory(path)) open(path);
                            } catch (IOException e) {
                                error(e);
                            }
                        }
                    )
            );
        });
    }

    private void trashItem(String path) {
        new AlertDialog.Builder(this)
            .setTitle("Move to project trash?")
            .setMessage(
                path +
                    "\nContents can be restored from the command palette. Existing restored destinations are never overwritten."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Move", (dialog, which) ->
                fileChange(
                    () -> new ProjectTrash(app.workspace).move(path),
                    entry -> {
                        for (ForgeApplication.OpenDocument doc : new ArrayList<>(app.documents))
                            if (doc.path.equals(path) || doc.path.startsWith(path + "/")) removeDocument(doc);
                        refreshTree();
                    }
                )
            )
            .show();
    }

    private void folderMenu(String path) {
        choose(
            path,
            List.of("New file / folder", "Rename / move folder", "Move folder to trash", "Duplicate folder"),
            i -> {
                if (i == 0) newItem(path);
                else if (i == 2) trashItem(path);
                else input("New project-relative folder path", path, next ->
                    fileChange(
                        () -> {
                            app.workspace.move(path, next);
                            return next;
                        },
                        result -> {
                            for (ForgeApplication.OpenDocument doc : app.documents)
                                if (doc.path.startsWith(path + "/")) doc.path =
                                    result + doc.path.substring(path.length());
                            expanded.remove(path);
                            expanded.add(result);
                            rebuildTabs();
                            refreshTree();
                        }
                    )
                );
            }
        );
    }

    private void projectMenu() {
        choose(
            "Workspace",
            List.of(
                "Switch project",
                "New project",
                "Import folder (private copy)",
                "Import ZIP backup",
                "Export ZIP backup",
                "Open file from Android",
                "Clone HTTPS repository"
            ),
            i -> {
                switch (i) {
                    case 0:
                        background(
                            "Projects…",
                            () -> app.projects(),
                            names -> choose("Open project", names, n -> switchProject(names.get(n)))
                        );
                        break;
                    case 1:
                        newProject();
                        break;
                    case 2:
                        startActivityForResult(
                            new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            ),
                            IMPORT_TREE
                        );
                        break;
                    case 3:
                        startActivityForResult(
                            new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .setType("application/zip")
                                .addCategory(Intent.CATEGORY_OPENABLE),
                            IMPORT_ZIP
                        );
                        break;
                    case 4:
                        startActivityForResult(
                            new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .setType("application/zip")
                                .putExtra(Intent.EXTRA_TITLE, app.project + ".zip")
                                .addCategory(Intent.CATEGORY_OPENABLE),
                            EXPORT_ZIP
                        );
                        break;
                    case 5:
                        startActivityForResult(
                            new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .setType("*/*")
                                .addCategory(Intent.CATEGORY_OPENABLE),
                            OPEN_FILE
                        );
                        break;
                    case 6:
                        cloneProject();
                        break;
                }
            }
        );
    }

    private void newProject() {
        input("Project name", "", name ->
            choose("Template", List.of("JavaScript", "SQL", "Web", "Python", "C++", "Java", "Empty"), i -> {
                String[] templates = { "JavaScript", "SQL", "Web", "Python", "C++", "Java", "Empty" };
                background(
                    "Creating project…",
                    () -> {
                        app.createProject(name, templates[i]);
                        return name;
                    },
                    this::switchProject
                );
            })
        );
    }

    private void switchProject(String name) {
        if (app.isRunning()) {
            toast("Stop execution before switching projects");
            return;
        }
        persistTerminals();
        fileChange(
            () -> {
                app.persistSession();
                app.loadProject(name);
                return name;
            },
            p -> {
                expanded.clear();
                primaryDoc = null;
                if (secondary != null) split();
                projectTitle.setText(p + "  ⌄");
                rebuildTabs();
                ForgeApplication.OpenDocument d = app.find(app.activePath);
                if (d != null) display(d, false);
                else primary.setText("");
                restoreTerminals();
                refreshTree();
                showPanel("Terminal");
            }
        );
    }

    private void cloneProject() {
        input("HTTPS repository URL", "https://github.com/", url ->
            input("New project name", "", name -> {
                new AlertDialog.Builder(this)
                    .setTitle("Clone using the internet?")
                    .setMessage(
                        "Forge will download the repository into a new private project. Git hooks and native processes are disabled. No packages will be installed."
                    )
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clone", (d, w) -> {
                        taskCancel = new CancellationToken();
                        CancellationToken cancel = taskCancel;
                        background(
                            "Cloning repository…",
                            () -> {
                                RemotePolicy.https(url);
                                if (
                                    !com.forgeide.core.util.TextUtils.isValidFileName(name)
                                ) throw new IOException("Invalid project name");
                                Path path = app.projects.resolve(name);
                                if (Files.exists(path)) throw new IOException("Project already exists");
                                Workspace target = new Workspace(path);
                                try (SafeGit g = new SafeGit(target, new CredentialVault(this))) {
                                    g.cloneFrom(url, cancel);
                                }
                                return name;
                            },
                            this::switchProject
                        );
                    })
                    .show();
            })
        );
    }

    private String pendingExportPath = "";

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == EXPORT_ZIP || request == EXPORT_FILE) {
            fileChange(
                () -> {
                    try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                        if (out == null) throw new IOException("Document provider returned no stream");
                        if (request == EXPORT_ZIP) ProjectArchive.exportZip(app.workspace, out);
                        else Files.copy(app.workspace.resolve(pendingExportPath), out);
                    }
                    return true;
                },
                v -> toast("Export complete")
            );
        } else if (request == OPEN_FILE) {
            input("Import file as", displayName(uri, "imported.txt"), name ->
                background(
                    "Importing file…",
                    () -> {
                        Path p = app.workspace.resolve(name);
                        if (Files.exists(p)) throw new IOException(
                            "File already exists; choose a different name"
                        );
                        try (InputStream in = getContentResolver().openInputStream(uri)) {
                            copyBounded(in, p, Workspace.MAX_TEXT_BYTES);
                        }
                        return name;
                    },
                    p -> {
                        refreshTree();
                        open(p);
                    }
                )
            );
        } else if (request == IMPORT_ZIP || request == IMPORT_TREE) {
            input("Imported project name", "Imported-" + (System.currentTimeMillis() % 100000), name ->
                background(
                    "Importing project…",
                    () -> {
                        if (!com.forgeide.core.util.TextUtils.isValidFileName(name)) throw new IOException(
                            "Invalid project name"
                        );
                        Path destination = app.projects.resolve(name);
                        if (Files.exists(destination)) throw new IOException("Project already exists");
                        Path staging = app.projects.resolve(".import-" + UUID.randomUUID());
                        Workspace target = new Workspace(staging);
                        try {
                            if (request == IMPORT_ZIP) {
                                try (InputStream in = getContentResolver().openInputStream(uri)) {
                                    ProjectArchive.importZip(target, in);
                                }
                            } else {
                                long[] budget = { 0, 0 };
                                copyTree(
                                    uri,
                                    android.provider.DocumentsContract.getTreeDocumentId(uri),
                                    target,
                                    "",
                                    0,
                                    budget
                                );
                            }
                            Files.move(staging, destination);
                        } catch (Exception e) {
                            for (String child : target.list("")) target.delete(child);
                            Files.deleteIfExists(staging);
                            throw e;
                        }
                        return name;
                    },
                    p -> {
                        toast("Imported private copy. Export to sync changes back.");
                        switchProject(p);
                    }
                )
            );
        }
    }

    private String displayName(Uri uri, String fallback) {
        try (
            android.database.Cursor cursor = getContentResolver().query(
                uri,
                new String[] { android.provider.OpenableColumns.DISPLAY_NAME },
                null,
                null,
                null
            )
        ) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (com.forgeide.core.util.TextUtils.isValidFileName(name)) return name;
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private void copyTree(Uri tree, String id, Workspace target, String prefix, int depth, long[] budget)
        throws IOException {
        if (depth > 40) throw new IOException("Folder nesting exceeds import limit");
        Uri children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        String[] columns = {
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
        };
        try (
            android.database.Cursor cursor = getContentResolver().query(children, columns, null, null, null)
        ) {
            if (cursor == null) throw new IOException("Folder provider could not be read");
            while (cursor.moveToNext()) {
                if (++budget[0] > 20000) throw new IOException("Too many imported files");
                String childId = cursor.getString(0),
                    name = cursor.getString(1),
                    mime = cursor.getString(2);
                if (!com.forgeide.core.util.TextUtils.isValidFileName(name)) throw new IOException(
                    "Unsafe imported filename"
                );
                String path = prefix + name;
                if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    target.mkdir(path);
                    copyTree(tree, childId, target, path + "/", depth + 1, budget);
                } else {
                    try (
                        InputStream in = getContentResolver().openInputStream(
                            android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, childId)
                        )
                    ) {
                        budget[1] += copyBounded(
                            in,
                            target.resolve(path),
                            ProjectArchive.MAX_BYTES - budget[1]
                        );
                    }
                }
            }
        }
    }

    private long copyBounded(InputStream in, Path path, long limit) throws IOException {
        if (in == null) throw new IOException("No document stream");
        Files.createDirectories(path.getParent());
        long total = 0;
        try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) != -1) {
                total += n;
                if (total > limit) throw new IOException("Import exceeds storage limit");
                out.write(b, 0, n);
            }
        } catch (IOException e) {
            Files.deleteIfExists(path);
            throw e;
        }
        return total;
    }

    private void share(String path) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                getPackageName() + ".files",
                app.workspace.resolve(path).toFile()
            );
            Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newRawUri("Source file", uri));
            startActivity(Intent.createChooser(share, "Share source file"));
        } catch (Exception e) {
            error(e);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    private void handleIncoming(Intent intent) {
        Uri uri = intent.getData();
        if (uri == null && Intent.ACTION_SEND.equals(intent.getAction())) uri = intent.getParcelableExtra(
            Intent.EXTRA_STREAM
        );
        if (uri != null && "content".equals(uri.getScheme())) {
            Intent data = new Intent().setData(uri);
            onActivityResult(OPEN_FILE, RESULT_OK, data);
        } else if (Intent.ACTION_SEND.equals(intent.getAction()) && intent.hasExtra(Intent.EXTRA_TEXT)) {
            CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (shared == null) return;
            if (shared.length() > Workspace.MAX_TEXT_BYTES) {
                error(new IOException("Shared text exceeds the editable size limit"));
                return;
            }
            String text = shared.toString();
            Workspace owner = app.workspace;
            input("Import shared text as", "shared.txt", name ->
                background(
                    "Importing shared text…",
                    () -> {
                        if (Files.exists(owner.resolve(name))) throw new IOException(
                            "A file with that name already exists"
                        );
                        owner.writeChecked(name, text, "");
                        return name;
                    },
                    path -> {
                        refreshTree();
                        if (app.workspace == owner) open(path);
                    }
                )
            );
        }
    }

    // ---------- Tool panels and confined terminal ----------
    private void showPanel(String name) {
        panelName = name;
        togglePanel(true);
        panelBody.removeAllViews();
        outputText = null;
        terminalText = null;
        debugText = null;
        switch (name) {
            case "Terminal":
                terminalPanel();
                break;
            case "Output": {
                LinearLayout tools = row();
                tools.addView(action("■", "Stop", this::stop));
                tools.addView(
                    action("⌫", "Clear output", () -> {
                        app.output.clear();
                        showPanel("Output");
                    })
                );
                tools.addView(
                    action("≡", "Build history", () -> {
                        List<String> history = app.buildHistory.history();
                        choose("Run history", history
                            .stream()
                            .map(s -> s.substring(0, Math.min(s.length(), 70)))
                            .collect(
                                java.util.stream.Collectors.toList()
                            ), i -> textDialog("Run log", history.get(i)));
                    })
                );
                panelBody.addView(tools);
                outputText = scrollText(
                    app.output.isEmpty()
                        ? "Run a JavaScript file to see live stdout, errors and exit status here.\nNative toolchains are never silently substituted."
                        : app.output.toString()
                );
                break;
            }
            case "Problems":
                if (problems.isEmpty()) scrollText(
                    "No reported problems.\nJSON validation and runtime/compiler diagnostics are available offline. Semantic diagnostics for other languages require an LSP provider."
                );
                else listPanel(
                    problems.stream().map(Diagnostic::toString).collect(java.util.stream.Collectors.toList()),
                    i -> {
                        Diagnostic d = problems.get(i);
                        goTo(d.file, d.line, d.column);
                    }
                );
                break;
            case "Debugger":
                debuggerPanel();
                break;
            case "Git":
                gitPanel();
                break;
            case "Search":
                scrollText(
                    "Search the whole project with the search icon or Ctrl+Shift+F.\nResults link directly to source. Replacements require preview and confirmation."
                );
                break;
            case "Logs":
                scrollText(
                    app.logs.isEmpty() ? "No troubleshooting messages." : String.join("\n\n", app.logs)
                );
                break;
        }
    }

    private TextView scrollText(String value) {
        ScrollView scroll = new ScrollView(this);
        TextView view = label(value, 12, text);
        view.setGravity(Gravity.TOP | Gravity.START);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(dp(12), dp(8), dp(12), dp(16));
        scroll.addView(view, new ScrollView.LayoutParams(-1, -2));
        panelBody.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return view;
    }

    private void listPanel(List<String> items, Consumer<Integer> onClick) {
        ListView list = new ListView(this);
        list.setDividerHeight(dp(1));
        list.setAdapter(
            new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
                @Override
                public View getView(int position, View convert, android.view.ViewGroup parent) {
                    TextView t = (TextView) super.getView(position, convert, parent);
                    t.setTextColor(text);
                    t.setTextSize(12);
                    t.setMinHeight(dp(44));
                    return t;
                }
            }
        );
        list.setOnItemClickListener((p, v, i, id) -> onClick.accept(i));
        panelBody.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private Path terminalStatePath(String project) {
        return app.stateDir.resolve(
            "terminals-" + AtomicFiles.sha256(project.getBytes(StandardCharsets.UTF_8)) + ".json"
        );
    }

    private void restoreTerminals() {
        if (lastTerminalProject.equals(app.project)) return;
        terminalIndex = 0;
        lastTerminalProject = app.project;
        List<TerminalSession> retained = app.terminalSessions.get(app.project);
        if (retained != null) {
            terminals = retained;
            for (TerminalSession session : terminals) attachTerminal(session);
            return;
        }
        terminals = new ArrayList<>();
        app.terminalSessions.put(app.project, terminals);
        while (app.terminalSessions.size() > 4) app.terminalSessions.remove(
            app.terminalSessions.keySet().iterator().next()
        );
        try {
            Path file = terminalStatePath(app.project);
            if (Files.exists(file)) for (Object value : Json.parseArray(
                com.forgeide.core.util.AtomicFiles.readUtf8(file)
            )) {
                if (terminals.size() >= 6) break;
                TerminalSession t = newTerminal("Terminal " + (terminals.size() + 1));
                t.restore(String.valueOf(value));
                terminals.add(t);
            }
        } catch (Exception e) {
            app.log("Terminal history not restored: " + e.getMessage());
        }
        if (terminals.isEmpty()) terminals.add(newTerminal("Terminal 1"));
    }

    private TerminalSession newTerminal(String id) {
        TerminalSession t = new TerminalSession(id, app.workspace);
        attachTerminal(t);
        return t;
    }

    private void attachTerminal(TerminalSession t) {
        ForgeApplication state = app;
        Workspace owner = app.workspace;
        java.lang.ref.WeakReference<MainActivity> view = new java.lang.ref.WeakReference<>(this);
        t.register("run", (a, cwd, env, cancel) -> {
            String path = a.size() > 1 ? t.resolvePath(a.get(1)) : null;
            state.main.post(() -> {
                MainActivity activity = view.get();
                if (activity != null && !activity.destroyed && state.workspace == owner) activity.startRun(
                    false,
                    path,
                    t,
                    a.size() > 2 ? a.subList(2, a.size()) : List.of(),
                    env,
                    cwd
                );
                else t.append("No active project view; run was not started.\n");
            });
            return TerminalSession.Result.ok("Launching in isolated sandbox…\n");
        });
        t.register("git", (a, cwd, env, cancel) -> {
            try (SafeGit g = new SafeGit(owner, new CredentialVault(state))) {
                if (a.size() < 2 || a.get(1).equals("status")) return TerminalSession.Result.ok(
                    g.branch() + "\n" + g.status().toString() + "\n"
                );
                switch (a.get(1)) {
                    case "log":
                        return TerminalSession.Result.ok(
                            String.join("\n", g.history(a.size() > 2 ? a.get(2) : null)) + "\n"
                        );
                    case "diff":
                        return TerminalSession.Result.ok(g.diff(a.size() > 2 ? a.get(2) : null));
                    case "branch":
                        return TerminalSession.Result.ok(String.join("\n", g.branches()) + "\n");
                    default:
                        return new TerminalSession.Result(
                            "",
                            "Use the Git panel for state-changing Git operations and explicit network consent.\n",
                            2
                        );
                }
            }
        });
    }

    private void persistTerminals() {
        if (terminals.isEmpty() || lastTerminalProject.isEmpty()) return;
        List<String> values = new ArrayList<>();
        for (TerminalSession t : terminals) values.add(t.serialize());
        Path path = terminalStatePath(lastTerminalProject);
        app.io.execute(() -> {
            try {
                AtomicFiles.write(path, Json.write(values));
            } catch (IOException e) {
                app.log("Terminal persistence: " + e.getMessage());
            }
        });
    }

    private void terminalPanel() {
        restoreTerminals();
        LinearLayout tools = row();
        TerminalSession terminal = terminals.get(Math.min(terminalIndex, terminals.size() - 1));
        visibleTerminal = terminal;
        TextView name = label(terminal.id + "  ⌄", 12, accent);
        name.setMinHeight(dp(36));
        name.setOnClickListener(v -> {
            List<String> names = new ArrayList<>();
            for (TerminalSession t : terminals) names.add(t.id);
            names.add("+ New session");
            choose("Terminal sessions", names, i -> {
                if (i == terminals.size()) {
                    if (terminals.size() >= 6) {
                        toast("Maximum six sessions");
                        return;
                    }
                    terminals.add(newTerminal("Terminal " + (terminals.size() + 1)));
                }
                terminalIndex = i;
                showPanel("Terminal");
            });
        });
        tools.addView(name, new LinearLayout.LayoutParams(0, dp(36), 1));
        tools.addView(
            action("⌕", "Search terminal output", () ->
                input("Find in terminal", "", query -> {
                    String out = terminal.output();
                    int at = out.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
                    textDialog(
                        "Terminal search",
                        at < 0
                            ? "No matches"
                            : out.substring(Math.max(0, at - 100), Math.min(out.length(), at + 2000))
                    );
                })
            )
        );
        tools.addView(
            action("⌫", "Clear terminal", () -> {
                terminal.clear();
                showPanel("Terminal");
            })
        );
        tools.addView(action("■", "Stop execution", this::stop));
        panelBody.addView(tools);
        terminalText = scrollText(
            terminal.output().isEmpty()
                ? "Forge project shell · isolated file authority\nType help for available commands. This is not a native PTY.\n"
                : terminal.output()
        );
        LinearLayout prompt = row();
        TextView dollar = label("$", 14, accent);
        prompt.addView(dollar);
        terminalInput = new EditText(this);
        terminalInput.setSingleLine(true);
        terminalInput.setTextColor(text);
        terminalInput.setHintTextColor(muted);
        terminalInput.setTextSize(12);
        terminalInput.setTypeface(Typeface.MONOSPACE);
        terminalInput.setHint(terminal.cwd().isEmpty() ? app.project : terminal.cwd());
        terminalInput.setInputType(
            android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        );
        prompt.addView(terminalInput, new LinearLayout.LayoutParams(0, dp(44), 1));
        prompt.addView(action("↵", "Execute command", () -> executeTerminal(terminal)));
        terminalInput.setOnEditorActionListener((v, id, event) -> {
            if (event == null || event.getAction() == KeyEvent.ACTION_DOWN) {
                executeTerminal(terminal);
                return true;
            }
            return false;
        });
        final int[] historyIndex = { terminal.history().size() };
        terminalInput.setOnKeyListener((v, key, event) -> {
            if (
                event.getAction() == KeyEvent.ACTION_DOWN &&
                (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN)
            ) {
                List<String> h = terminal.history();
                historyIndex[0] = Math.max(
                    0,
                    Math.min(h.size(), historyIndex[0] + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1))
                );
                terminalInput.setText(historyIndex[0] == h.size() ? "" : h.get(historyIndex[0]));
                terminalInput.setSelection(terminalInput.length());
                return true;
            }
            return false;
        });
        panelBody.addView(prompt);
    }

    private void executeTerminal(TerminalSession terminal) {
        String command = terminalInput.getText().toString();
        terminalInput.setText("");
        terminal.append("$ " + TerminalSession.redactCommand(command) + "\n");
        taskCancel = new CancellationToken();
        CancellationToken cancel = taskCancel;
        background(
            "Executing project command…",
            () -> terminal.execute(command, cancel),
            result -> {
                terminal.append(result.stdout + result.stderr);
                if (result.exitCode != 0) terminal.append("[exit " + result.exitCode + "]\n");
                if (panelName.equals("Terminal")) showPanel("Terminal");
                refreshTree();
                persistTerminals();
            }
        );
    }

    private void debuggerPanel() {
        LinearLayout tools = row();
        for (String[] cmd : new String[][] {
            { "▶", "continue", "Continue" },
            { "Ⅱ", "pause", "Pause" },
            { "↷", "over", "Step over" },
            { "↓", "into", "Step into" },
            { "↑", "out", "Step out" },
        })
            tools.addView(action(cmd[0], cmd[2], () -> app.execution.control(cmd[1], "")));
        tools.addView(action("⊙", "Toggle breakpoint at cursor", this::toggleBreakpoint));
        tools.addView(
            action("ƒ", "Evaluate watch", () ->
                input("Watch expression", "", value -> app.execution.control("evaluate", value))
            )
        );
        tools.addView(action("■", "Stop debug session", this::stop));
        panelBody.addView(tools);
        debugText = scrollText(debugDescription());
    }

    private String debugDescription() {
        StringBuilder b = new StringBuilder();
        if (app.debugState.isEmpty()) b.append(
            "Debug JavaScript with real breakpoints, stepping, stack frames and variables.\nPress Debug current file to pause on the first executable line.\n"
        );
        else try {
            JsonObject state = JsonObject.parse(app.debugState);
            if (state.getString("event", "").equals("terminated")) b
                .append("Debug session ended · exit ")
                .append(state.getInt("exit", 1))
                .append('\n');
            else if (state.getString("event", "").equals("evaluation")) b
                .append("Watch: ")
                .append(state.getString("expression", ""))
                .append(" = ")
                .append(state.getString("value", ""))
                .append('\n');
            else {
                b.append("Paused · ").append(state.getString("reason", "")).append('\n');
                for (Object raw : state.getArray("stack")) {
                    JsonObject f = new JsonObject((Map<String, Object>) raw);
                    b
                        .append("\n")
                        .append(f.getString("name", ""))
                        .append("  ")
                        .append(f.getString("file", ""))
                        .append(':')
                        .append(f.getInt("line", 1))
                        .append('\n');
                    f
                        .getObject("variables")
                        .raw()
                        .forEach((k, v) -> b.append("  ").append(k).append(" = ").append(v).append('\n'));
                }
            }
        } catch (Exception e) {
            b.append(app.debugState);
        }
        b.append("\nBreakpoints:\n");
        for (Breakpoints.Point p : app.breakpoints.all())
            b
                .append(p.file)
                .append(':')
                .append(p.line + 1)
                .append(p.condition.isEmpty() ? "" : " if " + p.condition)
                .append('\n');
        return b.toString();
    }

    private void toggleBreakpoint() {
        ForgeApplication.OpenDocument d = current();
        if (d == null) return;
        boolean on = app.breakpoints.toggle(d.path, focused.getCursor().getLeftLine());
        toast(on ? "Breakpoint added" : "Breakpoint removed");
        showPanel("Debugger");
        app.io.execute(() -> {
            try {
                app.persistSession();
            } catch (IOException e) {
                app.log(e.getMessage());
            }
        });
    }

    private void conditionalBreakpoint() {
        ForgeApplication.OpenDocument d = current();
        if (d == null) return;
        int line = focused.getCursor().getLeftLine();
        input("Breakpoint condition · line " + (line + 1), "", value -> {
            app.breakpoints.condition(d.path, line, value);
            showPanel("Debugger");
        });
    }

    private void stop() {
        if (app.runCancel != null) app.runCancel.cancel();
        taskCancel.cancel();
        app.execution.cancel();
        appendOutput("\n[execution / task stopped]\n", null);
        refreshStatus();
    }

    private void appendOutput(String value, TerminalSession terminal) {
        app.output.append(value);
        if (terminal != null) terminal.append(value);
        app.changed();
    }

    private void startRun(boolean debug, String requestedFile, TerminalSession terminal) {
        startRun(debug, requestedFile, terminal, null, null, null);
    }

    private void startRun(
        boolean debug,
        String requestedFile,
        TerminalSession terminal,
        List<String> launchArguments,
        Map<String, String> launchEnvironment,
        String launchDirectory
    ) {
        if (app.isRunning()) {
            toast("Stop the active execution before starting another");
            return;
        }
        ForgeApplication.OpenDocument d = current();
        String file = requestedFile != null ? requestedFile : d == null ? "" : d.path;
        if (file.isEmpty()) {
            toast("Open a file first");
            return;
        }
        if (languages.forFile(file).id().equals("html")) {
            if (debug) {
                toast("Browser debugging requires a separate compatible provider");
                return;
            }
            fileChange(
                () -> file,
                p -> startActivity(new Intent(this, PreviewActivity.class).putExtra("file", p))
            );
            return;
        }
        String runtime = languages.forFile(file).id();
        Toolchain provider = app.toolchains.forLanguage(runtime);
        if (provider == null) {
            toolchains();
            return;
        }
        if (
            debug &&
            (!(provider instanceof DebuggableToolchain) || !((DebuggableToolchain) provider).supportsDebug())
        ) {
            toast("This provider has no interactive debugger installed");
            return;
        }
        if (app.settings.bool("requireHardLimits")) {
            error(
                new SecurityException(
                    "Hard memory quotas / CPU shares are not supported by Android's isolated JavaScript provider. Execution is blocked by your strict policy."
                )
            );
            return;
        }
        Workspace runWorkspace = app.workspace;
        fileChange(
            () -> {
                Path configPath = runWorkspace.resolve(".forge/run.json");
                RunConfiguration base = Files.exists(configPath)
                    ? RunConfiguration.parse(runWorkspace.read(".forge/run.json"))
                    : new RunConfiguration(
                          "Current file",
                          "javascript",
                          file,
                          "",
                          List.of(),
                          Map.of(),
                          "",
                          List.of(),
                          List.of(),
                          List.of()
                      );
                runWorkspace.resolve(launchDirectory == null ? base.workingDirectory : launchDirectory);
                if (
                    app.toolchains
                        .all()
                        .stream()
                        .noneMatch(p -> p.id().equals(base.toolchain))
                ) throw new IOException("Selected toolchain is not installed: " + base.toolchain);
                return new RunConfiguration(
                    base.name,
                    provider.id(),
                    file,
                    launchDirectory == null ? base.workingDirectory : launchDirectory,
                    launchArguments == null ? base.arguments : launchArguments,
                    launchEnvironment == null ? base.environment : launchEnvironment,
                    base.stdin,
                    base.buildCommand,
                    base.runCommand,
                    base.debugCommand
                );
            },
            config ->
                background(
                    "Preparing isolated run…",
                    () -> runWorkspace.read(config.file),
                    source -> {
                        if (app.workspace != runWorkspace) {
                            toast("Project changed; run cancelled");
                            return;
                        }
                        app.output.set(
                            "› " +
                                (debug ? "Debug" : "Run") +
                                " " +
                                config.file +
                                " · isolated " +
                                config.toolchain +
                                "\n"
                        );
                        app.debugState = "";
                        if (terminal == null) showPanel(debug ? "Debugger" : "Output");
                        SandboxPolicy policy = new SandboxPolicy(
                            (debug ? 60 : app.settings.integer("executionSeconds")) * 1000L,
                            app.settings.integer("outputKiB") * 1024,
                            SandboxPolicy.safeDefault().required
                        );
                        try {
                            executeProvider(
                                provider,
                                source,
                                config,
                                policy,
                                debug,
                                s -> appendOutput(s, terminal),
                                s -> appendOutput(s, terminal),
                                json ->
                                    app.main.post(() -> {
                                        app.debugState = json;
                                        app.changed();
                                        if (destroyed) return;
                                        try {
                                            JsonObject event = JsonObject.parse(json);
                                            if (event.getString("event", "").equals("sandboxReady")) return;
                                            if (
                                                event.getString("event", "").equals("stopped") &&
                                                !event.getArray("stack").isEmpty()
                                            ) {
                                                JsonObject frame = new JsonObject(
                                                    (Map<String, Object>) event.getArray("stack").get(0)
                                                );
                                                goTo(
                                                    frame.getString("file", config.file),
                                                    frame.getInt("line", 1) - 1,
                                                    0
                                                );
                                                showPanel("Debugger");
                                            }
                                        } catch (Exception e) {
                                            app.log(e.getMessage());
                                        }
                                        refreshState();
                                    })
                            ).whenComplete((exit, error) -> {
                                appendOutput(
                                    error == null
                                        ? "\n[exit " + exit + "]\n"
                                        : "\n[run failed: " + message(error) + "]\n",
                                    terminal
                                );
                                app.buildHistory.add(app.output.toString());
                                if (debug) {
                                    app.debugState = Json.write(
                                        Map.of("event", "terminated", "exit", exit == null ? 1 : exit)
                                    );
                                    app.changed();
                                }
                                app.main.post(() -> {
                                    problems.clear();
                                    problems.addAll(BuildLog.diagnostics(app.output.toString()));
                                    refreshStatus();
                                    applyDiagnostics();
                                });
                            });
                        } catch (Exception e) {
                            error(e);
                        }
                    }
                )
        );
    }

    private void runConfigured() {
        Workspace owner = app.workspace;
        fileChange(
            () -> RunConfiguration.parse(owner.read(".forge/run.json")),
            config -> {
                if (app.workspace != owner) {
                    toast("Project changed; run cancelled");
                    return;
                }
                startRun(false, config.file, null);
            }
        );
    }

    private CompletableFuture<Integer> executeProvider(
        Toolchain provider,
        String source,
        RunConfiguration config,
        SandboxPolicy policy,
        boolean debug,
        Consumer<String> stdout,
        Consumer<String> stderr,
        Consumer<String> events
    ) {
        CancellationToken cancel = new CancellationToken();
        app.runCancel = cancel;
        CompletableFuture<Integer> run = debug
            ? ((DebuggableToolchain) provider).debug(
                  source,
                  config,
                  policy,
                  cancel,
                  app.breakpoints.all(),
                  stdout,
                  stderr,
                  events
              )
            : provider.run(source, config, policy, cancel, stdout, stderr);
        app.activeRun = run;
        app.changed();
        return run;
    }

    private void build() {
        ForgeApplication.OpenDocument doc = current();
        if (doc == null) return;
        String id = languages.forFile(doc.path).id();
        if (id.equals("javascript")) fileChange(
            () -> {
                org.mozilla.javascript.Context cx = org.mozilla.javascript.Context.enter();
                try {
                    cx.setOptimizationLevel(-1);
                    cx.setLanguageVersion(org.mozilla.javascript.Context.VERSION_ES6);
                    cx.compileString(doc.content.toString(), doc.path, 1, null);
                    return "Syntax check succeeded. JavaScript is interpreted; no native binary is produced.";
                } finally {
                    org.mozilla.javascript.Context.exit();
                }
            },
            value -> {
                app.output.set(value + "\n");
                showPanel("Output");
            }
        );
        else toolchains();
    }

    private void toolchains() {
        textDialog(
            "Toolchains & dependencies",
            "INSTALLED OFFLINE\nJavaScript · Rhino ES6 interpreter\n  Isolated Android UID, no Java/Android interop\n  No Node.js, require(), npm or filesystem APIs\n  Conditional breakpoints, stepping, stack, watches\nHTML/CSS · static offline preview (scripts disabled)\nSQLite · isolated in-memory SQL execution\nJSON · validation + lossless layout formatting\nProject shell · scoped file commands; not a PTY\nGit · pure Java, HTTPS; native hooks disabled\n\nADDITIONAL ISOLATED PROVIDERS REQUIRED\nC/C++ · Clang + ABI-compatible runtime + debugger\nJava · compiler, Android-compatible execution provider\nPython · interpreter + isolated filesystem provider\nTypeScript · compiler + runtime provider\nLSP/DAP servers · compatible transport/provider\nBlack / Prettier / clang-format / ESLint\nnpm / pip / Maven / Gradle / CMake / Make\n\nNo runtimes or packages are downloaded automatically. Android cannot safely execute arbitrary desktop binaries. Hard memory ceilings and CPU-share quotas are unavailable; strict policies block execution rather than weakening isolation."
        );
    }

    // ---------- Search, language intelligence and edit commands ----------
    private void analyzeCurrent() {
        ForgeApplication.OpenDocument d = current();
        if (d == null || d.content.length() > 512 * 1024) return;
        long revision = d.revision.get();
        if (d == analyzedDocument && revision == analyzedRevision) return;
        analysis.execute(() -> {
            try {
                String source = d.content.toString();
                List<Diagnostic> found = new ArrayList<>(
                    localLanguage.diagnose(languages.forFile(d.path), d.path, source, new CancellationToken())
                );
                if (languages.forFile(d.path).id().equals("javascript")) {
                    org.mozilla.javascript.Context cx = org.mozilla.javascript.Context.enter();
                    try {
                        cx.setOptimizationLevel(-1);
                        cx.setLanguageVersion(org.mozilla.javascript.Context.VERSION_ES6);
                        cx.compileString(source, d.path, 1, null);
                    } catch (org.mozilla.javascript.RhinoException e) {
                        found.add(
                            new Diagnostic(
                                d.path,
                                e.lineNumber() - 1,
                                e.columnNumber() - 1,
                                Diagnostic.Severity.ERROR,
                                e.details(),
                                "Rhino"
                            )
                        );
                    } finally {
                        org.mozilla.javascript.Context.exit();
                    }
                }
                app.main.post(() -> {
                    if (destroyed || current() != d || revision != d.revision.get()) return;
                    analyzedRevision = revision;
                    analyzedDocument = d;
                    problems.removeIf(
                        p -> p.file.equals(d.path) && (p.source.equals("JSON") || p.source.equals("Rhino"))
                    );
                    problems.addAll(found);
                    applyDiagnostics();
                });
            } catch (StackOverflowError excessiveNesting) {
                app.log(
                    "Syntax analysis stopped: excessive nesting. The document is still editable and recoverable."
                );
            } catch (Exception e) {
                app.log("Analysis: " + e.getMessage());
            }
        });
    }

    private void applyDiagnostics() {
        if (destroyed) return;
        for (CodeEditor editor : new CodeEditor[] { primary, secondary }) {
            if (editor == null) continue;
            ForgeApplication.OpenDocument d = docFor(editor);
            if (d == null) continue;
            DiagnosticsContainer container = new DiagnosticsContainer();
            for (Diagnostic p : problems)
                if (p.file.equals(d.path)) {
                    int line = Math.min(p.line, editor.getLineCount() - 1);
                    int col = Math.min(p.column, editor.getText().getColumnCount(line));
                    int start = editor.getText().getIndexer().getCharIndex(line, col);
                    container.addDiagnostic(
                        new DiagnosticRegion(
                            start,
                            Math.min(editor.getText().length(), start + 1),
                            p.severity == Diagnostic.Severity.WARNING
                                ? DiagnosticRegion.SEVERITY_WARNING
                                : DiagnosticRegion.SEVERITY_ERROR
                        )
                    );
                }
            editor.setDiagnostics(container);
        }
        if (panelName.equals("Problems")) showPanel("Problems");
    }

    private void goTo(String file, int line, int column) {
        background(
            "Navigating…",
            () -> app.open(file),
            doc -> {
                display(doc, false);
                int l = Math.max(0, Math.min(line, primary.getLineCount() - 1));
                primary.setSelection(l, Math.max(0, Math.min(column, primary.getText().getColumnCount(l))));
                primary.requestFocus();
            }
        );
    }

    private void goToLine() {
        ForgeApplication.OpenDocument d = current();
        if (d == null) return;
        input("Go to line (1–" + focused.getLineCount() + ")", String.valueOf(d.line + 1), value -> {
            try {
                int line = Integer.parseInt(value) - 1;
                if (line < 0 || line >= focused.getLineCount()) throw new IllegalArgumentException(
                    "Line outside document"
                );
                focused.setSelection(line, 0);
            } catch (Exception e) {
                error(e);
            }
        });
    }

    private void symbols() {
        ForgeApplication.OpenDocument d = current();
        if (d == null) return;
        background(
            "Finding symbols…",
            () ->
                localLanguage.symbols(
                    languages.forFile(d.path),
                    d.content.toString(),
                    new CancellationToken()
                ),
            items -> {
                if (items.isEmpty()) {
                    toast("No lexical declarations found; semantic symbols need an LSP provider");
                    return;
                }
                choose(
                    "Document symbols · lexical",
                    items.stream().map(Object::toString).collect(java.util.stream.Collectors.toList()),
                    i -> goTo(d.path, items.get(i).line, 0)
                );
            }
        );
    }

    private void definition() {
        ForgeApplication.OpenDocument d = current();
        if (d == null) return;
        String word = com.forgeide.core.language.Language.wordAt(
            d.content.toString(),
            focused.getCursor().getLeft()
        );
        background(
            "Looking for declarations…",
            () ->
                localLanguage.symbols(
                    languages.forFile(d.path),
                    d.content.toString(),
                    new CancellationToken()
                ),
            items -> {
                for (LanguageService.Symbol s : items)
                    if (s.name.equals(word)) {
                        goTo(d.path, s.line, 0);
                        return;
                    }
                toast("No local lexical declaration. Type-aware definition lookup requires an LSP provider.");
            }
        );
    }

    private void references() {
        ForgeApplication.OpenDocument d = current();
        if (d == null) return;
        String word = com.forgeide.core.language.Language.wordAt(
            d.content.toString(),
            focused.getCursor().getLeft()
        );
        if (!word.isEmpty()) searchProject(new FindReplaceEngine.Query(word, false, true, true), false, "");
    }

    private void format() {
        ForgeApplication.OpenDocument d = current();
        if (d == null) return;
        CodeEditor editor = focused;
        long revision = d.revision.get();
        background(
            "Formatting…",
            () ->
                localLanguage.format(
                    languages.forFile(d.path),
                    d.content.toString(),
                    app.settings.integer("tabSize")
                ),
            value -> {
                if (revision != d.revision.get()) {
                    toast("Document changed while formatting; no changes applied");
                    return;
                }
                editor.getText().replace(0, editor.getText().length(), value);
                toast(
                    languages.forFile(d.path).id().equals("json")
                        ? "JSON layout formatted"
                        : "Trailing whitespace cleaned. Full formatting needs a language formatter provider."
                );
            }
        );
    }

    private void editOperation(String command) {
        ForgeApplication.OpenDocument d = current();
        if (d == null) return;
        CodeEditor editor = focused;
        int line = editor.getCursor().getLeftLine(),
            from = line,
            to = line;
        if (command.equals("up")) {
            if (line == 0) return;
            from = line - 1;
        } else if (command.equals("down") || command.equals("join")) {
            if (line + 1 >= editor.getLineCount()) return;
            to = line + 1;
        } else if (command.equals("comment")) to = editor.getCursor().getRightLine();
        int start = editor.getText().getIndexer().getCharIndex(from, 0);
        int end = editor.getText().getIndexer().getCharIndex(to, editor.getText().getColumnCount(to));
        Document fragment = new Document(editor.getText().subSequence(start, end).toString());
        switch (command) {
            case "duplicate":
                EditOperations.duplicateLine(fragment, 0);
                break;
            case "up":
                EditOperations.moveLineUp(fragment, 1);
                break;
            case "down":
                EditOperations.moveLineDown(fragment, 0);
                break;
            case "join":
                EditOperations.joinLines(fragment, 0);
                break;
            case "comment":
                String prefix = languages.forFile(d.path).lineComment();
                if (prefix == null) {
                    toast("This language has no line comment syntax");
                    return;
                }
                EditOperations.toggleComment(fragment, 0, to - from, prefix);
                break;
        }
        editor.getText().replace(start, end, fragment.text());
        editor.setSelection(Math.min(line, editor.getLineCount() - 1), 0);
    }

    private void findDialog() {
        if (current() == null) return;
        LinearLayout box = column();
        box.setPadding(dp(18), 0, dp(18), dp(10));
        EditText query = new EditText(this);
        query.setHint("Find");
        query.setSingleLine(true);
        box.addView(query);
        EditText replacement = new EditText(this);
        replacement.setHint("Replace with");
        replacement.setSingleLine(true);
        box.addView(replacement);
        CheckBox regex = new CheckBox(this);
        regex.setText("Regular expression");
        box.addView(regex);
        CheckBox sensitive = new CheckBox(this);
        sensitive.setText("Match case");
        box.addView(sensitive);
        CheckBox whole = new CheckBox(this);
        whole.setText("Whole word");
        box.addView(whole);
        LinearLayout buttons = row();
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Find & replace")
            .setView(box)
            .setNegativeButton("Close", (d, w) -> focused.getSearcher().stopSearch())
            .create();
        for (String name : List.of("Next", "Previous", "Replace", "All")) {
            Button b = new Button(this);
            b.setText(name);
            b.setTextSize(11);
            buttons.addView(b, new LinearLayout.LayoutParams(0, dp(44), 1));
            b.setOnClickListener(v -> {
                String pattern = query.getText().toString();
                if (pattern.isEmpty()) return;
                FindReplaceEngine.Query q = new FindReplaceEngine.Query(
                    pattern,
                    regex.isChecked(),
                    sensitive.isChecked(),
                    whole.isChecked()
                );
                CodeEditor target = focused;
                ForgeApplication.OpenDocument doc = current();
                int from = target.getCursor().getRight();
                long revision = doc.revision.get();
                String replace = replacement.getText().toString();
                background(
                    "Searching document…",
                    () -> {
                        Document snapshot = new Document(doc.content.toString());
                        FindReplaceEngine engine = new FindReplaceEngine(snapshot);
                        if (!engine.isValid(q)) throw new IllegalArgumentException(
                            "Invalid regular expression"
                        );
                        if (name.equals("All")) {
                            int count = engine.replaceAll(q, replace, null);
                            return new Object[] { snapshot.text(), count };
                        }
                        FindReplaceEngine.Match match = name.equals("Previous")
                            ? engine.findPrevious(q, target.getCursor().getLeft(), true)
                            : engine.findNext(q, from, true);
                        if (match != null && name.equals("Replace")) engine.replace(q, match, replace);
                        return new Object[] { name.equals("Replace") ? snapshot.text() : null, match };
                    },
                    result -> {
                        if (doc.revision.get() != revision) {
                            toast("Document changed; search again");
                            return;
                        }
                        if (name.equals("All")) {
                            new AlertDialog.Builder(this)
                                .setTitle("Replace " + result[1] + " matches?")
                                .setMessage("This is one undo step.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Replace", (x, w) -> {
                                    if (doc.revision.get() != revision) {
                                        toast("Document changed; no replacement applied");
                                        return;
                                    }
                                    target
                                        .getText()
                                        .replace(0, target.getText().length(), (String) result[0]);
                                    dialog.dismiss();
                                })
                                .show();
                        } else {
                            FindReplaceEngine.Match match = (FindReplaceEngine.Match) result[1];
                            if (match == null) {
                                toast("No matches");
                                return;
                            }
                            if (result[0] != null) target
                                .getText()
                                .replace(0, target.getText().length(), (String) result[0]);
                            focused = target;
                            selectOffset(
                                match.range.start,
                                Math.min(
                                    target.getText().length(),
                                    name.equals("Replace")
                                        ? match.range.start + replace.length()
                                        : match.range.end
                                )
                            );
                        }
                    }
                );
            });
        }
        box.addView(buttons);
        dialog.show();
    }

    private void projectSearch() {
        LinearLayout box = column();
        box.setPadding(dp(18), 0, dp(18), dp(8));
        EditText query = new EditText(this);
        query.setHint("Text or regular expression");
        box.addView(query);
        EditText replace = new EditText(this);
        replace.setHint("Replacement (optional)");
        box.addView(replace);
        CheckBox regex = new CheckBox(this);
        regex.setText("Regex · line-scoped");
        box.addView(regex);
        CheckBox sensitive = new CheckBox(this);
        sensitive.setText("Match case");
        box.addView(sensitive);
        new AlertDialog.Builder(this)
            .setTitle("Search project")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Preview replace", (d, w) ->
                searchProject(
                    new FindReplaceEngine.Query(
                        query.getText().toString(),
                        regex.isChecked(),
                        sensitive.isChecked(),
                        false
                    ),
                    true,
                    replace.getText().toString()
                )
            )
            .setPositiveButton("Search", (d, w) ->
                searchProject(
                    new FindReplaceEngine.Query(
                        query.getText().toString(),
                        regex.isChecked(),
                        sensitive.isChecked(),
                        false
                    ),
                    false,
                    ""
                )
            )
            .show();
    }

    private void searchProject(FindReplaceEngine.Query query, boolean replacing, String replacement) {
        taskCancel = new CancellationToken();
        CancellationToken cancel = taskCancel;
        Workspace workspace = app.workspace;
        background(
            "Searching project…",
            () -> new ProjectSearch().search(workspace, query, 1000, cancel),
            hits -> {
                showPanel("Search");
                panelBody.removeAllViews();
                if (hits.isEmpty()) {
                    scrollText("No matches in searchable UTF-8 project files.");
                    return;
                }
                if (replacing) {
                    TextView apply = label("Replace " + hits.size() + " previewed matches…", 12, accent);
                    apply.setMinHeight(dp(44));
                    apply.setOnClickListener(v ->
                        new AlertDialog.Builder(this)
                            .setTitle("Replace across files?")
                            .setMessage(
                                "Only the previewed matches will change. Files changed since this search are rejected. Original files are backed up under .forge/replace-backups. Save/close open documents first."
                            )
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Replace", (d, w) -> {
                                for (ForgeApplication.OpenDocument doc : app.documents)
                                    for (ProjectSearch.Hit h : hits)
                                        if (doc.path.equals(h.file)) {
                                            toast("Close affected open tabs before replacing across files");
                                            return;
                                        }
                                background(
                                    "Replacing…",
                                    () ->
                                        new ProjectSearch().replace(
                                            workspace,
                                            query,
                                            replacement,
                                            hits,
                                            cancel,
                                            file -> {
                                                try {
                                                    String backup =
                                                        ".forge/replace-backups/" +
                                                        System.currentTimeMillis() +
                                                        "/" +
                                                        file;
                                                    workspace.write(backup, workspace.read(file));
                                                } catch (IOException e) {
                                                    throw new CompletionException(e);
                                                }
                                            }
                                        ),
                                    count -> {
                                        toast("Replaced " + count + " matches");
                                        refreshTree();
                                    }
                                );
                            })
                            .show()
                    );
                    panelBody.addView(apply);
                }
                listPanel(
                    hits.stream().map(Object::toString).collect(java.util.stream.Collectors.toList()),
                    i -> {
                        ProjectSearch.Hit h = hits.get(i);
                        goTo(h.file, h.line, h.column);
                    }
                );
            }
        );
    }

    private void foldedOverview() {
        ForgeApplication.OpenDocument doc = current();
        if (doc == null) return;
        background(
            "Computing folds…",
            () -> {
                String source = doc.content.toString();
                List<FoldingRegion> regions = localLanguage.folds(
                    languages.forFile(doc.path),
                    source,
                    new CancellationToken()
                );
                FoldingModel folds = new FoldingModel();
                folds.setRegions(regions);
                folds.foldAll();
                String[] lines = source.split("\n", -1);
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < lines.length; i++) if (folds.isLineVisible(i)) {
                    text.append(i + 1).append("  ").append(lines[i]);
                    if (folds.isFolded(i)) text
                        .append("   … [")
                        .append(folds.findRegion(i).endLine - i)
                        .append(" lines]");
                    text.append('\n');
                }
                return text.toString();
            },
            value -> textDialog("Folded overview · read-only", value)
        );
    }

    private void quickOpen() {
        background(
            "Listing files…",
            () -> app.workspace.files(20000),
            files -> searchable("Quick open", files, i -> open(files.get(i)))
        );
    }

    private void searchable(String title, List<String> items, Consumer<Integer> select) {
        LinearLayout box = column();
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("Type to filter…");
        box.addView(query);
        ListView list = new ListView(this);
        box.addView(list, new LinearLayout.LayoutParams(-1, dp(360)));
        List<Integer> indices = new ArrayList<>();
        Runnable filter = () -> {
            indices.clear();
            List<String> visible = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) if (
                FuzzyMatcher.match(query.getText().toString(), items.get(i)) != null
            ) {
                indices.add(i);
                visible.add(items.get(i));
                if (visible.size() == 200) break;
            }
            list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, visible));
        };
        filter.run();
        query.addTextChangedListener(watcher(filter));
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("Cancel", null)
            .create();
        list.setOnItemClickListener((p, v, i, id) -> {
            int index = indices.get(i);
            dialog.dismiss();
            select.accept(index);
        });
        dialog.show();
    }

    private TextWatcher watcher(Runnable after) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int n) {}

            public void onTextChanged(CharSequence s, int a, int before, int count) {
                after.run();
            }

            public void afterTextChanged(Editable s) {}
        };
    }

    // ---------- Trusted Git / GitHub UI ----------
    private <T> void gitWork(String title, GitOperation<T> operation, Consumer<T> done) {
        background(
            title + "…",
            () -> {
                System.setProperty("user.home", app.stateDir.resolve("git-home").toString());
                try (SafeGit git = new SafeGit(app.workspace, new CredentialVault(this))) {
                    return operation.run(git);
                }
            },
            done
        );
    }

    private interface GitOperation<T> {
        T run(SafeGit git) throws Exception;
    }

    private void gitPanel() {
        LinearLayout tools = row();
        for (String[] item : new String[][] {
            { "+", "Initialize" },
            { "✓", "Commit all" },
            { "⑂", "Branches" },
            { "⇅", "Sync / GitHub" },
            { "▣", "Stash" },
            { "↻", "Refresh" },
        })
            tools.addView(
                action(item[0], item[1], () -> {
                    switch (item[1]) {
                        case "Initialize":
                            gitWork(
                                "Initializing",
                                g -> {
                                    g.init();
                                    return true;
                                },
                                v -> gitPanelRefresh()
                            );
                            break;
                        case "Commit all":
                            commit();
                            break;
                        case "Branches":
                            branchMenu();
                            break;
                        case "Sync / GitHub":
                            syncMenu();
                            break;
                        case "Stash":
                            choose(
                                "Stash",
                                List.of("Stash tracked changes", "Apply latest stash and remove it"),
                                i ->
                                    fileChange(
                                        () -> {
                                            try (
                                                SafeGit g = new SafeGit(
                                                    app.workspace,
                                                    new CredentialVault(this)
                                                )
                                            ) {
                                                g.stash(i == 1);
                                            }
                                            return true;
                                        },
                                        v -> switchProject(app.project)
                                    )
                            );
                            break;
                        default:
                            gitPanelRefresh();
                    }
                })
            );
        panelBody.addView(tools);
        scrollText("Reading repository status…");
        gitPanelRefresh();
    }

    private void gitPanelRefresh() {
        gitWork(
            "Reading Git status",
            g -> new Object[] { g.branch(), g.status() },
            result -> {
                if (!panelName.equals("Git")) return;
                while (panelBody.getChildCount() > 1) panelBody.removeViewAt(1);
                List<GitService.Change> changes = (List<GitService.Change>) result[1];
                TextView title = label("⑂ " + result[0] + "  ·  " + changes.size() + " changes", 12, accent);
                title.setMinHeight(dp(32));
                panelBody.addView(title);
                if (changes.isEmpty()) scrollText("Working tree is clean.");
                else listPanel(
                    changes.stream().map(Object::toString).collect(java.util.stream.Collectors.toList()),
                    i -> {
                        GitService.Change c = changes.get(i);
                        choose(
                            c.path,
                            List.of("Open file", "Show working-tree diff", "Resolve conflict"),
                            n -> {
                                if (n == 0) open(c.path);
                                else if (n == 2) resolveConflict(c.path);
                                else gitWork(
                                    "Diff",
                                    g -> g.diff(c.path),
                                    diff ->
                                        textDialog(
                                            c.path,
                                            diff.isEmpty()
                                                ? "Untracked files have no base diff. Open the file to inspect its content."
                                                : diff
                                        )
                                );
                            }
                        );
                    }
                );
            }
        );
    }

    private void commit() {
        input("Commit message (stages all changes)", "", message -> {
            String author = getPreferences(0).getString("gitAuthor", "Developer"),
                email = getPreferences(0).getString("gitEmail", "developer@localhost");
            fileChange(
                () -> {
                    try (SafeGit g = new SafeGit(app.workspace, new CredentialVault(this))) {
                        g.commit(message, author, email);
                    }
                    return true;
                },
                v -> {
                    toast("Commit created");
                    gitPanelRefresh();
                }
            );
        });
    }

    private void branchMenu() {
        choose(
            "Branches",
            List.of(
                "Switch branch",
                "Create branch",
                "Merge branch",
                "Commit history",
                "Set author name",
                "Set author email"
            ),
            i -> {
                if (i == 3) {
                    gitWork(
                        "History",
                        g -> g.history(null),
                        v -> textDialog("Commit history", String.join("\n\n", v))
                    );
                    return;
                }
                if (i >= 4) {
                    String key = i == 4 ? "gitAuthor" : "gitEmail";
                    input(
                        i == 4 ? "Git author name" : "Git author email",
                        getPreferences(0).getString(key, i == 4 ? "Developer" : "developer@localhost"),
                        value -> getPreferences(0).edit().putString(key, value).apply()
                    );
                    return;
                }
                if (i == 1) {
                    input("New branch name", "", name ->
                        fileChange(
                            () -> {
                                try (SafeGit g = new SafeGit(app.workspace, new CredentialVault(this))) {
                                    g.checkout(name, true);
                                }
                                return true;
                            },
                            v -> switchProject(app.project)
                        )
                    );
                    return;
                }
                gitWork("Listing branches", SafeGit::branches, branches ->
                    choose(i == 0 ? "Switch branch" : "Merge branch", branches, n ->
                        fileChange(
                            () -> {
                                try (SafeGit g = new SafeGit(app.workspace, new CredentialVault(this))) {
                                    if (i == 0) g.checkout(branches.get(n), false);
                                    else g.merge(branches.get(n));
                                }
                                return true;
                            },
                            v -> switchProject(app.project)
                        )
                    )
                );
            }
        );
    }

    private void syncMenu() {
        CredentialVault vault = new CredentialVault(this);
        choose(
            "Git / GitHub · network operations ask first",
            List.of(
                "Fetch",
                "Pull",
                "Push",
                "Set HTTPS origin",
                "Connect GitHub securely",
                "Create private GitHub repository",
                "Disconnect GitHub"
            ),
            i -> {
                if (i <= 2) {
                    String operation = new String[] { "fetch", "pull", "push" }[i];
                    new AlertDialog.Builder(this)
                        .setTitle(operation + " using the internet?")
                        .setMessage(
                            "Your GitHub credential is used only by trusted Git transport. It is never passed to terminal environments or sandboxed code."
                        )
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Continue", (d, w) -> {
                            taskCancel = new CancellationToken();
                            CancellationToken cancel = taskCancel;
                            fileChange(
                                () -> {
                                    try (SafeGit g = new SafeGit(app.workspace, new CredentialVault(this))) {
                                        g.remote(operation, cancel);
                                    }
                                    return true;
                                },
                                v -> {
                                    toast(operation + " complete");
                                    if (operation.equals("pull")) switchProject(app.project);
                                    else gitPanelRefresh();
                                }
                            );
                        })
                        .show();
                } else if (i == 3) input("Origin HTTPS URL", "https://github.com/", url ->
                    gitWork(
                        "Saving origin",
                        g -> {
                            g.origin(url);
                            return true;
                        },
                        v -> toast("Origin saved")
                    )
                );
                else if (i == 4) connectGitHub();
                else if (i == 5) input("Create PRIVATE GitHub repository", app.project, name ->
                    new AlertDialog.Builder(this)
                        .setTitle("Create private repository online?")
                        .setMessage(
                            "This creates a repository in your connected GitHub account and sets this project's origin. Push remains a separate action."
                        )
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Create", (d, w) ->
                            background(
                                "Creating GitHub repository…",
                                () -> {
                                    String url = new GitHubApi(vault).createRepository(name, true);
                                    try (SafeGit g = new SafeGit(app.workspace, vault)) {
                                        g.init();
                                        g.origin(url);
                                    }
                                    return url;
                                },
                                url -> textDialog("Repository created", url + "\nCommit and push when ready.")
                            )
                        )
                        .show()
                );
                else background(
                    "Disconnecting…",
                    () -> {
                        vault.clear();
                        return true;
                    },
                    v -> toast("GitHub credential removed")
                );
            }
        );
    }

    private void connectGitHub() {
        EditText token = new EditText(this);
        token.setSingleLine(true);
        token.setInputType(
            android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        token.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        token.setHint("Fine-grained GitHub token");
        token.setPadding(dp(20), dp(12), dp(20), dp(12));
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Connect GitHub")
            .setMessage(
                "Use a least-privilege fine-grained token limited to the repositories you choose. Stored encrypted with Android Keystore; excluded from backup, logs, projects and runtimes. You can revoke it at github.com/settings/tokens."
            )
            .setView(token)
            .setNegativeButton("Cancel", (d, w) -> token.setText(""))
            .setPositiveButton("Connect", (d, w) -> {
                String secret = token.getText().toString().trim();
                token.setText("");
                if (secret.isEmpty()) return;
                background(
                    "Verifying GitHub account…",
                    () -> {
                        CredentialVault vault = new CredentialVault(this);
                        String login = new GitHubApi(vault).verifyToken(secret);
                        vault.store(secret);
                        return login;
                    },
                    login -> toast("Connected as " + login)
                );
            })
            .create();
        dialog.getWindow();
        dialog.setOnShowListener(v -> dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
        dialog.show();
    }

    private void resolveConflict(String path) {
        background(
            "Reading conflicts…",
            () -> app.open(path),
            doc -> {
                String source = doc.content.toString();
                List<ConflictParser.Conflict> conflicts = ConflictParser.parse(source);
                if (conflicts.isEmpty()) {
                    toast("No standard Git conflict blocks in this file");
                    return;
                }
                ConflictParser.Conflict c = conflicts.get(0);
                choose(
                    "Resolve first conflict in " + path,
                    List.of("Keep ours", "Keep theirs", "Keep both", "Inspect conflict"),
                    i -> {
                        if (i == 3) {
                            display(doc, false);
                            textDialog("Ours / theirs", "OURS\n" + c.ours + "\nTHEIRS\n" + c.theirs);
                            return;
                        }
                        if (!doc.content.toString().equals(source)) {
                            toast("File changed; inspect the conflict again");
                            return;
                        }
                        String next = ConflictParser.resolve(source, c, i == 0, i == 2);
                        doc.content.replace(0, doc.content.length(), next);
                        display(doc, false);
                        toast(
                            "Conflict resolved in editor. Save and commit when all conflicts are resolved."
                        );
                    }
                );
            }
        );
    }

    // ---------- Settings, commands, shortcuts and lifecycle ----------
    private void saveAll() {
        app
            .saveAll()
            .whenComplete((v, e) ->
                app.main.post(() -> {
                    if (destroyed) return;
                    if (e != null) error(e);
                    else {
                        toast("All changes saved");
                        refreshState();
                    }
                })
            );
    }

    private void settingsDialog() {
        choose(
            "IDE settings",
            List.of(
                "Editor appearance",
                "Indentation & editing",
                "Autosave & recovery",
                "Sandbox & execution",
                "Project run configuration",
                "Project-specific settings JSON",
                "Keyboard shortcuts",
                "Toolchains / package support",
                "Git / GitHub",
                "Storage & recovery status",
                "About / licenses"
            ),
            i -> {
                switch (i) {
                    case 0:
                        appearanceSettings();
                        break;
                    case 1:
                        choose(
                            "Editing",
                            List.of(
                                "Indent size: " + app.settings.integer("tabSize"),
                                "Use tabs: " + app.settings.bool("useTabs"),
                                "Auto-close pairs: " + app.settings.bool("autoClose")
                            ),
                            n -> {
                                if (n == 0) numberSetting("tabSize");
                                else setSetting(
                                    n == 1 ? "useTabs" : "autoClose",
                                    !app.settings.bool(n == 1 ? "useTabs" : "autoClose")
                                );
                            }
                        );
                        break;
                    case 2:
                        choose(
                            "Recovery",
                            List.of(
                                "Autosave: " + app.settings.bool("autosave"),
                                "Autosave interval: " + app.settings.integer("autosaveSeconds") + "s",
                                "Inspect recovery journals",
                                "Restore deleted file"
                            ),
                            n -> {
                                if (n == 0) setSetting("autosave", !app.settings.bool("autosave"));
                                else if (n == 1) numberSetting("autosaveSeconds");
                                else if (n == 2) recoveryStatus();
                                else restoreTrash();
                            }
                        );
                        break;
                    case 3:
                        choose(
                            "Sandbox",
                            List.of(
                                "Execution timeout: " + app.settings.integer("executionSeconds") + "s",
                                "Output cap: " + app.settings.integer("outputKiB") + " KiB",
                                "Require hard CPU/memory quotas: " + app.settings.bool("requireHardLimits"),
                                "Isolation details"
                            ),
                            n -> {
                                if (n == 0) numberSetting("executionSeconds");
                                else if (n == 1) numberSetting("outputKiB");
                                else if (n == 2) setSetting(
                                    "requireHardLimits",
                                    !app.settings.bool("requireHardLimits")
                                );
                                else toolchains();
                            }
                        );
                        break;
                    case 4:
                        background(
                            "Run configuration…",
                            () -> {
                                String path = ".forge/run.json";
                                if (!Files.exists(app.workspace.resolve(path))) app.workspace.write(
                                    path,
                                    new RunConfiguration(
                                        "Current file",
                                        "javascript",
                                        current() == null ? "main.js" : current().path,
                                        "",
                                        List.of(),
                                        Map.of(),
                                        "",
                                        List.of(),
                                        List.of(),
                                        List.of()
                                    ).toJson()
                                );
                                return path;
                            },
                            this::open
                        );
                        break;
                    case 5:
                        background(
                            "Project settings…",
                            () -> {
                                String path = ".forge/settings.json";
                                if (!Files.exists(app.workspace.resolve(path))) app.workspace.write(
                                    path,
                                    "{\n  \"tabSize\": 4\n}\n"
                                );
                                return path;
                            },
                            p -> {
                                toast("Project overrides take effect when the project is reopened");
                                open(p);
                            }
                        );
                        break;
                    case 6:
                        shortcutSettings();
                        break;
                    case 7:
                        toolchains();
                        break;
                    case 8:
                        syncMenu();
                        break;
                    case 9:
                        recoveryStatus();
                        break;
                    case 10:
                        about();
                        break;
                }
            }
        );
    }

    private void about() {
        new AlertDialog.Builder(this)
            .setTitle("Forge IDE · " + BuildConfig.VERSION_NAME)
            .setMessage(
                "Native Android development workspace.\n\nSora 0.23.5 · Rhino 1.7.15 · AndroidX SQLite 2.6.2 · JGit 5.13.3.\n\nSource, build scripts, limitations and security notes: github.com/DigantaDey/Project-Android-IDE-Terminal\n\nUninstalled native toolchains are identified explicitly; no shell or compiler success is simulated."
            )
            .setPositiveButton("Close", null)
            .setNeutralButton("Third-party notices", (d, w) -> thirdPartyNotices())
            .show();
    }

    private void thirdPartyNotices() {
        background(
            "Reading licenses…",
            () -> Arrays.asList(getAssets().list("")),
            names ->
                choose("Third-party notices", names, i ->
                    background(
                        "Opening license…",
                        () -> {
                            try (
                                InputStream input = getAssets().open(names.get(i));
                                ByteArrayOutputStream text = new ByteArrayOutputStream()
                            ) {
                                byte[] buffer = new byte[8192];
                                int count;
                                while ((count = input.read(buffer)) != -1) {
                                    if (text.size() + count > 256 * 1024) throw new IOException(
                                        "License file exceeds display limit"
                                    );
                                    text.write(buffer, 0, count);
                                }
                                return text.toString(StandardCharsets.UTF_8.name());
                            }
                        },
                        value -> textDialog(names.get(i), value)
                    )
                )
        );
    }

    private void appearanceSettings() {
        choose(
            "Appearance",
            List.of(
                "Theme: " + app.settings.string("theme"),
                "Font family: " + app.settings.string("fontFamily"),
                "Font size: " + app.settings.integer("fontSize"),
                "Syntax theme: " + app.settings.string("syntaxTheme"),
                "Word wrap: " + app.settings.bool("wordWrap"),
                "Minimap: " + app.settings.bool("minimap"),
                "UI scale: " + app.settings.integer("uiScale") + "%"
            ),
            i -> {
                if (i == 0) choose("Theme", List.of("system", "dark", "light"), n ->
                    setSetting("theme", List.of("system", "dark", "light").get(n))
                );
                else if (i == 1) choose("Font", List.of("monospace", "sans-serif", "serif"), n ->
                    setSetting("fontFamily", List.of("monospace", "sans-serif", "serif").get(n))
                );
                else if (i == 2) numberSetting("fontSize");
                else if (i == 3) choose("Syntax theme", List.of("warm", "cool", "monochrome"), n ->
                    setSetting("syntaxTheme", List.of("warm", "cool", "monochrome").get(n))
                );
                else if (i == 4) setSetting("wordWrap", !app.settings.bool("wordWrap"));
                else if (i == 5) setSetting("minimap", !app.settings.bool("minimap"));
                else numberSetting("uiScale");
            }
        );
    }

    private void numberSetting(String key) {
        input(key, String.valueOf(app.settings.integer(key)), value -> {
            try {
                setSetting(key, Integer.parseInt(value));
            } catch (NumberFormatException e) {
                error(new IllegalArgumentException("Enter a whole number"));
            }
        });
    }

    private void setSetting(String key, Object value) {
        try {
            app.settings.set(key, value, false);
        } catch (Exception e) {
            error(e);
            return;
        }
        background(
            "Saving settings…",
            () -> {
                app.settings.save(app.stateDir.resolve("settings.json"), false);
                return true;
            },
            v -> recreate()
        );
    }

    private void recoveryStatus() {
        background(
            "Inspecting recovery…",
            () -> {
                List<RecoveryStore.Entry> entries = app.recovery.recover();
                long bytes;
                try (java.util.stream.Stream<Path> files = Files.walk(app.projects)) {
                    bytes = files
                        .filter(Files::isRegularFile)
                        .mapToLong(p -> {
                            try {
                                return Files.size(p);
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .sum();
                }
                StringBuilder b = new StringBuilder(
                    "Private project storage: " +
                        bytes / 1024 +
                        " KiB\nRecovery journals: " +
                        entries.size() +
                        "\n\n"
                );
                for (RecoveryStore.Entry e : entries)
                    b
                        .append(e.project)
                        .append('/')
                        .append(e.path)
                        .append(" · ")
                        .append(e.text.length())
                        .append(" chars\n");
                b.append(
                    "\nRecovery is automatic when a project opens. Journals and failed-save copies are never silently deleted. Disk conflicts reject saves. Last unflushed keystrokes may be lost if Android kills the process before the recovery journal is durable.\n\nUse Export ZIP for a portable project backup. Dependencies, .git and trash are excluded."
                );
                return b.toString();
            },
            value -> textDialog("Storage & recovery", value)
        );
    }

    private void addCommand(String id, String title, String shortcut, Runnable action) {
        commands.register(new CommandRegistry.Command(id, title, shortcut, action, () -> true));
    }

    private void registerCommands() {
        addCommand("palette", "Show command palette", "Ctrl+Shift+P", this::palette);
        addCommand("open", "Quick open file", "Ctrl+P", this::quickOpen);
        addCommand("project", "Open / switch project", "", this::projectMenu);
        addCommand("new", "New file", "Ctrl+N", () -> newItem(""));
        addCommand("save", "Save all", "Ctrl+S", this::saveAll);
        addCommand("close", "Close active tab", "Ctrl+W", () -> {
            if (current() != null) closeDocument(current());
        });
        addCommand("find", "Find in document", "Ctrl+F", this::findDialog);
        addCommand("replace", "Replace in document", "Ctrl+H", this::findDialog);
        addCommand("search", "Search project / replace across files", "Ctrl+Shift+F", this::projectSearch);
        addCommand("line", "Go to line", "Ctrl+G", this::goToLine);
        addCommand("symbol", "Go to document symbol", "Ctrl+Shift+O", this::symbols);
        addCommand("definition", "Find local declaration", "F12", this::definition);
        addCommand("references", "Find textual references in project", "Shift+F12", this::references);
        addCommand("format", "Format JSON / clean whitespace", "Ctrl+Alt+L", this::format);
        addCommand("run", "Run current file", "F5", () -> startRun(false, null, null));
        addCommand("runConfigured", "Run project configuration", "", this::runConfigured);
        addCommand("build", "Build / syntax check", "Ctrl+Shift+B", this::build);
        addCommand("debug", "Debug current JavaScript file", "Shift+F5", () -> startRun(true, null, null));
        addCommand("terminal", "Toggle terminal", "Ctrl+`", () -> {
            if (panelVisible && panelName.equals("Terminal")) togglePanel(false);
            else showPanel("Terminal");
        });
        addCommand("stop", "Stop execution", "Shift+F2", this::stop);
        addCommand("split", "Split / unsplit editor", "Ctrl+\\", this::split);
        addCommand("undo", "Undo", "Ctrl+Z", () -> focused.undo());
        addCommand("redo", "Redo", "Ctrl+Shift+Z", () -> focused.redo());
        addCommand("copy", "Copy selection / line", "", () -> focused.copyText());
        addCommand("paste", "Paste", "", () -> focused.pasteText());
        addCommand("cut", "Cut selection / line", "", () -> focused.cutText());
        addCommand("selectAll", "Select all", "", () -> focused.selectAll());
        addCommand("comment", "Toggle line comments", "Ctrl+/", () -> editOperation("comment"));
        addCommand("duplicate", "Duplicate line", "Ctrl+D", () -> editOperation("duplicate"));
        addCommand("join", "Join lines", "", () -> editOperation("join"));
        addCommand("up", "Move line up", "Alt+Up", () -> editOperation("up"));
        addCommand("down", "Move line down", "Alt+Down", () -> editOperation("down"));
        addCommand("fold", "Folded document overview (read-only)", "", this::foldedOverview);
        addCommand("breakpoint", "Toggle breakpoint at cursor", "F9", this::toggleBreakpoint);
        addCommand("condition", "Conditional breakpoint", "", this::conditionalBreakpoint);
        addCommand("problems", "Open problems panel", "", () -> showPanel("Problems"));
        addCommand("git", "Open Git changes", "", () -> showPanel("Git"));
        addCommand("settings", "Open settings / change theme", "Ctrl+,", this::settingsDialog);
        addCommand("toolchains", "Toolchain / package manager availability", "", this::toolchains);
        addCommand("restore", "Restore deleted file", "", this::restoreTrash);
        addCommand("licenses", "Third-party notices", "", this::thirdPartyNotices);
    }

    private void palette() {
        LinearLayout box = column();
        box.setPadding(dp(10), dp(6), dp(10), dp(6));
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("> Type a command");
        box.addView(query);
        ListView list = new ListView(this);
        box.addView(list, new LinearLayout.LayoutParams(-1, dp(360)));
        List<CommandRegistry.Command> matches = new ArrayList<>();
        Runnable filter = () -> {
            matches.clear();
            matches.addAll(commands.search(query.getText().toString()));
            list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, matches));
        };
        filter.run();
        query.addTextChangedListener(watcher(filter));
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Command palette")
            .setView(box)
            .setNegativeButton("Close", null)
            .create();
        list.setOnItemClickListener((p, v, i, id) -> {
            CommandRegistry.Command c = matches.get(i);
            dialog.dismiss();
            commands.execute(c.id);
        });
        query.setOnEditorActionListener((v, id, event) -> {
            if (!matches.isEmpty()) {
                String command = matches.get(0).id;
                dialog.dismiss();
                commands.execute(command);
            }
            return true;
        });
        dialog.show();
        query.requestFocus();
    }

    private void mainMenu() {
        List<CommandRegistry.Command> all = commands.search("");
        choose(
            "Forge commands",
            all.stream().map(Object::toString).collect(java.util.stream.Collectors.toList()),
            i -> commands.execute(all.get(i).id)
        );
    }

    private final Map<String, String> shortcutOverrides = new HashMap<>();

    private void shortcutSettings() {
        Path file = app.stateDir.resolve("shortcuts.json");
        background(
            "Reading shortcuts…",
            () ->
                Files.exists(file)
                    ? com.forgeide.core.util.AtomicFiles.readUtf8(file)
                    : "{\n  \"save\": \"Ctrl+S\",\n  \"run\": \"F5\"\n}",
            json -> {
                EditText field = new EditText(this);
                field.setTypeface(Typeface.MONOSPACE);
                field.setText(json);
                field.setMinLines(7);
                new AlertDialog.Builder(this)
                    .setTitle("Shortcut overrides by command ID")
                    .setMessage(
                        "Examples: save, run, build, format, find, replace, terminal, palette, new, close. Use Ctrl+, Shift+, Alt+ and a key name."
                    )
                    .setView(field)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Save", (d, w) ->
                        background(
                            "Saving shortcuts…",
                            () -> {
                                Map<String, Object> values = Json.parseObject(field.getText().toString());
                                for (Object value : values.values())
                                    if (
                                        !(value instanceof String) || ((String) value).length() > 40
                                    ) throw new IOException("Invalid shortcut value");
                                AtomicFiles.write(file, Json.writePretty(values, 2));
                                return values;
                            },
                            values -> {
                                shortcutOverrides.clear();
                                values.forEach((k, v) -> shortcutOverrides.put(k, String.valueOf(v)));
                                toast("Shortcuts updated");
                            }
                        )
                    )
                    .show();
            }
        );
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (app != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (
                event.isCtrlPressed() &&
                event.getKeyCode() == KeyEvent.KEYCODE_TAB &&
                !app.documents.isEmpty()
            ) {
                int i = app.documents.indexOf(current());
                display(
                    app.documents.get(
                        (i + (event.isShiftPressed() ? -1 : 1) + app.documents.size()) % app.documents.size()
                    ),
                    false
                );
                return true;
            }
            String key = keyName(event);
            String chord =
                (event.isCtrlPressed() ? "Ctrl+" : "") +
                (event.isShiftPressed() ? "Shift+" : "") +
                (event.isAltPressed() ? "Alt+" : "") +
                key;
            for (CommandRegistry.Command c : commands.search(""))
                if (
                    !shortcutOverrides.getOrDefault(c.id, c.shortcut).isEmpty() &&
                    shortcutOverrides.getOrDefault(c.id, c.shortcut).equalsIgnoreCase(chord)
                ) {
                    commands.execute(c.id);
                    return true;
                }
        }
        return super.dispatchKeyEvent(event);
    }

    private String keyName(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_GRAVE:
                return "`";
            case KeyEvent.KEYCODE_BACKSLASH:
                return "\\";
            case KeyEvent.KEYCODE_SLASH:
                return "/";
            case KeyEvent.KEYCODE_COMMA:
                return ",";
            case KeyEvent.KEYCODE_DPAD_UP:
                return "Up";
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return "Down";
            default:
                String n = KeyEvent.keyCodeToString(e.getKeyCode()).replace("KEYCODE_", "");
                return n.length() == 1 ? n.toUpperCase(Locale.ROOT) : n;
        }
    }

    private void back() {
        if (sidebarVisible && !wide) {
            toggleSidebar();
            return;
        }
        if (panelVisible) {
            togglePanel(false);
            return;
        }
        if (app.isRunning()) {
            new AlertDialog.Builder(this)
                .setTitle("Stop running code and leave?")
                .setNegativeButton("Keep working", null)
                .setPositiveButton("Stop and leave", (d, w) -> {
                    stop();
                    leave();
                })
                .show();
        } else leave();
    }

    private void leave() {
        fileChange(
            () -> {
                for (ForgeApplication.OpenDocument d : app.documents) app.journal(d);
                app.persistSession();
                return true;
            },
            v -> finish()
        );
    }

    @Override
    public void onBackPressed() {
        back();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (app != null && app.initialized) {
            app.main.removeCallbacks(autosave);
            app.main.postDelayed(autosave, app.settings.integer("autosaveSeconds") * 1000L);
            Path file = app.stateDir.resolve("shortcuts.json");
            app.io.execute(() -> {
                try {
                    if (Files.exists(file)) {
                        Map<String, Object> values = Json.parseObject(
                            com.forgeide.core.util.AtomicFiles.readUtf8(file)
                        );
                        app.main.post(() -> {
                            shortcutOverrides.clear();
                            values.forEach((k, v) -> shortcutOverrides.put(k, String.valueOf(v)));
                        });
                    }
                } catch (Exception e) {
                    app.log("Shortcut settings: " + e.getMessage());
                }
            });
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (app != null && app.initialized) {
            app.main.removeCallbacks(autosave);
            persistTerminals();
            app.io.execute(() -> {
                try {
                    for (ForgeApplication.OpenDocument d : app.documents) app.journal(d);
                    app.persistSession();
                } catch (IOException e) {
                    app.log("Session persistence: " + e.getMessage());
                }
            });
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("pendingExportPath", pendingExportPath);
        state.putString("primary", primaryDoc == null ? "" : primaryDoc.path);
        state.putString("secondary", secondaryDoc == null ? "" : secondaryDoc.path);
        state.putBoolean("focusRight", focused == secondary && secondary != null);
        state.putString("panel", panelName);
        state.putBoolean("panelVisible", panelVisible);
        state.putBoolean("sidebar", sidebarVisible);
        state.putInt("terminal", terminalIndex);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (app != null) {
            app.observers.remove(observer);
            app.main.removeCallbacks(autosave);
            app.main.removeCallbacks(diagnosticTask);
            if (isFinishing() && app.execution != null) app.execution.cancel();
        }
        analysis.shutdownNow();
        if (primary != null) primary.release();
        if (secondary != null) secondary.release();
        super.onDestroy();
    }
}
