/** Lightweight canvas minimap — renders code shape, tap/drag to scroll (req. 2, 15). */
import { useEffect, useRef, useCallback } from 'react';
import type { EditorView } from '@codemirror/view';

const LINE_H = 2;   // px per line
const W = 56;       // minimap width

export function Minimap({ getView }: { getView: () => EditorView | null }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const wrapRef = useRef<HTMLDivElement>(null);

  const draw = useCallback(() => {
    const view = getView();
    const canvas = canvasRef.current;
    if (!view || !canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const lines = view.state.doc.lines;
    const dpr = window.devicePixelRatio || 1;
    const h = Math.max(lines * LINE_H, 60);
    canvas.width = W * dpr;
    canvas.height = Math.min(h, 2400) * dpr;
    canvas.style.height = Math.min(h, 2400) + 'px';
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, W, h);
    const styles = getComputedStyle(document.body);
    const base = styles.getPropertyValue('--minimap-ink').trim() || '#4a5568';
    const kw = styles.getPropertyValue('--minimap-kw').trim() || '#4f7cff';
    const str = styles.getPropertyValue('--minimap-str').trim() || '#3aa675';
    const com = styles.getPropertyValue('--minimap-com').trim() || '#333d52';
    const doc = view.state.doc;
    for (let i = 1; i <= Math.min(lines, 1200); i++) {
      const text = doc.line(i).text;
      if (!text.trim()) continue;
      const indent = text.length - text.trimStart().length;
      const x = Math.min(2 + indent * 0.9, W - 6);
      const w = Math.max(3, Math.min(text.trim().length * 0.9, W - x - 2));
      let color = base;
      const t = text.trim();
      if (t.startsWith('//') || t.startsWith('#') || t.startsWith('*') || t.startsWith('<!--')) color = com;
      else if (/^(import|export|function|class|const|let|var|def|return|if|for|while|interface|type|public|private|static)\b/.test(t)) color = kw;
      else if (/^["'`].*["'`][;,]?$/.test(t) || t.includes('=>')) color = str;
      ctx.fillStyle = color;
      ctx.globalAlpha = 0.75;
      ctx.fillRect(x, (i - 1) * LINE_H, w, 1.2);
    }
    ctx.globalAlpha = 1;
    // viewport indicator
    const scroller = view.scrollDOM;
    const total = scroller.scrollHeight || 1;
    const viewH = scroller.clientHeight || 1;
    const top = (scroller.scrollTop / total) * Math.min(h, 2400);
    const vh = Math.max(18, (viewH / total) * Math.min(h, 2400));
    ctx.fillStyle = 'rgba(127,156,255,0.16)';
    ctx.fillRect(0, top, W, vh);
  }, [getView]);

  useEffect(() => {
    let raf = 0;
    const schedule = () => { cancelAnimationFrame(raf); raf = requestAnimationFrame(draw); };
    const interval = setInterval(schedule, 900); // cheap polling keeps it in sync
    window.addEventListener('resize', schedule);
    schedule();
    return () => { clearInterval(interval); cancelAnimationFrame(raf); window.removeEventListener('resize', schedule); };
  }, [draw]);

  const jump = (clientY: number) => {
    const view = getView();
    const canvas = canvasRef.current;
    if (!view || !canvas) return;
    const rect = canvas.getBoundingClientRect();
    const frac = Math.min(1, Math.max(0, (clientY - rect.top) / rect.height));
    const scroller = view.scrollDOM;
    scroller.scrollTop = frac * (scroller.scrollHeight - scroller.clientHeight);
  };

  return (
    <div
      ref={wrapRef}
      className="minimap"
      onPointerDown={(e) => { (e.target as HTMLElement).setPointerCapture(e.pointerId); jump(e.clientY); }}
      onPointerMove={(e) => { if (e.buttons) jump(e.clientY); }}
    >
      <canvas ref={canvasRef} />
    </div>
  );
}
