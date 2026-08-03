#!/usr/bin/env python3
"""(JIT.1)(e) round 819 — split `Transformer.transformToCommonJS` (28,991
bytecodes, 3.6x HotSpot's 8,000-byte `HugeMethodLimit`, so never JIT-compiled —
the LARGEST method in the compiler) into an entry plus nineteen `tcjs*` helpers.

The new file is built as a PURE FUNCTION of HEAD, so `tcjs_split_verify.py` can
reconstruct it byte for byte and the working tree cannot have drifted.

Region sizes were MEASURED before the edit with `scripts/method_bytes_by_line.py`
(round 816's instrument), not estimated — see the table in
`docs/perf/setup-phase-and-huge-methods.md` § 24.

THE ONE SHAPE THIS TARGET HAS THAT NO EARLIER ONE IN THE ARC DID: a moved region
that CONTINUES THE CALLER'S LOOP. `transformToCommonJS`'s bulk is a
`for (stmt in statementsToProcess) { when (stmt) { … } }`, and two of the seven
`when` arms hold `continue`s that target that loop — one in the
`VariableStatement` arm, five in the `ImportDeclaration` arm (the function holds
27 `continue`s and no `break`; the other 21 all belong to loops NESTED inside
their own region and move with it). A `continue` cannot survive extraction into a member
function, and rewriting it to `return` would be an edit to the moved text at
six deeply-nested sites.

So those two helpers wrap the moved region in a ONE-ITERATION FRAME:

    for (stmt in listOf(stmtIn)) { <the arm, verbatim> }

A single-element loop makes `continue` mean exactly what it meant before —
abandon the rest of THIS statement's processing — so the region moves verbatim
and the control-flow token census is unchanged on both sides. The frame's loop
variable is `stmt`, which is also what the arm's smart-cast subject was called,
so no reference inside the region is rewritten either.

Run:  python3 scripts/tcjs_split_apply.py [--check]
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import subprocess
import sys

PATH = "src/commonMain/kotlin/Transformer.kt"
FN_START, FN_END = 1390, 3562

ADDED_DECLS = '''    /**
     * What [tcjsDetectModuleShape] decides about a file before any statement is
     * transformed: whether it uses `export = X` (which suppresses the
     * `__esModule` preamble) and whether it carries static module syntax at all.
     */
    private data class CjsModuleShape(
        val hasExportEquals: Boolean,
        val hasStaticModuleDeclarations: Boolean,
    )

    /**
     * The name pre-scan of [tcjsCollectDeclaredNames]: which top-level names are
     * declared as functions only (they use the hoisted export-stub path), which
     * are runtime declarations of any kind, and which are PURE types (erased, so
     * an export of one disappears).
     */
    private data class CjsDeclaredNames(
        val functionOnlyNames: MutableSet<String>,
        val runtimeDeclaredNames: MutableSet<String>,
        val pureTypeNames: Set<String>,
    )

    /**
     * The reference/namespace pre-scan of [tcjsCollectNamespaceExports]: the
     * names referenced in value positions (used to elide unused imports), the
     * roots reached through `import X = a.b`, the exported namespace/enum names
     * whose IIFE arguments must be rewritten, and their export aliases.
     */
    private data class CjsNamespaceExports(
        val valueReferencedNames: Set<String>,
        val importEqualsReferencedNames: Set<String>,
        val exportedNsEnumNames: MutableSet<String>,
        val iifeExportAliases: MutableMap<String, MutableList<String>>,
    )

    /**
     * The `export { X as Y }` pre-scan of [tcjsCollectExportClauses]: local name
     * to export names for locally-declared runtime names, and the clause ALIASES
     * of direct-exported vars.
     */
    private data class CjsExportClauses(
        val namedExportLocalToExport: MutableMap<String, MutableList<String>>,
        val directExportClauseAliases: MutableMap<String, MutableList<String>>,
    )

    /**
     * What [tcjsSplitPrologueDirectives] separates off the front of the
     * statement list: the prologue directives (re-inserted at the very top later)
     * and the leading injected helper statements, leaving the statements the
     * main loop actually transforms.
     */
    private data class CjsPrologueSplit(
        val hasUseStrictPrologue: Boolean,
        val useStrictSingleQuote: Boolean,
        val otherPrologueDirectives: MutableList<ExpressionStatement>,
        val leadingHelpers: List<Statement>,
        val statementsToProcess: List<Statement>,
    )

    /**
     * The two import-helper flags a region may flip. They travel together
     * because a helper cannot write back to the caller's `var`.
     */
    private data class CjsImportHelperFlags(
        val needsImportStar: Boolean,
        val needsImportDefault: Boolean,
    )

    /**
     * The four helper flags the `export … from` arm may flip —
     * `importStarUsedFirst` records which of `__importStar` / `__exportStar` was
     * reached first, which decides the order the two helpers are emitted in.
     */
    private data class CjsExportHelperFlags(
        val needsImportStar: Boolean,
        val needsImportDefault: Boolean,
        val needsExportStar: Boolean,
        val importStarUsedFirst: Boolean,
    )

    /**
     * The `import x = M.N` alias names collected by
     * [tcjsCollectInternalAliasNames]: all of them, and the subset that is
     * unreferenced and therefore elidable.
     */
    private data class CjsInternalAliases(
        val unusedInternalAliasNames: Set<String>,
        val internalAliasNames: Set<String>,
    )
'''

# ---------------------------------------------------------------------------
# The nineteen regions. Each is a dict:
#   name, a, b        HEAD line span of the moved region (inclusive)
#   ind               the region's own source indent (8 body-level, 20 in a
#                     `when` arm) — asserted, so a rebased HEAD cannot slip
#   ded               characters removed from each moved line
#   kdoc              the helper's KDoc
#   params            [(parameter, type, argument-expression-at-the-call-site)]
#   pre               prologue lines (before the moved text)
#   post              lines after the moved text and before `return` (the frame
#                     close, for the two framed arms)
#   ret               None, or (return type, [holder field names]) for a data
#                     class, or (type, "expr") for a plain value
#   var               the name the call site binds the result to
#   unpack            call-site lines after the call
#   call_ind          indent of the call site
# ---------------------------------------------------------------------------

RESULT = ("result", "MutableList<Statement>", "result")
OSF = ("originalSourceFile", "SourceFile", "originalSourceFile")

HELPERS = [
    dict(
        name="tcjsDetectModuleShape", a=1396, b=1465, ind=8, ded=0,
        kdoc="""    /**
     * Decides the file's module shape and emits the `__esModule` preamble.
     *
     * `export = X` files use `module.exports = X` and get NO preamble — unless
     * the exported name is a pure type, in which case the export erases and the
     * file is an ordinary module again. A file with no static module syntax at
     * all (only a dynamic `import()`) gets no preamble either.
     */""",
        params=[OSF, RESULT],
        ret=("CjsModuleShape", ["hasExportEquals", "hasStaticModuleDeclarations"]),
        var="moduleShape",
        unpack=[
            "        val hasExportEquals = moduleShape.hasExportEquals",
            "        val hasStaticModuleDeclarations = moduleShape.hasStaticModuleDeclarations",
        ],
    ),
    dict(
        name="tcjsCollectDeclaredNames", a=1533, b=1689, ind=8, ded=0,
        kdoc="""    /**
     * Pre-scans the ORIGINAL source for the top-level name classes the CommonJS
     * transform keys on: JS-hoisted function declarations (whose re-exports use
     * a stub placed before the body), every runtime declaration, and the names
     * declared ONLY as a type alias / interface, whose exports tsc erases.
     */""",
        params=[OSF, ("namedImportLocalNames", "MutableSet<String>", "namedImportLocalNames")],
        ret=("CjsDeclaredNames", ["functionOnlyNames", "runtimeDeclaredNames", "pureTypeNames"]),
        var="declaredNames",
        unpack=[
            "        val functionOnlyNames = declaredNames.functionOnlyNames",
            "        val runtimeDeclaredNames = declaredNames.runtimeDeclaredNames",
            "        val pureTypeNames = declaredNames.pureTypeNames",
        ],
    ),
    dict(
        name="tcjsCollectNamespaceExports", a=1691, b=1761, ind=8, ded=0,
        kdoc="""    /**
     * Pre-scans the ORIGINAL source for value-position references (an import
     * bound name that appears in none of them is elided) and for the exported
     * namespace/enum names, whose transformed IIFE arguments have to be
     * rewritten to `exports.X` even though the `export` modifier is long gone.
     */""",
        params=[OSF],
        ret=("CjsNamespaceExports", ["valueReferencedNames", "importEqualsReferencedNames",
                                     "exportedNsEnumNames", "iifeExportAliases"]),
        var="namespaceExports",
        unpack=[
            "        val valueReferencedNames = namespaceExports.valueReferencedNames",
            "        val importEqualsReferencedNames = namespaceExports.importEqualsReferencedNames",
            "        val exportedNsEnumNames = namespaceExports.exportedNsEnumNames",
            "        val iifeExportAliases = namespaceExports.iifeExportAliases",
        ],
    ),
    dict(
        name="tcjsCollectExportClauses", a=1763, b=1835, ind=8, ded=0,
        kdoc="""    /**
     * Pre-scans `export { X as Y }` clauses, which may appear AFTER the
     * declaration they export. Without this the `__decorate` assignment for a
     * decorated class is emitted before the export is known and loses its
     * `exports.Y =` prefix.
     */""",
        params=[OSF,
                ("directExportedVarNames", "MutableSet<String>", "directExportedVarNames"),
                ("pureTypeNames", "Set<String>", "pureTypeNames"),
                ("functionOnlyNames", "MutableSet<String>", "functionOnlyNames"),
                ("runtimeDeclaredNames", "MutableSet<String>", "runtimeDeclaredNames")],
        ret=("CjsExportClauses", ["namedExportLocalToExport", "directExportClauseAliases"]),
        var="exportClauses",
        unpack=[
            "        val namedExportLocalToExport = exportClauses.namedExportLocalToExport",
            "        val directExportClauseAliases = exportClauses.directExportClauseAliases",
        ],
    ),
    dict(
        name="tcjsSplitPrologueDirectives", a=1837, b=1869, ind=8, ded=0,
        kdoc="""    /**
     * Strips the prologue directives off the front of the statement list —
     * `\"use strict\"` is re-inserted at the very top after the helpers, the rest
     * go between it and the preamble. Injected `RawStatement` helpers do NOT end
     * the prologue zone; they are separated off as [CjsPrologueSplit.leadingHelpers].
     */""",
        params=[("statements", "List<Statement>", "statements")],
        ret=("CjsPrologueSplit", ["hasUseStrictPrologue", "useStrictSingleQuote",
                                  "otherPrologueDirectives", "leadingHelpers",
                                  "statementsToProcess"]),
        var="prologueSplit",
        unpack=[
            "        val hasUseStrictPrologue = prologueSplit.hasUseStrictPrologue",
            "        val useStrictSingleQuote = prologueSplit.useStrictSingleQuote",
            "        val otherPrologueDirectives = prologueSplit.otherPrologueDirectives",
            "        val leadingHelpers = prologueSplit.leadingHelpers",
            "        val statementsToProcess = prologueSplit.statementsToProcess",
        ],
    ),
    # ---- the seven `when` arms of the main loop --------------------------
    dict(
        name="tcjsTransformVariableStatement", a=1876, b=2249, ind=20, ded=8,
        kdoc="""    /**
     * The `VariableStatement` arm of the main loop: exported / non-exported
     * consts, the direct-export path (`exports.x = v`, no local binding), the
     * keep-declaration path for function/arrow/class initializers, and
     * destructuring — including the empty-pattern side-effect form.
     *
     * ONE-ITERATION FRAME: the arm holds a `continue` targeting the caller's
     * loop, so the moved text runs inside `for (stmt in listOf(stmtIn))` and
     * keeps that `continue` verbatim (see this file's module doc).
     */""",
        params=[("stmtIn", "VariableStatement", "stmt"), RESULT,
                ("renameMap", "MutableMap<String, Expression>", "renameMap"),
                ("exportedVarNames", "MutableList<String>", "exportedVarNames"),
                ("directExportedVarNames", "MutableSet<String>", "directExportedVarNames"),
                ("conflictingExportedNames", "MutableSet<String>", "conflictingExportedNames"),
                ("declarationStmtForName", "MutableMap<String, Statement>", "declarationStmtForName"),
                ("importStmtForLocalName", "MutableMap<String, Statement>", "importStmtForLocalName"),
                ("keepDeclExportAssignments", "MutableSet<Statement>", "keepDeclExportAssignments"),
                ("keepDeclFunctionVarNames", "MutableSet<String>", "keepDeclFunctionVarNames"),
                ("sideEffectTempVars", "MutableList<String>", "sideEffectTempVars")],
        pre=["        for (stmt in listOf(stmtIn)) {"],
        post=["        }"],
        call_ind=20,
    ),
    dict(
        name="tcjsTransformFunctionDeclaration", a=2253, b=2291, ind=20, ded=12,
        kdoc="""    /**
     * The `FunctionDeclaration` arm: a function declaration is JS-hoisted, so an
     * export of one becomes a stub (`exports.f = f`) emitted before the body
     * rather than an assignment after it.
     */""",
        params=[("stmt", "FunctionDeclaration", "stmt"), RESULT,
                ("hasExportEquals", "Boolean", "hasExportEquals"),
                ("renameMap", "MutableMap<String, Expression>", "renameMap"),
                ("functionExportStubs", "MutableList<Statement>", "functionExportStubs")],
        call_ind=20,
    ),
    dict(
        name="tcjsTransformClassDeclaration", a=2295, b=2325, ind=20, ded=12,
        kdoc="""    /**
     * The `ClassDeclaration` arm: a class is NOT hoisted, so an export becomes a
     * void0 hoist plus an assignment after the class body. `export default class`
     * additionally records the name, so its static initializers can be reordered
     * ahead of `exports.default = X`.
     */""",
        params=[("stmt", "ClassDeclaration", "stmt"), RESULT,
                ("hasExportEquals", "Boolean", "hasExportEquals"),
                ("exportedVarNames", "MutableList<String>", "exportedVarNames"),
                ("declarationStmtForName", "MutableMap<String, Statement>", "declarationStmtForName"),
                ("defaultExportedClassNames", "MutableSet<String>", "defaultExportedClassNames")],
        call_ind=20,
    ),
    dict(
        name="tcjsTransformExportAssignment", a=2329, b=2375, ind=20, ded=12,
        kdoc="""    /**
     * The `ExportAssignment` arm — `export = X` becomes `module.exports = X`,
     * `export default X` becomes `exports.default = X`, and both erase entirely
     * when the exported name is a pure type.
     */""",
        params=[("stmt", "ExportAssignment", "stmt"), RESULT,
                ("pureTypeNames", "Set<String>", "pureTypeNames"),
                ("exportedVarNames", "MutableList<String>", "exportedVarNames"),
                ("directExportedVarNames", "MutableSet<String>", "directExportedVarNames"),
                ("deferredExportAssignments", "MutableList<Statement>", "deferredExportAssignments")],
        call_ind=20,
    ),
    dict(
        name="tcjsTransformImportDeclaration", a=2379, b=2631, ind=20, ded=8,
        kdoc="""    /**
     * The `ImportDeclaration` arm: every ES import form lowered to a `require`
     * const plus a rename of its bound names, with the `esModuleInterop` helper
     * selection (`__importStar` / `__importDefault`) and the elision of imports
     * whose bindings are referenced in no value position.
     *
     * ONE-ITERATION FRAME: the arm holds four `continue`s targeting the caller's
     * loop (see this file's module doc). The two helper flags are threaded
     * in-and-out because a helper cannot write back to the caller's `var`.
     */""",
        params=[("stmtIn", "ImportDeclaration", "stmt"),
                ("needsImportStarIn", "Boolean", "needsImportStar"),
                ("needsImportDefaultIn", "Boolean", "needsImportDefault"),
                OSF, RESULT,
                ("renameMap", "MutableMap<String, Expression>", "renameMap"),
                ("moduleNameCounter", "MutableMap<String, Int>", "moduleNameCounter"),
                ("defaultModuleTempVars", "MutableSet<String>", "defaultModuleTempVars"),
                ("importStmtForLocalName", "MutableMap<String, Statement>", "importStmtForLocalName"),
                ("regularImportRequires", "MutableSet<VariableStatement>", "regularImportRequires"),
                ("namespaceImportRequires", "MutableSet<VariableStatement>", "namespaceImportRequires"),
                ("valueReferencedNames", "Set<String>", "valueReferencedNames"),
                ("importEqualsReferencedNames", "Set<String>", "importEqualsReferencedNames")],
        pre=["        var needsImportStar = needsImportStarIn",
             "        var needsImportDefault = needsImportDefaultIn",
             "        for (stmt in listOf(stmtIn)) {"],
        post=["        }"],
        ret=("CjsImportHelperFlags", ["needsImportStar", "needsImportDefault"]),
        var="importFlags",
        unpack=[
            "                    needsImportStar = importFlags.needsImportStar",
            "                    needsImportDefault = importFlags.needsImportDefault",
        ],
        call_ind=20,
    ),
    dict(
        name="tcjsTransformExportDeclaration", a=2635, b=2837, ind=20, ded=12,
        kdoc="""    /**
     * The `ExportDeclaration` arm — `export * from`, `export * as ns from`,
     * `export { a, b } from` and the module-less `export { a as b }`, each with
     * its own re-export form (`__exportStar`, `Object.defineProperty` getters or
     * a plain `exports.x =`).
     *
     * All four helper flags are threaded in-and-out: this arm is the only place
     * that decides `importStarUsedFirst`, i.e. which of `__importStar` and
     * `__exportStar` is emitted first.
     */""",
        params=[("stmt", "ExportDeclaration", "stmt"),
                ("needsImportStarIn", "Boolean", "needsImportStar"),
                ("needsImportDefaultIn", "Boolean", "needsImportDefault"),
                ("needsExportStarIn", "Boolean", "needsExportStar"),
                ("importStarUsedFirstIn", "Boolean", "importStarUsedFirst"),
                OSF, RESULT,
                ("renameMap", "MutableMap<String, Expression>", "renameMap"),
                ("moduleNameCounter", "MutableMap<String, Int>", "moduleNameCounter"),
                ("exportedVarNames", "MutableList<String>", "exportedVarNames"),
                ("directExportedVarNames", "MutableSet<String>", "directExportedVarNames"),
                ("exportedNsEnumNames", "MutableSet<String>", "exportedNsEnumNames"),
                ("functionOnlyNames", "MutableSet<String>", "functionOnlyNames"),
                ("functionStubExportedNames", "MutableSet<String>", "functionStubExportedNames"),
                ("functionExportStubs", "MutableList<Statement>", "functionExportStubs"),
                ("namedImportLocalNames", "MutableSet<String>", "namedImportLocalNames"),
                ("importStmtForLocalName", "MutableMap<String, Statement>", "importStmtForLocalName"),
                ("declarationStmtForName", "MutableMap<String, Statement>", "declarationStmtForName"),
                ("exportAssignmentsAfterImport", "MutableMap<Statement, MutableList<Statement>>",
                 "exportAssignmentsAfterImport"),
                ("pureTypeNames", "Set<String>", "pureTypeNames"),
                ("runtimeDeclaredNames", "MutableSet<String>", "runtimeDeclaredNames")],
        pre=["        var needsImportStar = needsImportStarIn",
             "        var needsImportDefault = needsImportDefaultIn",
             "        var needsExportStar = needsExportStarIn",
             "        var importStarUsedFirst = importStarUsedFirstIn"],
        ret=("CjsExportHelperFlags", ["needsImportStar", "needsImportDefault",
                                      "needsExportStar", "importStarUsedFirst"]),
        var="exportFlags",
        unpack=[
            "                    needsImportStar = exportFlags.needsImportStar",
            "                    needsImportDefault = exportFlags.needsImportDefault",
            "                    needsExportStar = exportFlags.needsExportStar",
            "                    importStarUsedFirst = exportFlags.importStarUsedFirst",
        ],
        call_ind=20,
    ),
    dict(
        name="tcjsTransformOtherStatement", a=2841, b=2918, ind=20, ded=12,
        kdoc="""    /**
     * The `else` arm: everything the transform reaches as an already-lowered
     * expression statement — namespace/enum IIFEs whose argument must become
     * `exports.X`, decorator assignments that have to chain `exports.Y =`, and
     * late-export wrapping for `export { x }` clauses seen elsewhere.
     */""",
        params=[("stmt", "Statement", "stmt"), RESULT,
                ("exportedVarNames", "MutableList<String>", "exportedVarNames"),
                ("directExportedVarNames", "MutableSet<String>", "directExportedVarNames"),
                ("exportedNsEnumNames", "MutableSet<String>", "exportedNsEnumNames"),
                ("iifeExportAliases", "MutableMap<String, MutableList<String>>", "iifeExportAliases"),
                ("namedExportLocalToExport", "MutableMap<String, MutableList<String>>",
                 "namedExportLocalToExport"),
                ("directExportClauseAliases", "MutableMap<String, MutableList<String>>",
                 "directExportClauseAliases"),
                ("keepDeclFunctionVarNames", "MutableSet<String>", "keepDeclFunctionVarNames")],
        call_ind=20,
    ),
    # ---- the post-loop pipeline ------------------------------------------
    dict(
        name="tcjsExtractEarlyPrePreamble", a=2981, b=3032, ind=8, ded=0,
        kdoc="""    /**
     * Detaches module-level header comments (copyright blocks, `///` reference
     * directives) from the first real statement so they can be emitted BEFORE
     * the `__esModule` preamble. Runs before the stub / void0 insertions, which
     * would otherwise move the statement this reads positions from.
     */""",
        params=[OSF, RESULT,
                ("hasExportEquals", "Boolean", "hasExportEquals"),
                ("prePreambleStatements", "MutableList<Statement>", "prePreambleStatements")],
    ),
    dict(
        name="tcjsPrependHoistedVars", a=3063, b=3118, ind=8, ded=0,
        kdoc="""    /**
     * Prepends the hoisted `var` declarations — side-effect destructuring temps
     * and computed-property key temps — ahead of the preamble, then inserts the
     * function export stubs after them. Returns how many statements were
     * prepended, which the later helper insertion has to add to its own baseline.
     */""",
        params=[RESULT,
                ("hasExportEquals", "Boolean", "hasExportEquals"),
                ("exportedVarNames", "MutableList<String>", "exportedVarNames"),
                ("sideEffectTempVars", "MutableList<String>", "sideEffectTempVars"),
                ("functionExportStubs", "MutableList<Statement>", "functionExportStubs")],
        ret=("Int", "        return prependedCount"),
        var="prependedCount",
    ),
    dict(
        name="tcjsRewriteExportMutations", a=3137, b=3180, ind=8, ded=0,
        kdoc="""    /**
     * B321/B322: `++x` / `--x` of a late-exported local anywhere in the tree, and
     * destructuring assignments whose targets include exported names (flattened
     * into per-element `exports.N =` chains). Runs BEFORE the global direct-export
     * identifier rewrite, so the patterns still hold raw identifiers.
     */""",
        params=[RESULT,
                ("directExportedVarNames", "MutableSet<String>", "directExportedVarNames"),
                ("namedExportLocalToExport", "MutableMap<String, MutableList<String>>",
                 "namedExportLocalToExport"),
                ("directExportClauseAliases", "MutableMap<String, MutableList<String>>",
                 "directExportClauseAliases")],
    ),
    dict(
        name="tcjsCollectInternalAliasNames", a=3231, b=3253, ind=8, ded=0,
        kdoc="""    /**
     * Collects the `import x = M.N` alias names, and the subset that is elidable:
     * the module-reference root is a namespace declared in this file AND the
     * alias name occurs nowhere else in the source. Script files (no static
     * module syntax) elide nothing — their aliases may be referenced elsewhere.
     */""",
        params=[OSF, ("hasStaticModuleDeclarations", "Boolean", "hasStaticModuleDeclarations")],
        ret=("CjsInternalAliases", ["unusedInternalAliasNames", "internalAliasNames"]),
        var="internalAliases",
        unpack=[
            "        val unusedInternalAliasNames = internalAliases.unusedInternalAliasNames",
            "        val internalAliasNames = internalAliases.internalAliasNames",
        ],
    ),
    dict(
        name="tcjsElideUnusedImports", a=3255, b=3394, ind=8, ded=0,
        kdoc="""    /**
     * Import elision: drops the `require` consts whose bound name is referenced
     * in no value position, and the unused internal module aliases. Clears the
     * helper flags when every import that wanted a helper has gone, and carries
     * a dropped statement's leading comments over to whatever survives.
     */""",
        params=[("needsImportStarIn", "Boolean", "needsImportStar"),
                ("needsImportDefaultIn", "Boolean", "needsImportDefault"),
                OSF, RESULT,
                ("requireImportStmts", "List<VariableStatement>", "requireImportStmts"),
                ("regularImportRequires", "MutableSet<VariableStatement>", "regularImportRequires"),
                ("namespaceImportRequires", "MutableSet<VariableStatement>", "namespaceImportRequires"),
                ("importStmtForLocalName", "MutableMap<String, Statement>", "importStmtForLocalName"),
                ("prePreambleStatements", "MutableList<Statement>", "prePreambleStatements"),
                ("valueReferencedNames", "Set<String>", "valueReferencedNames"),
                ("internalAliasNames", "Set<String>", "internalAliasNames"),
                ("unusedInternalAliasNames", "Set<String>", "unusedInternalAliasNames")],
        pre=["        var needsImportStar = needsImportStarIn",
             "        var needsImportDefault = needsImportDefaultIn"],
        ret=("CjsImportHelperFlags", ["needsImportStar", "needsImportDefault"]),
        var="elisionFlags",
        unpack=[
            "        needsImportStar = elisionFlags.needsImportStar",
            "        needsImportDefault = elisionFlags.needsImportDefault",
        ],
    ),
    dict(
        name="tcjsMoveDetachedHeaderComments", a=3396, b=3439, ind=8, ded=0,
        kdoc="""    /**
     * Post-elision pass of the same header-comment move: a copyright block or
     * `///amd-dependency` run is lifted ahead of the preamble, but ONLY when a
     * blank line separates the LAST comment of the run from the statement, so a
     * contiguous block is never partially moved.
     */""",
        params=[OSF, RESULT,
                ("hasExportEquals", "Boolean", "hasExportEquals"),
                ("prePreambleStatements", "MutableList<Statement>", "prePreambleStatements")],
    ),
    dict(
        name="tcjsInsertHelpersAndPrologue", a=3441, b=3559, ind=8, ded=0,
        kdoc="""    /**
     * The final assembly: inline runtime helpers (or the tslib imports that
     * replace them under `importHelpers`), the passed-in leading helpers, the
     * `\"use strict\"` prologue, the other prologue directives, hoisted `///`
     * reference directives, and the pre-preamble comment statements — each at
     * the exact position tsc emits it.
     */""",
        params=[RESULT,
                ("hasExportEquals", "Boolean", "hasExportEquals"),
                ("hasStaticModuleDeclarations", "Boolean", "hasStaticModuleDeclarations"),
                ("hasUseStrictPrologue", "Boolean", "hasUseStrictPrologue"),
                ("useStrictSingleQuote", "Boolean", "useStrictSingleQuote"),
                ("needsImportStar", "Boolean", "needsImportStar"),
                ("needsImportDefault", "Boolean", "needsImportDefault"),
                ("needsExportStar", "Boolean", "needsExportStar"),
                ("importStarUsedFirst", "Boolean", "importStarUsedFirst"),
                ("prependedCount", "Int", "prependedCount"),
                ("leadingHelpers", "List<Statement>", "leadingHelpers"),
                ("otherPrologueDirectives", "MutableList<ExpressionStatement>", "otherPrologueDirectives"),
                ("prePreambleStatements", "MutableList<Statement>", "prePreambleStatements"),
                ("exportedVarNames", "MutableList<String>", "exportedVarNames"),
                ("functionExportStubs", "MutableList<Statement>", "functionExportStubs")],
    ),
]


def signature(h):
    out = [f"    private fun {h['name']}("]
    for p, t, _ in h["params"]:
        out.append(f"        {p}: {t},")
    ret = h.get("ret")
    rt = f": {ret[0]}" if ret else ""
    out.append(f"    ){rt} {{")
    return out


def tail(h):
    """Lines after the moved region: the frame close, then the return."""
    out = list(h.get("post", []))
    ret = h.get("ret")
    if ret:
        if isinstance(ret[1], str):
            out.append(ret[1])
        else:
            out.append(f"        return {ret[0]}(")
            for f in ret[1]:
                out.append(f"            {f} = {f},")
            out.append("        )")
    return out


def call(h):
    ind = " " * h.get("call_ind", 8)
    ret = h.get("ret")
    head = f"{ind}{h['name']}("
    if ret:
        head = f"{ind}val {h['var']} = {h['name']}("
    out = [head]
    for p, _, arg in h["params"]:
        out.append(f"{ind}    {p} = {arg},")
    out.append(f"{ind})")
    out += h.get("unpack", [])
    return out


def dedent(lines, n):
    return [l[n:] if l.strip() else l for l in lines]


def build(head):
    """The new file, as a pure function of HEAD's text."""
    hl = head.split("\n")
    entry, ln = [], FN_START
    for h in HELPERS:
        entry += hl[ln - 1:h["a"] - 1]
        entry += call(h)
        ln = h["b"] + 1
    entry += hl[ln - 1:FN_END]

    helpers = []
    for h in HELPERS:
        helpers.append("")
        helpers += h["kdoc"].split("\n")
        helpers += signature(h)
        helpers += h.get("pre", [])
        helpers += dedent(hl[h["a"] - 1:h["b"]], h["ded"])
        helpers += tail(h)
        helpers.append("    }")

    return "\n".join(
        hl[:FN_START - 1] + ADDED_DECLS.split("\n") + entry + helpers + hl[FN_END:]
    )


def main():
    head = subprocess.run(["git", "show", f"HEAD:{PATH}"],
                          capture_output=True, text=True, check=True).stdout
    hl = head.split("\n")
    # POSITIVE CONTROLS on the line numbers, so a rebased HEAD cannot silently
    # move the regions under the script.
    assert hl[FN_START - 1].startswith("    private fun transformToCommonJS("), hl[FN_START - 1]
    assert hl[FN_END - 1] == "    }", repr(hl[FN_END - 1])
    assert hl[FN_START - 2] == "", repr(hl[FN_START - 2])
    prev = FN_START
    for h in HELPERS:
        # the region OPENS at its own statement indent; its last line may be a
        # continuation (`.toSet()`), so that edge is only bounded below.
        first = hl[h["a"] - 1]
        assert len(first) - len(first.lstrip()) == h["ind"], (h["name"], h["a"], first)
        last = hl[h["b"] - 1]
        assert last.strip() and last.startswith(" " * h["ind"]), (h["name"], h["b"], last)
        assert h["a"] > prev, (h["name"], h["a"], prev)
        prev = h["b"]
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
