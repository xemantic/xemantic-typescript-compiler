#!/usr/bin/env python3
"""Census of the hand-written pin tests for the SECOND leg of the (TSGO.2) diagnostics oracle
(docs/goport-diag-oracle.md § 7): which @Test functions are expressible as
(source, options, expected diagnostics) and can therefore be run against the ported engine.

Static and heuristic (a Kotlin-source scan, no compilation): every `@Test fun` body in
xemantic-typescript-compiler-*/src/*Test*/ is classified by the entry point it calls and by the
shape of its assertions.

Entry points (first match wins, in this order):
  diagnose          CompilerTestSupport.diagnose(source, directives, fileName): the harness's own
                    `// @option` + `// @Filename` text format -> a conformance case verbatim
  compile-string    TypeScriptCompiler().compile(text, fileName[, …]) — the same text format
  verbatim          diagnoseVerbatim(source, fileName, options): one file, a CompilerOptions object
  project           ProjectCompiler / Project / Vfs: a project directory with a tsconfig.json
  checker-direct    Checker(...) / Binder(...) constructed directly (symbol identity, internals)
  other             no diagnostics entry point (parser/scanner/emitter/type-capture/perf/...)

Assertion shapes (several may apply): silence (`none {`), presence (`any {` / `count {`),
exact (`==` against a list/size), message (message text), position (start/line/character/length).

Writes build/goport/diag-pins/census.json (one row per test) and prints the summary.
"""

import collections
import json
import os
import re
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUT = os.path.join(REPO, "build/goport/diag-pins/census.json")

TEST_FUN = re.compile(r"@Test\s*(?:@\w+(?:\([^)]*\))?\s*)*fun\s+(`[^`]+`|\w+)\s*\(")
ENTRY = [
    ("diagnose", re.compile(r"\bdiagnose\s*\(")),
    ("compile-string", re.compile(r"TypeScriptCompiler\s*\([^)]*\)\s*\.\s*compile\s*\(")),
    ("verbatim", re.compile(r"\bdiagnoseVerbatim\s*\(")),
    ("project", re.compile(r"\b(ProjectCompiler|Project\s*\(|Project\.|InMemoryVfs|OverlayVfs|MemoryVfs)")),
    ("checker-direct", re.compile(r"\b(Checker|Binder)\s*\(")),
]
SHAPES = {
    "silence": re.compile(r"\bnone\s*\{"),
    "presence": re.compile(r"\b(any|count)\s*\{"),
    "exact": re.compile(r"(\.size\s*==|==\s*listOf\(|diagnostics\s*==|\.map\s*\{[^}]*code[^}]*\}\s*==)"),
    "message": re.compile(r"\bmessage\b"),
    "position": re.compile(r"\b(start|line|character|length)\s*==|\bposition"),
}


def bodies(src):
    """Yield (name, region) per @Test function: the text from its `fun` to the next @Test (or
    the end of the file). Coarse on purpose: it covers block AND expression bodies
    (`fun x() = diagnose(...) should { ... }`) without a Kotlin parser; a trailing private helper
    lands in the last test's region, which only ever adds an entry point it may call."""
    ms = list(TEST_FUN.finditer(src))
    for k, m in enumerate(ms):
        end = ms[k + 1].start() if k + 1 < len(ms) else len(src)
        yield m.group(1).strip("`"), src[m.end():end]


HELPER_FUN = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:\w+\.)?(\w+)\s*\(")


def helper_regions(src):
    """name -> text of each non-@Test function in the file (to the next `fun`)."""
    out = {}
    ms = list(HELPER_FUN.finditer(src))
    for k, m in enumerate(ms):
        end = ms[k + 1].start() if k + 1 < len(ms) else len(src)
        out.setdefault(m.group(1), src[m.end():end])
    return out


def helper_entry(src, body, depth, _cache={}):
    key = id(src)
    if _cache.get("key") != key:
        _cache.clear()
        _cache["key"] = key
        _cache["regions"] = helper_regions(src)
    regions = _cache["regions"]
    seen = set()
    frontier = set(re.findall(r"\b(\w+)\s*\(", body))
    for _ in range(depth):
        nxt = set()
        for h in frontier - seen:
            seen.add(h)
            r = regions.get(h)
            if r is None:
                continue
            e = next((k for k, rx in ENTRY if rx.search(r)), None)
            if e:
                return e + "(helper)"
            nxt |= set(re.findall(r"\b(\w+)\s*\(", r))
        frontier = nxt
    return None


def main():
    rows = []
    for mod in sorted(os.listdir(REPO)):
        if not mod.startswith("xemantic-typescript-compiler-"):
            continue
        src_root = os.path.join(REPO, mod, "src")
        if not os.path.isdir(src_root):
            continue
        for sset in sorted(os.listdir(src_root)):
            if "Test" not in sset:
                continue
            for dp, _, fns in os.walk(os.path.join(src_root, sset)):
                for fn in sorted(fns):
                    if not fn.endswith(".kt"):
                        continue
                    path = os.path.join(dp, fn)
                    src = open(path, encoding="utf-8", errors="replace").read()
                    for name, body in bodies(src):
                        entry = next((k for k, rx in ENTRY if rx.search(body)), "other")
                        # a helper defined in the same file (`private fun rows(...)`) may wrap an
                        # entry point: follow non-test functions up to three calls deep
                        if entry == "other":
                            entry = helper_entry(src, body, 3) or "other"
                        rows.append({
                            "module": mod, "sourceSet": sset, "file": os.path.relpath(path, REPO),
                            "class": fn[:-3], "test": name, "entry": entry,
                            "shapes": sorted(k for k, rx in SHAPES.items() if rx.search(body)),
                            "multiFile": bool(re.search(r"@[Ff]ilename", body)),
                        })
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump(rows, open(OUT, "w"), indent=1)
    by_entry = collections.Counter(r["entry"] for r in rows)
    by_mod = collections.Counter((r["module"], r["entry"]) for r in rows)
    expressible = [r for r in rows if r["entry"].split("(")[0] in ("diagnose", "compile-string", "verbatim")]
    shapes = collections.Counter(s for r in expressible for s in r["shapes"])
    print(f"{len(rows)} @Test functions in {len({r['file'] for r in rows})} files")
    for k, v in by_entry.most_common():
        print(f"  {k:18} {v}")
    print("by module:")
    for (m, e), v in sorted(by_mod.items()):
        print(f"  {m:44} {e:18} {v}")
    print(f"expressible as (source, options, expected diagnostics): {len(expressible)} "
          f"in {len({r['class'] for r in expressible})} classes; multi-file {sum(r['multiFile'] for r in expressible)}")
    for k, v in shapes.most_common():
        print(f"  assertion shape {k:10} {v}")
    print(f"wrote {os.path.relpath(OUT, REPO)}")


if __name__ == "__main__":
    main()
