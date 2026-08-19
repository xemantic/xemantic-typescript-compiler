#!/usr/bin/env python3
"""Run OUR binary over PRISTINE tsc's own fixtures and difference the diagnostics.

WHAT IT IS
----------
The sharpest FP instrument in this repo: for a population of pristine fixtures selected
by an explicit ERE, it materialises pristine's OWN input (`pristine_oracle.extract_sources`,
which recovers it from the `.js` / `.errors.txt` baselines), compiles it with our binary,
and differences the `(file, line, code)` rows against pristine's own `.errors.txt`.

OURS-ONLY is the number that matters -- diagnostics WE emit that pristine does not, i.e.
candidate FALSE POSITIVES.  PRISTINE-ONLY is dominated by checks this compiler does not
implement and is reported for context only.

WHY IT SUPERSEDES `round940_pristine_sweep.py`
----------------------------------------------
That script honoured exactly ONE directive (`// @target`) and forced `strict: false` on
everything else.  Round 939's method note -- a fixed scratch tsconfig MANUFACTURES false
positives -- applies to every other directive just as much: a `.tsx` fixture compiled
without `@jsx` is 30 rows of TS17004 that say nothing about the compiler.  Round 941
measured that at **147 of 397 OURS-ONLY rows (37%)**.

The fix is to stop re-deriving the mapping in Python: `TsConfigLoader` routes every
`compilerOptions` key through the SAME `applyDirective` the corpus harness uses, so the
fixture's directives are copied into the scratch tsconfig VERBATIM (keys lowercased) and
the compiler applies them exactly as the generated suite would.  `@filename` is dropped
(a harness directive, not a compiler option); unknown keys are ignored by `applyDirective`
itself, so nothing has to be whitelisted.

USAGE
    python3 scripts/pristine_sweep.py --classes <dir> --out <file> [--limit N]
    python3 scripts/pristine_sweep.py --classes <dir> --out <file> --only <stem>[,<stem>...]
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

# The population: a fixture whose source carries a COMPUTED member key.  Kept identical
# to round 940's so the OURS-ONLY counts stay comparable across rounds.
PATTERN = (r"^[[:space:]]*(get |set |static |readonly |async )*"
           r"\[[A-Za-z_$\"'`][^]:]*\][[:space:]]*[(:;?]")

# tsc's harness directive syntax: `// @key: value` at the start of a line.
DIRECTIVE_RE = re.compile(r"^[ \t]*//[ \t]*@([A-Za-z]+)[ \t]*:[ \t]*(.*?)[ \t]*$", re.M)
# `@filename` names the file being introduced; it is not a compiler option.  Everything
# else is passed through -- `applyDirective` ignores a key it does not model.
NOT_AN_OPTION = {"filename", "symlink", "link", "currentdirectory"}

# tsc's own default is `strict: false`; THIS compiler's is the inverse (every strict-family
# check fires unless `strict: false` is EXPLICIT -- CHK.13).  A fixture naming any of these
# has already said what it wants and must never be overridden.
STRICT_FAMILY = {"strict", "strictnullchecks", "strictpropertyinitialization",
                 "noimplicitany", "noimplicitthis", "strictfunctiontypes",
                 "strictbindcallapply", "alwaysstrict", "usedefineforclassfields"}

OURS_RE = re.compile(r"([^/\s]+\.[cm]?[jt]sx?):(\d+):(\d+) - error (TS\d+)")


_ERR_SECTION = re.compile(r"^==== (.+?) \(\d+ errors?\) ====\s*$")


def baseline_body(stem: str) -> dict[str, list[str]]:
    """Pristine's OWN view of each input file, read off an `.errors.txt` annotation.

    This is the ALIGNMENT ORACLE: the sweep differences (file, line, code), so a source
    whose line numbering is off by even one reports every row as a divergence in BOTH
    directions.  Round 941 found exactly that on 27 of 630 fixtures (the case-file
    fallback still carried the harness `// @target:` directives that tsc strips).
    """
    out: dict[str, list[str]] = {}
    for b in po.baselines_for(stem):
        cur, buf = None, []
        for line in po.annotated_source(b).splitlines():
            m = _ERR_SECTION.match(line)
            if m:
                if cur:
                    out.setdefault(cur, buf)
                cur, buf = m.group(1).rsplit("/", 1)[-1], []
                continue
            if cur is None or line.startswith("!!!"):
                continue
            if line.strip(" ~") == "" and "~" in line:
                continue
            buf.append((line[4:] if line.startswith("    ") else line).rstrip())
        if cur:
            out.setdefault(cur, buf)
        if out:
            return out
    return out


def alignment(stem: str, files: dict[str, str]) -> str:
    """"aligned" / "misaligned" / "unknown" -- does our input match pristine's line for line?"""
    body = baseline_body(stem)
    if not body:
        return "unknown"
    verdict = "unknown"
    for name, lines in body.items():
        ours = files.get(name)
        if ours is None:
            continue
        mine = [l.rstrip() for l in ours.split("\n")]
        while mine and mine[-1] == "":
            mine.pop()
        theirs = list(lines)
        while theirs and theirs[-1] == "":
            theirs.pop()
        if mine != theirs:
            return "misaligned"
        verdict = "aligned"
    return verdict


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


def directives_of(stem: str, blob: str) -> dict[str, str]:
    """Every `// @key: value` of the fixture, LAST-wins, harness-only keys dropped.

    READ FROM `tests/cases` WHEN THE CASE IS THERE, never from the extracted blob alone:
    **the `.js` baseline STRIPS the directive comments** while echoing everything else
    verbatim, so a sweep that reads only what it extracted sees NO directives at all for
    every fixture whose source came from a baseline (round 941: `jsxElementType` recovered
    zero of its three, and its 30 TS17004 rows were the sweep's own missing `@jsx`).
    `tests/cases` is INCOMPLETE in this clone (round 939), hence the blob fallback.
    """
    text = blob
    case = po.case_index().get(stem)
    if case is not None:
        text = case.read_text(errors="replace") + "\n" + blob
    out: dict[str, str] = {}
    # `tests/cases` is INCOMPLETE in this clone, and for a missing case the baseline's own
    # VARIATION SUFFIX is the only surviving record of the option it was compiled under --
    # `derivedClassSuperProperties(target=es2015).errors.txt` says `target: es2015`, and
    # without it the sweep compiles at the esnext default, where tsc's own TS2376 rule is
    # switched off entirely by `emitStandardClassFields` (round 941).
    for b in po.baselines_for(stem):
        rest = b.name[len(stem):-len(".errors.txt")]
        for kv in rest.strip("()").split(","):
            if "=" in kv:
                k, v = kv.split("=", 1)
                out.setdefault(k.strip().lower(), v.strip())
    for m in DIRECTIVE_RE.finditer(text):
        key = m.group(1).lower()
        if key in NOT_AN_OPTION:
            continue
        out[key] = m.group(2)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--classes", required=True, help="compiler class dir under test")
    ap.add_argument("--out", required=True)
    ap.add_argument("--work", default=str(REPO / "build" / "bench" / "pristine-sweep"))
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--only", default="", help="comma-separated stems (skips the grep)")
    ap.add_argument("--tsc-strict-default", action="store_true",
                    help="inject `strict: false` into every fixture that sets NO strict-family "
                         "directive -- i.e. reproduce tsc's OWN default, which this compiler "
                         "deliberately inverts (CHK.13).  A DIAGNOSTIC arm: differencing it "
                         "against the canonical run says how many ours-only rows are the "
                         "strict-by-default CONVENTION rather than a modelling gap.  Round 940 "
                         "forced `strict: false` on EVERY fixture, which is different and wrong "
                         "-- it overrides a fixture that asks for `@strict: true`.")
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
    if args.only:
        stems = [s for s in args.only.split(",") if s]
    else:
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
            blob += text + "\n"
        opts: dict[str, object] = dict(directives_of(stem, blob))
        # A `.tsx`/`.jsx` input whose case file is NOT in this clone loses its `@jsx`
        # with it, and every JSX element is then TS17004 -- a statement about the
        # sweep's tsconfig, not about the compiler (round 941: 30 rows on
        # `tsxLibraryManagedAttributes` alone).  Defaulting it can only REMOVE an
        # ours-only row, never add one, so the direction is safe.
        if "jsx" not in opts and any(n.endswith((".tsx", ".jsx")) for n in files):
            opts["jsx"] = "react"
        # ONLY when the case file is in this clone: an ABSENT directive is evidence that
        # pristine compiled without it, a MISSING CASE FILE is not.  Measured round 943 --
        # `strictPropertyInitialization` has no case file here and its own baseline carries
        # 20 TS2564, i.e. pristine had the flag ON and injecting `strict: false` would have
        # deleted four GENUINE false positives from the count.  Same failure shape as round
        # 941's defect (c), one directive over.
        if (args.tsc_strict_default and not (STRICT_FAMILY & set(opts))
                and po.case_index().get(stem) is not None):
            opts["strict"] = False
        opts["noEmit"] = True
        (d / "tsconfig.json").write_text(json.dumps(
            {"compilerOptions": opts, "include": ["**/*"]}))
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
        results[stem] = {"directives": opts, "ours_only": only_ours,
                         "alignment": alignment(stem, files),
                         "n_ours": len(ours), "n_pristine": len(pris),
                         "pristine_only": len(pris - ours)}
        ran += 1

    total_ours_only = sum(len(v["ours_only"]) for v in results.values())
    with_ours_only = sum(1 for v in results.values() if v["ours_only"])
    misaligned = sorted(k for k, v in results.items() if v["alignment"] == "misaligned")
    Path(args.out).write_text(json.dumps(
        {"classes": args.classes, "pattern": PATTERN, "stems_matched": len(stems),
         "ran": ran, "skipped_no_source": skipped,
         "fixtures_with_ours_only": with_ours_only,
         "total_ours_only_rows": total_ours_only,
         "misaligned": misaligned,
         "results": results}, indent=1, sort_keys=True))
    print(f"stems={len(stems)} ran={ran} skipped={skipped} "
          f"fixtures_with_ours_only={with_ours_only} ours_only_rows={total_ours_only} "
          f"misaligned={len(misaligned)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
