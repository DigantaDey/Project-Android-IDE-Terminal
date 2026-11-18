/** Status bar: branch, problems, cursor, language, indentation, sandbox state (req. 12). */
import { useEffect, useState } from 'react';
import { GitBranch, XCircle, AlertTriangle, WifiOff, ShieldCheck } from 'lucide-react';
import { store, useApp } from '../state/store';
import { getEditorView } from '../editor/CodeEditor';
import { languageById } from '../core/languages';

export function StatusBar() {
  const project = useApp((s) => s.projects.find((p) => p.id === s.activeProjectId));
  const activeFile = useApp((s) => s.activeFile);
  const diagnostics = useApp((s) => s.diagnostics);
  const settings = useApp((s) => s.settings);
  const running = useApp((s) => s.running);
  const [cursor, setCursor] = useState({ line: 1, col: 1 });

  useEffect(() => {
    const id = setInterval(() => {
      const v = getEditorView();
      if (!v) return;
      const pos = v.state.selection.main.from;
      const line = v.state.doc.lineAt(pos);
      const next = { line: line.number, col: pos - line.from + 1 };
      setCursor((c) => (c.line === next.line && c.col === next.col ? c : next));
    }, 250);
    return () => clearInterval(id);
  }, []);

  const errors = diagnostics.filter((d) => d.severity === 'error').length;
  const warnings = diagnostics.filter((d) => d.severity === 'warning').length;
  const lang = activeFile ? languageById(project?.files[activeFile]?.language ?? 'plaintext') : null;

  return (
    <footer className="statusbar">
      {project?.git?.initialized && (
        <button className="status-item" onClick={() => store.set({ sidebarOpen: true, sidebarPanel: 'git' })}>
          <GitBranch size={11} /> {project.git.HEAD}
        </button>
      )}
      <button className="status-item" onClick={() => store.set({ bottomOpen: true, bottomTab: 'problems' })}>
        <XCircle size={11} className="err" /> <span className={errors ? 'err' : ''}>{errors}</span>
        <AlertTriangle size={11} className="warn" style={{ marginLeft: 4 }} /> <span className={warnings ? 'warn' : ''}>{warnings}</span>
      </button>
      {running && <span className="status-item" style={{ color: 'var(--ok)' }}>● {running.label}</span>}
      <span className="spacer" />
      <span className="status-item" title="Network disabled in sandbox"><WifiOff size={11} /> <ShieldCheck size={11} /> sandbox</span>
      {activeFile && (
        <span className="status-item mono">Ln {cursor.line}, Col {cursor.col}</span>
      )}
      <button className="status-item" onClick={() => store.set({ settingsOpen: true })}>
        {settings.useTabs ? 'Tabs' : `Spaces: ${settings.tabSize}`}
      </button>
      {lang && <span className="status-item" style={{ color: lang.color }}>{lang.name}</span>}
    </footer>
  );
}
