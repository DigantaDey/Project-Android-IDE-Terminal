/** Editor tab strip with unsaved indicators and overflow scrolling. */
import { X } from 'lucide-react';
import { store, useApp } from '../state/store';
import * as vfs from '../core/vfs';
import { languageById } from '../core/languages';

export function Tabs() {
  const openFiles = useApp((s) => s.openFiles);
  const activeFile = useApp((s) => s.activeFile);
  const dirty = useApp((s) => s.dirty);
  const project = useApp((s) => s.projects.find((p) => p.id === s.activeProjectId));

  if (!openFiles.length) return null;

  return (
    <div className="tabs" role="tablist">
      {openFiles.map((path) => {
        const f = project?.files[path];
        if (!f) return null;
        const lang = languageById(f.language);
        const isActive = path === activeFile;
        const isDirty = dirty.includes(path);
        return (
          <div
            key={path}
            role="tab"
            aria-selected={isActive}
            className={'tab' + (isActive ? ' active' : '')}
            onClick={() => store.setActiveFile(path)}
            title={path}
          >
            <span className="ficon" style={{ color: lang.color }}>{lang.icon}</span>
            <span>{vfs.basename(path)}</span>
            {isDirty ? (
              <span className="dot" title="Unsaved changes" />
            ) : (
              <button className="close" title="Close" onClick={(e) => { e.stopPropagation(); store.closeFile(path); }}>
                <X size={12} />
              </button>
            )}
          </div>
        );
      })}
    </div>
  );
}
