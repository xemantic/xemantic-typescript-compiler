#!/usr/bin/env python3
"""(JIT.1)(e) round 821 — apply the split of
`Checker.tryInferSingleTypeParamFromArgs`, 11,930 bytecodes -> an entry plus
three helpers. The LAST method in the (JIT.1) census.

The boundaries come from `scripts/tisp_split_analyze.py`, which is a DATA-FLOW
analysis rather than the contiguity argument every earlier target in this arc
took: this body is one `for (tp in orderedTps)` loop whose regions all touch the
same locals, so what decides a seam is the read/write set and the liveness, not
the shape.

    region        lines  bytecodes  reads                       writes
    PASS1           316      3,109  tp tps tpsSet params args   candidates (container)
                                    forReturnType candidates    tpSawAnyArg (LIVE-OUT)
    PASS2           422      5,470  tp tps tpsSet params args   candidates (container)
                                    mapperPairs candidates
    CONSTRAINT      131      1,566  tp constraint firstWidened  — (diagnostics only)
                                    first effectiveCandidates
                                    params args source fileName

THREE THINGS MAKE THIS SPLIT EXACT RATHER THAN CAREFUL.

  * **A mutated CONTAINER crosses a call boundary for free.** `candidates` is
    only ever appended to, so it is passed as a `MutableList` parameter and the
    moved text needs no edit at all. Only `tpSawAnyArg` is REBOUND, and it is
    the one thing handed back.
  * **`Boolean?` makes every `return null` in the moved text mean exactly what
    it meant before.** All 22 of them were whole-function bails; in a helper
    returning `Boolean?` they are still `return null`, and each call site writes
    `?: return null`. Nothing in the moved regions is rewritten — 869 lines move
    VERBATIM, and the diff carries no hand-edited control flow to review.
  * **No region holds a `continue`/`break` that targeted the caller's loop**
    (measured: 0/0/0), which is what makes a plain helper legal here at all —
    round 819's target needed a one-iteration frame for exactly that reason.

The one thing that is NOT a pure move: the local `data class Candidate` is
hoisted to a private nested class, because a helper signature cannot name a
class declared inside a function body. Its parameter list is unchanged.

Run:  python3 scripts/tisp_split_apply.py [--check]
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
#  xemantic-typescript-compiler - a conformant TypeScript compiler and type
#  checker that runs on JVM, native, and WebAssembly
#  Copyright (C) 2026 Kazimierz Pogoda / Xemantic
#
#  This program is free software: you can redistribute it and/or modify
#  it under the terms of the GNU Affero General Public License as
#  published by the Free Software Foundation, version 3 of the License.
import subprocess
import sys

PATH = "src/commonMain/kotlin/Checker.kt"

FN_HEAD = "    private fun tryInferSingleTypeParamFromArgs("
CANDIDATE = ("        data class Candidate(val argIdx: Int, val widenedType: Type, "
             "val literalType: Type?, val fromObjLit: Boolean = false)")

CANDIDATE_HOIST = [
    "    /**",
    "     * One inference candidate gathered for a single type parameter by",
    "     * [tryInferSingleTypeParamFromArgs]: the argument position it came from, the",
    "     * widened type it contributes, the literal form kept for TS2345 display, and",
    "     * whether it came from an object literal's property values (a weaker anchor,",
    "     * see B52.3).",
    "     *",
    "     * (JIT.1)(e) round 821: this was a LOCAL data class inside",
    "     * [tryInferSingleTypeParamFromArgs]. It is nested here unchanged so the",
    "     * gathering helpers split out of that function can name it in their",
    "     * signatures.",
    "     */",
    "    private data class Candidate(val argIdx: Int, val widenedType: Type, "
    "val literalType: Type?, val fromObjLit: Boolean = false)",
    "",
]

# Each region: the exact first and last source line of the run that MOVES, the
# dedent applied to it, the helper it becomes, and the call that replaces it.
HELPERS = [
    dict(
        name="tispGatherAnchorCandidates",
        first="            // Round 440: track whether this TP had an `any`-typed arg soft-skipped at a",
        last="            }",          # closes the `for (i in params.indices)` of pass 1
        last_hint=115511,
        dedent=4,
        doc=[
            "    /**",
            "     * (JIT.1)(e) round 821 — pass 1 of [tryInferSingleTypeParamFromArgs]'s",
            "     * per-type-parameter candidate gathering: the ANCHOR positions (bare-`tp`,",
            "     * rest-`tp[]`, `Array<tp>`, anonymous-object-of-`tp` members, the nullable",
            "     * union modes, predicate position, the `tp[][]` and droppable-union",
            "     * anchors). Moved VERBATIM out of the `for (tp in orderedTps)` loop.",
            "     *",
            "     * Appends to [candidates] (a container the caller owns), and RETURNS the",
            "     * `tpSawAnyArg` flag, which is the only value that crossed the boundary as",
            "     * a rebind. **`null` means the whole inference bails** — every `return",
            "     * null` inside the moved text was a whole-function bail before the split",
            "     * and still is, via the caller's `?: return null`.",
            "     */",
        ],
        sig=[
            "    private fun tispGatherAnchorCandidates(",
            "        tp: Type.TypeParam,",
            "        tps: List<Type.TypeParam>,",
            "        tpsSet: Set<Type.TypeParam>,",
            "        params: List<Symbol>,",
            "        args: List<Expression>,",
            "        forReturnType: Boolean,",
            "        candidates: MutableList<Candidate>,",
            "    ): Boolean? {",
        ],
        tail=["        return tpSawAnyArg", "    }", ""],
        call=[
            "            val tpSawAnyArg = tispGatherAnchorCandidates(",
            "                tp = tp,",
            "                tps = tps,",
            "                tpsSet = tpsSet,",
            "                params = params,",
            "                args = args,",
            "                forReturnType = forReturnType,",
            "                candidates = candidates,",
            "            ) ?: return null",
        ],
    ),
    dict(
        name="tispGatherCallbackCandidates",
        first="            // Pass 2 (B83.4a): callback (B83.1 gate (f)) positions. Walks the same",
        last="            }",
        last_hint=115933,
        dedent=4,
        doc=[
            "    /**",
            "     * (JIT.1)(e) round 821 — pass 2 of [tryInferSingleTypeParamFromArgs]'s",
            "     * per-type-parameter candidate gathering: the CALLBACK positions (B83.1",
            "     * gate (f) single- and multi-param shapes, and the B83.4b/c/d/i",
            "     * callback-RETURN inferences that re-type an un-annotated lambda body).",
            "     * Moved VERBATIM out of the `for (tp in orderedTps)` loop.",
            "     *",
            "     * Reads [mapperPairs] — the type parameters anchored by EARLIER iterations",
            "     * — and never writes it, which is why it can take the caller's list as a",
            "     * read-only `List`. Appends to [candidates]. **`null` means the whole",
            "     * inference bails**, as in [tispGatherAnchorCandidates].",
            "     */",
        ],
        sig=[
            "    private fun tispGatherCallbackCandidates(",
            "        tp: Type.TypeParam,",
            "        tps: List<Type.TypeParam>,",
            "        tpsSet: Set<Type.TypeParam>,",
            "        params: List<Symbol>,",
            "        args: List<Expression>,",
            "        mapperPairs: List<Pair<Type.TypeParam, Type>>,",
            "        candidates: MutableList<Candidate>,",
            "    ): Boolean? {",
        ],
        tail=["        return true", "    }", ""],
        call=[
            "            tispGatherCallbackCandidates(",
            "                tp = tp,",
            "                tps = tps,",
            "                tpsSet = tpsSet,",
            "                params = params,",
            "                args = args,",
            "                mapperPairs = mapperPairs,",
            "                candidates = candidates,",
            "            ) ?: return null",
        ],
    ),
    dict(
        name="tispCheckConstraint",
        first="                // Round 729: the round-725 rule's THIRD arm. An INFERRED candidate that is an",
        last="                }",       # closes `if (!ok) {`
        last_hint=116099,
        dedent=8,
        doc=[
            "    /**",
            "     * (JIT.1)(e) round 821 — the constraint leg of",
            "     * [tryInferSingleTypeParamFromArgs]: the inferred type (the widened first",
            "     * candidate) must satisfy `tp`'s constraint, and when it does not this is",
            "     * where B98.r118's TS2345 / B273's TS2322+TS6502 / B98.r128's",
            "     * self-referential-constraint TS2345 are emitted. Moved VERBATIM out of",
            "     * the `if (constraint != null) { … }` block.",
            "     *",
            "     * **`null` means the whole inference bails** — which, on this leg, is what",
            "     * every failing constraint does after emitting (or deliberately not",
            "     * emitting) its diagnostic.",
            "     */",
        ],
        sig=[
            "    private fun tispCheckConstraint(",
            "        tp: Type.TypeParam,",
            "        constraint: Type,",
            "        firstWidened: Type,",
            "        first: Candidate,",
            "        effectiveCandidates: List<Candidate>,",
            "        params: List<Symbol>,",
            "        args: List<Expression>,",
            "        source: String?,",
            "        fileName: String?,",
            "    ): Boolean? {",
        ],
        tail=["        return true", "    }", ""],
        call=[
            "                tispCheckConstraint(",
            "                    tp = tp,",
            "                    constraint = constraint,",
            "                    firstWidened = firstWidened,",
            "                    first = first,",
            "                    effectiveCandidates = effectiveCandidates,",
            "                    params = params,",
            "                    args = args,",
            "                    source = source,",
            "                    fileName = fileName,",
            "                ) ?: return null",
        ],
    ),
]


def head_lines():
    return subprocess.run(["git", "show", f"HEAD:{PATH}"],
                          capture_output=True, text=True, check=True).stdout.split("\n")


def locate(lines):
    """Resolve every anchor to a 0-based line index, uniquely, inside the fn."""
    fn = [i for i, l in enumerate(lines) if l == FN_HEAD]
    assert len(fn) == 1, fn
    fn_start = fn[0]
    # end of the function: first line that is exactly "    }" after fn_start
    fn_end = next(i for i in range(fn_start, len(lines)) if lines[i] == "    }")
    cand = [i for i in range(fn_start, fn_end) if lines[i] == CANDIDATE]
    assert len(cand) == 1, cand
    out = dict(fn_start=fn_start, fn_end=fn_end, candidate=cand[0])
    for h in HELPERS:
        f = [i for i in range(fn_start, fn_end) if lines[i] == h["first"]]
        assert len(f) == 1, (h["name"], f)
        # the last line is a bare brace, so it is resolved by the MEASURED hint
        # and then CHECKED, never searched for.
        b = h["last_hint"] - 1
        assert lines[b] == h["last"], (h["name"], b, repr(lines[b]))
        assert f[0] < b, (h["name"], f[0], b)
        h["a"], h["b"] = f[0], b
    # regions must be disjoint and in order
    spans = sorted((h["a"], h["b"]) for h in HELPERS)
    for (a1, b1), (a2, _) in zip(spans, spans[1:]):
        assert b1 < a2, (a1, b1, a2)
    return out


def body(h, lines):
    """The moved run, dedented."""
    out = []
    for l in lines[h["a"]:h["b"] + 1]:
        if l.strip():
            assert l.startswith(" " * h["dedent"]), repr(l)
            out.append(l[h["dedent"]:])
        else:
            out.append(l)
    return out


def apply(lines):
    loc = locate(lines)
    new = list(lines)
    # bottom-up so earlier indices stay valid
    for h in sorted(HELPERS, key=lambda x: -x["a"]):
        new[h["a"]:h["b"] + 1] = h["call"]
    # the local data class declaration goes away (hoisted)
    assert new[loc["candidate"]] == CANDIDATE
    del new[loc["candidate"]]
    # the hoisted data class and the three helpers land, in that order,
    # immediately after the entry function's closing brace.
    fn_end = next(i for i in range(loc["fn_start"], len(new)) if new[i] == "    }")
    assert new[fn_end + 1] == "", repr(new[fn_end + 1])
    block = list(CANDIDATE_HOIST)
    for h in HELPERS:
        block += h["doc"] + h["sig"] + body(h, lines) + h["tail"]
    new[fn_end + 2:fn_end + 2] = block
    return new


def main():
    lines = head_lines()
    new = apply(lines)
    if "--check" in sys.argv:
        cur = open(PATH, encoding="utf-8").read().split("\n")
        print("MATCHES WORKING TREE" if cur == new else "DIFFERS from working tree")
        return 0 if cur == new else 1
    open(PATH, "w", encoding="utf-8").write("\n".join(new))
    print(f"applied: {len(lines)} -> {len(new)} lines")
    return 0


if __name__ == "__main__":
    sys.exit(main())
