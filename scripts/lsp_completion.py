#!/usr/bin/env python3
"""Ask tsc 7.0.2's LSP server for the COMPLETIONS at a list of carets.

(API.12)'s step 1, the same technique rounds 923-928 used for hover, references
and rename: `tools/tsgo-7.0.2/lib/tsc --lsp -stdio` answers
`textDocument/completion`, so what a caret INSIDE the string of `o["…"]` is
supposed to offer — and, decisively, whether the item text carries the quotes and
what span accepting it replaces — can be READ OUT of tsc rather than reasoned
about.

Usage: lsp_completion.py <projectDir> <spec.json>
spec.json: {"files": {"name.ts": "text"},
            "carets": [{"label":..,"file":..,"offset":..,"want":["a","b"]}]}

Prints, per caret, the item count and every item whose label is in `want` (or the
first few when `want` is absent) as label / kind / insertText / textEdit range
with the text it would write, so the quoting question is visible as characters.
"""
import json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lsp_rename import Lsp, offset_to_pos, pos_to_offset  # noqa: E402

KINDS = {1: "Text", 2: "Method", 3: "Function", 5: "Field", 6: "Variable",
         7: "Class", 8: "Interface", 9: "Module", 10: "Property", 14: "Keyword",
         20: "EnumMember", 13: "Enum", 21: "Constant", 22: "Struct", 25: "TypeParameter"}


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
        print("=== %s  (%s@%d)   ...%r|%r..." % (
            caret["label"], name, offset,
            text[max(0, offset - 12):offset], text[offset:offset + 8]))
        resp = lsp.request("textDocument/completion", {
            "textDocument": {"uri": uri}, "position": offset_to_pos(text, offset)})
        if "error" in resp:
            print("    ERROR %s" % resp["error"].get("message"))
            continue
        result = resp.get("result")
        if result is None:
            print("    null result — NO completions offered")
            continue
        items = result["items"] if isinstance(result, dict) else result
        incomplete = result.get("isIncomplete") if isinstance(result, dict) else None
        print("    %d items (isIncomplete=%s)" % (len(items), incomplete))
        want = caret.get("want")
        shown = 0
        for it in items:
            label = it.get("label")
            if want is not None and label not in want:
                continue
            if want is None and shown >= caret.get("show", 8):
                break
            shown += 1
            te = it.get("textEdit") or {}
            rng = te.get("range") or te.get("replace") or {}
            span = ""
            if rng:
                s = pos_to_offset(text, rng["start"])
                e = pos_to_offset(text, rng["end"])
                span = " edit[%d,%d)=%r over %r" % (s, e, te.get("newText"), text[s:e])
            print("    label=%-16r kind=%-12s insert=%-16r%s" % (
                label, KINDS.get(it.get("kind"), it.get("kind")),
                it.get("insertText"), span))
        if want is not None and shown == 0:
            print("    (none of %s offered; first labels: %s)" % (
                want, [i.get("label") for i in items[:10]]))
    lsp.request("shutdown", {})
    lsp.notify("exit", {})


if __name__ == "__main__":
    main()
