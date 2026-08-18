#!/usr/bin/env python3
"""Ask tsc 7.0.2's LSP server for the DEFINITION of a name at a list of carets.

The fourth oracle beside `lsp_hover.py`, `lsp_member_refs.py`, `lsp_rename.py` and
`lsp_completion.py`, added in round 930 for the question none of them could answer:
where does tsc's own go-to-definition NAVIGATE. It settled `super.p` — tsc answers
the BASE's declaration in both the overridden and the inherited shape, where this
API answered nothing — which is what made that a defect rather than a divergence.

Usage: lsp_definition.py <projectDir> <spec.json>
spec.json: {"files": {"name.ts": "text"},
            "carets": [{"label":..,"file":..,"offset":..}]}

Prints, per caret, every location as `file[start,end) 'text'`.
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
        text = spec["files"][caret["file"]]
        answer = lsp.request("textDocument/definition", {
            "textDocument": {"uri": "file://" + os.path.join(root, caret["file"])},
            "position": offset_to_pos(text, caret["offset"])})
        # The envelope, then either a Location, a list of them, or a LocationLink
        # (which spells its fields `targetUri`/`targetSelectionRange`) — all three
        # are legal answers to this request and tsc has used more than one.
        result = answer.get("result") if isinstance(answer, dict) else answer
        locations = result if isinstance(result, list) else ([result] if result else [])
        rendered = []
        for location in locations:
            uri = location.get("uri") or location.get("targetUri")
            span = (location.get("range") or location.get("targetSelectionRange")
                    or location.get("targetRange"))
            name = uri.rsplit("/", 1)[-1]
            body = spec["files"].get(name, "")
            start = pos_to_offset(body, span["start"])
            end = pos_to_offset(body, span["end"])
            rendered.append(f"{name}[{start},{end}) {body[start:end]!r}")
        print(caret["label"], "->", rendered if rendered else "(nothing)")
    lsp.close() if hasattr(lsp, "close") else None


if __name__ == "__main__":
    main()
