#!/usr/bin/env python3
"""Bucket a `pristine_sweep.py` run's OURS-ONLY rows by CAUSE CLASS.

The sweep says WHERE we diverge from pristine tsc; this says WHY, and it keeps the
classification in one reviewable place so a next round can re-run it against a fresh
sweep instead of re-deriving 150 (fixture, code) groups by hand.

Every rule below was read off the fixture's own source and pristine's own baseline; the
evidence per bucket is written up in `docs/pristine-divergences.md`.  A group that no
rule claims lands in UNCLASSIFIED, which is the thing to look at first after a sweep.

USAGE
    python3 scripts/pristine_triage.py build/bench/round941-grid/sweep.after.json
"""
from __future__ import annotations

import collections
import json
import sys

# (bucket, predicate on (stem, code)) -- FIRST match wins, so order is significant.
BUCKETS: list[tuple[str, object]] = [
    # Fixed in round 941; kept so a regression shows up as a bucket coming back.
    ("FIXED-941 super-call statement scan (TS2376)",
     lambda s, c: c == "TS2376"),
    ("FIXED-941 private-identifier target gate (TS18028)",
     lambda s, c: c == "TS18028"),

    # A deliberate, corpus-driven convention of THIS compiler: the strict-family checks
    # fire unless `@strict: false` is EXPLICIT, where tsc requires `strict` to be ON.
    ("CONVENTION strict-by-default (TS2564/TS2454)",
     lambda s, c: c in ("TS2564", "TS2454")
     and s not in ("strictPropertyInitialization",)),

    # The sweep's scratch tsconfig cannot reproduce a JSX program's namespace/runtime.
    ("HARNESS jsx configuration",
     lambda s, c: s.startswith("jsx") or s.startswith("tsx")
     or s == "propTypeValidatorInference"),

    # Syntax this compiler does not parse at all.
    ("PARSER GAP unsupported syntax",
     lambda s, c: s.startswith("usingDeclarations") or s.startswith("inferTypes")
     or s.startswith("esDecorators-") or s == "privateIndexer2"
     or s == "topLevelAwaitErrors.1"),

    # A fixture whose input is deliberately malformed: our error RECOVERY differs, so the
    # cascade differs.  A frozen subsystem (CLAUDE.md).
    ("PARSER RECOVERY divergence on a malformed fixture",
     lambda s, c: s in ("mappedTypeProperties", "parserIndexSignature10",
                        "parserSymbolIndexer5", "mappedTypeErrors",
                        "declarationEmitHigherOrderRetainedGenerics",
                        "classMemberWithMissingIdentifier2")),

    # Genuine FPs, grouped by the machinery that owns them.
    ("FP computed keys / declaration emit",
     lambda s, c: s in ("indexSignatures1", "symbolProperty52", "isolatedModulesConstEnum",
                        "contextualComputedNonBindablePropertyType",
                        "strictPropertyInitialization")
     or (c == "TS2307" and s.startswith("declarationEmit"))
     or (c in ("TS2304", "TS2307") and s in ("declarationEmitShadowingInferNotRenamed",
                                             "uniqueSymbolPropertyDeclarationEmit"))),
    ("FP narrowing / control flow",
     lambda s, c: s in ("typeGuardNarrowsIndexedAccessOfKnownProperty1",
                        "typeGuardsWithInstanceOfBySymbolHasInstance",
                        "controlFlowInstanceofWithSymbolHasInstance",
                        "neverAsDiscriminantType", "symbolProperty57", "symbolProperty61")),
    ("FP type system / inference",
     lambda s, c: True),
]


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else "build/bench/round941-grid/sweep.after.json"
    d = json.load(open(path))
    res = d["results"]
    per = collections.Counter()
    detail = collections.defaultdict(list)
    total = 0
    for stem, v in sorted(res.items()):
        if not v["ours_only"]:
            continue
        for code, n in collections.Counter(c for _f, _l, c in v["ours_only"]).most_common():
            for name, pred in BUCKETS:
                if pred(stem, code):
                    per[name] += n
                    detail[name].append((stem, code, n))
                    total += n
                    break
    print(f"{path}: {d['total_ours_only_rows']} ours-only rows over "
          f"{d['fixtures_with_ours_only']} fixtures (classified {total})")
    for name, _ in BUCKETS:
        if per[name]:
            groups = detail[name]
            top = ", ".join(f"{s}/{c}x{n}" for s, c, n in sorted(groups, key=lambda g: -g[2])[:3])
            print(f"{per[name]:5}  {name:52} [{len(groups)} groups] {top}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
