/**
 * Toolchains panel — honest on-device capability matrix (req. 24).
 * What runs today vs. what activates via pluggable toolchain modules.
 */
import { useState } from 'react';
import { CheckCircle2, Download, MonitorPlay, PackageX } from 'lucide-react';
import { LANGUAGES } from '../../core/languages';
import { storageUsage, formatBytes } from '../../core/storage';

interface ToolchainInfo { plugin: string; name: string; detail: string; size: string; }

const PLUGINS: Record<string, ToolchainInfo> = {
  pyodide: { plugin: 'pyodide', name: 'Pyodide — CPython WASM', detail: 'Full CPython compiled to WebAssembly. Runs offline after install. Enables Python execution, pip (WASM wheels), and Black formatting.', size: '~12 MB' },
  clang: { plugin: 'clang', name: 'clang/wasm-ld — C & C++', detail: 'clang compiled to WASM compiles C/C++ to a WASM module, executed in the sandbox with a POSIX shim. Alternative native path: Android NDK bridge.', size: '~45 MB' },
  openjdk: { plugin: 'openjdk', name: 'ECJ + ART bridge — Java', detail: 'Eclipse Compiler for Java compiles sources; the ART bridge dexes and runs bytecode via the platform runtime.', size: '~38 MB' },
  sqlite: { plugin: 'sqlite', name: 'SQLite (sql.js WASM)', detail: 'In-memory SQLite engine for running SQL scripts with results grid.', size: '~1.5 MB' },
  node: { plugin: 'node', name: 'Node compatibility layer', detail: 'npm package installs (pure-JS wheels), node-style require() resolution mapped onto the project VFS.', size: '~6 MB' },
};

export function ToolchainsPanel() {
  const [installed, setInstalled] = useState<string[]>(() => {
    try { return JSON.parse(localStorage.getItem('forge.ide.v1.plugins') || '[]'); } catch { return []; }
  });
  const [note, setNote] = useState('');
  const usage = storageUsage();

  const install = (p: ToolchainInfo) => {
    setNote(`${p.name}: plugins ship as downloadable modules in the native Android build (Play Asset Delivery). This sandboxed preview documents the integration point — the IDE core already routes execution through it.`);
    void p;
  };

  return (
    <>
      <div className="sidebar-header"><span>Toolchains</span></div>
      <div className="sidebar-body">
        <div className="section-note">
          Language execution is modular. Built-in runtimes work offline today; native toolchains attach as plugins without restructuring the IDE.
        </div>
        <div className="search-file-head">Built in</div>
        {Object.values(LANGUAGES).filter((l) => l.run.kind !== 'toolchain').map((l) => (
          <div className="toolchain-card" key={l.id}>
            <div className="tc-head">
              <span style={{ color: l.color }}>{l.icon}</span> {l.name}
              <span style={{ marginLeft: 'auto' }} className={'tc-badge ' + l.run.kind}>
                {l.run.kind === 'local' ? <CheckCircle2 size={10} style={{ verticalAlign: -1 }} /> : <MonitorPlay size={10} style={{ verticalAlign: -1 }} />} {l.run.kind === 'local' ? 'on-device' : 'preview'}
              </span>
            </div>
            <div className="tc-desc">{l.run.description}</div>
          </div>
        ))}
        <div className="search-file-head">Plugin toolchains</div>
        {Object.values(PLUGINS).map((p) => (
          <div className="toolchain-card" key={p.plugin}>
            <div className="tc-head">
              <PackageX size={15} style={{ color: 'var(--warn)' }} /> {p.name}
              <span className="tc-badge toolchain" style={{ marginLeft: 'auto' }}>plugin</span>
            </div>
            <div className="tc-desc">{p.detail}</div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span className="chip">{p.size}</span>
              <button className="btn small" style={{ marginLeft: 'auto' }} onClick={() => install(p)} disabled={installed.includes(p.plugin)}>
                <Download size={12} /> {installed.includes(p.plugin) ? 'Installed' : 'Install'}
              </button>
            </div>
          </div>
        ))}
        {note && <div className="section-note" style={{ color: 'var(--warn)' }}>{note}</div>}
        <div className="divider" />
        <div className="section-note">Workspace storage used: <b>{formatBytes(usage.used)}</b> · {usage.keys} keys. Clear from Settings → Storage.</div>
      </div>
    </>
  );
}
