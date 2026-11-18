/** First-run sample workspaces — also used by the "New Project" templates. */
import type { FileNode, Project } from '../core/types';
import { uid } from '../core/types';
import { languageForPath } from '../core/languages';

interface Spec { [path: string]: string | Spec }

const HELLO_JS = `// Welcome to Forge — a professional IDE for Android.
// This file runs 100% on-device inside the secure worker sandbox.
// Try: tap ▶ Run (Ctrl+R), set a breakpoint on line 12, then Debug.

function fibonacci(n) {
  if (n <= 1) return n;
  return fibonacci(n - 1) + fibonacci(n - 2);
}

const results = [];
for (let i = 0; i < 10; i++) {
  results.push(fibonacci(i));   // <- breakpoint-friendly line
}
console.log('Fibonacci:', results.join(', '));

const stats = { count: results.length, sum: results.reduce((a, b) => a + b, 0) };
console.log('Stats:', stats);
console.warn('Network is disabled inside the sandbox — try fetch() to see.');
`;

const HELLO_TS = `// TypeScript — types are stripped before sandbox execution.
interface Device { kind: string; width: number; }

const devices: Device[] = [
  { kind: 'phone', width: 412 },
  { kind: 'foldable', width: 768 },
  { kind: 'tablet', width: 1024 },
];

for (const d of devices) {
  console.log(\`\${d.kind.padEnd(8)} → \${d.width}dp\`);
}
`;

const HELLO_PY = `# Python files get full editing support: highlighting, linting,
# formatting rules and symbol navigation. Execution requires the
# Pyodide (WASM CPython) toolchain plugin — see the Toolchains panel.

def greet(name: str) -> str:
    return f"Hello, {name}!"

for name in ["phone", "tablet", "foldable"]:
    print(greet(name))
`;

const HELLO_C = `// C sources are fully editable with clang-format style formatting.
// On-device compilation plugs in via the clang toolchain (WASM/NDK).
#include <stdio.h>

int main(void) {
    for (int i = 0; i < 5; i++) {
        printf("forge %d\\n", i);
    }
    return 0;
}
`;

const WEB_INDEX = `<!doctype html>
<html>
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <link rel="stylesheet" href="style.css" />
    <title>Forge Preview</title>
  </head>
  <body>
    <main class="card">
      <h1>Hello from Forge</h1>
      <p>This page is rendered by the isolated preview sandbox.</p>
      <button id="btn">Tap me</button>
      <p id="count">0 taps</p>
    </main>
    <script src="script.js"></script>
  </body>
</html>
`;

const WEB_CSS = `body {
  margin: 0;
  min-height: 100vh;
  display: grid;
  place-items: center;
  background: linear-gradient(160deg, #101527, #1d1030);
  color: #eef1ff;
  font-family: system-ui, sans-serif;
}

.card {
  text-align: center;
  padding: 32px 40px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.06);
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.4);
}

button {
  font-size: 16px;
  padding: 10px 22px;
  border: 0;
  border-radius: 10px;
  background: #4f7cff;
  color: white;
  cursor: pointer;
}
`;

const WEB_JS = `let taps = 0;
document.getElementById('btn').addEventListener('click', () => {
  taps += 1;
  document.getElementById('count').textContent = taps + ' taps';
});
`;

const README = `# Welcome to Forge IDE

A professional, offline-first development environment designed for
**phones, tablets and foldables**.

## Quick tour

| Action | Shortcut |
| ------ | -------- |
| Command palette | \`Ctrl+K\` or ✦ in the bar |
| Run file | \`Ctrl+R\` |
| Debug (breakpoints) | click the line number gutter |
| Terminal | \`Ctrl+\`\` |
| Save | \`Ctrl+S\` |
| Format | \`Ctrl+Shift+F\` |

## What runs on-device

- **JavaScript / TypeScript** → real execution in a secure worker sandbox
- **HTML / CSS / Markdown** → live isolated preview
- **Shell** → the Forge shell interprets scripts over the project VFS
- **C / C++ / Java / Python / SQL** → full editing + linting; execution
  activates when the matching toolchain plugin is installed
  (Toolchains panel — wrench icon).

## Security model

User code executes in a dedicated worker with no DOM, no network and a
configurable time limit. GitHub tokens, IDE state and Android APIs are
never reachable from executed code.
`;

const NOTES_SQL = `-- SQLite runs through the sql.js WASM engine once the plugin is installed.
CREATE TABLE devices (id INTEGER PRIMARY KEY, kind TEXT, width INTEGER);
INSERT INTO devices (kind, width) VALUES ('phone', 412), ('tablet', 1024);
SELECT * FROM devices WHERE width > 500 ORDER BY width;
`;

function buildTree(spec: Spec, base = ''): { root: FileNode; files: Project['files'] } {
  const files: Project['files'] = {};
  const make = (s: Spec, basePath: string): FileNode[] => {
    const nodes: FileNode[] = [];
    for (const [name, val] of Object.entries(s)) {
      if (typeof val === 'string') {
        const path = basePath + '/' + name;
        nodes.push({ id: uid(), name, type: 'file' });
        files[path] = { path, content: val, language: languageForPath(path), updatedAt: Date.now() };
      } else {
        nodes.push({ id: uid(), name, type: 'folder', children: make(val, basePath + '/' + name) });
      }
    }
    return nodes;
  };
  const root: FileNode = { id: uid(), name: '/', type: 'folder', children: make(spec, base) };
  return { root, files };
}

function makeProject(name: string, spec: Spec, runFile: string): Project {
  const { root, files } = buildTree(spec);
  const rc = { id: uid(), name: 'Run ' + runFile, file: '/' + runFile, args: '', env: {}, cwd: '/', buildCmd: '', runCmd: '', debugCmd: '' };
  return { id: uid(), name, createdAt: Date.now(), root, files, runConfigs: [rc], activeRunConfigId: rc.id };
}

export function seedProjects(count?: number, name?: string, template: 'empty' | 'javascript' | 'web' | 'python' = 'javascript'): Project[] {
  if (count !== undefined && name !== undefined) {
    const specs: Record<string, Spec> = {
      empty: { 'README.md': `# ${name}\n\nCreated with Forge IDE.\n` },
      javascript: { 'README.md': `# ${name}\n`, src: { 'main.js': HELLO_JS, 'util.ts': HELLO_TS }, 'package.json': JSON.stringify({ name: name.toLowerCase().replace(/\s+/g, '-'), version: '1.0.0', main: 'src/main.js' }, null, 2) },
      web: { 'index.html': WEB_INDEX, 'style.css': WEB_CSS, 'script.js': WEB_JS, 'README.md': `# ${name}\n\nWeb project — press Run for a live preview.\n` },
      python: { 'README.md': `# ${name}\n\nPython editing is fully featured; execution needs the Pyodide plugin.\n`, 'main.py': HELLO_PY },
    };
    const p = makeProject(name, specs[template], template === 'web' ? 'index.html' : template === 'python' ? 'main.py' : template === 'javascript' ? 'src/main.js' : 'README.md');
    return [p];
  }
  return [
    makeProject('forge-tour', {
      'README.md': README,
      src: {
        'main.js': HELLO_JS,
        'types.ts': HELLO_TS,
        'hello.py': HELLO_PY,
        'native.c': HELLO_C,
        'data.sql': NOTES_SQL,
      },
      web: { 'index.html': WEB_INDEX, 'style.css': WEB_CSS, 'script.js': WEB_JS },
      'config.json': JSON.stringify({ project: 'forge-tour', version: 1, sandbox: { network: false, timeoutMs: 8000 } }, null, 2),
      '.gitignore': 'node_modules/\nbuild/\n*.log\n',
    }, 'src/main.js'),
  ];
}
