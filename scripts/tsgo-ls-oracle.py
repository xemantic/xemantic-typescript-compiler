#!/usr/bin/env python3
"""The (TSGO.4-a) language-service recording (docs/goport-ls.md § 4).

Drives the SHIPPED tsgo language server (`tools/tsgo-7.0.2/lib/tsc --lsp -stdio`) over the projects
of the type-oracle corpus (build/goport/api-projects: tsc's 78 sources and 200 conformance cases, made
by scripts/tsgo-api-oracle.py) and records its answers:

    build/goport/ls-oracle/<name>.jsonl.gz    line 1 = header {"root","config","libDir","initialize",
                                              "configuration"}; then one {"m","p","r"|"e"} per request
    build/goport/ls-oracle/manifest.tsv       name, records, sha256 of the .jsonl.gz

The port's LsParityTest (TSGO_LS=1) replays every request through the ported language service in
process and compares the JSON answers.

Per non-library file of a project the requests are, at identifier-start carets chosen evenly through
the file (comments and strings skipped): textDocument/hover and textDocument/definition at up to
--hover carets, textDocument/references (includeDeclaration true) at up to --refs carets,
textDocument/completion at up to --completions identifier carets and as many right after a `.`, and
one textDocument/diagnostic (pull). Auto-import completions are switched off
(`js/ts.suggest.autoImports: false`): they need the project system's auto-import registry, which the
port does not run.

Usage: scripts/tsgo-ls-oracle.py [--projects DIR] [--only NAME,...] [--limit N] [--workers N]
"""

import argparse
import concurrent.futures as cf
import gzip
import hashlib
import json
import os
import re
import subprocess
import sys
import threading

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TSC = os.path.realpath(os.path.join(REPO, "tools/tsgo-7.0.2/lib/tsc"))
LIB_DIR = os.path.dirname(TSC)
OUT = os.path.join(REPO, "build/goport/ls-oracle")

CAPABILITIES = {
    "general": {"positionEncodings": ["utf-16"]},
    "textDocument": {
        "hover": {"contentFormat": ["markdown", "plaintext"]},
        "definition": {"linkSupport": True},
        "references": {},
        "completion": {
            "completionItem": {
                "snippetSupport": True,
                "commitCharactersSupport": True,
                "documentationFormat": ["markdown", "plaintext"],
                "deprecatedSupport": True,
                "preselectSupport": True,
                "insertReplaceSupport": True,
                "labelDetailsSupport": True,
                "resolveSupport": {"properties": ["documentation", "detail", "additionalTextEdits"]},
                "tagSupport": {"valueSet": [1]},
            },
            "completionList": {"itemDefaults": ["commitCharacters", "editRange", "insertTextFormat", "data"]},
            "contextSupport": True,
        },
        "diagnostic": {"relatedDocumentSupport": False},
        "publishDiagnostics": {"relatedInformation": True, "tagSupport": {"valueSet": [1, 2]}, "versionSupport": True},
    },
    "workspace": {"configuration": True},
}

# The answer to the server's workspace/configuration request: js/ts, typescript, javascript, editor.
CONFIGURATION = [{"suggest": {"autoImports": False}}, None, None, None]


class Lsp:
    def __init__(self, root):
        self.p = subprocess.Popen([TSC, "--lsp", "-stdio"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, cwd=root)
        self.id = 0
        self.err = []
        threading.Thread(target=self._drain, daemon=True).start()

    def _drain(self):
        for line in self.p.stderr:
            self.err.append(line.decode("utf8", "replace"))
            del self.err[:-50]

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
            if "id" in msg and "method" in msg:  # a server -> client request
                result = CONFIGURATION if msg["method"] == "workspace/configuration" else None
                self.send({"jsonrpc": "2.0", "id": msg["id"], "result": result})

    def notify(self, method, params):
        self.send({"jsonrpc": "2.0", "method": method, "params": params})

    def close(self):
        try:
            self.request("shutdown", None)
            self.notify("exit", None)
            self.p.wait(timeout=30)
        except Exception:
            self.p.kill()


# ------------------------------------------------------------------ carets

_word = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")


def code_mask(text):
    """Offsets of `text` that are code: comments and string/template literals blanked (approximate)."""
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out[i:j] = " " * (j - i)
            i = j
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out[i:j] = [ch if ch == "\n" else " " for ch in text[i:j]]
            i = j
        elif c in "'\"`":
            j = i + 1
            while j < n and text[j] != c:
                if text[j] == "\\":
                    j += 1
                elif c != "`" and text[j] == "\n":
                    break
                j += 1
            j = min(j + 1, n)
            out[i:j] = [ch if ch == "\n" else " " for ch in text[i:j]]
            i = j
        else:
            i += 1
    return "".join(out)


def spread(xs, k):
    if len(xs) <= k:
        return xs
    return [xs[(i * len(xs)) // k] for i in range(k)]


def carets(text, hover, refs, completions):
    code = code_mask(text)
    idents = [m.start() for m in _word.finditer(code)]
    dots = [m.end() for m in re.finditer(r"\.(?=[A-Za-z_$])", code)]
    h = spread(idents, hover)
    return h, spread(idents, refs), spread(idents, completions) + spread(dots, completions)


class Lines:
    """Offset (Python str index) -> LSP {line, character} in UTF-16, lines broken at \\r\\n, \\r, \\n."""

    def __init__(self, text):
        self.text = text
        self.starts = [0]
        i, n = 0, len(text)
        while i < n:
            c = text[i]
            if c == "\r":
                if i + 1 < n and text[i + 1] == "\n":
                    i += 1
                self.starts.append(i + 1)
            elif c == "\n":
                self.starts.append(i + 1)
            i += 1

    def pos(self, off):
        lo, hi = 0, len(self.starts) - 1
        while lo < hi:
            m = (lo + hi + 1) // 2
            if self.starts[m] <= off:
                lo = m
            else:
                hi = m - 1
        seg = self.text[self.starts[lo]:off]
        return {"line": lo, "character": len(seg.encode("utf-16-le")) // 2}


# ------------------------------------------------------------------ one project

def config_files(root, config):
    """The project's own source files: the tsconfig's `files`, or every .ts/.tsx under root."""
    cfg = json.load(open(config))
    if "files" in cfg:
        return [os.path.join(root, f) for f in cfg["files"]]
    out = []
    for d, _, fs in os.walk(root):
        for f in fs:
            if f.endswith((".ts", ".tsx", ".js", ".jsx")) and not f.endswith(".d.ts"):
                out.append(os.path.join(d, f))
    return sorted(out)


def uri(path):
    from urllib.parse import quote
    return "file://" + quote(path, safe="/")


def record(name, root, args):
    root = os.path.realpath(root)
    config = os.path.join(root, "tsconfig.json")
    files = config_files(root, config)
    init = {
        "processId": os.getpid(),
        "rootUri": uri(root),
        "capabilities": CAPABILITIES,
        "initializationOptions": {"disablePushDiagnostics": True},
    }
    lsp = Lsp(root)
    recs = []
    try:
        lsp.request("initialize", init)
        lsp.notify("initialized", {})
        texts = {}
        for f in files:
            raw = open(f, "rb").read()
            # an editor hands the server the DOCUMENT text, without a UTF-8 BOM (VS Code strips it)
            text = raw.decode("utf-8-sig", "surrogateescape")
            texts[f] = text
            lsp.notify("textDocument/didOpen", {"textDocument": {
                "uri": uri(f), "languageId": "typescript", "version": 1, "text": text}})

        def req(m, p):
            resp = lsp.request(m, p)
            rec = {"m": m, "p": p}
            if "error" in resp:
                rec["e"] = resp["error"]
            else:
                rec["r"] = resp["result"]
            recs.append(rec)

        for f in files:
            text = texts[f]
            lines = Lines(text)
            doc = {"uri": uri(f)}
            h, r, c = carets(text, args.hover, args.refs, args.completions)
            req("textDocument/diagnostic", {"textDocument": doc})
            for off in h:
                req("textDocument/hover", {"textDocument": doc, "position": lines.pos(off)})
                req("textDocument/definition", {"textDocument": doc, "position": lines.pos(off)})
            for off in r:
                req("textDocument/references", {"textDocument": doc, "position": lines.pos(off),
                                                "context": {"includeDeclaration": True}})
            for off in c:
                req("textDocument/completion", {"textDocument": doc, "position": lines.pos(off),
                                                "context": {"triggerKind": 1}})
    finally:
        lsp.close()
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name + ".jsonl.gz")
    header = {"root": root, "config": config, "libDir": LIB_DIR, "initialize": init,
              "configuration": CONFIGURATION}
    with gzip.open(path, "wt", encoding="utf-8") as out:
        out.write(json.dumps(header, ensure_ascii=False) + "\n")
        for rec in recs:
            out.write(json.dumps(rec, ensure_ascii=False) + "\n")
    sha = hashlib.sha256(open(path, "rb").read()).hexdigest()
    return name, len(recs), sha


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--projects", default=os.path.join(REPO, "build/goport/api-projects"))
    ap.add_argument("--only", default="")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--hover", type=int, default=60)
    ap.add_argument("--refs", type=int, default=6)
    ap.add_argument("--completions", type=int, default=2)
    args = ap.parse_args()
    names = sorted(n for n in os.listdir(args.projects)
                   if os.path.isfile(os.path.join(args.projects, n, "tsconfig.json")))
    if args.only:
        keep = set(args.only.split(","))
        names = [n for n in names if n in keep]
    if args.limit:
        names = names[:args.limit]
    results = []
    with cf.ThreadPoolExecutor(args.workers) as ex:
        futs = {ex.submit(record, n, os.path.join(args.projects, n), args): n for n in names}
        for fut in cf.as_completed(futs):
            try:
                results.append(fut.result())
                print("%-12s %7d records" % results[-1][:2], flush=True)
            except Exception as e:
                print("%-12s FAILED %s" % (futs[fut], e), file=sys.stderr, flush=True)
    # The manifest lists every recording on disk: a partial run (--only/--limit) re-records only its projects.
    manifest = {}
    path = os.path.join(OUT, "manifest.tsv")
    if os.path.exists(path):
        for line in open(path):
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 3:
                manifest[parts[0]] = (parts[0], int(parts[1]), parts[2])
    for r in results:
        manifest[r[0]] = r
    with open(path, "w") as m:
        for name, n, sha in sorted(manifest.values()):
            m.write("%s\t%d\t%s\n" % (name, n, sha))
    print("recorded %d projects, %d requests -> %s" % (len(results), sum(r[1] for r in results), OUT))


if __name__ == "__main__":
    main()
