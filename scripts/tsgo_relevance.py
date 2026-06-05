#!/usr/bin/env python3
"""
tsgo-relevance layer — classify failing tests by relevance to the *tsgo*
(TypeScript 7.0 native / "Project Corsa") compatibility target rather than the
legacy tsc 5.x test suite.

Why this exists
---------------
Our goal is an *equivalent* compiler, but the target is the FUTURE tsgo, not the
current tsc. tsgo removes a set of legacy features (see TSGO-RELEVANCE.md). Tests
whose whole point is one of those removed features should NOT count against our
progress and are not worth chasing. This module marks them so surgical effort
focuses only on tsgo-relevant failures.

It is a *reporting/analysis* layer ONLY — it does NOT touch the test-generation
pipeline (build.gradle.kts). The full suite still runs every test; this just
classifies the results. That keeps the ground-truth failure count intact and
carries zero risk of breaking generation.

Classification (see TSGO-RELEVANCE.md for the authoritative policy + rationale):

  IRRELEVANT  — targets a feature tsgo removed; do not chase. Two sources:
                (a) SIGNAL RULES (auto): a JS-emit / source-map SUBTEST whose
                    target is ES3/ES5 or whose module is AMD/System/UMD — tsgo
                    removed those emit targets/module emitters, so the emitted
                    JS we are diffing against no longer exists in tsgo.
                (b) CURATED denylist in TSGO-RELEVANCE.md (removed options,
                    removed JSDoc tags, etc.) — verified by a human/agent.
  DIVERGES    — tsgo's behavior differs from the tsc baseline we diff against
                (e.g. strict-by-default), so the baseline is the WRONG target.
                Curated in TSGO-RELEVANCE.md. Treated as "not chase the tsc
                baseline" but flagged distinctly from removed-feature IRRELEVANT.
  RELEVANT    — everything else (the overwhelming majority — tsgo keeps the type
                system, so core type-checking / decl-emit tests are all relevant).

Usage:
  python3 scripts/tsgo_relevance.py              # report the relevance breakdown
  python3 scripts/tsgo_relevance.py --list-relevant     # one relevant failing subtest per line
  python3 scripts/tsgo_relevance.py --list-irrelevant   # irrelevant subtests + reason
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
XML_GLOB = os.path.join(
    REPO, "build", "test-results", "jvmTest",
    "TEST-com.xemantic.typescript.compiler.TypeScriptCompilerTests_*.xml",
)
CASES = os.path.join(REPO, "typescript-repo", "tests", "cases")
POLICY = os.path.join(REPO, "TSGO-RELEVANCE.md")

# Targets / modules tsgo removed as EMIT targets (legacy downlevel / module emitters).
DEAD_EMIT_TARGETS = {"es3", "es5"}
DEAD_EMIT_MODULES = {"amd", "system", "umd"}

# ---- source index (base-name -> first matching source path) ----------------
_src_index = None


def src_index():
    global _src_index
    if _src_index is not None:
        return _src_index
    _src_index = {}
    for d in ("compiler", "conformance"):
        for root, _, files in os.walk(os.path.join(CASES, d)):
            for f in files:
                if f.endswith(".ts") or f.endswith(".tsx"):
                    _src_index.setdefault(f.rsplit(".", 1)[0], os.path.join(root, f))
    return _src_index


def subtest_kind(name: str) -> str:
    n = name.lower()
    if "compiles to javascript" in n:
        return "js"
    if "source map" in n or "sourcemap" in n:
        return "sourcemap"
    if "declaration" in n:
        return "decl"
    if "expected errors" in n:
        return "errors"
    return "other"


def parse_params(name: str):
    """Per-subtest parameterized config encoded in the test name, e.g.
    `foo_ts__target_es5__module_amd__compiles...` -> {'target':'es5','module':'amd'}."""
    out = {}
    for m in re.finditer(r"__([a-z]+)_([A-Za-z0-9.\-]+)__", name):
        out.setdefault(m.group(1), m.group(2).lower())
    return out


def source_directive(base: str, key: str):
    """First value of a `// @key: v` directive in the test source (lowercased).
    For multi-value (parameterized) directives returns the raw comma list."""
    src = src_index().get(base)
    if not src:
        return None
    head = open(src, encoding="utf-8", errors="replace").read()[:2500]
    m = re.search(r"(?im)^\s*//\s*@" + re.escape(key) + r"\s*:\s*([^\n]+)", head)
    return m.group(1).strip().lower() if m else None


def base_name(name: str):
    m = re.match(r"([A-Za-z0-9_\-]+)_ts(?:__|\b| )", name)
    return m.group(1) if m else None


# ---- curated policy lists (parsed from TSGO-RELEVANCE.md) -------------------
def load_curated():
    r"""Returns (irrelevant: dict[name->reason], diverges: dict[name->reason]).
    Parses bullet lines under the '## Curated IRRELEVANT' and
    '## Curated DIVERGES' headings of the form:  - `name_ts` — reason."""
    irr, div = {}, {}
    if not os.path.isfile(POLICY):
        return irr, div
    text = open(POLICY, encoding="utf-8").read()
    for heading, bucket in (("Curated IRRELEVANT", irr), ("Curated DIVERGES", div)):
        m = re.search(r"##+\s*" + heading + r".*?(?=^##\s|\Z)", text, re.S | re.M)
        if not m:
            continue
        for line in m.group(0).splitlines():
            lm = re.match(r"\s*-\s*`([A-Za-z0-9_\-]+)`\s*[—\-:]*\s*(.*)", line)
            if lm:
                name = lm.group(1)
                key = name[:-3] if name.endswith("_ts") else name
                bucket[key] = lm.group(2).strip() or "(no reason given)"
    return irr, div


def classify(name: str, curated_irr, curated_div):
    """-> (relevance, reason). relevance in {relevant, irrelevant, diverges}."""
    base = base_name(name)
    if base is None:
        return "relevant", ""
    if base in curated_div:
        return "diverges", "curated: " + curated_div[base]
    if base in curated_irr:
        return "irrelevant", "curated: " + curated_irr[base]
    kind = subtest_kind(name)
    params = parse_params(name)
    # SIGNAL RULES apply only to emitted-output subtests (js / sourcemap).
    if kind in ("js", "sourcemap"):
        target = params.get("target") or (source_directive(base, "target") or "")
        module = params.get("module") or (source_directive(base, "module") or "")
        # target/module directives may be comma lists for non-parameterized tests;
        # a single static value is what reaches a single subtest, but be lenient.
        tset = {t.strip() for t in target.split(",")} if target else set()
        mset = {m.strip() for m in module.split(",")} if module else set()
        if tset & DEAD_EMIT_TARGETS:
            return "irrelevant", "signal: ES3/ES5 JS-emit (tsgo removed legacy emit targets)"
        if mset & DEAD_EMIT_MODULES:
            return "irrelevant", "signal: AMD/System/UMD emit (tsgo removed legacy module emitters)"
    return "relevant", ""


def failing_subtests():
    out = []
    for xf in glob.glob(XML_GLOB):
        try:
            tree = ET.parse(xf)
        except ET.ParseError:
            continue
        for tc in tree.getroot().iter("testcase"):
            if tc.find("failure") is None and tc.find("error") is None:
                continue
            out.append(tc.get("name", ""))
    return out


def irrelevant_bases():
    """Public helper for find_candidates.py: the set of `<base>_ts` names that are
    tsgo-IRRELEVANT or DIVERGES (a base counts if ANY of its failing subtests is so
    classified — the irrelevant set is tiny and per-base failures are homogeneous,
    so this base-level approximation matches the subtest truth in practice).
    Returns names WITH the `_ts` suffix to match find_candidates' key format."""
    curated_irr, curated_div = load_curated()
    out = set()
    for name in failing_subtests():
        rel, _ = classify(name, curated_irr, curated_div)
        if rel != "relevant":
            base = base_name(name)
            if base:
                out.add(base + "_ts")
    return out


def main(argv):
    if not glob.glob(XML_GLOB):
        print("No test XMLs. Run the full suite first:", file=sys.stderr)
        print("  rm -rf build/test-results/jvmTest/binary && ./gradlew jvmTest", file=sys.stderr)
        return 2
    curated_irr, curated_div = load_curated()
    subs = failing_subtests()
    buckets = defaultdict(list)
    reasons = defaultdict(lambda: defaultdict(int))
    for name in subs:
        rel, reason = classify(name, curated_irr, curated_div)
        buckets[rel].append((name, reason))
        if rel != "relevant":
            reasons[rel][reason.split(":")[0] + ": " + reason.split(":", 1)[-1].split("(")[0].strip()[:60]] += 1

    if "--list-relevant" in argv:
        for name, _ in sorted(buckets["relevant"]):
            print(name)
        return 0
    if "--list-irrelevant" in argv:
        for name, reason in sorted(buckets["irrelevant"] + buckets["diverges"]):
            print(f"{name}\t{reason}")
        return 0

    total = len(subs)
    print(f"Failing subtests: {total}")
    print(f"  RELEVANT (chase these):   {len(buckets['relevant'])}")
    print(f"  IRRELEVANT (tsgo-removed): {len(buckets['irrelevant'])}")
    print(f"  DIVERGES (baseline wrong): {len(buckets['diverges'])}")
    print(f"  curated denylist entries: {len(curated_irr)} irrelevant, {len(curated_div)} diverges")
    for rel in ("irrelevant", "diverges"):
        if reasons[rel]:
            print(f"\n  {rel.upper()} by reason:")
            for r, c in sorted(reasons[rel].items(), key=lambda x: -x[1]):
                print(f"    {c:>4}  {r}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
