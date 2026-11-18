/** Left icon rail + active side panel (req. 3, 15). */
import { Files, Search, GitBranch, PlayCircle, Wrench, CircleAlert } from 'lucide-react';
import { store, useApp, type SidebarPanel } from '../state/store';
import { Explorer } from './panels/Explorer';
import { SearchPanel } from './panels/SearchPanel';
import { GitPanel } from './panels/GitPanel';
import { RunPanel } from './panels/RunPanel';
import { ToolchainsPanel } from './panels/ToolchainsPanel';

const ITEMS: { id: SidebarPanel; icon: typeof Files; label: string }[] = [
  { id: 'explorer', icon: Files, label: 'Explorer' },
  { id: 'search', icon: Search, label: 'Search' },
  { id: 'git', icon: GitBranch, label: 'Source Control' },
  { id: 'run', icon: PlayCircle, label: 'Run & Debug' },
  { id: 'toolchains', icon: Wrench, label: 'Toolchains' },
];

export function Sidebar() {
  const panel = useApp((s) => s.sidebarPanel);
  const open = useApp((s) => s.sidebarOpen);
  const problems = useApp((s) => s.diagnostics.filter((d) => d.severity === 'error').length);

  return (
    <>
      <nav className="rail">
        {ITEMS.map(({ id, icon: Icon, label }) => (
          <button
            key={id}
            className={'rail-btn' + (open && panel === id ? ' active' : '')}
            title={label}
            onClick={() => store.set({ sidebarPanel: id, sidebarOpen: panel === id ? !open : true })}
          >
            <Icon size={19} />
          </button>
        ))}
        <div className="spacer" />
        <button
          className="rail-btn"
          title="Problems"
          onClick={() => store.set({ bottomOpen: true, bottomTab: 'problems' })}
        >
          <CircleAlert size={19} />
          {problems > 0 && <span className="badge">{problems}</span>}
        </button>
      </nav>
      {open && (
        <aside className="sidebar">
          {panel === 'explorer' && <Explorer />}
          {panel === 'search' && <SearchPanel />}
          {panel === 'git' && <GitPanel />}
          {panel === 'run' && <RunPanel />}
          {panel === 'toolchains' && <ToolchainsPanel />}
        </aside>
      )}
    </>
  );
}
