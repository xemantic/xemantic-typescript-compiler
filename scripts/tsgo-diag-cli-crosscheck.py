#!/usr/bin/env python3
"""Cross-check of the (TSGO.2) diagnostics oracle against the shipped tsgo CLI
(docs/goport-diag-oracle.md § 5).

For a sample of materialized configurations, runs

    tools/tsgo-7.0.2/lib/tsc -p <projectDir>/tsconfig.json --noEmit --pretty false   (cwd = projectDir)

parses its output, and compares it with build/goport/diag-oracle/<case>/<variation>.jsonl on
(file, line, column, code, flattened message text). Related information is not printed by
`--pretty false`, so it is not compared here (the baseline cross-check covers it).

Every configuration is classified; the classes are the point of this script:

  agree                 identical multisets
  option-location       only option diagnostics differ, and only in location: the harness has no
                        config file for `// @option` directives (file null), the CLI reports them
                        at the generated tsconfig.json
  path-model            the case has files outside the harness's current directory (absolute
                        virtual paths, /.lib, node_modules at /) or symlinks: the CLI resolves `/x`
                        against the real root and typeRoots against the tsconfig's directory
  noemit-option         only option diagnostics caused by the cross-check's own --noEmit
  differ                anything else (printed with both sides)

By default the oracle side is first passed through the CLI's own diagnostic gate
(compiler.GetDiagnosticsOfAnyProgram: semantic diagnostics only without syntactic, program or
global ones) and --noEmit's dropping of SkippedOnNoEmit diagnostics; --no-gate compares the raw
harness list and shows how much of the disagreement those two mechanisms explain.

Usage: scripts/tsgo-diag-cli-crosscheck.py [--sample 400] [--with-diags 200] [--workers 4] [--show 30]
"""

import argparse
import collections
import concurrent.futures as cf
import json
import os
import re
import subprocess
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TSC = os.path.join(REPO, "tools/tsgo-7.0.2/lib/tsc")
CASES_DIR = os.path.join(REPO, "build/goport/diag-cases")
ORACLE = os.path.join(REPO, "build/goport/diag-oracle")

LOC = re.compile(r"^(?P<file>.+?)\((?P<line>\d+),(?P<col>\d+)\): (?P<cat>error|warning|message|suggestion) TS(?P<code>-?\d+): (?P<msg>.*)$")
GLOBAL = re.compile(r"^(?P<cat>error|warning|message|suggestion) TS(?P<code>-?\d+): (?P<msg>.*)$")
# diagnostics whose presence depends on the cross-check's own --noEmit: option conflicts, and the
# emit-dependent program diagnostics (cannot write file / project root needed for an export map)
NOEMIT_CODES = {5053, 5069, 6304, 5094, 5055, 5056, 2209}


def norm_file(f):
    if f is None:
        return None
    base = f.rsplit("/", 1)[-1]
    if f.startswith("bundled:///libs/") or (base.startswith("lib.") and base.endswith(".d.ts") and "/vfs/" not in f
                                             and not f.startswith("vfs/")):
        return "lib:" + base
    if f.startswith("vfs/"):
        return "/" + f[len("vfs/"):]
    return f


def line_col(text, start):
    """1-based line and UTF-16 column, as tsgo's diagnostic writer prints them."""
    pre = text[:start]
    line = pre.count(b"\n")
    ls = pre.rfind(b"\n") + 1
    seg = pre[ls:].decode("utf-8", errors="replace")
    col = sum(2 if ord(c) > 0xFFFF else 1 for c in seg)
    return line + 1, col + 1


def cli_gate(diags, decl_emit):
    """What tsgo's CLI would print from the harness's phase-tagged list:
    compiler.GetDiagnosticsOfAnyProgram (config, then syntactic; only without syntactic errors
    program + global; only without any of those semantic (+ global again); declaration only under
    noEmit with declaration emit), with --noEmit dropping SkippedOnNoEmit diagnostics."""
    by = collections.defaultdict(list)
    for d in diags:
        if d["skippedOnNoEmit"]:
            continue
        by[d["phase"]].append(d)
    out = list(by["config"])
    base = len(out)
    out += by["syntactic"]
    if len(out) == base:
        out += by["program"] + by["global"]
        if len(out) == base:
            out += by["semantic"]
            if decl_emit:
                out += by["declaration"]
    return out


def oracle_rows(pdir, out, gate):
    diags = [json.loads(l) for l in open(out, encoding="utf-8")]
    if gate:
        cj = json.load(open(os.path.join(pdir, "case.json")))
        opts = cj["compilerOptions"]
        decl = bool(opts.get("declaration") == 1 or opts.get("composite") == 1)
        diags = cli_gate(diags, decl)
    rows = []
    cache = {}
    for d in diags:
        f = d["file"]
        if f is None:
            loc = None
        else:
            if f not in cache:
                p = os.path.join(pdir, "vfs", f.lstrip("/"))
                cache[f] = open(p, "rb").read() if os.path.exists(p) else None
            if cache[f] is None:  # a lib file: line/col cannot be recomputed here
                loc = ("?", "?")
            else:
                raw = cache[f]
                if raw.startswith(b"\xef\xbb\xbf"):
                    raw = raw[3:]
                loc = line_col(raw, d["start"])
        rows.append((norm_file(f), loc, d["code"], d["text"].replace("\r\n", "\n"), d["phase"]))
    return rows


def cli_rows(pdir):
    p = subprocess.run([TSC, "-p", "tsconfig.json", "--noEmit", "--pretty", "false"], cwd=pdir,
                       capture_output=True, timeout=300)
    text = p.stdout.decode("utf-8", errors="replace").replace("\r\n", "\n")
    vfs_abs = os.path.join(pdir, "vfs")
    rows, cur = [], None
    for line in text.split("\n"):
        m = LOC.match(line) or GLOBAL.match(line)
        if m:
            if cur:
                rows.append(cur)
            g = m.groupdict()
            loc = (int(g["line"]), int(g["col"])) if g.get("line") else None
            cur = [norm_file(g.get("file")), loc, int(g["code"]), g["msg"]]
        elif cur and line.startswith("  "):
            cur[3] += "\n" + line
        elif line.strip() and cur:
            cur[3] += "\n" + line
    if cur:
        rows.append(cur)
    out = []
    for f, loc, code, msg in rows:
        msg = msg.replace(vfs_abs, "")  # absolute paths inside messages
        out.append((f, loc, code, msg))
    return out, p.returncode


def classify(o, c, cj):
    ok = collections.Counter((f, l, code, t) for f, l, code, t, _ in o)
    # lib locations cannot be recomputed for the oracle: compare those on (file, code, text)
    ck = collections.Counter()
    for f, l, code, t in c:
        if f and f.startswith("lib:"):
            l = ("?", "?")
        ck[(f, l, code, t)] += 1
    if ok == ck:
        return "agree", [], []
    only_o = list((ok - ck).elements())
    only_c = list((ck - ok).elements())
    # an option diagnostic: the harness has no config file for directives (no location), the CLI
    # reports it at the generated tsconfig.json
    if sorted((code, t) for f, l, code, t in only_o if f is None) == sorted(
            (code, t) for f, l, code, t in only_c if f == "tsconfig.json") and all(
            f is None for f, *_ in only_o) and all(f == "tsconfig.json" for f, *_ in only_c):
        return "option-location", only_o, only_c
    outside = any(not f["path"].startswith(cj["currentDirectory"] + "/") for f in cj["files"]) or cj["symlinks"]
    if outside or any("/.lib/" in t for *_, t in only_o + only_c):
        return "path-model", only_o, only_c
    if only_o and all(r[2] in NOEMIT_CODES for r in only_o):
        return "noemit-option", only_o, only_c
    return "differ", only_o, only_c


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sample", type=int, default=400, help="evenly spaced over all configurations")
    ap.add_argument("--with-diags", type=int, default=200, help="additionally, evenly spaced over those with diagnostics")
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--show", type=int, default=40)
    ap.add_argument("--json", help="write per-configuration results here")
    ap.add_argument("--no-gate", action="store_true",
                    help="compare the full harness list, without simulating the CLI's phase gate and --noEmit")
    args = ap.parse_args()

    m = json.load(open(os.path.join(ORACLE, "manifest.json")))
    if not m.get("complete"):
        sys.exit("tsgo-diag-cli-crosscheck: REFUSED: the oracle manifest is incomplete")
    ents = [e for e in m["entries"] if e["status"] == "ok"]
    pick = {}
    for pool, n in ((ents, args.sample), ([e for e in ents if e.get("count")], args.with_diags)):
        if n and pool:
            step = max(1, len(pool) // n)
            for e in pool[::step][:n]:
                pick[(e["case"], e["variation"])] = e
    sample = list(pick.values())

    def one(e):
        pdir = os.path.join(CASES_DIR, e["case"], e["variation"])
        o = oracle_rows(pdir, os.path.join(ORACLE, e["out"]), not args.no_gate)
        c, rc = cli_rows(pdir)
        cls, oo, cc = classify(o, c, json.load(open(os.path.join(pdir, "case.json"))))
        return e, cls, oo, cc, len(o), len(c)

    results = []
    with cf.ThreadPoolExecutor(args.workers) as ex:
        for r in ex.map(one, sample):
            results.append(r)
    counts = collections.Counter(r[1] for r in results)
    rows_o = sum(r[4] for r in results)
    rows_c = sum(r[5] for r in results)
    agree_rows = sum(r[4] for r in results if r[1] == "agree")
    print(f"sampled {len(results)} configurations ({sum(1 for r in results if r[4])} with oracle diagnostics); "
          f"oracle rows {rows_o}, CLI rows {rows_c}, rows in agreeing configurations {agree_rows}")
    for k, v in counts.most_common():
        print(f"  {k:16} {v}")
    shown = 0
    for e, cls, oo, cc, no, nc in sorted(results, key=lambda r: r[1]):
        if cls == "agree" or shown >= args.show:
            continue
        shown += 1
        print(f"\n[{cls}] {e['case']} [{e['variation']}] layer={e.get('layer')} oracle={no} cli={nc}")
        for r in oo[:6]:
            print(f"   oracle-only {r[0]} {r[1]} TS{r[2]} {r[3][:140]!r}")
        for r in cc[:6]:
            print(f"   cli-only    {r[0]} {r[1]} TS{r[2]} {r[3][:140]!r}")
    if args.json:
        with open(args.json, "w") as f:
            for e, cls, oo, cc, no, nc in results:
                f.write(json.dumps({"case": e["case"], "variation": e["variation"], "class": cls, "oracle": no,
                                    "cli": nc, "oracleOnly": oo, "cliOnly": cc}) + "\n")


if __name__ == "__main__":
    main()
