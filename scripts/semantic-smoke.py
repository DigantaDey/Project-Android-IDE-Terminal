#!/usr/bin/env python3
"""Exercise the actual pinned JS/WASM servers offline on the build host, not mocks.

Android instrumentation separately verifies clangd plus all six of these servers
through the authenticated companion UID, raw pipes and native syscall policy.
"""
import argparse
from collections import deque
import json
from pathlib import Path
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parent.parent


class Peer:
    def __init__(self, prefix, server, project):
        self.server = server
        self.project = project
        self.messages = queue.Queue(maxsize=256)
        self.events = []
        self.errors = deque(maxlen=20)
        self.sequence = 0
        self.config = {}
        options = {}
        if server == "typescript":
            options = {"hostInfo": "Forge host tests", "disableAutomaticTypingAcquisition": True, "maxTsServerMemory": 256, "tsserver": {"path": str(prefix / "share/forge-lsp/node_modules/typescript/lib/tsserver.js"), "useSyntaxServer": "never"}}
        elif server == "pyright":
            self.config = {"python": {"pythonPath": sys.executable, "analysis": {"typeCheckingMode": "basic", "diagnosticMode": "openFilesOnly"}}}
        elif server == "bashls":
            options = {"shellcheckPath": "", "shfmt": {"path": ""}, "explainshellEndpoint": "", "backgroundAnalysisMaxFiles": 100}
            self.config = {"bashIde": options}
        elif server == "json":
            self.config = {"json": {"schemaDownload": {"enable": False}, "validate": {"enable": True}}}
        self.process = subprocess.Popen([shutil.which("node"), "--max-old-space-size=256", str(prefix / "share/forge-lsp/launch.cjs"), server], cwd=project, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        threading.Thread(target=self.reader, daemon=True).start()
        threading.Thread(target=self.stderr, daemon=True).start()
        initialized = self.request("initialize", {"processId": None, "rootUri": project.as_uri() + "/", "workspaceFolders": [{"uri": project.as_uri() + "/", "name": "Fixture"}], "initializationOptions": options, "capabilities": {"general": {"positionEncodings": ["utf-16"]}, "workspace": {"configuration": True, "workspaceFolders": True}, "textDocument": {"completion": {"completionItem": {"snippetSupport": False}}, "definition": {"linkSupport": True}, "documentSymbol": {"hierarchicalDocumentSymbolSupport": True}, "publishDiagnostics": {"versionSupport": True}}}})
        self.capabilities = initialized["result"]["capabilities"]
        self.notify("initialized", {})
        self.notify("workspace/didChangeConfiguration", {"settings": self.config})

    def stderr(self):
        for line in self.process.stderr:
            self.errors.append(line.decode("utf-8", "replace")[:2000])

    def reader(self):
        try:
            while True:
                length = None
                count = 0
                while True:
                    line = self.process.stdout.readline(8193)
                    count += len(line)
                    if not line:
                        raise EOFError("server stdout closed")
                    if count > 8192:
                        raise ValueError("oversized protocol header")
                    if line == b"\r\n":
                        break
                    if line.lower().startswith(b"content-length:"):
                        length = int(line.split(b":", 1)[1])
                if length is None or not 0 <= length <= 8 * 1024 * 1024:
                    raise ValueError("invalid protocol length")
                data = self.process.stdout.read(length)
                if len(data) != length:
                    raise EOFError("truncated LSP message")
                self.messages.put(json.loads(data), timeout=5)
        except Exception as error:
            self.messages.put(error)

    def write(self, message):
        data = json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode()
        self.process.stdin.write(("Content-Length: " + str(len(data)) + "\r\n\r\n").encode() + data)
        self.process.stdin.flush()

    def notify(self, method, params):
        self.write({"jsonrpc": "2.0", "method": method, "params": params})

    def next(self, timeout):
        try:
            message = self.messages.get(timeout=max(0.01, timeout))
        except queue.Empty as error:
            raise AssertionError(self.server + " timeout; stderr: " + "".join(self.errors)) from error
        if isinstance(message, Exception):
            raise AssertionError(self.server + ": " + str(message) + "; stderr: " + "".join(self.errors)) from message
        if "method" in message and "id" in message:
            method = message["method"]
            result = None
            if method == "workspace/configuration":
                result = []
                for item in message.get("params", {}).get("items", []):
                    value = self.config
                    for part in item.get("section", "").split("."):
                        if part:
                            value = value.get(part) if isinstance(value, dict) else None
                    result.append(value)
            elif method == "workspace/workspaceFolders":
                result = [{"uri": self.project.as_uri() + "/", "name": "Fixture"}]
            elif method == "workspace/applyEdit":
                result = {"applied": False}
            elif method not in ("window/workDoneProgress/create", "window/showMessageRequest"):
                self.write({"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32601, "message": "Unsupported client method"}})
                return None
            self.write({"jsonrpc": "2.0", "id": message["id"], "result": result})
            return None
        if "method" in message:
            self.events.append(message)
        return message

    def request(self, method, params):
        self.sequence += 1
        identifier = self.sequence
        self.write({"jsonrpc": "2.0", "id": identifier, "method": method, "params": params})
        end = time.monotonic() + 35
        while time.monotonic() < end:
            message = self.next(end - time.monotonic())
            if message and message.get("id") == identifier and "method" not in message:
                if "error" in message:
                    raise AssertionError(self.server + " " + method + ": " + str(message["error"]) + "; stderr: " + "".join(self.errors))
                return message
        raise AssertionError("No response: " + method)

    def open(self, name, language, text):
        path = self.project / name
        path.write_text(text)
        self.uri = path.as_uri()
        self.notify("textDocument/didOpen", {"textDocument": {"uri": self.uri, "languageId": language, "version": 1, "text": text}})

    def at(self, method, line, column):
        params = {"textDocument": {"uri": self.uri}, "position": {"line": line, "character": column}}
        if method == "references":
            params["context"] = {"includeDeclaration": True}
        return self.request("textDocument/" + method, params)["result"]

    def symbols(self):
        return self.request("textDocument/documentSymbol", {"textDocument": {"uri": self.uri}})["result"]

    def diagnostics(self, nonempty=True):
        end = time.monotonic() + 35
        while True:
            for event in self.events:
                if event["method"] == "textDocument/publishDiagnostics" and event.get("params", {}).get("uri") == self.uri and bool(event["params"].get("diagnostics")) == nonempty:
                    self.events.clear()
                    return event["params"]
            self.events.clear()
            if time.monotonic() >= end:
                raise AssertionError(self.server + " did not publish expected diagnostics")
            self.next(end - time.monotonic())

    def change(self, text):
        self.events.clear()
        self.notify("textDocument/didChange", {"textDocument": {"uri": self.uri, "version": 2}, "contentChanges": [{"text": text}]})

    def close(self):
        try:
            self.request("shutdown", {})
            self.notify("exit", {})
            self.process.stdin.close()
            self.process.wait(timeout=8)
        finally:
            if self.process.poll() is None:
                self.process.kill()
                self.process.wait()


def labels(value):
    return {item["label"] for item in (value if isinstance(value, list) else value.get("items", []))}


def check(prefix, server):
    with tempfile.TemporaryDirectory(prefix="forge-lsp-test-") as directory:
        peer = Peer(prefix, server, Path(directory))
        try:
            if server == "typescript":
                peer.open("main.ts", "typescript", "function twice(value: number): number { return value * 2; }\nconst answer = twice(21);\nanswer.toFi\n")
                assert "toFixed" in labels(peer.at("completion", 2, 11))
                assert "number" in json.dumps(peer.at("hover", 1, 7))
                assert peer.at("definition", 1, 17)
                assert len(peer.at("references", 0, 11)) >= 2
                assert peer.symbols()
                peer.diagnostics()
                peer.change("const answer: number = 'wrong';\n")
                assert any(item.get("code") == 2322 for item in peer.diagnostics()["diagnostics"])
            elif server == "pyright":
                peer.open("main.py", "python", "class Rocket:\n    speed: int = 42\nrocket = Rocket()\nrocket.speed\n")
                assert "speed" in labels(peer.at("completion", 3, 7))
                assert "Rocket" in json.dumps(peer.at("hover", 2, 2))
                assert peer.at("definition", 3, 9)
                assert peer.symbols()
                peer.change("value: int = 'wrong'\n")
                assert peer.diagnostics()["diagnostics"]
            elif server == "bashls":
                peer.open("main.sh", "shellscript", "#!/bin/bash\ngreet() { echo hello; }\ngreet\n")
                # This server parses didOpen asynchronously; its versioned diagnostics
                # notification is a real readiness signal, not a timing-dependent sleep.
                peer.diagnostics(False)
                assert "greet" in json.dumps(peer.symbols())
                assert peer.at("definition", 2, 2)
                assert "greet" in labels(peer.at("completion", 2, 3))
            elif server == "html":
                peer.open("index.html", "html", "<di")
                assert any("div" in item for item in labels(peer.at("completion", 0, 3)))
                assert peer.capabilities.get("hoverProvider")
            elif server == "css":
                peer.open("style.css", "css", "a { colo }")
                assert "color" in labels(peer.at("completion", 0, 8))
                peer.change("a { color: #fff; }")
                assert "color" in json.dumps(peer.at("hover", 0, 6)).lower()
            elif server == "json":
                peer.open("main.json", "json", '{"a":}')
                peer.diagnostics()
                peer.change('{"a":1}')
                peer.diagnostics(False)
                assert peer.request("textDocument/formatting", {"textDocument": {"uri": peer.uri}, "options": {"tabSize": 2, "insertSpaces": True}})["result"]
            print("PASS", server, "real semantic stdio, configuration, inference/navigation or structured document service", flush=True)
        finally:
            peer.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefix", type=Path, default=ROOT / "build/semantic-host")
    parser.add_argument("--server", choices=("typescript", "pyright", "bashls", "html", "css", "json"))
    args = parser.parse_args()
    for server in ([args.server] if args.server else ["typescript", "pyright", "bashls", "html", "css", "json"]):
        check(args.prefix.resolve(), server)


if __name__ == "__main__":
    main()
