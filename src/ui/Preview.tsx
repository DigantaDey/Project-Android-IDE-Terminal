/** Isolated HTML/Markdown preview overlay — sandboxed iframe (req. 6). */
import { X, RotateCw, ShieldCheck } from 'lucide-react';
import { store, useApp } from '../state/store';

export function PreviewOverlay() {
  const open = useApp((s) => s.previewOpen);
  const html = useApp((s) => s.previewHtml);
  const title = useApp((s) => s.previewTitle);
  const activeFile = useApp((s) => s.activeFile);
  if (!open) return null;

  return (
    <div className="preview-wrap">
      <div className="preview-bar">
        <button className="iconbtn" onClick={() => store.set({ previewOpen: false })} title="Close preview"><X size={17} /></button>
        <span className="url">preview://sandbox/{title}</span>
        <span className="chip" title="Runs in a sandboxed iframe — no access to IDE data"><ShieldCheck size={10} style={{ verticalAlign: -1 }} /> isolated</span>
        <button className="iconbtn" title="Reload" onClick={() => store.openPreview(activeFile ?? undefined)}><RotateCw size={15} /></button>
      </div>
      <iframe className="preview-frame" sandbox="allow-scripts" srcDoc={html} title="preview" />
    </div>
  );
}
