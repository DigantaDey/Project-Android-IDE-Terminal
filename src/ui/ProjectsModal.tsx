/** Project manager: create/switch/delete/import/export (req. 3, 13). */
import { useRef, useState } from 'react';
import { X, FolderPlus, FolderOpen, Trash2, Download, Upload } from 'lucide-react';
import { store, useApp } from '../state/store';
import JSZip from 'jszip';

type Template = 'empty' | 'javascript' | 'web' | 'python';

const TEMPLATES: { id: Template; label: string; desc: string }[] = [
  { id: 'javascript', label: 'JavaScript', desc: 'Runs on-device immediately' },
  { id: 'web', label: 'Web (HTML/CSS/JS)', desc: 'Live preview sandbox' },
  { id: 'python', label: 'Python', desc: 'Editing now; Pyodide plugin to run' },
  { id: 'empty', label: 'Empty', desc: 'Start from scratch' },
];

export function ProjectsModal() {
  const open = useApp((s) => s.newProjectOpen);
  const projects = useApp((s) => s.projects);
  const activeId = useApp((s) => s.activeProjectId);
  const [name, setName] = useState('my-project');
  const [template, setTemplate] = useState<Template>('javascript');
  const fileRef = useRef<HTMLInputElement>(null);

  if (!open) return null;

  const importZip = async (file: File) => {
    try {
      const zip = await JSZip.loadAsync(file);
      const entries = Object.entries(zip.files).filter(([, f]) => !f.dir);
      if (!entries.length) return;
      const projName = file.name.replace(/\.zip$/i, '') || 'imported-project';
      store.createProject(projName, 'empty');
      for (const [path, entry] of entries) {
        const clean = '/' + path.replace(/^\//, '');
        if (clean.split('/').some((seg) => seg === '__MACOSX' || seg.startsWith('.'))) continue;
        const text = await entry.async('string').catch(() => null);
        if (text == null || text.length > 1024 * 1024) continue;
        // ensure folders
        const parts = clean.split('/').filter(Boolean);
        let dir = '/';
        for (let i = 0; i < parts.length - 1; i++) {
          store.createFolder(dir, parts[i]);
          dir = dir === '/' ? '/' + parts[i] : dir + '/' + parts[i];
        }
        store.createFile(dir, parts[parts.length - 1], text);
      }
      // don't leave dozens of imported files open in tabs
      store.set({ openFiles: [], activeFile: null });
    } catch (e) {
      alert('Import failed: ' + (e instanceof Error ? e.message : String(e)));
    }
  };

  return (
    <div className="overlay" onClick={() => store.set({ newProjectOpen: false })}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
        <div className="sheet-header">
          Projects
          <button className="iconbtn" onClick={() => store.set({ newProjectOpen: false })}><X size={17} /></button>
        </div>
        <div className="sheet-body">
          <div className="field-label">Open project</div>
          {projects.map((p) => (
            <div key={p.id} className={'git-status-row' + (p.id === activeId ? ' active' : '')} onClick={() => { store.switchProject(p.id); store.set({ newProjectOpen: false }); }}>
              <FolderOpen size={14} style={{ color: p.id === activeId ? 'var(--accent)' : 'var(--text-faint)' }} />
              <span style={{ flex: 1 }}>{p.name}</span>
              <span className="chip">{Object.keys(p.files).length} files</span>
              {projects.length > 1 && (
                <button className="iconbtn" style={{ width: 26, height: 26 }} onClick={(e) => { e.stopPropagation(); if (confirm(`Delete project '${p.name}'?`)) store.deleteProject(p.id); }}>
                  <Trash2 size={13} />
                </button>
              )}
            </div>
          ))}

          <div className="field-label">New project</div>
          <input className="textfield" value={name} onChange={(e) => setName(e.target.value)} placeholder="project name" />
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, marginTop: 8 }}>
            {TEMPLATES.map((t) => (
              <button
                key={t.id}
                className="btn"
                style={{
                  flexDirection: 'column', alignItems: 'flex-start', gap: 2, padding: '10px 12px', height: 'auto',
                  borderColor: template === t.id ? 'var(--accent)' : undefined,
                  background: template === t.id ? 'var(--bg-4)' : undefined,
                }}
                onClick={() => setTemplate(t.id)}
              >
                <b>{t.label}</b>
                <span style={{ fontSize: 11, color: 'var(--text-dim)' }}>{t.desc}</span>
              </button>
            ))}
          </div>
          <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
            <button className="btn primary" style={{ flex: 1 }} onClick={() => { store.createProject(name || 'untitled', template); }}>
              <FolderPlus size={14} /> Create project
            </button>
            <button className="btn" onClick={() => fileRef.current?.click()}><Upload size={14} /> Import .zip</button>
            <input ref={fileRef} type="file" accept=".zip" style={{ display: 'none' }} onChange={(e) => { const f = e.target.files?.[0]; if (f) void importZip(f); }} />
          </div>

          <div className="section-note" style={{ paddingLeft: 0, marginTop: 10 }}>
            <Download size={11} style={{ verticalAlign: -1 }} /> Export any project as .zip from the Explorer header. In the native
            Android build this flows through the system file picker (Storage Access Framework) — no broad storage permission needed.
          </div>
        </div>
      </div>
    </div>
  );
}
