#!/usr/bin/env python3
"""Ask tsc 7.0.2's LSP server for the REFERENCES of a member at a list of carets.

(API.9)'s step 1, and the technique rounds 923/924/925 used for hover and rename:
`tools/tsgo-7.0.2/lib/tsc --lsp -stdio` answers `textDocument/references`, so the
occurrence set of a MEMBER — which round 925 measured as short here by three
kinds — can be READ OUT of tsc rather than reasoned about.

Usage: lsp_member_refs.py <projectDir> <spec.json>
spec.json: {"files": {"name.ts": "text"},
            "carets": [{"label":..,"file":..,"offset":..}]}

Prints, per caret, every reference as `file [start,end) "text"  | the whole line`,
so a span that is a string literal or a binding element's property name is visible
as such rather than as a number.
"""
import json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lsp_rename import Lsp, offset_to_pos, pos_to_offset  # noqa: E402


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
    lsp.request("initialize", {"processId": os.getpid(),
                               "rootUri": "file://" + root, "capabilities": {}})
    lsp.notify("initialized", {})
    for name, text in spec["files"].items():
        lsp.notify("textDocument/didOpen", {"textDocument": {
            "uri": "file://" + os.path.join(root, name),
            "languageId": "typescript", "version": 1, "text": text}})

    for caret in spec["carets"]:
        name, offset = caret["file"], caret["offset"]
        text = spec["files"][name]
        uri = "file://" + os.path.join(root, name)
        print("=== %s  (%s@%d, %r)" % (
            caret["label"], name, offset, text[offset:offset + 14].split("\n")[0]))
        resp = lsp.request("textDocument/references", {
            "textDocument": {"uri": uri}, "position": offset_to_pos(text, offset),
            "context": {"includeDeclaration": True}})
        if "error" in resp:
            print("    ERROR %s" % resp["error"].get("message"))
            continue
        hits = resp.get("result") or []
        print("    %d references" % len(hits))
        rows = []
        for h in hits:
            fname = os.path.relpath(h["uri"][len("file://"):], root)
            ftext = spec["files"].get(fname)
            if ftext is None:
                rows.append((fname, -1, -1, "?", "?"))
                continue
            s = pos_to_offset(ftext, h["range"]["start"])
            e = pos_to_offset(ftext, h["range"]["end"])
            line = ftext[:s].count("\n")
            rows.append((fname, s, e, ftext[s:e], ftext.split("\n")[line].strip()))
        for r in sorted(rows):
            print("    %-10s [%4d,%4d) %-14r | %s" % r)
    lsp.request("shutdown", {})
    lsp.notify("exit", {})


main()
