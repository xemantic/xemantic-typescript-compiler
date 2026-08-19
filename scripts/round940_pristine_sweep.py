#!/usr/bin/env python3
"""Round 940 — run OUR binary over every ungated PRISTINE fixture carrying a computed
member key, and difference it against what pristine tsc emitted.

WHY THIS EXISTS
---------------
Round 939 did this sweep by hand and committed only the oracle it stands on
(`scripts/pristine_oracle.py`).  The sweep is the sharper instrument of the two — it is
the only thing in this repo that reads OUR answer against PRISTINE's for a shape no
corpus baseline gates — so round 940 makes it a script, which is what lets a fix be
judged by "did the OURS-ONLY count go down and nothing else move".

WHAT IT MEASURES
----------------
For each fixture stem:
  * `pristine_oracle.extract_sources` writes pristine's OWN input back out (the `.js`
    baseline echoes every input verbatim), so our compiler sees exactly what pristine saw;
  * the case's own `// @target` directive is honoured when present, because round 939's
    method note — paid for twice — is that a FIXED scratch tsconfig manufactures false
    positives (`uniqueSymbols` lost an OURS-ONLY row moving es2015 -> esnext);
  * our `--noEmit --listAll` output is differenced against the baseline as a set of
    (line, code) pairs.  An ABSENT `.errors.txt` means pristine was SILENT, which is
    evidence, not a missing datum.

OURS-ONLY is the number that matters: those are diagnostics we emit and pristine does
not, i.e. candidate FALSE POSITIVES.  PRISTINE-ONLY is dominated by checks this compiler
does not implement and is reported for context only.

USAGE
    python3 scripts/round940_pristine_sweep.py --classes <dir> --out <file> [--limit N]
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "scripts"))
import pristine_oracle as po  # noqa: E402

# The population: a fixture whose source carries a COMPUTED member key.  Kept as one
# ERE so the selection is reproducible and quotable.
PATTERN = (r"^[[:space:]]*(get |set |static |readonly |async )*"
           r"\[[A-Za-z_$\"'`][^]:]*\][[:space:]]*[(:;?]")

TARGET_RE = re.compile(r"^\s*//\s*@target\s*:\s*(\S+)\s*$", re.M | re.I)
OURS_RE = re.compile(r"([^/\s]+\.[cm]?[jt]sx?):(\d+):(\d+) - error (TS\d+)")


def pristine_rows(stem: str) -> set[tuple[str, int, str]]:
    rows: set[tuple[str, int, str]] = set()
    for b in po.baselines_for(stem):
        if not b.name.endswith(".errors.txt"):
            continue
        for line in b.read_text(errors="replace").splitlines():
            m = re.match(r"^(\S+?)\((\d+),(\d+)\): error (TS\d+)", line)
            if m:
                rows.add((Path(m.group(1)).name, int(m.group(2)), m.group(4)))
    return rows


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--classes", required=True, help="compiler class dir under test")
    ap.add_argument("--out", required=True)
    ap.add_argument("--work", default=str(REPO / "build" / "bench" / "round940-sweep"))
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    deps = subprocess.run([str(REPO / "scripts" / "lib" / "dep-classpath.sh"), "--print"],
                          capture_output=True, text=True)
    if deps.returncode != 0 or not deps.stdout.strip():
        print("REFUSED: dependency classpath did not resolve", file=sys.stderr)
        return 2
    cp = f"{args.classes}:{deps.stdout.strip()}"
    if not Path(args.classes, "com/xemantic/typescript/compiler/MainKt.class").exists():
        print(f"REFUSED: no MainKt in {args.classes}", file=sys.stderr)
        return 2

    work = Path(args.work)
    stems = po.grep_stems(PATTERN, False)
    if args.limit:
        stems = stems[: args.limit]

    results = {}
    ran = skipped = 0
    for stem in stems:
        try:
            files = po.extract_sources(stem)
        except Exception:
            files = {}
        if not files:
            skipped += 1
            continue
        d = work / stem
        subprocess.run(["rm", "-rf", str(d)], check=False)
        d.mkdir(parents=True, exist_ok=True)
        blob = ""
        for name, text in files.items():
            p = d / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text)
            blob += text
        m = TARGET_RE.search(blob)
        target = m.group(1).lower() if m else "esnext"
        if target in ("es3", "es5"):          # CompilerOptions maps an explicit ES3/ES5 up
            target = "es2015"
        (d / "tsconfig.json").write_text(json.dumps(
            {"compilerOptions": {"target": target, "strict": False, "noEmit": True},
             "include": ["**/*"]}))
        proc = subprocess.run(
            ["java", "-Xmx3g", "-cp", cp, "com.xemantic.typescript.compiler.MainKt",
             "--noEmit", "--listAll", str(d)],
            capture_output=True, text=True, timeout=300)
        ours = {(mm.group(1), int(mm.group(2)), mm.group(4))
                for mm in OURS_RE.finditer(proc.stdout + proc.stderr)}
        if "more error(s)" in proc.stdout:     # round 811: a truncated capture lies
            print(f"REFUSED {stem}: truncated capture", file=sys.stderr)
            return 2
        pris = pristine_rows(stem)
        only_ours = sorted(ours - pris)
        results[stem] = {"target": target, "ours_only": only_ours,
                         "n_ours": len(ours), "n_pristine": len(pris),
                         "pristine_only": len(pris - ours)}
        ran += 1

    total_ours_only = sum(len(v["ours_only"]) for v in results.values())
    with_ours_only = sum(1 for v in results.values() if v["ours_only"])
    Path(args.out).write_text(json.dumps(
        {"classes": args.classes, "pattern": PATTERN, "stems_matched": len(stems),
         "ran": ran, "skipped_no_source": skipped,
         "fixtures_with_ours_only": with_ours_only,
         "total_ours_only_rows": total_ours_only,
         "results": results}, indent=1, sort_keys=True))
    print(f"stems={len(stems)} ran={ran} skipped={skipped} "
          f"fixtures_with_ours_only={with_ours_only} ours_only_rows={total_ours_only}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
