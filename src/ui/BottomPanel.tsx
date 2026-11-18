/** Bottom panel: Terminal | Output | Problems | Debug console (req. 5, 12). Resizable. */
import { useEffect, useRef, useState } from 'react';
import { X, Plus, Trash2, ChevronDown, Ban, Play, StepForward, ArrowRightToLine, Eye } from 'lucide-react';
import { store, useApp, promptOf, type BottomTab } from '../state/store';
import type { TerminalSession } from '../core/types';
import * as vfs from '../core/vfs';

const TABS: { id: BottomTab; label: string }[] = [
  { id: 'terminal', label: 'Terminal' },
  { id: 'output', label: 'Output' },
  { id: 'problems', label: 'Problems' },
  { id: 'debug', label: 'Debug' },
];

export function BottomPanel() {
  const open = useApp((s) => s.bottomOpen);
  const tab = useApp((s) => s.bottomTab);
  const height = useApp((s) => s.bottomHeight);
  const problems = useApp((s) => s.diagnostics.length);
  const dragging = useRef(false);

  const onResizeStart = (e: React.PointerEvent) => {
    dragging.current = true;
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
    const startY = e.clientY;
    const startH = height;
    const move = (ev: PointerEvent) => {
      if (!dragging.current) return;
      const h = Math.min(window.innerHeight * 0.75, Math.max(120, startH + (startY - ev.clientY)));
      store.set({ bottomHeight: h });
    };
    const up = () => {
      dragging.current = false;
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  };

  if (!open) return null;

  return (
    <section className="bottom" style={{ height }}>
      <div className="bottom-resize" onPointerDown={onResizeStart} />
      <div className="bottom-bar">
        {TABS.map((t) => (
          <button key={t.id} className={'bottom-tab' + (tab === t.id ? ' active' : '')} onClick={() => store.set({ bottomTab: t.id })}>
            {t.label}
            {t.id === 'problems' && problems > 0 && <span className="count">{problems}</span>}
          </button>
        ))}
        <span style={{ flex: 1 }} />
        {tab === 'terminal' && <TerminalTabs />}
        <button className="iconbtn" title="Close panel" onClick={() => store.set({ bottomOpen: false })}><ChevronDown size={16} /></button>
      </div>
      <div className="bottom-body">
        {tab === 'terminal' && <TerminalView />}
        {tab === 'output' && <OutputView />}
        {tab === 'problems' && <ProblemsView />}
        {tab === 'debug' && <DebugView />}
      </div>
    </section>
  );
}

function TerminalTabs() {
  const terminals = useApp((s) => s.terminals);
  const activeId = useApp((s) => s.activeTerminalId);
  return (
    <span className="term-tabs">
      {terminals.map((t) => (
        <button key={t.id} className={'term-tab' + (t.id === activeId ? ' active' : '')} onClick={() => store.set({ activeTerminalId: t.id })}>
          {t.name.replace('Terminal ', 'T')}
        </button>
      ))}
      <button className="iconbtn" style={{ width: 26, height: 26 }} title="New terminal" onClick={() => store.newTerminal()}><Plus size={14} /></button>
    </span>
  );
}

function TerminalView() {
  const terminals = useApp((s) => s.terminals);
  const activeId = useApp((s) => s.activeTerminalId);
  const term = terminals.find((t) => t.id === activeId) ?? terminals[0];
  const [input, setInput] = useState('');
  const [histIdx, setHistIdx] = useState(-1);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight });
  }, [term?.lines.length]);

  if (!term) {
    return <div className="empty-state"><span>No terminal sessions.</span><button className="btn primary small" onClick={() => store.newTerminal()}><Plus size={13} /> New terminal</button></div>;
  }

  const submit = () => {
    if (!input.trim()) return;
    store.execInTerminal(term.id, input);
    setInput('');
    setHistIdx(-1);
  };

  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') submit();
    else if (e.key === 'ArrowUp') {
      e.preventDefault();
      const idx = histIdx < 0 ? term.history.length - 1 : Math.max(0, histIdx - 1);
      if (term.history[idx] !== undefined) { setInput(term.history[idx]); setHistIdx(idx); }
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      const idx = histIdx + 1;
      if (idx >= term.history.length) { setInput(''); setHistIdx(-1); }
      else { setInput(term.history[idx]); setHistIdx(idx); }
    }
  };

  return (
    <>
      <div className="term" ref={scrollRef} onClick={() => document.getElementById('term-input')?.focus()}>
        {term.lines.map((l) => (
          <div key={l.id} className={'term-line ' + l.kind}>{l.text}</div>
        ))}
        <div className="term-line in">{promptOf(term)} <span style={{ opacity: 0.7 }}>{input}▊</span></div>
      </div>
      <div className="term-input-row">
        <span className="term-prompt">{promptOf(term)}</span>
        <input
          id="term-input" className="term-input" value={input} autoComplete="off" autoCapitalize="off" autoCorrect="off" spellCheck={false}
          onChange={(e) => setInput(e.target.value)} onKeyDown={onKey} placeholder="type a command — try `help`"
        />
        <button className="iconbtn" title="Clear" onClick={() => store.execInTerminal(term.id, 'clear')}><Ban size={15} /></button>
        <button className="iconbtn" title="Close session" onClick={() => store.closeTerminal(term.id)}><Trash2 size={15} /></button>
      </div>
    </>
  );
}

function OutputView() {
  const builds = useApp((s) => s.builds);
  const running = useApp((s) => s.running);
  const [showAll, setShowAll] = useState(false);
  const rec = builds[0];
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => { scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight }); }, [rec?.output.length]);

  if (!rec) return <div className="empty-state">Build &amp; run output appears here.<br />Press ▶ to run the active file.</div>;

  return (
    <>
      <div className="output-meta">
        <span className={'exit-badge ' + (rec.status === 'success' ? 'ok' : rec.status === 'running' ? 'ok' : 'bad')}>
          {rec.status === 'running' ? 'RUNNING' : `exit ${rec.exitCode ?? '—'}`}
        </span>
        <span>{rec.kind.toUpperCase()} · {rec.file}</span>
        <span>{new Date(rec.startedAt).toLocaleTimeString()}</span>
        {rec.endedAt && <span>{rec.endedAt - rec.startedAt}ms</span>}
        <span style={{ flex: 1 }} />
        {running && <button className="btn small danger" onClick={() => store.stopRunning()}><X size={12} /> Stop</button>}
        <button className="btn small" onClick={() => setShowAll(!showAll)}>{showAll ? 'Latest' : `History (${builds.length})`}</button>
      </div>
      {!showAll ? (
        <div className="output" ref={scrollRef}>
          {rec.output.map((o, i) => (
            o.text === '__clear__' ? null : <div key={i} className={o.stream}>{o.text}</div>
          ))}
          {rec.output.length === 0 && <div className="system">…waiting for output…</div>}
        </div>
      ) : (
        <div className="output">
          {builds.map((b) => (
            <div key={b.id} className="system" style={{ padding: '4px 0', borderBottom: '1px solid var(--border)' }}>
              [{new Date(b.startedAt).toLocaleTimeString()}] {b.kind} {b.file} → {b.status}{b.exitCode !== undefined ? ` (${b.exitCode})` : ''}
            </div>
          ))}
        </div>
      )}
    </>
  );
}

function ProblemsView() {
  const diagnostics = useApp((s) => s.diagnostics);
  if (!diagnostics.length) return <div className="empty-state">✓ No problems detected.<br /><button className="btn small" onClick={() => store.lintWorkspace()}>Lint workspace</button></div>;
  const sorted = [...diagnostics].sort((a, b) => (a.severity === b.severity ? a.file.localeCompare(b.file) : a.severity === 'error' ? -1 : 1));
  return (
    <div className="problems">
      {sorted.map((d, i) => (
        <div key={i} className="problem-row" onClick={() => store.openFile(d.file, d.line)}>
          <span className="sev" style={{ color: d.severity === 'error' ? 'var(--err)' : d.severity === 'warning' ? 'var(--warn)' : 'var(--accent)' }}>
            {d.severity === 'error' ? '✖' : d.severity === 'warning' ? '⚠' : 'ℹ'}
          </span>
          <span className="msg">{d.message}</span>
          <span className="loc">{vfs.basename(d.file)}:{d.line}:{d.col}</span>
          <span className="src">{d.source}</span>
        </div>
      ))}
    </div>
  );
}

function DebugView() {
  const debug = useApp((s) => s.debug);
  const breakpoints = useApp((s) => s.breakpoints);
  const [watch, setWatch] = useState('');

  return (
    <div style={{ flex: 1, overflowY: 'auto' }}>
      <div className="debug-toolbar">
        <button className="btn small" disabled={debug.status !== 'paused'} onClick={() => store.debugResume('continue')} title="Continue"><Play size={13} /> Continue</button>
        <button className="btn small" disabled={debug.status === 'idle'} onClick={() => store.debugResume('step')} title="Step (next executed line)"><StepForward size={13} /> Step</button>
        <button className="btn small" disabled={debug.status === 'idle'} onClick={() => store.stopRunning()} title="Stop"><X size={13} /> Stop</button>
        <span style={{ flex: 1 }} />
        <span className="chip">{debug.status}</span>
      </div>
      {debug.status === 'paused' && (
        <div className="debug-section">
          <h4>Paused</h4>
          <div className="stack-frame" style={{ color: 'var(--warn)' }}>{debug.file}:{debug.line}</div>
        </div>
      )}
      <div className="debug-section">
        <h4>Call stack</h4>
        {debug.callStack.length === 0 && <div className="section-note" style={{ padding: 0 }}>—</div>}
        {debug.callStack.map((f, i) => <div key={i} className="stack-frame">{f.name} · line {f.line}</div>)}
      </div>
      <div className="debug-section">
        <h4>Global scope</h4>
        {Object.entries(debug.scopes).filter(([k]) => !k.startsWith('__') && !['self', 'globalThis', 'console', 'postMessage', 'onmessage', 'close', 'caches', 'crypto', 'indexedDB', 'location', 'navigator', 'origin'].includes(k)).slice(0, 40).map(([k, v]) => (
          <div key={k} className="kv"><span className="k">{k}</span><span className="v">{String(v).slice(0, 80)}</span></div>
        ))}
        {Object.keys(debug.scopes).length === 0 && <div className="section-note" style={{ padding: 0 }}>Pause at a breakpoint to inspect globals. Local variables inside functions require the native debug adapter.</div>}
      </div>
      <div className="debug-section">
        <h4>Watches</h4>
        <div style={{ display: 'flex', gap: 6 }}>
          <input className="textfield" placeholder="expression (evaluated in worker scope)" value={watch} onChange={(e) => setWatch(e.target.value)} onKeyDown={(e) => {
            if (e.key === 'Enter' && watch.trim()) { store.setWatches([...debug.watches, watch.trim()]); setWatch(''); }
          }} />
          <button className="btn small" onClick={() => { if (watch.trim()) { store.setWatches([...debug.watches, watch.trim()]); setWatch(''); } }}><Eye size={13} /></button>
        </div>
        {debug.watches.map((w) => (
          <div key={w} className="kv"><span className="k">{w}</span><span className="v">{debug.watchValues[w] ?? (debug.status === 'paused' ? String(debug.scopes[w] ?? 'undefined') : '—')}</span></div>
        ))}
      </div>
      <div className="debug-section">
        <h4>Breakpoints</h4>
        {breakpoints.map((b) => (
          <div key={b.id} className="kv" style={{ cursor: 'pointer' }} onClick={() => store.openFile(b.file, b.line)}>
            <span className="k">{vfs.basename(b.file)}:{b.line}</span><span className="v">{b.enabled ? 'enabled' : 'disabled'}</span>
          </div>
        ))}
        {breakpoints.length === 0 && <div className="section-note" style={{ padding: 0 }}>Add breakpoints from the editor gutter.</div>}
      </div>
      <div className="debug-section">
        <h4>Console output → Output tab</h4>
        <div className="section-note" style={{ padding: 0 }}>stdout/stderr stream to the Output panel while debugging.</div>
      </div>
      <div style={{ padding: '0 12px 12px' }}>
        <button className="btn small" onClick={() => void store.runActive(true)} disabled={debug.status !== 'idle'}>
          <ArrowRightToLine size={12} /> Restart debug session
        </button>
      </div>
    </div>
  );
}
