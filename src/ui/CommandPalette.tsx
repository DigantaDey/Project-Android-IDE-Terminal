/** Searchable command palette (req. 16) — Ctrl+K. */
import { useEffect, useMemo, useRef, useState } from 'react';
import { store, useApp } from '../state/store';
import * as vfs from '../core/vfs';

interface Cmd { id: string; title: string; keys?: string; cat?: string; run: () => void; }

export function CommandPalette() {
  const open = useApp((s) => s.paletteOpen);
  const project = useApp((s) => s.projects.find((p) => p.id === s.activeProjectId));
  const [query, setQuery] = useState('');
  const [sel, setSel] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (open) { setQuery(''); setSel(0); setTimeout(() => inputRef.current?.focus(), 30); }
  }, [open]);

  const commands = useMemo<Cmd[]>(() => {
    const s = store;
    const list: Cmd[] = [
      { id: 'save', title: 'File: Save all', keys: 'Ctrl+S', cat: 'file', run: () => s.saveAll() },
      { id: 'run', title: 'Run: Run active file', keys: 'Ctrl+R', cat: 'run', run: () => void s.runActive(false) },
      { id: 'debug', title: 'Run: Start debugging', keys: 'Ctrl+Shift+D', cat: 'run', run: () => void s.runActive(true) },
      { id: 'stop', title: 'Run: Stop process', cat: 'run', run: () => s.stopRunning() },
      { id: 'format', title: 'File: Format document', keys: 'Ctrl+Shift+F', cat: 'file', run: () => void s.formatFile() },
      { id: 'lint', title: 'Analyze: Lint workspace', cat: 'analyze', run: () => s.lintWorkspace() },
      { id: 'terminal', title: 'View: Toggle terminal', keys: 'Ctrl+`', cat: 'view', run: () => s.set({ bottomOpen: true, bottomTab: 'terminal' }) },
      { id: 'newterm', title: 'Terminal: New session', cat: 'terminal', run: () => s.newTerminal() },
      { id: 'output', title: 'View: Toggle output', cat: 'view', run: () => s.set({ bottomOpen: true, bottomTab: 'output' }) },
      { id: 'problems', title: 'View: Toggle problems', cat: 'view', run: () => s.set({ bottomOpen: true, bottomTab: 'problems' }) },
      { id: 'explorer', title: 'View: Explorer', cat: 'view', run: () => s.set({ sidebarOpen: true, sidebarPanel: 'explorer' }) },
      { id: 'search', title: 'View: Project search', cat: 'view', run: () => s.set({ sidebarOpen: true, sidebarPanel: 'search' }) },
      { id: 'gitp', title: 'View: Source control', cat: 'view', run: () => s.set({ sidebarOpen: true, sidebarPanel: 'git' }) },
      { id: 'toolchains', title: 'View: Toolchains', cat: 'view', run: () => s.set({ sidebarOpen: true, sidebarPanel: 'toolchains' }) },
      { id: 'theme', title: 'Preferences: Toggle theme', cat: 'prefs', run: () => { const st = s.state.settings; s.set({ settings: { ...st, theme: st.theme === 'dark' ? 'light' : 'dark' } }); } },
      { id: 'settings', title: 'Preferences: Open settings', cat: 'prefs', run: () => s.set({ settingsOpen: true }) },
      { id: 'projects', title: 'File: Project manager', cat: 'file', run: () => s.set({ newProjectOpen: true }) },
      { id: 'newfile', title: 'File: New file', keys: 'Ctrl+N', cat: 'file', run: () => { const name = prompt('File name (e.g. src/util.js):'); if (name) s.createFile('/', name.replace(/^\//, '')); } },
      { id: 'preview', title: 'Run: Open HTML/Markdown preview', cat: 'run', run: () => s.openPreview() },
      { id: 'gitinit', title: 'Git: Initialize repository', cat: 'git', run: () => s.gitInit() },
      { id: 'gitc', title: 'Git: Commit all', cat: 'git', run: () => { const m = prompt('Commit message:'); if (m) s.gitCommit(m); } },
    ];
    if (project) {
      for (const path of vfs.allFiles(project).slice(0, 200)) {
        list.push({ id: 'open:' + path, title: `Open ${path}`, cat: 'files', run: () => s.openFile(path) });
      }
    }
    return list;
  }, [project]);

  const filtered = useMemo(() => {
    const q = query.toLowerCase().trim();
    if (!q) return commands;
    return commands
      .map((c) => ({ c, score: score(c.title.toLowerCase(), q) }))
      .filter((x) => x.score > 0)
      .sort((a, b) => b.score - a.score)
      .slice(0, 40)
      .map((x) => x.c);
  }, [commands, query]);

  useEffect(() => { setSel(0); }, [query]);

  if (!open) return null;

  const exec = (c: Cmd) => { store.set({ paletteOpen: false }); c.run(); };

  return (
    <div className="overlay" onClick={() => store.set({ paletteOpen: false })}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
        <input
          ref={inputRef} className="palette-input" placeholder="Type a command or file name…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown') { e.preventDefault(); setSel(Math.min(sel + 1, filtered.length - 1)); }
            else if (e.key === 'ArrowUp') { e.preventDefault(); setSel(Math.max(0, sel - 1)); }
            else if (e.key === 'Enter' && filtered[sel]) exec(filtered[sel]);
            else if (e.key === 'Escape') store.set({ paletteOpen: false });
          }}
        />
        <div className="palette-list">
          {filtered.length === 0 && <div className="empty-state">No matching commands.</div>}
          {filtered.map((c, i) => (
            <div key={c.id} className={'palette-item' + (i === sel ? ' selected' : '')} onMouseEnter={() => setSel(i)} onClick={() => exec(c)}>
              <span className="cat">{c.cat}</span>
              <span>{c.title}</span>
              {c.keys && <span className="keys">{c.keys}</span>}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function score(text: string, q: string): number {
  if (text.includes(q)) return 100 - text.indexOf(q);
  // subsequence match
  let ti = 0;
  for (const ch of q) {
    const idx = text.indexOf(ch, ti);
    if (idx < 0) return 0;
    ti = idx + 1;
  }
  return 30 - (ti - q.length);
}
