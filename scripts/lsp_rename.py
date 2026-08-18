#!/usr/bin/env python3
"""Ask tsc 7.0.2's LSP server what RENAME does at a list of carets — GROUND TRUTH.

Round 925's step 1, and the same technique rounds 923/924 used for hover:
`tools/tsgo-7.0.2/lib/tsc --lsp -stdio` answers `textDocument/prepareRename` and
`textDocument/rename`, so what a shorthand `{ p }` or an `import { p as q }`
rewrites to can be READ OUT of tsc rather than reasoned about.

Usage: lsp_rename.py <projectDir> <spec.json>
spec.json: {"files": {"name.ts": "text"},
            "carets": [{"label":..,"file":..,"offset":..,"newName":".."}]}

Prints, per caret: the prepareRename answer and then every edit as
    file [start,end) -> "newText"    (offsets, converted back from LSP positions)
plus the RESULTING TEXT of each touched file, which is what the edit plan has to
reproduce.
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
            if "id" in msg and "method" in msg:
                self.send({"jsonrpc": "2.0", "id": msg["id"], "result": None})

    def notify(self, method, params):
        self.send({"jsonrpc": "2.0", "method": method, "params": params})


def offset_to_pos(text, offset):
    before = text[:offset]
    return {"line": before.count("\n"), "character": offset - (before.rfind("\n") + 1)}


def pos_to_offset(text, pos):
    lines = text.split("\n")
    off = 0
    for i in range(pos["line"]):
        off += len(lines[i]) + 1
    return off + pos["character"]


def apply_edits(text, edits):
    """Apply LSP TextEdits back-to-front; returns the new text."""
    spans = sorted(
        ((pos_to_offset(text, e["range"]["start"]),
          pos_to_offset(text, e["range"]["end"]), e["newText"]) for e in edits),
        key=lambda s: s[0], reverse=True)
    for start, end, new in spans:
        text = text[:start] + new + text[end:]
    return text


def main():
    root = os.path.abspath(sys.argv[1])
    spec = json.load(open(sys.argv[2]))
    os.makedirs(root, exist_ok=True)
    for name, text in spec["files"].items():
        path = os.path.join(root, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(text)
    if "tsconfig.json" not in spec["files"]:
        with open(os.path.join(root, "tsconfig.json"), "w") as f:
            json.dump({"compilerOptions": {"strict": True, "target": "es2020",
                                           "module": "esnext"}}, f)
    lsp = Lsp(root)
    lsp.request("initialize", {
        "processId": os.getpid(),
        "rootUri": "file://" + root,
        "capabilities": {"textDocument": {"rename": {"prepareSupport": True}}},
    })
    lsp.notify("initialized", {})
    for name, text in spec["files"].items():
        lsp.notify("textDocument/didOpen", {"textDocument": {
            "uri": "file://" + os.path.join(root, name),
            "languageId": "typescript", "version": 1, "text": text}})

    for caret in spec["carets"]:
        name, offset = caret["file"], caret["offset"]
        text = spec["files"][name]
        uri = "file://" + os.path.join(root, name)
        pos = offset_to_pos(text, offset)
        print("=== %s  (%s@%d, %r -> %r)" % (
            caret["label"], name, offset,
            text[offset:offset + 12].split("\n")[0], caret.get("newName", "NEWNAME")))
        prep = lsp.request("textDocument/prepareRename",
                           {"textDocument": {"uri": uri}, "position": pos})
        if "error" in prep:
            print("    prepare: ERROR %s" % prep["error"].get("message"))
        else:
            r = prep.get("result")
            if r is None:
                print("    prepare: null (REFUSED)")
            else:
                rng = r.get("range", r)
                s = pos_to_offset(text, rng["start"])
                e = pos_to_offset(text, rng["end"])
                print("    prepare: [%d,%d) = %r" % (s, e, text[s:e]))
        resp = lsp.request("textDocument/rename", {
            "textDocument": {"uri": uri}, "position": pos,
            "newName": caret.get("newName", "NEWNAME")})
        if "error" in resp:
            print("    rename: ERROR %s" % resp["error"].get("message"))
            continue
        result = resp.get("result")
        if result is None:
            print("    rename: null (REFUSED)")
            continue
        changes = result.get("changes") or {}
        if not changes and result.get("documentChanges"):
            for dc in result["documentChanges"]:
                changes.setdefault(dc["textDocument"]["uri"], []).extend(dc["edits"])
        if not changes:
            print("    rename: no edits")
            continue
        for u, edits in sorted(changes.items()):
            short = u.rsplit("/", 1)[-1]
            fname = os.path.relpath(u[len("file://"):], root)
            ftext = spec["files"].get(fname)
            if ftext is None:
                print("    %s: %d edits in a file not in the spec" % (short, len(edits)))
                continue
            for e in sorted(edits, key=lambda e: (e["range"]["start"]["line"],
                                                  e["range"]["start"]["character"])):
                s = pos_to_offset(ftext, e["range"]["start"])
                en = pos_to_offset(ftext, e["range"]["end"])
                print("    %s [%d,%d) %r -> %r" % (short, s, en, ftext[s:en], e["newText"]))
            after = apply_edits(ftext, edits)
            for line in after.split("\n"):
                if caret.get("newName", "NEWNAME") in line:
                    print("        RESULT | %s" % line)
    lsp.request("shutdown", {})
    lsp.notify("exit", {})


main()
