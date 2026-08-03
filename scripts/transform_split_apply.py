#!/usr/bin/env python3
"""(JIT.1)(e) round 817 — split `Transformer.transform` (8,934 bytecodes, over
HotSpot's 8,000-byte `HugeMethodLimit`, so never JIT-compiled) into an entry plus
seven `tf*` helpers.

The new file is built as a PURE FUNCTION of HEAD, so `transform_split_verify.py`
can reconstruct it byte for byte and the working tree cannot have drifted.

Region sizes were MEASURED before the edit with `scripts/method_bytes_by_line.py`
(round 816's instrument), not estimated:

    470-525     391  the per-file state reset            KEPT
    526-620   1,272  the top-level name pre-pass         -> tfCollectTopLevelNames
    621-700     857  collision map .. transformStatements KEPT
    701-740   1,050  the helper-statement list           -> tfCollectHelperStatements
    751-785     550  leading-comment lifting             -> tfLiftLeadingComments
    790-806     259  deep-metadata temp hoist            KEPT
    816-852     462  the CommonJS branch                 KEPT  (holds a `return`)
    874-948   1,071  the ESM tslib import                -> tfInjectTslibImport
    956-976     283  internal import-alias elision       -> tfElideInternalImportAliases
    983-1006    275  the noLib metadata wrap             -> tfWrapNoLibMetadataArgs
    1013-1065   389  the createRequire header            -> tfInjectCreateRequireHeader

WHAT STAYS AND WHY. The two whole-function `return`s live in the CommonJS and
module:preserve branches (816-873); leaving those in the entry is what buys the
round-813 property — **no helper needs a return signal at all**, so no helper can
fail to propagate one. The rest of what stays is the per-file FIELD reset and the
plumbing between stages, which is where the `sourceFile` -> `transformed` data
dependency lives.

EVERY REGION MOVES AT DEDENT 0 (a region is at indent 8 inside `fun transform`,
and a helper's body is at indent 8 too), and every value-producing region moves
WITH ITS OWN `val` DECLARATION plus one added `return <name>` line — so not one
character of the moved text is edited.

Run:  python3 scripts/transform_split_apply.py [--check]
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import subprocess
import sys

PATH = "src/commonMain/kotlin/Transformer.kt"
FN_START, FN_END = 470, 1068

# name, first, last, dedent, KDoc, signature, the line returning the region's value
HELPERS = [
    (
        "tfCollectTopLevelNames", 526, 620, 0,
        """    /**
     * Pre-pass over the file's top-level statements: fills [topLevelTypeOnlyNames]
     * with the names that have no runtime counterpart (interfaces, type aliases,
     * type-only namespaces/imports, const-enum aliases whose values are inlined)
     * and [topLevelRuntimeNames] — the CALLER's set, mutated in place — with the
     * ones that do.
     *
     * The two sets are not independent: the caller subtracts the second from the
     * first immediately afterwards, so a name that is both a type and a value
     * (`interface X {}` beside `const X = 1`) must survive as a runtime name or
     * `export { X }` is wrongly erased. That is why the set is a parameter rather
     * than a return: it is the same instance the subtraction reads.
     */""",
        """    private fun tfCollectTopLevelNames(
        sourceFile: SourceFile,
        topLevelRuntimeNames: MutableSet<String>,
    ) {""",
        None,
    ),
    (
        "tfCollectHelperStatements", 701, 740, 0,
        """    /**
     * Appends the inline `__awaiter`/`__decorate`/... helper bodies to [helpers],
     * in tsc's first-usage order with its two priority fix-ups
     * (`__setFunctionName` after `__awaiter`, `__asyncValues` after `__awaiter`).
     *
     * Does nothing when [skipHelpers] — `noEmitHelpers`, or `importHelpers` on a
     * module file, where the helpers come from tslib instead.
     */""",
        """    private fun tfCollectHelperStatements(
        skipHelpers: Boolean,
        helpers: MutableList<RawStatement>,
    ) {""",
        None,
    ),
    (
        "tfLiftLeadingComments", 751, 785, 0,
        """    /**
     * Places [helpers] in front of [transformed], lifting the first statement's
     * DETACHED leading comments above them (tsc emits comment -> helpers ->
     * first statement).
     */""",
        """    private fun tfLiftLeadingComments(
        sourceFile: SourceFile,
        transformed: List<Statement>,
        helpers: List<RawStatement>,
    ): List<Statement> {""",
        "        return helpersAndTransformed",
    ),
    (
        "tfInjectTslibImport", 874, 948, 0,
        """    /**
     * For an ESM module with `importHelpers: true`, prepends
     * `import { __helperA, ... } from "tslib"` to [withHelpers] — sorted by helper
     * name, aliased where a local declaration shadows one, and placed after any
     * leading private-field `var _A_x;` hoists.
     */""",
        """    private fun tfInjectTslibImport(
        fileName: String,
        isCjsFileName: Boolean,
        withHelpers: List<Statement>,
    ): List<Statement> {""",
        "        return withTslib",
    ),
    (
        "tfElideInternalImportAliases", 956, 976, 0,
        """    /**
     * Erases `var x = M.N` emitted for an `import x = M.N` whose alias is
     * explicitly type-only or resolves to a const enum (its values are inlined at
     * the use sites, so the runtime alias is dead).
     */""",
        """    private fun tfElideInternalImportAliases(
        sourceFile: SourceFile,
        elided: List<Statement>,
    ): List<Statement> {""",
        "        return finalStatements",
    ),
    (
        "tfWrapNoLibMetadataArgs", 983, 1006, 0,
        """    /**
     * Under `noLib` + `isolatedModules`, wraps `__metadata("design:type", X)`
     * arguments naming a possibly-absent runtime global (Map, Set, ...) in tsc's
     * safety-check pattern, hoisting the `var _a;` it needs to the front.
     */""",
        """    private fun tfWrapNoLibMetadataArgs(
        finalStatements: List<Statement>,
    ): List<Statement> {""",
        "        return noLibWrapped",
    ),
    (
        "tfInjectCreateRequireHeader", 1013, 1065, 0,
        """    /**
     * Prepends the `createRequire` header a node/nodenext ESM file needs once an
     * `import X = require("mod")` has been rewritten to `const X = __require(...)`.
     */""",
        """    private fun tfInjectCreateRequireHeader(
        noLibWrapped: List<Statement>,
    ): List<Statement> {""",
        "        return withCreateRequire",
    ),
]

# region name -> the call site that replaces it. Every argument is passed BY NAME
# (round 816's rule): a positional call whose arguments could permute and still
# type-check is a mistake no compiler can catch, and `List` is covariant here.
CALLS = {
    "tfCollectTopLevelNames": [
        "        tfCollectTopLevelNames(",
        "            sourceFile = sourceFile,",
        "            topLevelRuntimeNames = topLevelRuntimeNames,",
        "        )",
    ],
    "tfCollectHelperStatements": [
        "        tfCollectHelperStatements(",
        "            skipHelpers = skipHelpers,",
        "            helpers = helpers,",
        "        )",
    ],
    "tfLiftLeadingComments": [
        "        val helpersAndTransformed = tfLiftLeadingComments(",
        "            sourceFile = sourceFile,",
        "            transformed = transformed,",
        "            helpers = helpers,",
        "        )",
    ],
    "tfInjectTslibImport": [
        "        val withTslib = tfInjectTslibImport(",
        "            fileName = fileName,",
        "            isCjsFileName = isCjsFileName,",
        "            withHelpers = withHelpers,",
        "        )",
    ],
    "tfElideInternalImportAliases": [
        "        val finalStatements = tfElideInternalImportAliases(",
        "            sourceFile = sourceFile,",
        "            elided = elided,",
        "        )",
    ],
    "tfWrapNoLibMetadataArgs": [
        "        val noLibWrapped = tfWrapNoLibMetadataArgs(",
        "            finalStatements = finalStatements,",
        "        )",
    ],
    "tfInjectCreateRequireHeader": [
        "        val withCreateRequire = tfInjectCreateRequireHeader(",
        "            noLibWrapped = noLibWrapped,",
        "        )",
    ],
}


def dedent(lines, n):
    return [l[n:] if l.strip() else l for l in lines]


def build(head):
    """The new file, as a pure function of HEAD's text."""
    hl = head.split("\n")
    entry, ln = [], FN_START
    for name, a, b, _ded, _kdoc, _sig, _ret in HELPERS:
        entry += hl[ln - 1:a - 1]
        entry += CALLS[name]
        ln = b + 1
    entry += hl[ln - 1:FN_END]

    helpers = []
    for name, a, b, ded, kdoc, sig, ret in HELPERS:
        helpers.append("")
        helpers += kdoc.split("\n")
        helpers += sig.split("\n")
        helpers += dedent(hl[a - 1:b], ded)
        if ret is not None:
            helpers.append(ret)
        helpers.append("    }")

    return "\n".join(hl[:FN_START - 1] + entry + helpers + hl[FN_END:])


def main():
    head = subprocess.run(["git", "show", f"HEAD:{PATH}"],
                          capture_output=True, text=True, check=True).stdout
    hl = head.split("\n")
    # POSITIVE CONTROLS on the line numbers, so a rebased HEAD cannot silently
    # move the regions under the script.
    assert hl[FN_START - 1].startswith("    fun transform(sourceFile"), hl[FN_START - 1]
    assert hl[FN_END - 1] == "    }", repr(hl[FN_END - 1])
    for name, a, b, *_ in HELPERS:
        assert hl[a - 1].startswith("        "), (name, hl[a - 1])
        assert hl[b - 1].startswith("        "), (name, hl[b - 1])
    out = build(head)
    if "--check" in sys.argv:
        same = open(PATH).read() == out
        print("reconstruction:", "IDENTICAL" if same else "DIFFERS")
        return 0 if same else 1
    open(PATH, "w").write(out)
    print(f"wrote {PATH}: {len(out)} chars, {len(HELPERS)} helpers")
    return 0


if __name__ == "__main__":
    sys.exit(main())
