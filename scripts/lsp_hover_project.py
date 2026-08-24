#!/usr/bin/env python3
"""Ask tsc 7.0.2's LSP server for hovers at carets of an EXISTING project — GROUND TRUTH.

`scripts/lsp_hover.py` MATERIALISES its fixture (it writes `spec["files"]` into the
root), which is right for a hand-written pin and wrong for a real project: pointing it
at `build/bench/tsc-project-*` would rewrite tsc's own sources from a spec. This one
opens the files that are already there, by path, and never writes.

CLAUDE.md's rule stands and is why this exists: do not hand-write an expected
hover while `tools/tsgo-7.0.2/lib/tsc --lsp -stdio` can be asked. Note the standing
caveat — that binary DIVERGES from pristine tsc in places, so it is the best
instrument for a *naming/display* question and not a baseline oracle.

Usage:  lsp_hover_project.py <projectDir> <carets.tsv>
carets.tsv: one `label<TAB>relativeFile<TAB>byteOffset` row per line (`#` comments ok).
Prints TSV: label \t file \t offset \t hover-text (newlines escaped)
"""
import json, os, subprocess, sys, threading

TSC = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "tools", "tsgo-7.0.2", "lib", "tsc")
TSC = os.path.normpath(TSC)


class Lsp:
    def __init__(self, root):
        self.p = subprocess.Popen(
            [TSC, "--lsp", "-stdio"], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=root)
        self.id = 0
        self.err = []
        threading.Thread(target=self._drain, daemon=True).start()

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
        buf = b""
        while len(buf) < n:
            chunk = self.p.stdout.read(n - len(buf))
            if not chunk:
                raise EOFError("short read")
            buf += chunk
        return json.loads(buf.decode("utf8"))

    def request(self, method, params):
        self.id += 1
        mine = self.id
        self.send({"jsonrpc": "2.0", "id": mine, "method": method, "params": params})
        while True:
            msg = self.read()
            if msg.get("id") == mine and ("result" in msg or "error" in msg):
                return msg
            if "id" in msg and "method" in msg:
                self.send({"jsonrpc": "2.0", "id": msg["id"], "result": None})

    def notify(self, method, params):
        self.send({"jsonrpc": "2.0", "method": method, "params": params})


def offset_to_pos(text, offset):
    before = text[:offset]
    return {"line": before.count("\n"), "character": offset - (before.rfind("\n") + 1)}


def main():
    root = os.path.abspath(sys.argv[1])
    carets = []
    for line in open(sys.argv[2]):
        line = line.split("#")[0].strip()
        if not line:
            continue
        label, rel, off = line.split("\t")
        carets.append((label, rel, int(off)))
    if not carets:
        sys.exit("REFUSED: no carets — an empty sweep agrees vacuously")

    texts = {}
    for _, rel, _ in carets:
        if rel not in texts:
            # newline="" is LOAD-BEARING: universal-newline translation collapses
            # CRLF and shifts every offset — and the tsc profile's sources ARE CRLF.
            with open(os.path.join(root, rel), encoding="utf8", newline="") as f:
                texts[rel] = f.read()

    lsp = Lsp(root)
    lsp.request("initialize", {
        "processId": os.getpid(), "rootUri": "file://" + root,
        "capabilities": {"textDocument": {"hover": {"contentFormat": ["plaintext"]}}}})
    lsp.notify("initialized", {})
    for rel, text in texts.items():
        lsp.notify("textDocument/didOpen", {"textDocument": {
            "uri": "file://" + os.path.join(root, rel),
            "languageId": "typescript", "version": 1, "text": text}})
    for label, rel, off in carets:
        resp = lsp.request("textDocument/hover", {
            "textDocument": {"uri": "file://" + os.path.join(root, rel)},
            "position": offset_to_pos(texts[rel], off)})
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
        print("%s\t%s\t%d\t%s" % (label, rel, off, body.replace("\n", "\\n")))
        sys.stdout.flush()
    lsp.request("shutdown", {})
    lsp.notify("exit", {})


main()
