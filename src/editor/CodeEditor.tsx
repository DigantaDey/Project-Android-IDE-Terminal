/**
 * Professional code editor surface (req. 2).
 * CodeMirror 6 with: language modes, linting, completion, folding,
 * breakpoints in the gutter, debug-line highlight, keybindings, minimap.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import CodeMirror, { type ReactCodeMirrorRef } from '@uiw/react-codemirror';
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, Decoration, type DecorationSet, gutter, GutterMarker } from '@codemirror/view';
import { EditorState, StateField, StateEffect, RangeSet, type Extension } from '@codemirror/state';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { searchKeymap, highlightSelectionMatches, openSearchPanel, closeSearchPanel } from '@codemirror/search';
import { autocompletion, completeAnyWord, type CompletionContext, type CompletionResult } from '@codemirror/autocomplete';
import { bracketMatching, indentUnit, foldGutter, codeFolding, syntaxHighlighting, defaultHighlightStyle, HighlightStyle } from '@codemirror/language';
import { lintGutter, setDiagnostics, type Diagnostic as CMDiagnostic } from '@codemirror/lint';
import { oneDark } from '@codemirror/theme-one-dark';
import { tags } from '@lezer/highlight';
import { store, useApp } from '../state/store';
import { languageById } from '../core/languages';
import { bus } from '../core/bus';
import { Minimap } from './Minimap';
import * as vfs from '../core/vfs';

// ---- breakpoint gutter ----
const setBpMarks = StateEffect.define<RangeSet<GutterMarker>>();

class BreakpointMarker extends GutterMarker {
  constructor(readonly enabled: boolean) { super(); }
  override toDOM() {
    const d = document.createElement('div');
    d.className = 'cm-bp' + (this.enabled ? '' : ' cm-bp-off');
    return d;
  }
}

const breakpointField = StateField.define<RangeSet<GutterMarker>>({
  create: () => RangeSet.empty,
  update(marks, tr) {
    marks = marks.map(tr.changes);
    for (const e of tr.effects) if (e.is(setBpMarks)) marks = e.value;
    return marks;
  },
});

const setDebugLine = StateEffect.define<number | null>();
const debugLineField = StateField.define<DecorationSet>({
  create: () => Decoration.none,
  update(deco, tr) {
    deco = deco.map(tr.changes);
    for (const e of tr.effects) {
      if (e.is(setDebugLine)) {
        if (e.value == null) { deco = Decoration.none; continue; }
        const line = tr.state.doc.line(Math.min(e.value, tr.state.doc.lines));
        deco = Decoration.set([Decoration.line({ class: 'cm-debug-line' }).range(line.from)]);
      }
    }
    return deco;
  },
  provide: (f) => EditorView.decorations.from(f),
});

const lightHighlight = HighlightStyle.define([
  { tag: tags.keyword, color: '#7c3aed' },
  { tag: tags.string, color: '#067d17' },
  { tag: tags.comment, color: '#8c8c8c', fontStyle: 'italic' },
  { tag: tags.number, color: '#1750eb' },
  { tag: tags.function(tags.variableName), color: '#795e26' },
  { tag: tags.typeName, color: '#267f99' },
]);

export function getEditorView(): EditorView | null { return currentView; }
let currentView: EditorView | null = null;

export function insertIntoEditor(text: string) {
  const view = currentView;
  if (!view) return;
  const { from, to } = view.state.selection.main;
  view.dispatch({ changes: { from, to, insert: text }, selection: { anchor: from + text.length }, scrollIntoView: true });
  view.focus();
}

export function CodeEditor() {
  const activeFile = useApp((s) => s.activeFile);
  const settings = useApp((s) => s.settings);
  const breakpoints = useApp((s) => s.breakpoints);
  const diagnostics = useApp((s) => s.diagnostics);
  const debug = useApp((s) => s.debug);
  const project = useApp((s) => s.projects.find((p) => p.id === s.activeProjectId));
  const draft = useApp((s) => (s.activeFile ? s.drafts[s.activeFile] : undefined));
  const ref = useRef<ReactCodeMirrorRef>(null);
  const [langExt, setLangExt] = useState<Extension | null>(null);

  const file = activeFile ? project?.files[activeFile] : undefined;
  const lang = languageById(file?.language ?? 'plaintext');
  const value = draft ?? file?.content ?? '';
  const isDark = settings.theme === 'dark' || (settings.theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);

  // dynamic language loading
  useEffect(() => {
    let cancelled = false;
    setLangExt(null);
    if (lang.cm) {
      lang.cm().then((ext) => { if (!cancelled) setLangExt(() => ext); }).catch(() => setLangExt(null));
    }
    return () => { cancelled = true; };
  }, [lang.id]);

  // breakpoint markers refresh
  useEffect(() => {
    const view = ref.current?.view;
    if (!view || !activeFile) return;
    const marks = breakpoints
      .filter((b) => b.file === activeFile && b.line <= view.state.doc.lines)
      .map((b) => new BreakpointMarker(b.enabled).range(view.state.doc.line(b.line).from))
      .sort((a, b) => a.from - b.from);
    view.dispatch({ effects: setBpMarks.of(RangeSet.of(marks)) });
  }, [breakpoints, activeFile, value]);

  // debug line highlight
  useEffect(() => {
    const view = ref.current?.view;
    if (!view) return;
    if (debug.status === 'paused' && debug.file === activeFile && debug.line) {
      view.dispatch({ effects: setDebugLine.of(debug.line) });
    } else {
      view.dispatch({ effects: setDebugLine.of(null) });
    }
  }, [debug.status, debug.line, debug.file, activeFile]);

  // push diagnostics into CM
  useEffect(() => {
    const view = ref.current?.view;
    if (!view || !activeFile) return;
    const mine = diagnostics.filter((d) => d.file === activeFile);
    const cmDiags: CMDiagnostic[] = mine
      .filter((d) => d.line <= view.state.doc.lines)
      .map((d) => {
        const line = view.state.doc.line(Math.max(1, d.line));
        const from = Math.min(line.from + Math.max(0, d.col - 1), line.to);
        return { from, to: Math.min(from + 1, line.to), severity: d.severity, message: d.message, source: d.source };
      });
    view.dispatch(setDiagnostics(view.state, cmDiags));
  }, [diagnostics, activeFile, value]);

  // reveal events (go-to-line from problems/search)
  useEffect(() => {
    return bus.on('editor-reveal', ({ line, col }) => {
      const view = ref.current?.view;
      if (!view) return;
      const ln = Math.min(line, view.state.doc.lines);
      const pos = view.state.doc.line(ln).from + (col ? col - 1 : 0);
      view.dispatch({ selection: { anchor: pos }, effects: EditorView.scrollIntoView(pos, { y: 'center' }) });
      view.focus();
    });
  }, []);

  const completions = useMemo(() => {
    const kws = lang.keywords ?? [];
    return (ctx: CompletionContext): CompletionResult | null => {
      const word = ctx.matchBefore(/[\w$]+/);
      if (!word || (word.from === word.to && !ctx.explicit)) return null;
      return { from: word.from, options: kws.map((k) => ({ label: k, type: 'keyword', boost: 1 })) };
    };
  }, [lang.id]);

  const extensions = useMemo(() => {
    const exts: Extension[] = [
      lineNumbers(),
      history(),
      foldGutter(),
      codeFolding(),
      highlightActiveLine(),
      highlightActiveLineGutter(),
      bracketMatching(),
      highlightSelectionMatches(),
      indentUnit.of(settings.useTabs ? '\t' : ' '.repeat(settings.tabSize)),
      lintGutter(),
      breakpointField,
      debugLineField,
      gutter({
        class: 'cm-bp-gutter',
        markers: (view) => view.state.field(breakpointField),
        initialSpacer: () => new BreakpointMarker(true),
        domEventHandlers: {
          mousedown: (view, line) => {
            if (!activeFile) return false;
            const ln = view.state.doc.lineAt(line.from).number;
            store.toggleBreakpoint(activeFile, ln);
            return true;
          },
        },
      }),
      autocompletion({ override: [completions, completeAnyWord], activateOnTyping: settings.autocomplete }),
      keymap.of([
        { key: 'Mod-s', run: () => { store.saveAll(); return true; } },
        { key: 'Mod-Shift-f', run: () => { void store.formatFile(); return true; } },
        { key: 'Mod-r', run: () => { void store.runActive(false); return true; } },
        { key: 'Mod-Shift-d', run: () => { void store.runActive(true); return true; } },
        { key: 'Mod-f', run: (v) => { openSearchPanel(v); return true; } },
        { key: 'Escape', run: (v) => closeSearchPanel(v) },
        ...defaultKeymap, ...historyKeymap, ...searchKeymap, indentWithTab,
      ]),
      EditorView.updateListener.of((u) => {
        if (u.docChanged && activeFile) {
          store.editActive(u.state.doc.toString());
          // live lint (debounced per language)
          debounceLint(activeFile);
        }
      }),
      EditorView.theme({
        '&': { fontSize: settings.fontSize + 'px', height: '100%' },
        '.cm-scroller': { fontFamily: settings.fontFamily, lineHeight: '1.55' },
        '.cm-content': { paddingBottom: '40vh' },
        '.cm-gutters': { borderRight: 'none' },
      }),
      settings.wordWrap ? EditorView.lineWrapping : [],
      isDark ? syntaxHighlighting(defaultHighlightStyle, { fallback: true }) : syntaxHighlighting(lightHighlight, { fallback: true }),
    ];
    if (langExt) exts.unshift(langExt);
    return exts;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [langExt, settings.fontSize, settings.fontFamily, settings.tabSize, settings.useTabs, settings.wordWrap, settings.autocomplete, activeFile, isDark, completions]);

  if (!activeFile || !file) {
    return (
      <div className="editor-empty">
        <div className="editor-empty-logo">⌘</div>
        <h2>Forge IDE</h2>
        <p>Open a file from the Explorer, or press <kbd>Ctrl</kbd>+<kbd>K</kbd> for the command palette.</p>
        <div className="editor-empty-hints">
          <span>▶ Ctrl+R — run</span><span>🐞 click gutter — breakpoint</span><span>⌨ coding bar below</span>
        </div>
      </div>
    );
  }

  return (
    <div className="editor-wrap">
      <div className="breadcrumb">
        {activeFile.split('/').filter(Boolean).map((seg, i, arr) => (
          <span key={i} className={i === arr.length - 1 ? 'crumb current' : 'crumb'}>{seg}{i < arr.length - 1 ? ' › ' : ''}</span>
        ))}
        <span className="breadcrumb-lang" style={{ color: lang.color }}>{lang.name}</span>
      </div>
      <div className="editor-main">
        <CodeMirror
          ref={ref}
          value={value}
          theme={isDark ? oneDark : 'light'}
          extensions={extensions}
          onCreateEditor={(view) => { currentView = view; }}
          basicSetup={false}
        />
        {settings.minimap && <Minimap getView={() => ref.current?.view ?? null} />}
      </div>
    </div>
  );
}

const lintTimers: Record<string, ReturnType<typeof setTimeout>> = {};
function debounceLint(path: string) {
  clearTimeout(lintTimers[path]);
  lintTimers[path] = setTimeout(() => store.lintFile(path), 700);
}
