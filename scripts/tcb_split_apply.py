#!/usr/bin/env python3
"""(JIT.1)(e) round 818 — split `Transformer.transformClassBody` (16,233
bytecodes, over HotSpot's 8,000-byte `HugeMethodLimit`, so never JIT-compiled)
into an entry plus nine `tcb*` helpers.

The new file is built as a PURE FUNCTION of HEAD, so `tcb_split_verify.py` can
reconstruct it byte for byte and the working tree cannot have drifted.

Region sizes were MEASURED before the edit with `scripts/method_bytes_by_line.py`
(round 816's instrument), not estimated:

    11392-11452    584  auto-accessor downlevel      -> tcbLowerAutoAccessors
    11544-11625  1,112  computed-key temp extraction -> tcbExtractComputedPropertyKeys
    11712-11813  1,832  private state allocation     -> tcbAllocatePrivateState
    11950-12051  1,161  instance field initializers  -> tcbBuildInstanceInitializers
    12054-12145  1,338  the constructor              -> tcbBuildTransformedConstructor
    12155-12319  1,677  the output member list       -> tcbBuildOutputMembers
    12372-12430  1,259  class-alias/heritage capture -> tcbCaptureClassAlias
    12432-12515  1,264  the alias+private comma stmt -> tcbEmitAliasAndPrivateState
    12517-12609    979  static field trailing stmts  -> tcbEmitStaticFieldTrailing

TWO THINGS THIS TARGET HAS THAT NO EARLIER ONE IN THE ARC DID.

1. A LOCAL DATA CLASS. `PrivateFieldInfo` is declared INSIDE the function body
   and constructed by a moved region, so the region cannot move while the type
   is un-nameable from a member function. It is LIFTED to a private nested data
   class — the only text change outside the mechanical extraction, and it is
   behaviour-free: the class captures nothing and never escapes the function.

2. A LOCAL FUNCTION CALLED FROM BOTH SIDES OF A BOUNDARY.
   `buildStaticBlockIife` closes over `classTempVar`/`heritageTempVar` and is
   called both inside the moved static-trailing loop AND after it, so it cannot
   move and cannot be duplicated. It is passed as a FUNCTION-TYPED PARAMETER
   (`::buildStaticBlockIife`), which leaves the moved call site
   `buildStaticBlockIife(member)` textually untouched. The ORDER that makes this
   sound is enforced by the data flow: `tcbCaptureClassAlias` runs first and the
   entry assigns both vars before the reference is ever invoked.
   `isCapturablePrivateMethod` needed no such treatment — its only two call
   sites are inside one region, so the local `fun` MOVES with them.

Run:  python3 scripts/tcb_split_apply.py [--check]
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import subprocess
import sys

PATH = "src/commonMain/kotlin/Transformer.kt"
FN_START, FN_END = 11375, 12695

# The local `data class PrivateFieldInfo` line, removed from the body and lifted
# to a private nested class. Asserted to occur EXACTLY once.
LOCAL_PFI = ("        data class PrivateFieldInfo(val fieldName: String, "
             "val weakMapVar: String, val initializer: Expression?)")

# Declarations added before the function: the lifted data class and the holder
# `tcbCaptureClassAlias` returns.
ADDED_DECLS = """    /**
     * A `#field` lowered to the WeakMap pattern (target < ES2022): the source
     * field name, the WeakMap/state variable allocated for it, and the
     * already-transformed initializer.
     *
     * (JIT.1)(e) round 818: this was a LOCAL data class inside
     * [transformClassBody]. It is lifted here so the regions that construct it
     * can live in `tcb*` helper methods; it captures nothing and never escapes
     * the transform, so lifting it is behaviour-free.
     */
    private data class PrivateFieldInfo(
        val fieldName: String,
        val weakMapVar: String,
        val initializer: Expression?,
    )

    /**
     * What [tcbCaptureClassAlias] decides: the class-alias temp (`_a = Foo`), the
     * heritage-capture temp (`extends (_c = Base)`) and the — possibly rewritten
     * — heritage clauses. All three are read by later stages of
     * [transformClassBody], which is why they travel together.
     */
    private data class ClassAliasCapture(
        val classTempVar: String?,
        val heritageTempVar: String?,
        val heritage: List<HeritageClause>?,
    )
"""

# name, first, last, dedent, KDoc, signature, prologue lines, the return line
HELPERS = [
    (
        "tcbLowerAutoAccessors", 11392, 11452, 0,
        """    /**
     * Below ES2022 a PUBLIC instance `accessor x` field has no native form, so
     * tsc lowers it to a WeakMap-backed private storage field plus a
     * getter/setter pair; the synthesized `#x_accessor_storage` then flows
     * through the ordinary private-field downlevel. Returns [membersIn]
     * unchanged at ES2022+ and for every member the lowering does not apply to.
     */""",
        """    private fun tcbLowerAutoAccessors(
        membersIn: List<ClassElement>,
    ): List<ClassElement> {""",
        [],
        "        return members",
    ),
    (
        "tcbExtractComputedPropertyKeys", 11544, 11625, 0,
        """    /**
     * Extracts non-literal computed property names to temp vars so the key
     * expression evaluates exactly once, at class-definition time.
     *
     * All four collections are the CALLER's instances, mutated in place: the
     * member -> temp map, the leading `var` names, the leftover key evaluations
     * flushed after the class, and the captures HOSTED inside the next kept
     * member's own brackets. They are parameters rather than a return because
     * each is read by a different later stage of [transformClassBody].
     */""",
        """    private fun tcbExtractComputedPropertyKeys(
        members: List<ClassElement>,
        computedPropTempVars: MutableMap<ClassElement, String>,
        computedPropLeadingVars: MutableList<String>,
        computedPropAssignments: MutableList<Pair<String?, Expression>>,
        hostedComputedCaptures: MutableMap<ClassElement, List<Pair<String?, Expression>>>,
    ) {""",
        [],
        None,
    ),
    (
        "tcbAllocatePrivateState", 11712, 11813, 0,
        """    /**
     * Allocates the private-state variables a `#`-member class needs below
     * ES2022 — WeakMaps for instance fields, a WeakSet brand plus per-method
     * function vars when private methods exist, descriptor vars for static
     * fields — into `hoistedVarScopes`, and (class-EXPRESSION form) builds their
     * init assignments.
     *
     * Returns the brand variable, or null when the class has no capturable
     * private method. The five collections are the caller's, mutated in place.
     */""",
        """    private fun tcbAllocatePrivateState(
        members: List<ClassElement>,
        name: Identifier?,
        assignedName: String?,
        isClassExpression: Boolean,
        needsPrivateFieldDownlevel: Boolean,
        privateFieldInfos: MutableList<PrivateFieldInfo>,
        privateStaticFieldVars: MutableMap<PropertyDeclaration, String>,
        privateMethodCaptured: MutableSet<ClassElement>,
        privateStateStatements: MutableList<Statement>,
        privateMethodVars: MutableList<Pair<MethodDeclaration, String>>,
    ): String? {""",
        [],
        "        return brandVar",
    ),
    (
        "tcbBuildInstanceInitializers", 11950, 12051, 0,
        """    /**
     * Appends the constructor-body statements for instance fields to
     * [propInitStatements] — plain `this.p = init` assignments, or, under
     * `useDefineForClassFields` below ES2022, the `Object.defineProperty(this,
     * "p", ...)` form (which also covers fields with no initializer).
     */""",
        """    private fun tcbBuildInstanceInitializers(
        instanceProperties: List<PropertyDeclaration>,
        computedPropTempVars: Map<ClassElement, String>,
        needsDefineLowering: Boolean,
        propInitStatements: MutableList<Statement>,
    ) {""",
        [],
        None,
    ),
    (
        "tcbBuildTransformedConstructor", 12054, 12145, 0,
        """    /**
     * Builds the emitted constructor: the source one with [propInitStatements]
     * spliced in after `super()` (or after the prologue directives and
     * synthetic hoists when there is no `super()`), a synthesized one when the
     * class has field initializers but no constructor, or null when neither.
     */""",
        """    private fun tcbBuildTransformedConstructor(
        existingConstructor: Constructor?,
        paramProperties: List<Parameter>,
        propInitStatements: List<Statement>,
        isDerived: Boolean,
    ): Constructor? {""",
        [],
        "        return transformedConstructor",
    ),
    (
        "tcbBuildOutputMembers", 12155, 12319, 0,
        """    /**
     * Fills [outputMembers] with the class-body members in SOURCE order, erasing
     * what has no runtime form (`declare` fields, overload signatures, index
     * signatures, downleveled private members) and re-homing the rest.
     *
     * Returns whether the transformed constructor was placed at its original
     * position — the caller prepends it when it was not.
     */""",
        """    private fun tcbBuildOutputMembers(
        members: List<ClassElement>,
        existingConstructor: Constructor?,
        transformedConstructor: Constructor?,
        needsPrivateFieldDownlevel: Boolean,
        needsDefineLowering: Boolean,
        privateMethodCaptured: Set<ClassElement>,
        hostedComputedCaptures: Map<ClassElement, List<Pair<String?, Expression>>>,
        computedPropTempVars: Map<ClassElement, String>,
        outputMembers: MutableList<ClassElement>,
    ): Boolean {""",
        [],
        "        return constructorAdded",
    ),
    (
        "tcbCaptureClassAlias", 12372, 12430, 4,
        """    /**
     * Decides whether the class must be captured into an alias temp
     * (`_a = Foo`) before its static initializers run — `this` or an async arrow
     * in a static field initializer, or `this`/`super` in a static block — and,
     * for a `super` read, captures the heritage expression too
     * (`extends (_c = Base)`), rewriting the clause.
     *
     * Both temps are read afterwards by the static-block IIFE builder, which is
     * why this stage runs before it and hands its answers back.
     */""",
        """    private fun tcbCaptureClassAlias(
        staticProperties: List<PropertyDeclaration>,
        staticBlocks: List<ClassStaticBlockDeclaration>,
        staticMethodAlias: String?,
        isClassExpression: Boolean,
        heritageIn: List<HeritageClause>?,
    ): ClassAliasCapture {""",
        [
            # NOT `var … = null`: the moved region's first act is to assign it
            # UNCONDITIONALLY, so Kotlin reports first a redundant initializer
            # and then a `var` that could be a `val` (the build is warning-clean
            # and must stay so). `heritageTempVar` and `finalHeritage` stay
            # initialised `var`s — each is assigned only inside a nested `if`.
            "        val classTempVar: String?",
            "        var heritageTempVar: String? = null",
            "        var finalHeritage = heritageIn",
        ],
        "        return ClassAliasCapture(classTempVar, heritageTempVar, finalHeritage)",
    ),
    (
        "tcbEmitAliasAndPrivateState", 12432, 12515, 4,
        """    /**
     * Appends the ONE trailing comma statement that carries the class-alias
     * capture and the declaration-path private captures —
     * `_a = Foo, _Foo_instances = new WeakSet(), _Foo_m = function _Foo_m() {}`
     * — in tsc's allocation order (brand, alias, method vars), plus the B502
     * static private method functions. Emits nothing when none apply.
     */""",
        """    private fun tcbEmitAliasAndPrivateState(
        name: Identifier?,
        effectiveName: String,
        isClassExpression: Boolean,
        classTempVar: String?,
        brandVar: String?,
        staticMethodAlias: String?,
        privateMethodVars: List<Pair<MethodDeclaration, String>>,
        staticPrivateMethodVars: List<Pair<MethodDeclaration, String>>,
        trailingStatements: MutableList<Statement>,
    ) {""",
        [],
        None,
    ),
    (
        "tcbEmitStaticFieldTrailing", 12517, 12609, 4,
        """    /**
     * Emits the post-class trailing statements for static members in MEMBER
     * order: a static block becomes its IIFE (built by the caller's
     * [buildStaticBlockIife], which closes over the alias temps this stage must
     * not re-decide), a private static field stores into its descriptor var, and
     * a public one becomes `Foo.p = init` with `this`, the class name and
     * `super` reads routed through the alias temps.
     */""",
        """    private fun tcbEmitStaticFieldTrailing(
        members: List<ClassElement>,
        staticProperties: List<PropertyDeclaration>,
        staticBlocks: List<ClassStaticBlockDeclaration>,
        privateStaticFieldVars: Map<PropertyDeclaration, String>,
        computedPropTempVars: Map<ClassElement, String>,
        name: Identifier?,
        effectiveName: String,
        isClassExpression: Boolean,
        classTempVar: String?,
        heritageTempVar: String?,
        buildStaticBlockIife: (ClassStaticBlockDeclaration) -> Statement,
        trailingStatements: MutableList<Statement>,
        emittedStaticBlocks: MutableList<ClassStaticBlockDeclaration>,
    ) {""",
        [],
        None,
    ),
]

# region name -> the call site that replaces it. Every argument is passed BY NAME
# (round 816's rule): a positional call whose arguments could permute and still
# type-check is a mistake no compiler can catch.
CALLS = {
    "tcbLowerAutoAccessors": [
        "        val members = tcbLowerAutoAccessors(",
        "            membersIn = membersIn,",
        "        )",
    ],
    "tcbExtractComputedPropertyKeys": [
        "        tcbExtractComputedPropertyKeys(",
        "            members = members,",
        "            computedPropTempVars = computedPropTempVars,",
        "            computedPropLeadingVars = computedPropLeadingVars,",
        "            computedPropAssignments = computedPropAssignments,",
        "            hostedComputedCaptures = hostedComputedCaptures,",
        "        )",
    ],
    "tcbAllocatePrivateState": [
        "        val brandVar = tcbAllocatePrivateState(",
        "            members = members,",
        "            name = name,",
        "            assignedName = assignedName,",
        "            isClassExpression = isClassExpression,",
        "            needsPrivateFieldDownlevel = needsPrivateFieldDownlevel,",
        "            privateFieldInfos = privateFieldInfos,",
        "            privateStaticFieldVars = privateStaticFieldVars,",
        "            privateMethodCaptured = privateMethodCaptured,",
        "            privateStateStatements = privateStateStatements,",
        "            privateMethodVars = privateMethodVars,",
        "        )",
    ],
    "tcbBuildInstanceInitializers": [
        "        tcbBuildInstanceInitializers(",
        "            instanceProperties = instanceProperties,",
        "            computedPropTempVars = computedPropTempVars,",
        "            needsDefineLowering = needsDefineLowering,",
        "            propInitStatements = propInitStatements,",
        "        )",
    ],
    "tcbBuildTransformedConstructor": [
        "        val transformedConstructor = tcbBuildTransformedConstructor(",
        "            existingConstructor = existingConstructor,",
        "            paramProperties = paramProperties,",
        "            propInitStatements = propInitStatements,",
        "            isDerived = isDerived,",
        "        )",
    ],
    "tcbBuildOutputMembers": [
        "        val constructorAdded = tcbBuildOutputMembers(",
        "            members = members,",
        "            existingConstructor = existingConstructor,",
        "            transformedConstructor = transformedConstructor,",
        "            needsPrivateFieldDownlevel = needsPrivateFieldDownlevel,",
        "            needsDefineLowering = needsDefineLowering,",
        "            privateMethodCaptured = privateMethodCaptured,",
        "            hostedComputedCaptures = hostedComputedCaptures,",
        "            computedPropTempVars = computedPropTempVars,",
        "            outputMembers = outputMembers,",
        "        )",
    ],
    "tcbCaptureClassAlias": [
        "            val aliasCapture = tcbCaptureClassAlias(",
        "                staticProperties = staticProperties,",
        "                staticBlocks = staticBlocks,",
        "                staticMethodAlias = staticMethodAlias,",
        "                isClassExpression = isClassExpression,",
        "                heritageIn = finalHeritage,",
        "            )",
        "            classTempVar = aliasCapture.classTempVar",
        "            heritageTempVar = aliasCapture.heritageTempVar",
        "            finalHeritage = aliasCapture.heritage",
    ],
    "tcbEmitAliasAndPrivateState": [
        "            tcbEmitAliasAndPrivateState(",
        "                name = name,",
        "                effectiveName = effectiveName,",
        "                isClassExpression = isClassExpression,",
        "                classTempVar = classTempVar,",
        "                brandVar = brandVar,",
        "                staticMethodAlias = staticMethodAlias,",
        "                privateMethodVars = privateMethodVars,",
        "                staticPrivateMethodVars = staticPrivateMethodVars,",
        "                trailingStatements = trailingStatements,",
        "            )",
    ],
    "tcbEmitStaticFieldTrailing": [
        "            tcbEmitStaticFieldTrailing(",
        "                members = members,",
        "                staticProperties = staticProperties,",
        "                staticBlocks = staticBlocks,",
        "                privateStaticFieldVars = privateStaticFieldVars,",
        "                computedPropTempVars = computedPropTempVars,",
        "                name = name,",
        "                effectiveName = effectiveName,",
        "                isClassExpression = isClassExpression,",
        "                classTempVar = classTempVar,",
        "                heritageTempVar = heritageTempVar,",
        "                buildStaticBlockIife = ::buildStaticBlockIife,",
        "                trailingStatements = trailingStatements,",
        "                emittedStaticBlocks = emittedStaticBlocks,",
        "            )",
    ],
}


def dedent(lines, n):
    return [l[n:] if l.strip() else l for l in lines]


def build(head):
    """The new file, as a pure function of HEAD's text."""
    hl = head.split("\n")

    entry, ln = [], FN_START
    for name, a, b, _ded, _kdoc, _sig, _pre, _ret in HELPERS:
        entry += hl[ln - 1:a - 1]
        entry += CALLS[name]
        ln = b + 1
    entry += hl[ln - 1:FN_END]
    # the local data class is lifted out of the body (see ADDED_DECLS)
    assert entry.count(LOCAL_PFI) == 1, entry.count(LOCAL_PFI)
    entry.remove(LOCAL_PFI)

    helpers = []
    for name, a, b, ded, kdoc, sig, pre, ret in HELPERS:
        helpers.append("")
        helpers += kdoc.split("\n")
        helpers += sig.split("\n")
        helpers += pre
        helpers += dedent(hl[a - 1:b], ded)
        if ret is not None:
            helpers.append(ret)
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
    assert hl[FN_START - 1].startswith("    private fun transformClassBody("), hl[FN_START - 1]
    assert hl[FN_END - 1] == "    }", repr(hl[FN_END - 1])
    assert hl[FN_START - 2] == "", repr(hl[FN_START - 2])
    for name, a, b, ded, *_ in HELPERS:
        assert hl[a - 1].startswith(" " * (8 + ded)), (name, hl[a - 1])
        assert hl[b - 1].startswith(" " * (8 + ded)), (name, hl[b - 1])
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
