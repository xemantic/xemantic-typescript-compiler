#!/usr/bin/env python3
"""Run a fixture through all THREE compilers and adjudicate it row by row.

Every (CHK.*) round needs the same instrument — our binary, tsgo 7.0.2 and
pristine `typescript@6.0.3` over one fixture, compared as `(file, line, code)` —
and every round so far has rebuilt it in a scratchpad.  This is that instrument.

Verdicts, per (file, line, code) row:
  AGREE        both references report it and so do we
  OURS-ONLY    we report it and NEITHER reference does   (a false positive)
  MISSING      both references report it and we do not   (a lost diagnostic)
  REF-SPLIT    the two references disagree -> NOT adjudicable, reported separately
  TEXT-DIFF    we report it at the right place with a DIFFERENT MESSAGE

TEXT-DIFF exists because the first version of this script did not have it, and a
(CHK.119) recon then found six rows where we emit at exactly the right position
with the wrong display (`typeof Foo` where both references say `() => void`).
Keyed on (file, line, code) alone those score as AGREE — the instrument reports a
clean bill of health on a wrong answer.  A display defect is invisible to the
8-profile grid too ((PARITY.1)), so without this there is no cheap instrument that
can see one at all.

REF-SPLIT is the load-bearing one: CLAUDE.md records that tsgo and pristine
diverge in whole families (overload elaborations, duplicate-member spans), so a
row on which they disagree is evidence about nothing and must never be counted
into a prize.

REFUSES (exit 2) rather than skipping when an arm is unavailable — a reference
sweep that silently drops to one compiler reads exactly like a clean measurement
(CLAUDE.md rounds 853/873).  `build/tools/tsc-ref` is provisioned by nothing in
this repo and vanishes on a clean, so its absence is a refusal, not a fallback.

Usage:
  scripts/ref_matrix.py <fixtureDir> [--classes DIR] [--json OUT] [--quiet]

The fixture directory needs a tsconfig.json and at least one .ts file.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TSGO = ROOT / "tools/tsgo-7.0.2/lib/tsc"
PRISTINE = ROOT / "build/tools/tsc-ref/node_modules/typescript/lib/tsc.js"
NODE = ROOT / "tools/node/bin/node"
DEFAULT_CLASSES = ROOT / "xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main"

# THE THREE COMPILERS DO NOT SHARE A ROW FORMAT, and that is a trap rather than a
# detail: the references print `t.ts(12,5): error TS2339: ...` while ours prints
# `/abs/t.ts:12:5 - error TS2339: ...`.  A parser matching only the reference form
# reads OUR row set as EMPTY, which surfaces as "missing everywhere" — a perfectly
# plausible measurement, not an obvious breakage.  `assert_parsed` below is the
# positive control that closes it.
REF_ROW = re.compile(r"^(?P<file>[^(]+)\((?P<line>\d+),(?P<col>\d+)\): error TS(?P<code>\d+): (?P<msg>.*)$")
OURS_ROW = re.compile(r"^(?P<file>.+?):(?P<line>\d+):(?P<col>\d+) - error TS(?P<code>\d+): (?P<msg>.*)$")
TRUNCATED = re.compile(r"and \d+ more error")


class Refusal(Exception):
    pass


def run(cmd, cwd=None, timeout=300):
    try:
        p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise Refusal(f"timed out after {timeout}s: {' '.join(map(str, cmd))}")
    return p.stdout + p.stderr


def parse(out: str, fixture: Path, arm: str):
    """-> (rows, detail) where rows is a set of (file, line, code)."""
    if TRUNCATED.search(out):
        # round 811: a truncated capture reports a regression that does not exist.
        raise Refusal(f"{arm}: capture is TRUNCATED ('and N more error(s)')")
    if "Could not find or load main class" in out or "DEAD CLASSPATH" in out:
        raise Refusal(f"{arm}: dead classpath — the compiler is not on it")
    rows, detail = set(), {}
    for line in out.splitlines():
        s = line.strip()
        m = REF_ROW.match(s) or OURS_ROW.match(s)
        if not m:
            continue
        f = m.group("file")
        # Normalise to a fixture-relative path so the three arms are comparable.
        try:
            f = str(Path(f).resolve().relative_to(fixture.resolve()))
        except (ValueError, OSError):
            f = os.path.basename(f)
        key = (f, int(m.group("line")), int(m.group("code")))
        rows.add(key)
        detail.setdefault(key, m.group("msg"))
    assert_parsed(out, rows, arm)
    return rows, detail


def assert_parsed(out: str, rows: set, arm: str) -> None:
    """Refuse an arm whose output plainly HAS diagnostics that we failed to parse.

    Without this, a row-format drift in any one compiler reads as that compiler
    being silent — i.e. as a finding.  This is the round-853 "a green gate can be
    a frozen one" law applied to a parser: the instrument must prove it saw what
    the raw output contains.
    """
    if rows:
        return
    if re.search(r"error TS\d+", out):
        sample = next((l.strip() for l in out.splitlines() if re.search(r"error TS\d+", l)), "")
        raise Refusal(
            f"{arm}: output contains 'error TS' but ZERO rows parsed — row format drift.\n"
            f"  first unparsed line: {sample}"
        )


def dep_classpath() -> str:
    out = run([str(ROOT / "scripts/lib/dep-classpath.sh"), "--print"])
    cp = out.strip().splitlines()[-1] if out.strip() else ""
    if not cp or ".jar" not in cp:
        raise Refusal("dep-classpath.sh produced no classpath")
    return cp


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("fixture", type=Path)
    ap.add_argument("--classes", type=Path, default=DEFAULT_CLASSES,
                    help="our compiler's class directory (default: core's jvm/main)")
    ap.add_argument("--json", type=Path, help="write the full adjudication here")
    ap.add_argument("--quiet", action="store_true", help="print only the summary line")
    args = ap.parse_args()

    fixture = args.fixture.resolve()
    try:
        if not (fixture / "tsconfig.json").is_file():
            raise Refusal(f"no tsconfig.json in {fixture}")
        if not list(fixture.rglob("*.ts")):
            raise Refusal(f"no .ts file in {fixture}")
        for p, what in ((TSGO, "tsgo 7.0.2"), (PRISTINE, "pristine typescript@6.0.3"), (NODE, "node")):
            if not p.exists():
                raise Refusal(f"{what} is absent at {p} — provision it or do not claim a reference sweep")
        if not (args.classes / "com/xemantic/typescript/compiler/MainKt.class").is_file():
            raise Refusal(f"no MainKt in {args.classes} — build first")

        ours_raw = run(["java", "-Xmx4g", "-cp", f"{args.classes}:{dep_classpath()}",
                        "com.xemantic.typescript.compiler.MainKt", "--noEmit", "--listAll", str(fixture)])
        tsgo_raw = run([str(TSGO), "--noEmit", "-p", str(fixture)])
        pris_raw = run([str(NODE), str(PRISTINE), "--noEmit", "-p", str(fixture)])

        ours, ours_msg = parse(ours_raw, fixture, "ours")
        tsgo, tsgo_msg = parse(tsgo_raw, fixture, "tsgo")
        pris, pris_msg = parse(pris_raw, fixture, "pristine")
    except Refusal as e:
        print(f"REFUSED: {e}", file=sys.stderr)
        return 2

    ref_split = tsgo ^ pris          # the two references disagree: not adjudicable
    both = tsgo & pris               # the adjudicable reference answer
    missing = sorted(both - ours)
    ours_only = sorted(ours - tsgo - pris)

    # Same place, same code — now ask whether we said the same THING.  A message the
    # two references do not themselves agree on is not adjudicable and stays in AGREE.
    agree, text_diff = [], []
    for k in sorted(both & ours):
        ref_t, ref_p = tsgo_msg.get(k, ""), pris_msg.get(k, "")
        if ref_t == ref_p and ours_msg.get(k, "") != ref_t:
            text_diff.append(k)
        else:
            agree.append(k)

    if not args.quiet:
        def show(title, keys, msgs):
            if not keys:
                return
            print(f"\n{title} ({len(keys)}):")
            for k in keys:
                print(f"  {k[0]}:{k[1]}  TS{k[2]}  {msgs.get(k, '')[:100]}")
        show("AGREE", agree, ours_msg)
        show("OURS-ONLY (false positive)", ours_only, ours_msg)
        show("MISSING (both references report)", missing, tsgo_msg)
        show("REF-SPLIT (references disagree — NOT adjudicable)", sorted(ref_split),
             {**tsgo_msg, **pris_msg})
        if text_diff:
            print(f"\nTEXT-DIFF (right row, wrong message) ({len(text_diff)}):")
            for k in text_diff:
                print(f"  {k[0]}:{k[1]}  TS{k[2]}")
                print(f"      ours: {ours_msg.get(k, '')}")
                print(f"      refs: {tsgo_msg.get(k, '')}")

    print(f"\nfixture={fixture.name} agree={len(agree)} ours-only={len(ours_only)} "
          f"missing={len(missing)} text-diff={len(text_diff)} ref-split={len(ref_split)}")
    if ref_split:
        print("NOTE: ref-split rows are evidence about NOTHING — keep them out of any prize.")

    if args.json:
        args.json.write_text(json.dumps({
            "fixture": str(fixture),
            "agree": [list(k) for k in agree],
            "ours_only": [[*k, ours_msg.get(k, "")] for k in ours_only],
            "missing": [[*k, tsgo_msg.get(k, "")] for k in missing],
            "text_diff": [[*k, ours_msg.get(k, ""), tsgo_msg.get(k, "")] for k in text_diff],
            "ref_split": [list(k) for k in sorted(ref_split)],
        }, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
