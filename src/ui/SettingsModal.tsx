/** IDE settings — editor, terminal, sandbox limits, git, storage (req. 21). */
import { X, Trash2 } from 'lucide-react';
import { store, useApp, DEFAULT_SETTINGS } from '../state/store';
import { storageUsage, formatBytes } from '../core/storage';

function Row({ label, desc, children }: { label: string; desc?: string; children: React.ReactNode }) {
  return (
    <div className="setting-row">
      <div style={{ flex: 1 }}>
        <div>{label}</div>
        {desc && <div className="desc">{desc}</div>}
      </div>
      {children}
    </div>
  );
}

function Switch({ value, onChange }: { value: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className="switch">
      <input type="checkbox" checked={value} onChange={(e) => onChange(e.target.checked)} />
      <span className="track" />
    </label>
  );
}

export function SettingsModal() {
  const open = useApp((s) => s.settingsOpen);
  const settings = useApp((s) => s.settings);
  if (!open) return null;

  const set = (patch: Partial<typeof settings>) => store.set({ settings: { ...settings, ...patch } });
  const usage = storageUsage();

  return (
    <div className="overlay" onClick={() => store.set({ settingsOpen: false })}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
        <div className="sheet-header">
          Settings
          <button className="iconbtn" onClick={() => store.set({ settingsOpen: false })}><X size={17} /></button>
        </div>
        <div className="sheet-body">
          <div className="field-label">Appearance</div>
          <Row label="Theme">
            <select value={settings.theme} onChange={(e) => set({ theme: e.target.value as typeof settings.theme })}>
              <option value="dark">Dark</option><option value="light">Light</option><option value="system">System</option>
            </select>
          </Row>
          <Row label="Font size" desc={`${settings.fontSize}px`}>
            <input type="number" min={10} max={28} value={settings.fontSize} onChange={(e) => set({ fontSize: Number(e.target.value) || 14 })} />
          </Row>
          <Row label="UI scale" desc={`${Math.round(settings.uiScale * 100)}%`}>
            <input type="number" min={0.8} max={1.4} step={0.05} value={settings.uiScale} onChange={(e) => set({ uiScale: Number(e.target.value) || 1 })} />
          </Row>
          <Row label="Coding keyboard bar" desc="Symbol row above the soft keyboard"><Switch value={settings.keyboardBar} onChange={(v) => set({ keyboardBar: v })} /></Row>

          <div className="field-label">Editor</div>
          <Row label="Tab size"><input type="number" min={1} max={8} value={settings.tabSize} onChange={(e) => set({ tabSize: Number(e.target.value) || 4 })} /></Row>
          <Row label="Indent with tabs"><Switch value={settings.useTabs} onChange={(v) => set({ useTabs: v })} /></Row>
          <Row label="Word wrap"><Switch value={settings.wordWrap} onChange={(v) => set({ wordWrap: v })} /></Row>
          <Row label="Minimap"><Switch value={settings.minimap} onChange={(v) => set({ minimap: v })} /></Row>
          <Row label="Autocomplete"><Switch value={settings.autocomplete} onChange={(v) => set({ autocomplete: v })} /></Row>
          <Row label="Autosave" desc="Saves ~1s after you stop typing"><Switch value={settings.autosave} onChange={(v) => set({ autosave: v })} /></Row>
          <Row label="Format on save"><Switch value={settings.formatOnSave} onChange={(v) => set({ formatOnSave: v })} /></Row>

          <div className="field-label">Execution sandbox</div>
          <Row label="Time limit" desc="Processes are killed after this" >
            <select value={settings.sandbox.timeoutMs} onChange={(e) => set({ sandbox: { ...settings.sandbox, timeoutMs: Number(e.target.value) } })}>
              <option value={3000}>3 s</option><option value={8000}>8 s</option><option value={20000}>20 s</option><option value={60000}>60 s</option>
            </select>
          </Row>
          <Row label="Memory limit" desc="Enforced by the native runtime; advisory here">
            <select value={settings.sandbox.memoryMb} onChange={(e) => set({ sandbox: { ...settings.sandbox, memoryMb: Number(e.target.value) } })}>
              <option value={64}>64 MB</option><option value={256}>256 MB</option><option value={512}>512 MB</option>
            </select>
          </Row>
          <Row label="Allow network in sandbox" desc="Disabled by default — code cannot reach the internet">
            <Switch value={settings.sandbox.network} onChange={(v) => set({ sandbox: { ...settings.sandbox, network: v } })} />
          </Row>

          <div className="field-label">Git identity</div>
          <Row label="Name"><input type="text" value={settings.gitUser} onChange={(e) => set({ gitUser: e.target.value })} /></Row>
          <Row label="Email"><input type="email" value={settings.gitEmail} onChange={(e) => set({ gitEmail: e.target.value })} /></Row>
          <div className="section-note" style={{ paddingLeft: 0 }}>
            GitHub tokens are stored in Android Keystore in the native build and are never readable by executed code or the terminal.
          </div>

          <div className="field-label">Storage</div>
          <Row label="Workspace storage" desc={`${usage.keys} keys`}>
            <span className="mono" style={{ fontSize: 12 }}>{formatBytes(usage.used)}</span>
          </Row>
          <Row label="Reset" desc="Clear all projects & settings">
            <button className="btn small danger" onClick={() => { if (confirm('Delete ALL projects and settings?')) { localStorage.clear(); location.reload(); } }}>
              <Trash2 size={13} /> Factory reset
            </button>
          </Row>

          <div className="field-label">Keyboard shortcuts</div>
          {[
            ['Ctrl+S', 'Save all'], ['Ctrl+R', 'Run'], ['Ctrl+Shift+D', 'Debug'], ['Ctrl+F', 'Find in file'],
            ['Ctrl+Shift+F', 'Format document'], ['Ctrl+K', 'Command palette'], ['Ctrl+`', 'Terminal'],
            ['Ctrl+N', 'New file'], ['Ctrl+W', 'Close tab'],
          ].map(([k, d]) => (
            <div key={k} className="kv" style={{ padding: '3px 0' }}><span className="v">{d}</span><span className="k">{k}</span></div>
          ))}

          <div style={{ height: 16 }} />
          <button className="btn" onClick={() => set({ ...DEFAULT_SETTINGS })}>Restore defaults</button>
        </div>
      </div>
    </div>
  );
}
