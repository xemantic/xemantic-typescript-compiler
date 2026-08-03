#!/usr/bin/env python3
"""(JIT.1)(d) round 813 — apply the split of `checkIndexSigInStatement`.

Mechanical: every moved region is a CONTIGUOUS run of the HEAD body, emitted
verbatim into its helper modulo a uniform dedent; the entry keeps the dispatch
head, the two index-signature lookups, the guards and the early `return`s, with
one call site per region. There is exactly ONE cross-boundary value — the
string index signature — and it is RETURNED, never stashed in a `Checker` field
(round 804's rule: a field would need round 791's save/restore to survive a
nested invocation, and this function recurses through `ModuleDeclaration`).

Run:  python3 scripts/indexsig_split_apply.py            # writes Checker.kt
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import sys

PATH = "src/commonMain/kotlin/Checker.kt"
FN_START, FN_END = 125554, 126096

# name -> (start, end, call-site line, KDoc, signature, extra trailing lines)
REGIONS = [
    ("R_NUMPROP", 125604, 125641,
     "            cisCheckNumericNamePropsVsNumberIndex(members, numberIndexType, source, fileName)",
     """    /**
     * (JIT.1)(d) round 813 — 17.191's numeric-name property loop, moved verbatim out of
     * [checkIndexSigInStatement]. Reached only when the type HAS a number index signature
     * whose value type is neither `any` nor the error type.
     */""",
     "    private fun cisCheckNumericNamePropsVsNumberIndex(\n"
     "        members: List<ClassElement>,\n"
     "        numberIndexType: Type,\n"
     "        source: String,\n"
     "        fileName: String,\n"
     "    ) {",
     None),
    ("R_STRSIG", 125645, 125676,
     "        val stringIndexSig = cisFindStringIndexSig(stmt, members)",
     """    /**
     * (JIT.1)(d) round 813 — the own-then-inherited `[s: string]: T` lookup, moved verbatim
     * out of [checkIndexSigInStatement]. THE ONE CROSS-BOUNDARY VALUE of that split: HEAD
     * held it in a `var` that this block's base-class walk mutated, so it is RETURNED here
     * rather than stashed in a field. B98.r19: an inherited string index signature is
     * checked against the derived type's own properties.
     */""",
     "    private fun cisFindStringIndexSig(stmt: Statement, members: List<ClassElement>): IndexSignature? {",
     ["        return stringIndexSig"]),
    ("R_ANON", 125686, 125742,
     "        cisCheckAnonIndexValueConflict(stmt, numberIndexSig, stringIndexSig, source, fileName)",
     """    /**
     * (JIT.1)(d) round 813 — B98.r20's TS2413 for ANONYMOUS object-literal index value
     * types (emitted at the type NAME), moved verbatim out of [checkIndexSigInStatement].
     */""",
     "    private fun cisCheckAnonIndexValueConflict(\n"
     "        stmt: Statement,\n"
     "        numberIndexSig: IndexSignature?,\n"
     "        stringIndexSig: IndexSignature?,\n"
     "        source: String,\n"
     "        fileName: String,\n"
     "    ) {",
     None),
    ("R_NAMED", 125759, 125884,
     "        cisCheckNamedInterfaceIndexValueConflict(stmt, source, fileName)",
     """    /**
     * (JIT.1)(d) round 813 — B98.r128b's TS2413 for NAMED-interface index value types
     * (emitted at the own index SIGNATURE), plus B272's primitive-value pair, moved
     * verbatim out of [checkIndexSigInStatement].
     */""",
     "    private fun cisCheckNamedInterfaceIndexValueConflict(stmt: Statement, source: String, fileName: String) {",
     None),
    ("R_NUMMETH", 125890, 125935,
     "        cisCheckNumericMethodsVsNumberIndex(stmt, members, source, fileName)",
     """    /**
     * (JIT.1)(d) round 813 — B272's TS2411 for zero-arg NUMERIC-named methods against a
     * primitive number index value type, moved verbatim out of [checkIndexSigInStatement]
     * (the `run { … }` wrapper is kept so the region is byte-identical to HEAD's).
     */""",
     "    private fun cisCheckNumericMethodsVsNumberIndex(\n"
     "        stmt: Statement,\n"
     "        members: List<ClassElement>,\n"
     "        source: String,\n"
     "        fileName: String,\n"
     "    ) {",
     None),
    ("R_PRIMMETH", 125948, 125998,
     "            cisCheckMethodsVsPrimitiveStringIndex(members, stringIndexType, source, fileName)",
     """    /**
     * (JIT.1)(d) round 813 — 16.4ez's TS2411 for METHODS against a PRIMITIVE string index
     * value type, moved verbatim out of [checkIndexSigInStatement]. The caller's
     * `stringIndexTypeIsPrimitive` guard stays in the entry, so this runs only for the
     * shape it owns; the same flag makes the general property loop skip methods.
     */""",
     "    private fun cisCheckMethodsVsPrimitiveStringIndex(\n"
     "        members: List<ClassElement>,\n"
     "        stringIndexType: Type,\n"
     "        source: String,\n"
     "        fileName: String,\n"
     "    ) {",
     None),
    ("R_PROPLOOP", 126001, 126095,
     "        cisCheckPropsVsStringIndex(members, stringIndexType, stringIndexTypeIsPrimitive, source, fileName)",
     """    /**
     * (JIT.1)(d) round 813 — the general TS2411 loop (every named property, and every
     * method when the string index value type is CALLABLE) against the string index type,
     * moved verbatim out of [checkIndexSigInStatement]. `stringIndexTypeIsPrimitive` is
     * passed because it is what makes this loop DEFER methods to
     * [cisCheckMethodsVsPrimitiveStringIndex] instead of double-reporting them.
     */""",
     "    private fun cisCheckPropsVsStringIndex(\n"
     "        members: List<ClassElement>,\n"
     "        stringIndexType: Type,\n"
     "        stringIndexTypeIsPrimitive: Boolean,\n"
     "        source: String,\n"
     "        fileName: String,\n"
     "    ) {",
     None),
]


def main():
    lines = open(PATH).read().split("\n")

    def body(a, b):
        return lines[a - 1:b]

    # --- the new entry -----------------------------------------------------
    entry = []
    i = FN_START
    starts = {a: r for r in REGIONS for a in (r[1],)}
    while i <= FN_END:
        if i in starts:
            r = starts[i]
            entry.append(r[3])
            i = r[2] + 1
            continue
        entry.append(lines[i - 1])
        i += 1

    # --- the helpers -------------------------------------------------------
    helpers = []
    for name, a, b, _call, doc, sig, extra in REGIONS:
        reg = body(a, b)
        first = reg[0]
        dedent = (len(first) - len(first.lstrip(" "))) - 8
        assert dedent >= 0, (name, dedent)
        out = []
        for l in reg:
            if not l.strip():
                out.append("")
            else:
                assert l[:dedent] == " " * dedent, (name, l)
                out.append(l[dedent:])
        helpers.append("")
        helpers.append(doc)
        helpers.append(sig)
        helpers.extend(out)
        if extra:
            helpers.extend(extra)
        helpers.append("    }")

    new = lines[:FN_START - 1] + entry + helpers + lines[FN_END:]
    open(PATH, "w").write("\n".join(new))
    print(f"entry {len(entry)} lines, helpers {len(helpers)} lines, "
          f"file {len(lines)} -> {len(new)} lines")


if __name__ == "__main__":
    sys.exit(main())
