'use strict';
// Executed only by the companion's pinned Node. No npm, npx, installers or shell.
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const entries = Object.freeze({
  pyright: ['pyright/langserver.index.js', '--stdio'],
  typescript: ['typescript-language-server/lib/cli.mjs', '--stdio'],
  bashls: ['bash-language-server/out/cli.js', 'start'],
  html: ['vscode-langservers-extracted/lib/html-language-server/node/htmlServerMain.js', '--stdio'],
  css: ['vscode-langservers-extracted/lib/css-language-server/node/cssServerMain.js', '--stdio'],
  json: ['vscode-langservers-extracted/lib/json-language-server/node/jsonServerMain.js', '--stdio'],
});
const id = process.argv[2];
if (!Object.prototype.hasOwnProperty.call(entries, id)) {
  process.stderr.write('Unknown bundled language server\n');
  process.exit(64);
}
const [entry, ...args] = entries[id];
const file = path.join(__dirname, 'node_modules', entry);
// Flags are owned by Forge, never by a project. LSP stays exclusively on stdout.
process.argv = [process.execPath, file, ...args];
if (id === 'bashls') {
  // Its CLI intentionally does nothing when imported instead of require.main.
  require(file).runCli();
} else {
  import(pathToFileURL(file).href).catch(error => {
    process.stderr.write(String(error) + '\n');
    process.exitCode = 1;
  });
}
