#!/usr/bin/env python3
"""
Find surgical-fix candidates from build/test-results/jvmTest/*.xml.

Parses junit XMLs, extracts failing baseline-mismatch tests, and classifies
each by diff shape:

  EXTRA     — actual has N extra error lines expected doesn't ("too aggressive")
  MISSING   — expected has N error lines actual doesn't ("simple new check")
  SWAP      — same position, different TS code ("wrong code at call site")

Cross-references each candidate against the "Explored-but-skipped" section
in PLAN-PHASE-4.md; flagged candidates print with a "[SKIP]" marker so the
agent doesn't re-investigate already-characterized failures.

Usage:
    python3 scripts/find_candidates.py            # all three buckets, first 20 each
    python3 scripts/find_candidates.py --all      # no per-bucket limit
    python3 scripts/find_candidates.py --fresh    # only candidates NOT in skipped log

Exit code is 0 unless XMLs are missing.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
XML_GLOB = os.path.join(
    REPO_ROOT, "build", "test-results", "jvmTest",
    "TEST-com.xemantic.typescript.compiler.TypeScriptCompilerTests_*.xml",
)
PLAN = os.path.join(REPO_ROOT, "PLAN-PHASE-4.md")

ERR_LINE_RE = re.compile(r"(.+?)\((\d+),(\d+)\): error (TS\d+): (.+)$")


def load_skipped_tests() -> set[str]:
    """Extract test-basename tokens from the 'Explored-but-skipped' section
    of PLAN-PHASE-4.md. Matches occurrences like ``testname_ts`` or
    ``testname_ts__suffix__`` inside backticks, and stores the base
    ``testname_ts`` form so parameterized variants match automatically.
    Tokens inside ~~strikethrough~~ markers are excluded (resolved entries)."""
    if not os.path.isfile(PLAN):
        return set()
    with open(PLAN) as f:
        text = f.read()
    m = re.search(
        r"### Explored-but-skipped tests.*?(?=^### )",
        text, re.DOTALL | re.MULTILINE,
    )
    if not m:
        return set()
    region = m.group(0)
    # Strip strikethrough spans first so resolved entries don't leak in.
    region_active = re.sub(r"~~.*?~~", "", region, flags=re.DOTALL)
    out: set[str] = set()
    for tok in re.findall(r"`([A-Za-z0-9_\-]+)`", region_active):
        base = re.match(r"([A-Za-z0-9_\-]+?_ts)(?:__|$)", tok)
        if base:
            out.add(base.group(1))
    return out


def parse_diff_lines(msg: str) -> tuple[list[str], list[str]]:
    minus, plus = [], []
    for ln in msg.splitlines():
        if ln.startswith("---") or ln.startswith("+++") or ln.startswith("@@"):
            continue
        if ln.startswith("-"):
            minus.append(ln[1:])
        elif ln.startswith("+"):
            plus.append(ln[1:])
    return minus, plus


def parse_err(ln: str):
    m = ERR_LINE_RE.match(ln.strip())
    if not m:
        return None
    return (m.group(1), int(m.group(2)), int(m.group(3)), m.group(4), m.group(5))


def collect():
    """Return (extras, missings, swaps) as lists of (testname, detail...)."""
    extras, missings, swaps = [], [], []
    for xml_file in glob.glob(XML_GLOB):
        try:
            tree = ET.parse(xml_file)
        except ET.ParseError:
            continue
        for tc in tree.getroot().iter("testcase"):
            failure = tc.find("failure")
            if failure is None:
                continue
            msg = failure.text or ""
            if "--- expected" not in msg:
                continue
            minus, plus = parse_diff_lines(msg)
            minus_parsed = [p for p in (parse_err(l) for l in minus) if p]
            plus_parsed = [p for p in (parse_err(l) for l in plus) if p]
            if not minus_parsed and not plus_parsed:
                continue
            exp_set = set(minus_parsed)
            act_set = set(plus_parsed)
            missing = exp_set - act_set
            extra = act_set - exp_set
            total = len(missing) + len(extra)
            if total == 0 or total > 3:
                continue
            exp_by_pos = {(f, l, c): (code, m) for (f, l, c, code, m) in missing}
            act_by_pos = {(f, l, c): (code, m) for (f, l, c, code, m) in extra}
            shared_positions = set(exp_by_pos) & set(act_by_pos)
            testname = tc.get("name", "").split(" has expected")[0]
            if shared_positions:
                pair = [(pos, exp_by_pos[pos], act_by_pos[pos]) for pos in shared_positions]
                swaps.append((testname, total, pair))
            elif extra and not missing:
                extras.append((testname, len(extra), list(extra)[:3]))
            elif missing and not extra:
                missings.append((testname, len(missing), list(missing)[:3]))
    return extras, missings, swaps


def tag(name: str, skipped: set[str]) -> str:
    base = name.split("[jvm]")[0].strip()
    # Strip the "_ts__strict_false__" suffix chunk when present to match skipped keys
    bare = re.match(r"([A-Za-z0-9_\-]+_ts)", base)
    key = bare.group(1) if bare else base
    return "[SKIP] " if key in skipped else "       "


def main(argv):
    fresh_only = "--fresh" in argv
    show_all = "--all" in argv
    skipped = load_skipped_tests()

    xmls = glob.glob(XML_GLOB)
    if not xmls:
        print(f"No test XMLs matched {XML_GLOB}", file=sys.stderr)
        print("Run: rm -rf build/test-results/jvmTest/binary && ./gradlew jvmTest", file=sys.stderr)
        return 2
    if len(xmls) < 20:
        print(
            f"WARNING: only {len(xmls)} XMLs found (expected ~27). "
            "The test runner wipes XMLs on `--tests *Name*` runs. "
            "Results below will be partial. Re-run the FULL suite first:",
            file=sys.stderr,
        )
        print(
            "  rm -rf build/test-results/jvmTest/binary && ./gradlew jvmTest",
            file=sys.stderr,
        )
        print(file=sys.stderr)

    extras, missings, swaps = collect()
    limit = None if show_all else 20

    def emit(title: str, rows, kind: str):
        print("=" * 80)
        filtered = rows
        if fresh_only:
            filtered = [r for r in rows if tag(r[0], skipped).strip() != "[SKIP]"]
        print(f"{title}: {len(filtered)}{' (filtered from ' + str(len(rows)) + ')' if fresh_only and len(filtered) != len(rows) else ''}")
        for row in sorted(filtered, key=lambda x: x[1])[:limit]:
            mark = tag(row[0], skipped)
            print(f"  {mark}+{row[1]} {row[0]}")
            if kind == "extra":
                for ex in row[2]:
                    print(f"           EXTRA: {ex[3]} @ ({ex[1]},{ex[2]}): {ex[4][:90]}")
            elif kind == "missing":
                for ex in row[2]:
                    print(f"           MISS:  {ex[3]} @ ({ex[1]},{ex[2]}): {ex[4][:90]}")
            else:  # swap
                for pos, ex, ac in row[2][:2]:
                    print(f"           SWAP @ ({pos[1]},{pos[2]}): exp {ex[0]}  act {ac[0]}")
                    print(f"                  exp: {ex[1][:80]}")
                    print(f"                  act: {ac[1][:80]}")

    emit("EXTRA DIAGS (too aggressive)", extras, "extra")
    emit("MISSING DIAGS", missings, "missing")
    emit("CODE SWAPS", swaps, "swap")

    if skipped:
        print()
        print(f"Cross-reference: {len(skipped)} tests in 'Explored-but-skipped' log.")
        print("Use --fresh to hide them.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
