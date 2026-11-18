/** Project explorer — tree, create/rename/delete/duplicate/move, context menu (req. 3). */
import { useState } from 'react';
import {
  ChevronRight, FilePlus, FolderPlus, RefreshCw, MoreHorizontal, Trash2, Copy,
  Pencil, FileCode, FolderOpen, Download, Upload,
} from 'lucide-react';
import { store, useApp } from '../../state/store';
import * as vfs from '../../core/vfs';
import type { FileNode } from '../../core/types';
import { languageById } from '../../core/languages';

interface Ctx { x: number; y: number; path: string; type: 'file' | 'folder'; }

export function Explorer() {
  const project = useApp((s) => s.projects.find((p) => p.id === s.activeProjectId));
  const activeFile = useApp((s) => s.activeFile);
  const expanded = useApp((s) => s.explorerExpanded);
  const dirty = useApp((s) => s.dirty);
  const [ctx, setCtx] = useState<Ctx | null>(null);
  const [creating, setCreating] = useState<{ dir: string; type: 'file' | 'folder' } | null>(null);
  const [renaming, setRenaming] = useState<string | null>(null);
  const [name, setName] = useState('');

  if (!project) {
    return (
      <>
        <div className="sidebar-header"><span>Explorer</span></div>
        <div className="empty-state">No project open.<br />Create one from the Projects menu.</div>
      </>
    );
  }

  const toggle = (path: string) => {
    store.set({ explorerExpanded: expanded.includes(path) ? expanded.filter((e) => e !== path) : [...expanded, path] });
  };

  const submitCreate = () => {
    if (!creating || !name.trim()) { setCreating(null); return; }
    if (creating.type === 'file') store.createFile(creating.dir, name.trim());
    else store.createFolder(creating.dir, name.trim());
    setCreating(null); setName('');
  };

  const submitRename = () => {
    if (renaming && name.trim()) store.renamePath(renaming, name.trim());
    setRenaming(null); setName('');
  };

  const exportProject = async () => {
    const JSZip = (await import('jszip')).default;
    const zip = new JSZip();
    for (const [path, f] of Object.entries(project.files)) zip.file(path.replace(/^\//, ''), f.content);
    const blob = await zip.generateAsync({ type: 'blob' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = project.name + '.zip';
    a.click();
    URL.revokeObjectURL(a.href);
  };

  const importFiles = async () => {
    const input = document.createElement('input');
    input.type = 'file';
    input.multiple = true;
    input.onchange = async () => {
      for (const file of Array.from(input.files ?? [])) {
        const text = await file.text();
        if (text.length > 512 * 1024) continue;
        store.createFile('/', file.name, text);
      }
    };
    input.click();
  };

  const renderNode = (node: FileNode, path: string, depth: number): JSX.Element[] => {
    const isFolder = node.type === 'folder';
    const isOpen = expanded.includes(path);
    const rows = [];
    rows.push(
      <div
        key={path}
        className={'tree-row' + (path === activeFile ? ' active' : '')}
        style={{ paddingLeft: 8 + depth * 12 }}
        onClick={() => (isFolder ? toggle(path) : store.openFile(path))}
        onContextMenu={(e) => { e.preventDefault(); setCtx({ x: e.clientX, y: e.clientY, path, type: node.type }); }}
      >
        <span className={'chev' + (isOpen ? ' open' : '')}>{isFolder ? <ChevronRight size={13} /> : null}</span>
        {isFolder ? (
          <span className="ficon" style={{ color: 'var(--warn)' }}>{isOpen ? '▾' : '▸'}</span>
        ) : (
          <span className="ficon" style={{ color: languageById(project.files[path]?.language ?? 'plaintext').color }}>
            {languageById(project.files[path]?.language ?? 'plaintext').icon}
          </span>
        )}
        {renaming === path ? (
          <input
            className="textfield"
            style={{ padding: '2px 6px', fontSize: 12.5 }}
            autoFocus
            defaultValue={node.name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') submitRename(); if (e.key === 'Escape') setRenaming(null); }}
            onBlur={submitRename}
            onClick={(e) => e.stopPropagation()}
          />
        ) : (
          <span className="fname">
            {node.name}
            {dirty.includes(path) && <span style={{ color: 'var(--warn)' }}> ●</span>}
          </span>
        )}
        <span className="row-actions">
          <button className="iconbtn" onClick={(e) => { e.stopPropagation(); setCtx({ x: e.clientX, y: e.clientY, path, type: node.type }); }}>
            <MoreHorizontal size={14} />
          </button>
        </span>
      </div>,
    );
    if (isFolder && isOpen && node.children) {
      const sorted = [...node.children].sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'folder' ? -1 : 1));
      for (const child of sorted) rows.push(...renderNode(child, path === '/' ? '/' + child.name : path + '/' + child.name, depth + 1));
    }
    return rows;
  };

  return (
    <>
      <div className="sidebar-header">
        <span>{project.name}</span>
        <span style={{ display: 'flex', gap: 2 }}>
          <button className="iconbtn" style={{ width: 26, height: 26 }} title="New file" onClick={() => { setCreating({ dir: '/', type: 'file' }); setName(''); }}><FilePlus size={15} /></button>
          <button className="iconbtn" style={{ width: 26, height: 26 }} title="New folder" onClick={() => { setCreating({ dir: '/', type: 'folder' }); setName(''); }}><FolderPlus size={15} /></button>
          <button className="iconbtn" style={{ width: 26, height: 26 }} title="Import files" onClick={() => void importFiles()}><Upload size={14} /></button>
          <button className="iconbtn" style={{ width: 26, height: 26 }} title="Export project (.zip)" onClick={() => void exportProject()}><Download size={14} /></button>
          <button className="iconbtn" style={{ width: 26, height: 26 }} title="Lint workspace" onClick={() => store.lintWorkspace()}><RefreshCw size={14} /></button>
        </span>
      </div>
      <div className="sidebar-body" onClick={() => setCtx(null)}>
        <div className="tree">
          {creating && creating.dir === '/' && (
            <div className="tree-row" style={{ paddingLeft: 8 }}>
              <span className="ficon">{creating.type === 'file' ? <FileCode size={13} /> : <FolderOpen size={13} />}</span>
              <input
                className="textfield" style={{ padding: '2px 6px', fontSize: 12.5 }} autoFocus placeholder={creating.type === 'file' ? 'filename.js' : 'folder name'}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') submitCreate(); if (e.key === 'Escape') setCreating(null); }}
                onBlur={submitCreate}
              />
            </div>
          )}
          {(project.root.children || []).length === 0 && <div className="section-note">Empty project — create your first file.</div>}
          {[...(project.root.children || [])]
            .sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'folder' ? -1 : 1))
            .flatMap((n) => renderNode(n, '/' + n.name, 0))}
        </div>
      </div>
      <div className="sidebar-footer">
        <div className="section-note" style={{ padding: 0 }}>
          {Object.keys(project.files).length} files · {project.git?.initialized ? `git: ${project.git.HEAD}` : 'no git repo'}
        </div>
      </div>

      {ctx && (
        <div className="ctx-menu" style={{ left: Math.min(ctx.x, window.innerWidth - 210), top: Math.min(ctx.y, window.innerHeight - 300) }} onClick={(e) => e.stopPropagation()}>
          {ctx.type === 'folder' && (
            <>
              <button className="ctx-item" onClick={() => { setCreating({ dir: ctx.path, type: 'file' }); setName(''); setCtx(null); }}><FilePlus size={14} /> New file</button>
              <button className="ctx-item" onClick={() => { setCreating({ dir: ctx.path, type: 'folder' }); setName(''); setCtx(null); }}><FolderPlus size={14} /> New folder</button>
              <div className="ctx-sep" />
            </>
          )}
          {ctx.type === 'file' && (
            <>
              <button className="ctx-item" onClick={() => { store.openFile(ctx.path); setCtx(null); }}><FileCode size={14} /> Open</button>
              <button className="ctx-item" onClick={() => { store.duplicatePath(ctx.path); setCtx(null); }}><Copy size={14} /> Duplicate</button>
              <button className="ctx-item" onClick={() => { void store.runFile(ctx.path); setCtx(null); }}><ChevronRight size={14} /> Run</button>
            </>
          )}
          <button className="ctx-item" onClick={() => { setRenaming(ctx.path); setName(vfs.basename(ctx.path)); setCtx(null); }}><Pencil size={14} /> Rename</button>
          <div className="ctx-sep" />
          <button className="ctx-item danger" onClick={() => { store.deletePath(ctx.path); setCtx(null); }}><Trash2 size={14} /> Delete</button>
        </div>
      )}

      {creating && creating.dir !== '/' && (
        <div className="overlay" style={{ zIndex: 400 }} onClick={() => setCreating(null)}>
          <div className="sheet" style={{ maxWidth: 360 }} onClick={(e) => e.stopPropagation()}>
            <div className="sheet-header">New {creating.type} in {creating.dir}</div>
            <div className="sheet-body">
              <input className="textfield" autoFocus placeholder={creating.type === 'file' ? 'main.js, style.css, util.py…' : 'folder name'} onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && submitCreate()} />
              <div style={{ marginTop: 12, display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
                <button className="btn" onClick={() => setCreating(null)}>Cancel</button>
                <button className="btn primary" onClick={submitCreate}>Create</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
