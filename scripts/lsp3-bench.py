#!/usr/bin/env python3
"""(LSP.3) The honest comparison: xtsc-lsp vs `tsgo --lsp`, BOTH LONG-LIVED.

Every earlier tsgo number on `docs/perf/incremental-vs-tsgo.md` came from its
`--incremental` CLI in fresh processes, whose per-query floor (process start,
.tsbuildinfo read, re-stat) tsgo's LSP never pays. This harness drives BOTH
servers over stdio in ONE session each, on tsc's own 78 compiler sources, with
the (INC.90) edit variants (CRLF-preserving — the fixture's own trap):

  cell 1  first-open to first hover   (spawn -> initialize -> didOpen -> hover)
  cell 2  hover after a BODY-ONLY didChange, reps alternated with reverts
  cell 3  hover after a SIGNATURE didChange, same shape
  cell 4  whole-project diagnostics — OURS ONLY (didSave -> publishDiagnostics
          wave; tsgo's LSP has no project-wide call, its own source says so)
  cell 5  per-file pull `textDocument/diagnostic` on the edited file, both

Receipts, per the kir-bench law (a server doing LESS work reads as the fastest
arm): every hover must be NON-EMPTY; after the body change a hover on the ADDED
line's own const must answer (proves the didChange landed — a server ignoring
edits reads as instant); the publish wave's summed row count is printed and
must be > 0.

Usage: lsp3-bench.py <ours|tsgo> <projectRoot> <editsDir> <reps>
Output: TSV rows  server cell rep ms receipt  on stdout; diagnostics to stderr.
"""
import json, os, subprocess, sys, threading, time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TSGO = os.path.join(REPO, "tools/tsgo-7.0.2/lib/tsc")


class Server:
    """A long-lived stdio LSP session (the lsp_hover.py client, generalized)."""

    def __init__(self, cmd, root):
        self.p = subprocess.Popen(
            cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, cwd=root)
        self.id = 0
        self.err = []
        self.notes = []            # server-initiated notifications, in order
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
        return json.loads(self.p.stdout.read(int(headers["content-length"])).decode("utf8"))

    def request(self, method, params):
        self.id += 1
        mine = self.id
        self.send({"jsonrpc": "2.0", "id": mine, "method": method, "params": params})
        while True:
            msg = self.read()
            if msg.get("id") == mine and ("result" in msg or "error" in msg):
                return msg
            if "id" in msg and "method" in msg:          # server -> client request
                self.send({"jsonrpc": "2.0", "id": msg["id"], "result": None})
            elif "method" in msg:                        # notification
                self.notes.append(msg)

    def notify(self, method, params):
        self.send({"jsonrpc": "2.0", "method": method, "params": params})


def read_text(path):
    # newline='' — the fixture is CRLF and a translated read shifts every offset
    # ((INC.41)'s trap; (INC.90) re-learned it in this very fixture).
    with open(path, newline="", encoding="utf-8") as f:
        return f.read()


def pos_of(text, needle, extra=0):
    i = text.index(needle) + extra
    before = text[:i]
    return {"line": before.count("\n"), "character": i - (before.rfind("\n") + 1)}


def hover_text(msg):
    r = msg.get("result")
    if not r:
        return ""
    c = r.get("contents")
    if isinstance(c, dict):
        return c.get("value", "") or ""
    if isinstance(c, list):
        return " ".join(str(x) for x in c)
    return str(c or "")


def main():
    which, root, edits, reps = sys.argv[1], os.path.abspath(sys.argv[2]), sys.argv[3], int(sys.argv[4])
    rel = "src/compiler/binder.ts"
    path = os.path.join(root, rel)
    uri = "file://" + path
    orig = read_text(os.path.join(edits, "orig.ts"))
    body = read_text(os.path.join(edits, "body.ts"))
    sig = read_text(os.path.join(edits, "sig.ts"))
    on_disk = read_text(path)
    assert on_disk == orig, "project's %s is not the recorded original" % rel

    if which == "ours":
        cp = os.environ["XTSC_LSP_CP"]
        cmd = ["java", "-Xmx4g", "-cp", cp,
               "com.xemantic.typescript.compiler.lsp.XtscLspMainKt"]
    else:
        cmd = [TSGO, "--lsp", "-stdio"]

    caret = lambda text: pos_of(text, "export function bindSourceFile", len("export function bindS"))
    rows = []
    t_spawn = time.monotonic()
    s = Server(cmd, root)
    init = s.request("initialize", {
        "processId": os.getpid(), "rootUri": "file://" + root,
        "capabilities": {"textDocument": {"hover": {}, "publishDiagnostics": {}}},
    })
    assert "result" in init, init
    s.notify("initialized", {})
    version = [1]

    def did_change(text):
        version[0] += 1
        s.notify("textDocument/didChange", {
            "textDocument": {"uri": uri, "version": version[0]},
            "contentChanges": [{"text": text}],
        })

    def hover(text, position=None):
        t0 = time.monotonic()
        msg = s.request("textDocument/hover", {
            "textDocument": {"uri": uri},
            "position": position or caret(text),
        })
        dt = (time.monotonic() - t0) * 1000
        return dt, hover_text(msg)

    # --- cell 1: first-open to first hover -----------------------------------
    s.notify("textDocument/didOpen", {"textDocument": {
        "uri": uri, "languageId": "typescript", "version": 1, "text": orig}})
    dt, txt = hover(orig)
    first = (time.monotonic() - t_spawn) * 1000
    assert txt, "empty first hover"
    rows.append((which, "first_open_to_first_hover", 0, first, "hover=%d chars" % len(txt)))

    # --- cells 2 and 3: hover after each edit shape --------------------------
    for cell, text in (("hover_after_body_edit", body), ("hover_after_signature_edit", sig)):
        for rep in range(reps):
            did_change(text)
            dt, txt = hover(text)
            assert txt, "empty hover in %s rep %d" % (cell, rep)
            receipt = "hover=%d chars" % len(txt)
            if cell == "hover_after_body_edit" and rep == 0:
                cdt, ctxt = hover(text, pos_of(text, "__bodyOnlyProbe", 3))
                assert ctxt, "the body edit did not land — probe hover empty"
                receipt += " probe_ok"
            rows.append((which, cell, rep, dt, receipt))
            did_change(orig)
            rdt, rtxt = hover(orig)
            assert rtxt, "empty revert hover"
            rows.append((which, cell + "_revert", rep, rdt, ""))

    # --- cell 4 (ours): the project-wide publish wave ------------------------
    if which == "ours":
        before = len(s.notes)
        t0 = time.monotonic()
        s.notify("textDocument/didSave", {"textDocument": {"uri": uri}, "text": orig})
        dt, _ = hover(orig)          # sequential server: publishes precede this reply
        wall = (time.monotonic() - t0) * 1000
        pubs = [n for n in s.notes[before:]
                if n.get("method") == "textDocument/publishDiagnostics"]
        rowsum = sum(len(n["params"].get("diagnostics", [])) for n in pubs)
        files = len(pubs)
        assert rowsum > 0, "publish wave carried no rows"
        rows.append((which, "project_diagnostics_publish", 0, wall,
                     "files=%d rows=%d (wall includes one trailing hover)" % (files, rowsum)))

    # --- cell 5: per-file pull on the edited file, both ----------------------
    for rep in range(reps):
        t0 = time.monotonic()
        msg = s.request("textDocument/diagnostic", {"textDocument": {"uri": uri}})
        dt = (time.monotonic() - t0) * 1000
        r = msg.get("result") or {}
        n = len(r.get("items", [])) if isinstance(r, dict) else -1
        rows.append((which, "pull_diagnostics_edited_file", rep, dt,
                     "items=%d" % n if "result" in msg else "error:%s" % msg.get("error")))

    for r in rows:
        print("%s\t%s\t%d\t%.0f\t%s" % r)
    pubs = [n for n in s.notes if n.get("method") == "textDocument/publishDiagnostics"]
    sys.stderr.write("session publishDiagnostics notifications: %d (rows %d)\n" % (
        len(pubs), sum(len(n.get("params", {}).get("diagnostics", [])) for n in pubs)))

    s.request("shutdown", None)
    s.notify("exit", {})
    try:
        s.p.wait(timeout=15)
    except subprocess.TimeoutExpired:
        # tsgo 7.0.2 does not exit on `exit` over -stdio in this build; the
        # measurement is complete, so a lingering process is killed, not fatal.
        sys.stderr.write("server did not exit after `exit`; killed\n")
        s.p.kill()


if __name__ == "__main__":
    main()
