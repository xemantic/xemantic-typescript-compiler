#!/usr/bin/env python3
"""(JIT.1)(d) round 814 — apply the split of the `Checker` CONSTRUCTOR.

Mechanical: every moved region is a CONTIGUOUS run of the HEAD `init` body,
emitted verbatim into its helper modulo a uniform dedent (0 for nine of the
ten). The entry keeps the SKELETON and nothing else: the `try`/`catch`
boundary, `PassTiming.noteInitStart`/`noteInitEnd`, the `PassTiming.enabled`
hook, the two `declarationOnly` branches — and one call site per region.

There are NO cross-boundary values: the `init` body declares exactly two locals
(`preAugmentationGlobalsKeys`, `shouldCheckDefiniteAssignment`) and each region
boundary was chosen so that both a declaration and every read of it fall inside
ONE region.

Run:  python3 scripts/init_split_apply.py            # writes Checker.kt
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import sys
import textwrap

PATH = "src/commonMain/kotlin/Checker.kt"
FN_START, FN_END = 5872, 7965

DOC = """    /**
{what}
     *
     * Moved VERBATIM out of the `Checker` constructor, which at **11,298
     * bytecodes** was over HotSpot's 8,000-byte `HugeMethodLimit` and therefore
     * never JIT-compiled. The body it came from is an ORDERED SEQUENCE of
     * `pass("name") {{ … }}` dispatches with no loops, no `return`/`break`/
     * `continue` and (across the whole `init`) two locals — so the ONLY thing a
     * split here can get wrong is the ORDER, and the only cut criterion is size.
     * Called from `init` in this position; do not reorder.
     */"""

# name -> (start, end, helper name, what-it-is)
REGIONS = [
    ("R_SETUP", 5882, 6014, "initSetupPasses",
     "the (SETUP.1) prologue — `checkLibOption` through `init:buildFileLocalTypeMaps`, "
     "the 14 setup passes round 802 wrapped to make `outside-pass` exhaustive by "
     "construction. Holds the `init` body's own `preAugmentationGlobalsKeys` local, "
     "whose only two readers are inside this run."),
    ("R_DECLONLY", 6022, 6050, "initDeclarationOnlyPasses",
     "the body of `if (declarationOnly)` — the `init:declarationOnlyDispatch` pass. "
     "The GUARD stays in the constructor; only its body moved."),
    ("R1", 6054, 6625, "initCheckPasses1",
     "checking passes, run 1 of 8: `checkUnusedDeclarations` .. `checkJSDocTypedefTags` "
     "(24 dispatches, and most of the run's bytecode is the inline "
     "`init:evolvingArrayUseSiteWalks` / definite-assignment / `checkSpine` block). "
     "Holds `shouldCheckDefiniteAssignment`, the `init` body's other local — declared "
     "and read inside this run, which is why the boundary sits where it does."),
    ("R2", 6626, 6843, "initCheckPasses2",
     "checking passes, run 2 of 8: `checkJsDocTypedefIndexSignature` .. "
     "`checkDefaultImports` (61 dispatches)."),
    ("R3", 6844, 7074, "initCheckPasses3",
     "checking passes, run 3 of 8: `checkNamespaceImportSyntheticDefaultCall` .. "
     "`checkCrossNamespaceClassHeritageUBD` (55 dispatches)."),
    ("R4", 7075, 7315, "initCheckPasses4",
     "checking passes, run 4 of 8: `checkUninitializedLetCapturedReads` .. "
     "`checkDestructuringDefaultTypeMismatches` (63 dispatches)."),
    ("R5", 7316, 7544, "initCheckPasses5",
     "checking passes, run 5 of 8: `checkOptionalParamNullishArithmetic` .. "
     "`checkCircularClassBaseViaDefaultTypeArg` (67 dispatches)."),
    ("R6", 7545, 7725, "initCheckPasses6",
     "checking passes, run 6 of 8: `checkCircularBaseTypeReferences` .. "
     "`checkCallTypeArgCount` (61 dispatches)."),
    ("R7", 7726, 7872, "initCheckPasses7",
     "checking passes, run 7 of 8: `checkReverseMappedExcessProps` .. "
     "`checkConditionalTypeAssignabilityDeferred` (41 dispatches)."),
    ("R8", 7873, 7952, "initCheckPasses8",
     "checking passes, run 8 of 8: `checkBuiltinIterator` .. `init:tpTargetReturnDedup` "
     "(49 dispatches). ENDS with the two RETRACTION passes "
     "(`init:flowDisabledTs2454Retraction`, `init:tpTargetReturnDedup`), which is why "
     "this run must stay last: they remove diagnostics earlier runs added."),
]

# the indentation of each call site in the entry
CALL_INDENT = {"R_DECLONLY": 12}


def call_site(name, fn):
    return " " * CALL_INDENT.get(name, 8) + fn + "()"


def main():
    lines = open(PATH).read().split("\n")

    entry = []
    i = FN_START
    starts = {a: r for r in REGIONS for a in (r[1],)}
    while i <= FN_END:
        if i in starts:
            name, a, b, fn, _what = starts[i]
            entry.append(call_site(name, fn))
            i = b + 1
            continue
        entry.append(lines[i - 1])
        i += 1

    helpers = []
    for name, a, b, fn, what in REGIONS:
        reg = lines[a - 1:b]
        first = next(l for l in reg if l.strip())
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
        wrapped = textwrap.fill("(JIT.1)(d) round 814 — " + what, width=79,
                                initial_indent="     * ",
                                subsequent_indent="     * ")
        helpers.append(DOC.format(what=wrapped))
        helpers.append(f"    private fun {fn}() {{")
        helpers.extend(out)
        helpers.append("    }")

    new = lines[:FN_START - 1] + entry + helpers + lines[FN_END:]
    open(PATH, "w").write("\n".join(new))
    print(f"entry {len(entry)} lines, helpers {len(helpers)} lines, "
          f"file {len(lines)} -> {len(new)} lines")


if __name__ == "__main__":
    sys.exit(main())
