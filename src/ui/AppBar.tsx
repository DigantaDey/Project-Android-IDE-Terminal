/** Top app bar: brand, active file, run/debug controls, palette, settings. */
import { PanelLeft, Play, Bug, Square, Sparkles, Settings as SettingsIcon, FolderOpen, Save, Sun, Moon } from 'lucide-react';
import { store, useApp } from '../state/store';
import * as vfs from '../core/vfs';
import { languageById } from '../core/languages';

export function AppBar() {
  const activeFile = useApp((s) => s.activeFile);
  const dirty = useApp((s) => s.dirty);
  const running = useApp((s) => s.running);
  const sidebarOpen = useApp((s) => s.sidebarOpen);
  const project = useApp((s) => s.projects.find((p) => p.id === s.activeProjectId));
  const settings = useApp((s) => s.settings);
  const debug = useApp((s) => s.debug);

  const lang = activeFile ? languageById(project?.files[activeFile]?.language ?? 'plaintext') : null;

  return (
    <header className="appbar">
      <button className={'iconbtn' + (sidebarOpen ? ' active' : '')} title="Toggle sidebar" onClick={() => store.set({ sidebarOpen: !sidebarOpen })}>
        <PanelLeft size={18} />
      </button>
      <div className="brand">
        <div className="logo">⌘</div>
        <span className="brand-name">Forge</span>
      </div>
      <div className="file-title">
        {activeFile ? (
          <>
            {vfs.basename(activeFile)}
            {dirty.includes(activeFile) && <span className="dirty-dot"> ●</span>}
            <span style={{ color: 'var(--text-faint)' }}> — {project?.name}</span>
          </>
        ) : (
          <span style={{ color: 'var(--text-faint)' }}>{project?.name ?? 'No project'}</span>
        )}
      </div>
      <button className="iconbtn" title="Save all (Ctrl+S)" onClick={() => store.saveAll()}><Save size={17} /></button>
      {running ? (
        <button className="iconbtn stop" title="Stop process" onClick={() => store.stopRunning()}><Square size={16} /></button>
      ) : (
        <>
          <button className="iconbtn run" title="Run (Ctrl+R)" onClick={() => void store.runActive(false)} disabled={!activeFile && !project}><Play size={17} /></button>
          <button
            className="iconbtn debug"
            title="Debug (Ctrl+Shift+D)"
            onClick={() => void store.runActive(true)}
            disabled={!lang || (lang.id !== 'javascript' && lang.id !== 'typescript')}
          >
            <Bug size={17} />
          </button>
        </>
      )}
      <button className="iconbtn" title="Command palette (Ctrl+K)" onClick={() => store.set({ paletteOpen: true })}><Sparkles size={17} /></button>
      <button
        className="iconbtn"
        title={`Theme: ${settings.theme}`}
        onClick={() => store.set({ settings: { ...settings, theme: settings.theme === 'dark' ? 'light' : 'dark' } })}
      >
        {settings.theme === 'dark' ? <Moon size={16} /> : <Sun size={16} />}
      </button>
      <button className="iconbtn" title="Settings" onClick={() => store.set({ settingsOpen: true })}><SettingsIcon size={17} /></button>
      <button className="iconbtn" title="Projects" onClick={() => store.set({ newProjectOpen: true })}><FolderOpen size={17} /></button>
      {debug.status === 'paused' && <span className="chip" style={{ color: 'var(--warn)' }}>paused :{debug.line}</span>}
    </header>
  );
}
