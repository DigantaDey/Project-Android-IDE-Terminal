/** On-screen coding key bar — symbols that are painful on mobile keyboards (req. 15). */
import { insertIntoEditor, getEditorView } from '../editor/CodeEditor';
import { useApp } from '../state/store';

const SYMBOLS = ['{', '}', '[', ']', '(', ')', '<', '>', ';', ':', '/', '\\', '|', '&', '*', '#', '$', '@', '_', '=', '+', '-', '"', "'", '`', '!', '?', '~', '%', '^'];

function pair(ch: string): string | null {
  const map: Record<string, string> = { '{': '{}', '[': '[]', '(': '()', '<': '<>', '"': '""', "'": "''", '`': '``' };
  return map[ch] ?? null;
}

export function KeyboardBar() {
  const enabled = useApp((s) => s.settings.keyboardBar);
  const activeFile = useApp((s) => s.activeFile);
  if (!enabled) return null;

  const press = (sym: string) => {
    const view = getEditorView();
    if (!view) return;
    const p = pair(sym);
    const sel = view.state.selection.main;
    if (p && !sel.empty) {
      // surround selection
      const text = view.state.sliceDoc(sel.from, sel.to);
      view.dispatch({
        changes: { from: sel.from, to: sel.to, insert: p[0] + text + p[1] },
        selection: { anchor: sel.from + 1, head: sel.from + 1 + text.length },
      });
      view.focus();
    } else if (p) {
      insertIntoEditor(p);
      const v2 = getEditorView();
      if (v2) {
        const pos = v2.state.selection.main.from;
        v2.dispatch({ selection: { anchor: pos - 1 } });
      }
    } else {
      insertIntoEditor(sym);
    }
  };

  const move = (dir: 'left' | 'right' | 'up' | 'down') => {
    const view = getEditorView();
    if (!view) return;
    view.focus();
    const pos = view.state.selection.main.from;
    if (dir === 'left') view.dispatch({ selection: { anchor: Math.max(0, pos - 1) } });
    else if (dir === 'right') view.dispatch({ selection: { anchor: Math.min(view.state.doc.length, pos + 1) } });
    else {
      const line = view.state.doc.lineAt(pos);
      const target = dir === 'up' ? Math.max(1, line.number - 1) : Math.min(view.state.doc.lines, line.number + 1);
      const col = pos - line.from;
      const tl = view.state.doc.line(target);
      view.dispatch({ selection: { anchor: Math.min(tl.from + col, tl.to) }, scrollIntoView: true });
    }
  };

  return (
    <div className="kbar" style={{ display: activeFile ? undefined : 'none' }}>
      <button className="wide" onClick={() => insertIntoEditor('\t')} title="Tab">⇥</button>
      <button className="wide" onClick={() => move('left')}>←</button>
      <button className="wide" onClick={() => move('down')}>↓</button>
      <button className="wide" onClick={() => move('up')}>↑</button>
      <button className="wide" onClick={() => move('right')}>→</button>
      {SYMBOLS.map((s) => (
        <button key={s} onClick={() => press(s)}>{s}</button>
      ))}
    </div>
  );
}
