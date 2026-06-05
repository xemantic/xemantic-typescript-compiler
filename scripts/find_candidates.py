#!/usr/bin/env python3
"""
Find surgical-fix candidates from build/test-results/jvmTest/*.xml.

Parses junit XMLs, extracts failing baseline-mismatch tests, and classifies
each by diff shape:

  EXTRA     — actual has N extra error lines expected doesn't ("too aggressive")
  MISSING   — expected has N error lines actual doesn't ("simple new check")
  SWAP      — same position, different TS code ("wrong code at call site")
  NONE      — baseline expects diagnostics but we emit NONE ("missing check")
  OUTPUT    — JS-emit / decl-emit / sourcemap output diff, no error codes involved

IMPORTANT — the three "diff" buckets (EXTRA/MISSING/SWAP) only see tests where
we ALREADY emit some parseable `error TSxxxx` line. Historically this caused
recurring false "surgical pool exhausted" sessions: ~70% of remaining failures
are NONE (we emit nothing) or OUTPUT (pure JS/decl/sourcemap diffs) and were
structurally invisible. The NONE and OUTPUT buckets close that blind spot. Do
NOT declare the pool exhausted unless NONE and OUTPUT are also dry.

The NONE bucket reads the expected `*.errors.txt` baseline from
typescript-repo/tests/baselines/reference/ to recover the expected codes, then
groups by code signature and sorts by error-line count (fewest = most tractable).

Cross-references each candidate against the "Explored-but-skipped" section
in PLAN-PHASE-4.md; flagged candidates print with a "[SKIP]" marker so the
agent doesn't re-investigate already-characterized failures.

Usage:
    python3 scripts/find_candidates.py            # all buckets, first 20 each
    python3 scripts/find_candidates.py --all      # no per-bucket limit
    python3 scripts/find_candidates.py --fresh    # only candidates NOT in skipped log
    python3 scripts/find_candidates.py --none      # NONE bucket only (grouped by code)
    python3 scripts/find_candidates.py --output    # OUTPUT bucket only
    python3 scripts/find_candidates.py --none --fresh --code TS2307   # focus one code

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
REF_DIR = os.path.join(REPO_ROOT, "typescript-repo", "tests", "baselines", "reference")

ERR_LINE_RE = re.compile(r"(.+?)\((\d+),(\d+)\): error (TS\d+): (.+)$")
NONE_RE = re.compile(r"reference/([^ \n]+\.errors\.txt)")
CODE_RE = re.compile(r"error (TS\d+)")

# Optional tsgo-relevance layer (see TSGO-RELEVANCE.md). `--tsgo` hides failures
# that target features tsgo (TypeScript 7.0) removed, so the candidate pool shows
# only tsgo-relevant work.
try:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from tsgo_relevance import irrelevant_bases as _tsgo_irrelevant_bases
except Exception:  # pragma: no cover
    _tsgo_irrelevant_bases = None


def _base_key(name: str) -> str:
    m = re.match(r"([A-Za-z0-9_\-]+_ts)", name.split("[jvm]")[0].strip())
    return m.group(1) if m else name


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


def _node_text(tc) -> str:
    """Full failure/error text (the `message` attr is JUnit-truncated; `.text`
    carries the complete diff / stack trace)."""
    node = tc.find("failure")
    if node is None:
        node = tc.find("error")
    if node is None:
        return ""
    return node.text or node.get("message") or ""


def _classify_output(desc: str) -> str:
    d = desc.lower()
    if "compiles to javascript" in d:
        return "js-emit"
    if "declaration" in d:
        return "decl-emit"
    if "source map" in d or "sourcemap" in d:
        return "sourcemap"
    return "other"


def collect_uncovered():
    """Return (none_produced, output_diffs).

    none_produced: list of (testname, code_sig_tuple, n_err_lines, baseline)
      — baseline expects diagnostics but we emit none. Codes recovered from the
      expected `*.errors.txt` baseline. These are INVISIBLE to collect() because
      no diff is produced.
    output_diffs: list of (testname, n_changed_lines, kind)
      — failing tests with an expected/actual diff but NO parseable error lines
      on either side (pure JS/decl/sourcemap/formatting). Also invisible to
      collect() (it requires at least one parsed error line).
    """
    none_produced, output_diffs = [], []
    for xml_file in glob.glob(XML_GLOB):
        try:
            tree = ET.parse(xml_file)
        except ET.ParseError:
            continue
        for tc in tree.getroot().iter("testcase"):
            if tc.find("failure") is None and tc.find("error") is None:
                continue
            msg = _node_text(tc)
            name = tc.get("name", "")
            short = name.split(" ")[0]
            if "but none produced" in msg:
                m = NONE_RE.search(msg)
                if not m:
                    continue
                path = os.path.join(REF_DIR, m.group(1))
                if not os.path.isfile(path):
                    continue
                with open(path, encoding="utf-8", errors="replace") as fh:
                    txt = fh.read()
                codes = CODE_RE.findall(txt)
                if not codes:
                    continue
                sig = tuple(sorted(set(codes)))
                none_produced.append((short, sig, len(codes), m.group(1)))
            elif "--- expected" in msg:
                minus, plus = parse_diff_lines(msg)
                if any(parse_err(l) for l in minus + plus):
                    continue  # has error lines → handled by collect()
                changed = len(minus) + len(plus)
                if changed == 0:
                    continue
                output_diffs.append((short, changed, _classify_output(name)))
    return none_produced, output_diffs


def tag(name: str, skipped: set[str]) -> str:
    base = name.split("[jvm]")[0].strip()
    # Strip the "_ts__strict_false__" suffix chunk when present to match skipped keys
    bare = re.match(r"([A-Za-z0-9_\-]+_ts)", base)
    key = bare.group(1) if bare else base
    return "[SKIP] " if key in skipped else "       "


def main(argv):
    fresh_only = "--fresh" in argv
    show_all = "--all" in argv
    none_only = "--none" in argv
    output_only = "--output" in argv
    tsgo_only = "--tsgo" in argv
    code_filter = None
    if "--code" in argv:
        i = argv.index("--code")
        if i + 1 < len(argv):
            code_filter = argv[i + 1].upper()
    skipped = load_skipped_tests()
    tsgo_irrelevant = set()
    if tsgo_only and _tsgo_irrelevant_bases is not None:
        tsgo_irrelevant = _tsgo_irrelevant_bases()

    def tsgo_drop(name: str) -> bool:
        return tsgo_only and _base_key(name) in tsgo_irrelevant

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
            filtered = [r for r in filtered if tag(r[0], skipped).strip() != "[SKIP]"]
        if tsgo_only:
            filtered = [r for r in filtered if not tsgo_drop(r[0])]
        print(f"{title}: {len(filtered)}{' (filtered from ' + str(len(rows)) + ')' if len(filtered) != len(rows) else ''}")
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

    def emit_none(none_produced):
        print("=" * 80)
        rows = none_produced
        if fresh_only:
            rows = [r for r in rows if tag(r[0], skipped).strip() != "[SKIP]"]
        if tsgo_only:
            rows = [r for r in rows if not tsgo_drop(r[0])]
        if code_filter:
            rows = [r for r in rows if code_filter in r[1]]
        # group by code signature; within a group sort by err-line count
        groups = defaultdict(list)
        for r in rows:
            groups[r[1]].append(r)
        # rank signatures: single-code first, then by tractability (fewest lines),
        # then by group size (bigger cluster = more leverage)
        ranked = sorted(
            groups.items(),
            key=lambda kv: (len(kv[0]), min(r[2] for r in kv[1]), -len(kv[1])),
        )
        total = sum(len(v) for v in groups.values())
        print(f"NONE-PRODUCED (baseline expects errors, we emit none): {total} "
              f"across {len(groups)} code signatures")
        for sig, members in ranked:
            members.sort(key=lambda r: r[2])
            print(f"  --- {'+'.join(sig)}  ({len(members)} tests) ---")
            for short, _sig, nlines, _bl in members[:limit]:
                mark = tag(short, skipped)
                print(f"    {mark}{nlines:>2}ln {short}")

    def emit_output(output_diffs):
        print("=" * 80)
        rows = output_diffs
        if fresh_only:
            rows = [r for r in rows if tag(r[0], skipped).strip() != "[SKIP]"]
        if tsgo_only:
            rows = [r for r in rows if not tsgo_drop(r[0])]
        if code_filter:
            rows = []  # OUTPUT diffs carry no codes
        bykind = defaultdict(list)
        for r in rows:
            bykind[r[2]].append(r)
        print(f"OUTPUT DIFFS (JS/decl/sourcemap, no error codes): {len(rows)}")
        for kind in sorted(bykind, key=lambda k: -len(bykind[k])):
            members = sorted(bykind[kind], key=lambda r: r[1])
            print(f"  --- {kind}  ({len(members)} tests) ---")
            for short, changed, _k in members[:limit]:
                mark = tag(short, skipped)
                print(f"    {mark}Δ{changed:<3} {short}")

    none_produced, output_diffs = collect_uncovered()

    if none_only:
        emit_none(none_produced)
    elif output_only:
        emit_output(output_diffs)
    else:
        emit("EXTRA DIAGS (too aggressive)", extras, "extra")
        emit("MISSING DIAGS", missings, "missing")
        emit("CODE SWAPS", swaps, "swap")
        emit_none(none_produced)
        emit_output(output_diffs)

    if skipped:
        print()
        print(f"Cross-reference: {len(skipped)} tests in 'Explored-but-skipped' log.")
        print("Use --fresh to hide them.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
