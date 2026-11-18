/** Project-wide search + replace with regex support (req. 2, 3). */
import { useMemo, useState } from 'react';
import { ReplaceAll } from 'lucide-react';
import { store, useApp } from '../../state/store';
import * as vfs from '../../core/vfs';

export function SearchPanel() {
  const project = useApp((s) => s.projects.find((p) => p.id === s.activeProjectId));
  const [query, setQuery] = useState('');
  const [replacement, setReplacement] = useState('');
  const [regex, setRegex] = useState(false);
  const [caseSense, setCaseSense] = useState(false);
  const [showReplace, setShowReplace] = useState(false);
  const [replaced, setReplaced] = useState<number | null>(null);

  const results = useMemo(() => {
    if (!project || !query) return [];
    return vfs.searchProject(project, query, { regex, caseSensitive: caseSense }).slice(0, 300);
  }, [project, query, regex, caseSense]);

  const grouped = useMemo(() => {
    const m = new Map<string, typeof results>();
    for (const r of results) {
      const arr = m.get(r.path) ?? [];
      arr.push(r);
      m.set(r.path, arr);
    }
    return Array.from(m.entries());
  }, [results]);

  const replaceAll = () => {
    if (!project || !query) return;
    const { files, count } = vfs.replaceInProject(project, query, replacement, { regex, caseSensitive: caseSense });
    store.set({ projects: store.state.projects.map((p) => (p.id === project.id ? { ...p, files } : p)) });
    setReplaced(count);
    store.lintWorkspace();
  };

  return (
    <>
      <div className="sidebar-header"><span>Search</span></div>
      <div style={{ padding: '0 10px 8px' }}>
        <div className="search-row">
          <input className="textfield" placeholder="Search in project" value={query} onChange={(e) => { setQuery(e.target.value); setReplaced(null); }} />
          <button className={'toggle-btn' + (caseSense ? ' active' : '')} title="Match case" onClick={() => setCaseSense(!caseSense)}>Aa</button>
          <button className={'toggle-btn' + (regex ? ' active' : '')} title="Regex" onClick={() => setRegex(!regex)}>.*</button>
        </div>
        <div className="search-row">
          <button className="btn small" onClick={() => setShowReplace(!showReplace)}>{showReplace ? '▾' : '▸'} Replace</button>
          {query && <span className="chip">{results.length}{results.length >= 300 ? '+' : ''} results</span>}
        </div>
        {showReplace && (
          <div className="search-row">
            <input className="textfield" placeholder="Replace with…" value={replacement} onChange={(e) => setReplacement(e.target.value)} />
            <button className="btn small primary" onClick={replaceAll} disabled={!query}><ReplaceAll size={13} /> All</button>
          </div>
        )}
        {replaced !== null && <div className="section-note" style={{ padding: '2px 0 0' }}>Replaced {replaced} occurrence{replaced === 1 ? '' : 's'} across the project.</div>}
      </div>
      <div className="sidebar-body">
        {!query && <div className="empty-state">Search across all project files.<br />Supports regular expressions.</div>}
        {grouped.map(([path, matches]) => (
          <div key={path}>
            <div className="search-file-head">{path} <span className="chip">{matches.length}</span></div>
            {matches.slice(0, 40).map((m, i) => (
              <div key={i} className="search-result" onClick={() => store.openFile(m.path, m.line)}>
                <span className="loc">{m.line}:{m.col}</span>
                <Highlight text={m.preview} query={query} regex={regex} caseSense={caseSense} />
              </div>
            ))}
          </div>
        ))}
      </div>
    </>
  );
}

function Highlight({ text, query, regex, caseSense }: { text: string; query: string; regex: boolean; caseSense: boolean }) {
  try {
    const re = new RegExp(regex ? query : vfs.escapeRegExp(query), caseSense ? 'g' : 'gi');
    const parts: (string | JSX.Element)[] = [];
    let last = 0, m: RegExpExecArray | null, k = 0;
    while ((m = re.exec(text)) && k < 20) {
      if (m.index > last) parts.push(text.slice(last, m.index));
      parts.push(<mark key={k}>{m[0]}</mark>);
      last = m.index + m[0].length;
      if (m[0].length === 0) re.lastIndex++;
      k++;
    }
    parts.push(text.slice(last));
    return <span className="mono" style={{ fontSize: 11.5 }}>{parts}</span>;
  } catch {
    return <span className="mono" style={{ fontSize: 11.5 }}>{text}</span>;
  }
}
