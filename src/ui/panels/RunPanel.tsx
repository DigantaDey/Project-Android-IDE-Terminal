/** Run configurations, breakpoints and debug controls (req. 7, 8). */
import { useState } from 'react';
import { Play, Bug, Plus, Trash2, CircleDot, Save } from 'lucide-react';
import { store, useApp } from '../../state/store';
import * as vfs from '../../core/vfs';
import type { RunConfig } from '../../core/types';
import { uid } from '../../core/types';

export function RunPanel() {
  const project = useApp((s) => s.projects.find((p) => p.id === s.activeProjectId));
  const breakpoints = useApp((s) => s.breakpoints);
  const debug = useApp((s) => s.debug);
  const [editing, setEditing] = useState<RunConfig | null>(null);

  if (!project) return <><div className="sidebar-header"><span>Run & Debug</span></div><div className="empty-state">No project open.</div></>;

  const activeRc = project.runConfigs.find((r) => r.id === project.activeRunConfigId) ?? project.runConfigs[0];
  const files = vfs.allFiles(project);

  const saveRc = () => {
    if (editing) { store.updateRunConfig(editing); setEditing(null); }
  };

  return (
    <>
      <div className="sidebar-header"><span>Run & Debug</span></div>
      <div className="sidebar-body">
        <div style={{ padding: '0 10px', display: 'flex', gap: 6 }}>
          <button className="btn primary small" style={{ flex: 1 }} onClick={() => void store.runActive(false)}><Play size={13} /> Run</button>
          <button className="btn small" style={{ flex: 1 }} onClick={() => void store.runActive(true)}><Bug size={13} /> Debug</button>
        </div>

        <div className="search-file-head">Run configurations</div>
        {project.runConfigs.map((rc) => (
          <div key={rc.id} className={'git-status-row' + (rc.id === project.activeRunConfigId ? ' active' : '')} onClick={() => store.set({ projects: store.state.projects.map((p) => p.id === project.id ? { ...p, activeRunConfigId: rc.id } : p) })}>
            <Play size={12} style={{ color: rc.id === project.activeRunConfigId ? 'var(--ok)' : 'var(--text-faint)' }} />
            <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis' }}>{rc.name}</span>
            <button className="iconbtn" style={{ width: 24, height: 24 }} onClick={(e) => { e.stopPropagation(); setEditing({ ...rc }); }} title="Edit"><Save size={12} /></button>
          </div>
        ))}
        <div style={{ padding: '6px 10px' }}>
          <button className="btn small" onClick={() => setEditing({ id: uid(), name: 'New config', file: files[0] ?? '/', args: '', env: {}, cwd: '/', buildCmd: '', runCmd: '', debugCmd: '' })}><Plus size={13} /> Add configuration</button>
        </div>

        {editing && (
          <div style={{ padding: '4px 10px 10px', borderTop: '1px solid var(--border)', marginTop: 4 }}>
            <div className="field-label">Name</div>
            <input className="textfield" value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} />
            <div className="field-label">Entry file</div>
            <select className="textfield" value={editing.file} onChange={(e) => setEditing({ ...editing, file: e.target.value })}>
              {files.map((f) => <option key={f} value={f}>{f}</option>)}
            </select>
            <div className="field-label">Program arguments</div>
            <input className="textfield" placeholder="--flag value" value={editing.args} onChange={(e) => setEditing({ ...editing, args: e.target.value })} />
            <div className="field-label">Environment (KEY=VALUE per line)</div>
            <textarea className="textfield" rows={2} value={Object.entries(editing.env).map(([k, v]) => `${k}=${v}`).join('\n')} onChange={(e) => {
              const env: Record<string, string> = {};
              for (const line of e.target.value.split('\n')) { const i = line.indexOf('='); if (i > 0) env[line.slice(0, i)] = line.slice(i + 1); }
              setEditing({ ...editing, env });
            }} />
            <div className="field-label">Working directory</div>
            <input className="textfield" value={editing.cwd} onChange={(e) => setEditing({ ...editing, cwd: e.target.value })} />
            <div style={{ display: 'flex', gap: 6, marginTop: 10 }}>
              <button className="btn primary small" onClick={saveRc}><Save size={12} /> Save</button>
              <button className="btn small" onClick={() => setEditing(null)}>Cancel</button>
              <button className="btn small danger" onClick={() => { store.deleteRunConfig(editing.id); setEditing(null); }}><Trash2 size={12} /></button>
            </div>
          </div>
        )}

        <div className="search-file-head">Breakpoints <span className="chip">{breakpoints.length}</span></div>
        {breakpoints.length === 0 && <div className="section-note">Click the editor gutter (left of line numbers) to add a breakpoint. Debug JS/TS files to pause at them.</div>}
        {breakpoints.map((b) => (
          <div key={b.id} className="git-status-row" onClick={() => store.openFile(b.file, b.line)}>
            <CircleDot size={12} style={{ color: 'var(--err)' }} />
            <span style={{ flex: 1 }} className="mono">{vfs.basename(b.file)}:{b.line}</span>
            <button className="iconbtn" style={{ width: 24, height: 24 }} onClick={(e) => { e.stopPropagation(); store.toggleBreakpoint(b.file, b.line); }}><Trash2 size={12} /></button>
          </div>
        ))}

        <div className="search-file-head">Debug status</div>
        <div className="section-note">
          {debug.status === 'idle' && 'No active debug session.'}
          {debug.status === 'running' && `Running ${debug.file}…`}
          {debug.status === 'paused' && `Paused at ${debug.file}:${debug.line} — use the Debug console panel to step.`}
        </div>
      </div>
    </>
  );
}
