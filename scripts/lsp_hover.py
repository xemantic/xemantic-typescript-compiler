#!/usr/bin/env python3
"""Ask tsc 7.0.2's LSP server for hovers at a list of carets — GROUND TRUTH.

`tools/tsgo-7.0.2/lib/tsc --lsp -stdio` is an LSP server, so an (API.*) expectation
can be READ OUT of tsc rather than reasoned from `typescript-go-repo/internal/ls`.
Round 924 used it for (BUG.4) and two of its own predictions were wrong: an object
literal's member widens to `number` where a shorthand's `const` source keeps `5`,
and a type reported under a synonymous alias is a display-layer property rather
than a member one.

Usage: lsp_hover.py <projectDir> <spec.json>
spec.json: {"files": {"name.ts": "text"}, "carets": [{"label":..,"file":..,"offset":..}]}
Prints TSV: label \t offset \t hover-text (newlines escaped)
"""
import json, os, subprocess, sys, threading

TSC = "/home/claude/git/xemantic-typescript-compiler/tools/tsgo-7.0.2/lib/tsc"


class Lsp:
    def __init__(self, root):
        self.p = subprocess.Popen(
            [TSC, "--lsp", "-stdio"], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=root)
        self.id = 0
        self.err = []
        t = threading.Thread(target=self._drain, daemon=True)
        t.start()

    def _drain(self):
        for line in self.p.stderr:
            self.err.append(line.decode("utf8", "replace"))

    def send(self, obj):
        data = json.dumps(obj).encode("utf8")
        self.p.stdin.write(b"Content-Length: %d\r\n\r\n" % len(data))
        self.p.stdin.write(data)
        self.p.stdin.flush()

    def read(self):
        headers = {}
        while True:
            line = self.p.stdout.readline()
            if not line:
                raise EOFError("server closed: " + "".join(self.err[-20:]))
            line = line.decode("utf8").strip()
            if line == "":
                break
            k, _, v = line.partition(":")
            headers[k.strip().lower()] = v.strip()
        n = int(headers["content-length"])
        return json.loads(self.p.stdout.read(n).decode("utf8"))

    def request(self, method, params):
        self.id += 1
        mine = self.id
        self.send({"jsonrpc": "2.0", "id": mine, "method": method, "params": params})
        while True:
            msg = self.read()
            if msg.get("id") == mine and ("result" in msg or "error" in msg):
                return msg
            if "id" in msg and "method" in msg:  # server -> client request
                self.send({"jsonrpc": "2.0", "id": msg["id"], "result": None})

    def notify(self, method, params):
        self.send({"jsonrpc": "2.0", "method": method, "params": params})


def offset_to_pos(text, offset):
    before = text[:offset]
    line = before.count("\n")
    col = offset - (before.rfind("\n") + 1)
    return {"line": line, "character": col}


def main():
    root = os.path.abspath(sys.argv[1])
    spec = json.load(open(sys.argv[2]))
    os.makedirs(root, exist_ok=True)
    for name, text in spec["files"].items():
        with open(os.path.join(root, name), "w") as f:
            f.write(text)
    if "tsconfig.json" not in spec["files"]:
        with open(os.path.join(root, "tsconfig.json"), "w") as f:
            json.dump({"compilerOptions": {"strict": True, "target": "es2020",
                                           "module": "esnext"}}, f)
    lsp = Lsp(root)
    lsp.request("initialize", {
        "processId": os.getpid(),
        "rootUri": "file://" + root,
        "capabilities": {"textDocument": {"hover": {"contentFormat": ["plaintext"]}}},
    })
    lsp.notify("initialized", {})
    for name, text in spec["files"].items():
        lsp.notify("textDocument/didOpen", {"textDocument": {
            "uri": "file://" + os.path.join(root, name),
            "languageId": "typescript", "version": 1, "text": text}})
    out = []
    for caret in spec["carets"]:
        name = caret["file"]
        text = spec["files"][name]
        resp = lsp.request("textDocument/hover", {
            "textDocument": {"uri": "file://" + os.path.join(root, name)},
            "position": offset_to_pos(text, caret["offset"])})
        result = resp.get("result")
        if result is None:
            body = "<none>"
        else:
            c = result.get("contents")
            if isinstance(c, dict):
                body = c.get("value", "")
            elif isinstance(c, list):
                body = " | ".join(x if isinstance(x, str) else x.get("value", "") for x in c)
            else:
                body = str(c)
        out.append((caret["label"], caret["offset"], body.replace("\n", "\\n")))
    for label, off, body in out:
        print("%s\t%d\t%s" % (label, off, body))
    lsp.request("shutdown", {})
    lsp.notify("exit", {})


main()
