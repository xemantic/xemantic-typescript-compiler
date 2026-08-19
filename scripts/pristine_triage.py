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

# ---------------------------------------------------------------------------------------
# Round 943: the SUB-triage of the largest bucket.  `FP type system / inference` was one
# 89-row label; every row below was re-verified against pristine's own baseline (or its
# ABSENCE, which is pristine saying nothing) and read against the fixture's own source.
#
# The column that decides what a next round does is TRACTABILITY:
#   MODELLING  a type-system capability this compiler does not have -- a feature, not a bug
#   SMALL      a bounded rule with a named site
#   CONVENTION the strict-family default (CHK.13), i.e. an OWNER decision
#   FORM       both compilers reject; we use a different code
#
# FIRST match wins, so order is significant.  Evidence per family:
# `docs/pristine-divergences.md` § 2.1.
SUB_BUCKETS: list[tuple[str, str, object]] = [
    # The strict-family default wearing a code the top-level classifier cannot see.
    # Sized by `pristine_sweep.py --tsc-strict-default` (which injects tsc's OWN default
    # where -- and only where -- the case file is present to say the directive is absent),
    # cross-checked against "does pristine's own baseline carry that code ANYWHERE".
    ("S4 strict-family default in another costume", "CONVENTION",
     lambda s, c: (s == "derivedClassSuperProperties" and c == "TS2683")
     or (s == "variadicTuples1" and c == "TS7019")
     or (s == "conditionalTypes1" and c == "TS2322")),

    # `getTupleType` types a `RestType` element as a PLAIN element, so `[...T]` IS `[T]`.
    ("S1 variadic tuple types unmodelled", "MODELLING",
     lambda s, c: s == "variadicTuples1"),

    ("S2 recursive conditional / mapped types over tuples", "MODELLING",
     lambda s, c: s in ("mappedTypesArraysTuples", "recursiveMappedTypes",
                        "ramdaToolsNoInfinite")
     or (s == "ramdaToolsNoInfinite2" and c == "TS2577")),

    ("S3 contextual typing through a mapped / conditional type", "MODELLING",
     lambda s, c: s in ("correlatedUnions", "mappedTypeRecursiveInference2",
                        "contextuallyTypedSymbolNamedProperties",
                        "inferenceUnionOfObjectsMappedContextualType",
                        "callOfConditionalTypeWithConcreteBranches",
                        "genericCallAtYieldExpressionInGenericCall2")),

    ("S5 keyof of an intersection / index signature / remapped mapped type", "MODELLING",
     lambda s, c: s == "keyRemappingKeyofResult"),

    # `libFeatureAvailable` reads the RAW `ES3` default where tsc's `getEmitScriptTarget`
    # defaults an unset target to the LATEST standard -- round 941's TS18028 defect, one
    # family over, plus the lib SET that follows the same target.  (CHK.17)
    ("S6 lib availability at the DEFAULT target", "SMALL-MEDIUM",
     lambda s, c: s in ("uniqueSymbols", "uniqueSymbolsDeclarations",
                        "intersectionTypeInference3")),

    ("S7 write through a generic indexed access - TS2862 where pristine says TS2322", "FORM",
     lambda s, c: s == "keyofAndIndexedAccessErrors"),

    # FIXED round 943.  Kept so a regression shows up as the family coming back.
    ("S8 alias type parameter shadowed in the TS2344 walker - FIXED 943", "SMALL",
     lambda s, c: s == "conditionalTypes1" and c == "TS2344"),

    ("S9 a function-body type ALIAS is not bound - B83.5 in type position", "MEDIUM",
     lambda s, c: s == "conditionalTypes1" and c == "TS2314"),

    ("S10 residue - one mechanism each", "MODELLING",
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
    sub = collections.Counter()
    subdet = collections.defaultdict(list)
    for stem, v in sorted(res.items()):
        if not v["ours_only"]:
            continue
        for code, n in collections.Counter(c for _f, _l, c in v["ours_only"]).most_common():
            if not BUCKETS[-1][1](stem, code):
                continue
            claimed = False
            for name, pred in BUCKETS[:-1]:
                if pred(stem, code):
                    claimed = True
                    break
            if claimed:
                continue
            for name, tract, pred in SUB_BUCKETS:
                if pred(stem, code):
                    sub[(name, tract)] += n
                    subdet[(name, tract)].append((stem, code, n))
                    break

    for name, _ in BUCKETS:
        if per[name]:
            groups = detail[name]
            top = ", ".join(f"{s}/{c}x{n}" for s, c, n in sorted(groups, key=lambda g: -g[2])[:3])
            print(f"{per[name]:5}  {name:52} [{len(groups)} groups] {top}")
    if sub:
        print("\n  -- SUB-TRIAGE of `FP type system / inference` (round 943) --")
        for (name, tract), n in sub.most_common():
            groups = subdet[(name, tract)]
            top = ", ".join(f"{s}/{c}x{k}" for s, c, k in sorted(groups, key=lambda g: -g[2])[:3])
            print(f"{n:5}  {tract:12} {name:58} [{len(groups)} groups] {top}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
