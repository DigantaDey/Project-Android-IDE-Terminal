/** Source control panel — status, staging, commit, branches, stash, diff viewer (req. 9). */
import { useMemo, useState } from 'react';
import { GitBranch, Plus, Minus, Check, Archive, ArchiveRestore, RefreshCw, GitMerge, CloudOff, History } from 'lucide-react';
import { store, useApp } from '../../state/store';
import * as gitm from '../../core/git';

export function GitPanel() {
  const project = useApp((s) => s.projects.find((p) => p.id === s.activeProjectId));
  const [msg, setMsg] = useState('');
  const [newBranch, setNewBranch] = useState('');
  const [diffFile, setDiffFile] = useState<string | null>(null);
  const [view, setView] = useState<'changes' | 'history'>('changes');
  const [notice, setNotice] = useState('');

  const g = project?.git;
  const status = useMemo(() => (project && g?.initialized ? gitm.status(project) : []), [project, g?.initialized]);
  const log = useMemo(() => (g?.initialized ? gitm.log(g, 30) : []), [g]);

  if (!project) return <><div className="sidebar-header"><span>Source Control</span></div><div className="empty-state">No project open.</div></>;

  if (!g?.initialized) {
    return (
      <>
        <div className="sidebar-header"><span>Source Control</span></div>
        <div className="empty-state">
          <GitBranch size={28} />
          <div>This project is not a git repository.</div>
          <button className="btn primary" onClick={() => store.gitInit()}>Initialize Repository</button>
          <div className="section-note">Local git runs fully offline. Push/pull requires GitHub connection and network.</div>
        </div>
      </>
    );
  }

  const err = (e: string | null) => setNotice(e ?? '');

  return (
    <>
      <div className="sidebar-header">
        <span>Source Control</span>
        <span className="branch-chip current"><GitBranch size={11} /> {g.HEAD}</span>
      </div>
      <div style={{ padding: '0 10px 8px', display: 'flex', gap: 6 }}>
        <button className={'btn small' + (view === 'changes' ? ' primary' : '')} onClick={() => setView('changes')}>Changes</button>
        <button className={'btn small' + (view === 'history' ? ' primary' : '')} onClick={() => setView('history')}><History size={12} /> History</button>
      </div>
      {notice && <div className="section-note" style={{ color: 'var(--warn)' }}>{notice}</div>}
      <div className="sidebar-body">
        {view === 'changes' ? (
          <>
            <div style={{ padding: '0 10px' }}>
              <textarea
                className="textfield" rows={2} placeholder={`Commit message (${g.HEAD})`}
                value={msg} onChange={(e) => setMsg(e.target.value)} style={{ resize: 'vertical' }}
              />
              <div style={{ display: 'flex', gap: 6, marginTop: 6 }}>
                <button className="btn small primary" style={{ flex: 1 }} disabled={!msg.trim()} onClick={() => { err(store.gitCommit(msg)); setMsg(''); }}>
                  <Check size={13} /> Commit
                </button>
                <button className="btn small" title="Stage all" onClick={() => store.gitStage(status.map((s) => s.path))}><Plus size={13} /></button>
                <button className="btn small" title="Unstage all" onClick={() => store.gitUnstage(g.staged)}><Minus size={13} /></button>
              </div>
            </div>
            <div className="search-file-head">Changes <span className="chip">{status.length}</span></div>
            {status.length === 0 && <div className="section-note">Working tree clean.</div>}
            {status.map((s) => (
              <div key={s.path} className="git-status-row" onClick={() => setDiffFile(diffFile === s.path ? null : s.path)} title="Tap to view diff">
                <span className={'st ' + (s.status === 'new' ? 'A' : s.status === 'modified' ? 'M' : 'D')}>{s.status === 'new' ? 'A' : s.status === 'modified' ? 'M' : 'D'}</span>
                <span className="fname" style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis' }}>{s.path}</span>
                {s.staged ? (
                  <button className="iconbtn" style={{ width: 24, height: 24 }} title="Unstage" onClick={(e) => { e.stopPropagation(); store.gitUnstage([s.path]); }}><Minus size={12} /></button>
                ) : (
                  <button className="iconbtn" style={{ width: 24, height: 24 }} title="Stage" onClick={(e) => { e.stopPropagation(); store.gitStage([s.path]); }}><Plus size={12} /></button>
                )}
              </div>
            ))}
            {diffFile && (
              <div style={{ margin: '6px 8px', border: '1px solid var(--border)', borderRadius: 8, overflow: 'hidden', maxHeight: 260, display: 'flex', flexDirection: 'column' }}>
                <div className="search-file-head" style={{ padding: '6px 10px' }}>diff: {diffFile}</div>
                <div className="diff-view" style={{ maxHeight: 220 }}>
                  {gitm.diffAgainstHead(project, diffFile).map((l, i) => (
                    <div key={i} className={'diff-line ' + l.type}>{l.type === 'add' ? '+ ' : l.type === 'del' ? '- ' : '  '}{l.text}</div>
                  ))}
                </div>
              </div>
            )}
            <div className="search-file-head">Branches</div>
            <div style={{ padding: '0 10px', display: 'flex', flexWrap: 'wrap' }}>
              {Object.keys(g.branches).map((b) => (
                <span key={b} className={'branch-chip' + (b === g.HEAD ? ' current' : '')} onClick={() => b !== g.HEAD && err(store.gitCheckout(b))} title={b === g.HEAD ? 'current' : 'checkout'}>
                  <GitBranch size={10} /> {b}
                </span>
              ))}
            </div>
            <div style={{ padding: '6px 10px', display: 'flex', gap: 6 }}>
              <input className="textfield" placeholder="new-branch" value={newBranch} onChange={(e) => setNewBranch(e.target.value)} style={{ flex: 1 }} />
              <button className="btn small" onClick={() => { err(store.gitBranch(newBranch)); setNewBranch(''); }}><Plus size={13} /></button>
              <button className="btn small" title="Merge into current" disabled={!Object.keys(g.branches).some((b) => b !== g.HEAD)} onClick={() => {
                const other = Object.keys(g.branches).find((b) => b !== g.HEAD);
                if (other) err(store.gitMerge(other));
              }}><GitMerge size={13} /></button>
            </div>
            <div className="search-file-head">Stash <span className="chip">{g.stash.length}</span></div>
            <div style={{ padding: '0 10px', display: 'flex', gap: 6 }}>
              <button className="btn small" onClick={() => err(store.gitStashPush())}><Archive size={13} /> Stash</button>
              <button className="btn small" onClick={() => err(store.gitStashPop())} disabled={!g.stash.length}><ArchiveRestore size={13} /> Pop</button>
            </div>
            <div className="search-file-head">Remotes</div>
            <div style={{ padding: '0 10px', display: 'flex', flexDirection: 'column', gap: 6 }}>
              {g.remotes.map((r) => <div key={r.name} className="mono" style={{ fontSize: 11.5, color: 'var(--text-dim)' }}>{r.name} → {r.url}</div>)}
              <button className="btn small" onClick={() => setNotice(gitm.REMOTE_HELP)}><CloudOff size={13} /> Push / Pull (requires network)</button>
            </div>
          </>
        ) : (
          <>
            {log.length === 0 && <div className="empty-state">No commits yet.</div>}
            {log.map((c) => (
              <div key={c.id} className="commit">
                <div className="cmsg">{c.message}</div>
                <div className="cmeta">{c.id} · {c.author} · {new Date(c.time).toLocaleString()}</div>
              </div>
            ))}
          </>
        )}
      </div>
      <div className="sidebar-footer">
        <button className="btn small" onClick={() => { store.set({ bottomOpen: true, bottomTab: 'terminal' }); }} style={{ width: '100%' }}>
          <RefreshCw size={12} /> git via terminal → <span className="mono">git status</span>
        </button>
      </div>
    </>
  );
}
