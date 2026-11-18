/** App shell — layout, global shortcuts, theme, toasts, overlays. */
import { useEffect, useState } from 'react';
import { store, useApp } from './state/store';
import { bus } from './core/bus';
import { AppBar } from './ui/AppBar';
import { Sidebar } from './ui/Sidebar';
import { Tabs } from './ui/Tabs';
import { CodeEditor } from './editor/CodeEditor';
import { BottomPanel } from './ui/BottomPanel';
import { StatusBar } from './ui/StatusBar';
import { KeyboardBar } from './ui/KeyboardBar';
import { CommandPalette } from './ui/CommandPalette';
import { SettingsModal } from './ui/SettingsModal';
import { ProjectsModal } from './ui/ProjectsModal';
import { PreviewOverlay } from './ui/Preview';

interface Toast { id: number; text: string; kind: string; }
let toastId = 0;

export default function App() {
  const settings = useApp((s) => s.settings);
  const ready = useApp((s) => s.ready);
  const [toasts, setToasts] = useState<Toast[]>([]);

  // theme + ui scale
  useEffect(() => {
    const dark = settings.theme === 'dark' || (settings.theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
    document.body.classList.toggle('theme-dark', dark);
    document.body.classList.toggle('theme-light', !dark);
    document.body.style.setProperty('--ui-scale', String(settings.uiScale));
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => {
      if (store.state.settings.theme === 'system') {
        document.body.classList.toggle('theme-dark', mq.matches);
        document.body.classList.toggle('theme-light', !mq.matches);
      }
    };
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, [settings.theme, settings.uiScale]);

  // toast events
  useEffect(() => {
    return bus.on('toast', ({ text, kind }) => {
      const id = ++toastId;
      setToasts((t) => [...t, { id, text, kind: kind ?? 'info' }]);
      setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 3800);
    });
  }, []);

  // global shortcuts (physical keyboards — req. 15)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const mod = e.ctrlKey || e.metaKey;
      if (mod && e.key.toLowerCase() === 'k') { e.preventDefault(); store.set({ paletteOpen: !store.state.paletteOpen }); }
      else if (mod && e.key === '`') { e.preventDefault(); store.set({ bottomOpen: true, bottomTab: 'terminal' }); }
      else if (mod && e.key.toLowerCase() === 's') { e.preventDefault(); store.saveAll(); }
      else if (mod && e.key.toLowerCase() === 'n') { e.preventDefault(); const n = prompt('New file name:'); if (n) store.createFile('/', n); }
      else if (mod && e.key.toLowerCase() === 'w') { e.preventDefault(); if (store.state.activeFile) store.closeFile(store.state.activeFile); }
      else if (mod && e.key === 'Tab') { e.preventDefault(); const s = store.state; if (s.openFiles.length > 1 && s.activeFile) { const i = s.openFiles.indexOf(s.activeFile); store.setActiveFile(s.openFiles[(i + 1) % s.openFiles.length]); } }
      else if (e.key === 'Escape') { store.set({ paletteOpen: false, settingsOpen: false, newProjectOpen: false }); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  // initial lint + boot splash removal
  useEffect(() => {
    if (!ready) return;
    document.getElementById('boot')?.remove();
    const t = setTimeout(() => { store.lintWorkspace(); if (store.state.activeFile == null && store.state.openFiles.length === 0) { const p = store.project(); const first = p && Object.keys(p.files).find((f) => f.endsWith('.js')); if (first) store.openFile(first); } }, 300);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready]);

  if (!ready) return null;

  return (
    <div className="ide">
      <AppBar />
      <div className="ide-body">
        <Sidebar />
        <div className="ide-center">
          <Tabs />
          <div className="editor-zone">
            <CodeEditor />
          </div>
          <BottomPanel />
          <KeyboardBar />
        </div>
      </div>
      <StatusBar />
      <CommandPalette />
      <SettingsModal />
      <ProjectsModal />
      <PreviewOverlay />
      <div className="toasts">
        {toasts.map((t) => <div key={t.id} className={'toast ' + t.kind}>{t.text}</div>)}
      </div>
    </div>
  );
}
