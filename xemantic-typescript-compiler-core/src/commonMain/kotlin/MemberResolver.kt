/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 *
 * xemantic-typescript-compiler - a conformant TypeScript compiler and type
 * checker that runs on JVM, native, and WebAssembly
 * Copyright (C) 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * As a special exception, this file contains Helper Code covered by the
 * xemantic-typescript-compiler Output Exception; additional permissions
 * are granted as described in the file LICENSE-EXCEPTION.
 */

package com.xemantic.typescript.compiler

/**
 * (INV.0) step 6a — the MEMBER-RESOLUTION seam, tsc's lazy member tables: given a
 * [Type.Object] whose `members` / `properties` / `callSignatures` /
 * `constructSignatures` / index infos are still null, BUILD them. Extracted
 * VERBATIM from `Checker.kt` (design § 6 Stage 0, "member resolution" in the core
 * order — the group ledger row 7 named as a seam of its own), as a final class
 * constructed once per [Checker]; no semantic change, and each of the three call
 * sites that survives in `Checker.kt` is a one-line delegation.
 *
 * ## What is here
 *
 * ONE contiguous span of `Checker.kt` — Phase 4's item 2c, whole. The entry point
 * [resolveStructuredTypeMembers] with the (WARM.3) round-849 CONSUMED-side census
 * guard and the `--passTiming` depth-0 reentrance probe under it; the recursive
 * core [resolveStructuredTypeMembersCore] carrying the B202.1 in-progress guard
 * (the mutually-recursive-heritage cycle break, and (INC.23)'s truncation flag);
 * the kind dispatch [resolveStructuredTypeMembersDispatch] and its timed twin
 * [resolveStructuredTypeMembersCensused]; B419's JS-expando synthesis
 * ([jsFileClassDecls] / [collectClassDeclsInto] / [collectConstructorThisAssignments]
 * / [inferJsExpandoPropType] / [classHasConstructorThisAssignment]), which turns a
 * `.js`/checkJs class's constructor `this.X = expr` writes into declared instance
 * members; and the three builders themselves — [resolveInterfaceMembers] with its
 * B280 namespace push and the 385-line [resolveInterfaceMembersCore] under it,
 * [resolveReferenceMembers] (a generic instantiation's table, rebuilt from its
 * target's through a [createTypeMapper]) and [resolveAnonymousTypeMembers].
 *
 * The one field whose only readers were this algorithm moved with it: the
 * `--passTiming` reentrance counter. [jsFileClassDeclsCache] is the family's own
 * memo and was always private to it.
 *
 * ## Two traps this file is the home of
 *
 * **A member table is built on FIRST ASK** (round 833). A new reader of
 * `type.members` / `type.properties` must call [resolveStructuredTypeMembers]
 * first, or its verdict depends on whether an earlier line in the file happened
 * to resolve that type — a helper that answers "bail" on a null table fails
 * SILENTLY, and a corpus baseline whose fixture resolves the type on the way
 * through masks it exactly.
 *
 * **A `keyof` over an IN-FLIGHT table must answer from the DECLARATIONS**
 * ((INC.25)). When the first ask arrives from inside the resolution its own
 * `keyof` needs, [resolveStructuredTypeMembersCore] returns with `properties`
 * still null; reading that as "no keys" degrades a mapped type to `any`, and
 * round 778's `getTypeOfSymbol` write gate then FREEZES the degraded answer.
 * [Checker.memberResolutionTruncated] is the flag that records it, and it is one
 * of this class's two ambient WRITES for that reason.
 *
 * ## Ambient surface (the ledger row, `docs/inversion-ambient-ledger.md` § 8)
 *
 * **22** [Checker] members are touched at **45** call sites — **21 READ** and
 * exactly **1 WRITTEN**, and that one (`memberResolutionTruncated`) is never read
 * here, so the two columns are disjoint. Each is reached through the FINAL
 * [Checker] class, a direct call with no interface and no captured lambda
 * (contract § 10). Plus one companion name set read as `Checker.LIB_MIN_TARGET`.
 *
 * The 45 sites: `getTypeFromTypeNode` 7, `currentTypeParamScope` 4,
 * `declaredMemberName` 4, `getMemberName` 4, `instantiateType` 3, and 2 each for
 * `getParameterSymbols` / `instantiateSignature` / `isLibSymbolForCensus` /
 * `requiredParameterCount` / `scopeMapper` / `withInstantiationContext`; the
 * remaining eleven are one site apiece.
 *
 * The 21 reads group as:
 *
 *  - TYPE RESOLUTION (6): `getTypeFromTypeNode`, `getTypeOfSymbol`,
 *    `getTypeOfExpression`, `getWidenedLiteralType`, `getUnionType`,
 *    `resolveBaseTypesLazy`;
 *  - INSTANTIATION CONTEXT (5): `instantiateType`, `instantiateSignature`,
 *    `scopeMapper`, `withInstantiationContext`, `currentTypeParamScope` — the
 *    ambient mapper the interface builder installs around a member's own type
 *    parameters (INV.5(b2c));
 *  - DECLARATION READING (4): `declaredMemberName`, `getMemberName`,
 *    `getParameterSymbols`, `requiredParameterCount` — the member-NAME family
 *    deliberately left in `Checker.kt` as a seam of its own;
 *  - NAMESPACE SCOPE (2): `pushInferenceNamespaceFor`, `inferenceNamespaceStack`
 *    (B280's push/pop, which must stay a PAIR);
 *  - LIB / TARGET (4): `builtinLibDecls`, `libFeatureAvailable`,
 *    `isLibSymbolForCensus`, `isJsLikeFileName`.
 *
 * And the single WRITE is:
 *
 *  - STATE (1): `memberResolutionTruncated`, READ nowhere here and WRITTEN once —
 *    (INC.25)'s truncation flag, an OUT channel to `getTypeOfSymbol`, not state
 *    this algorithm consults.
 *
 * There are **no ambient writes to CONTAINERS**: [symbolTypes] and
 * [memberResolutionInProgress] are handed in as the OBJECTS and `CheckerState`
 * keeps owning them, exactly as rows 3 and 7 do.
 *
 * ## What a later stage must make explicit, and one thing it must NOT
 *
 * **[Checker.getTypeOfSymbol] is an ambient READ and that is DELIBERATE.**
 * `docs/INVERSION-DESIGN.md` § 6 puts `getTypeOfSymbol` / `getTypeOfExpression`
 * in Stage 3, on the ground that "their ambient IS the checker" — so the seam
 * available at Stage 0 is the member TABLE builders *without* them, which is what
 * this file is. Pulling either down here would drag the whole symbol-typing
 * surface across the boundary and turn a 657-line move into the Stage 3 work
 * itself. The queue item asked this round to decide the question; the answer is
 * recorded here so a later round does not re-derive it.
 *
 * The row's other honest number, MEASURED rather than assumed: **ZERO** of the 22
 * reads is an absorption candidate — every one has other users left in
 * `Checker.kt`, `isLibSymbolForCensus` (2 elsewhere) and `memberResolutionTruncated`
 * (4) being the scarcest. Row 7's cheap mechanical win has no counterpart here;
 * this seam's ambient surface is SHARED all the way down, and shrinking it means
 * extracting the neighbouring seams (the member-NAME family that starts at
 * `Checker.getMemberName`, and Stage 3's symbol typing), not tidying this one.
 */
internal class MemberResolver(
    private val checker: Checker,
    private val binderResults: List<BinderResult>,
    /**
     * `CheckerState.symbolTypes` — handed in as the OBJECT, so a member or
     * parameter symbol typed while a table is being built is the same entry every
     * other reader of that field sees. `CheckerState` keeps owning it, and round
     * 778's write gate (which decides whether a resolution PERSISTS) stays with
     * `Checker.getTypeOfSymbol`; the writes here are mint-time writes for symbols
     * this class has just created, which is the ungated form (INC.6).
     */
    private val symbolTypes: IntKeyMap<Type>,
    /**
     * `CheckerState.memberResolutionInProgress` — the B202.1 cycle-break set,
     * handed in as the OBJECT; see above.
     */
    private val memberResolutionInProgress: HashSet<Int>,
) {

    /** `--passTiming` depth-0 reentrance guard for the member-mint time split. */
    private var mrProbeDepth = 0

    /**
     * (INV.0) step 6a — the resolution RESIDUE, the one thing about this seam only a
     * test at this level can state (`MemberResolverTest`), and the sibling of
     * [Relater.recursionResidue] (ledger row 7).
     *
     * B202.1's cycle break `add`s a type id to [memberResolutionInProgress] and removes
     * it in a `finally`, and the `--passTiming` probe does the same with [mrProbeDepth].
     * A dropped `finally` does not fail: a stale id makes every LATER resolution of that
     * type return member-LESS through the cycle break, so the type silently answers as if
     * it had no members at all — a lost diagnostic, or a wrong one, in whatever file is
     * checked next. Nothing else in this repo sees that, since the corpus, `cost_gate.py`
     * and a `--listAll` diff all read the ANSWER and never the bookkeeping.
     *
     * [memberResolutionInProgress] is handed in as the object rather than owned, so this
     * sum is a statement about `CheckerState`'s set, which is what the checker's own
     * `--passTiming` probe reports at `Checker.kt`'s member-resolve counter.
     */
    internal val resolutionResidue: Int get() = memberResolutionInProgress.size + mrProbeDepth

    /**
     * Lazily resolve an ObjectType's members, properties, and signatures.
     * After this call, type.members/properties/callSignatures are populated.
     */
    fun resolveStructuredTypeMembers(type: Type.Object) {
        if (type.properties != null) {
            // (WARM.3) the CONSUMED side for MEMBER TABLES, and it must live HERE
            // rather than in `…Core` below: the two guards are identical, so the
            // wrapper absorbs EVERY hit and `…Core`'s copy of the branch is dead
            // for this purpose (round 849 — `LibTypeCensusTest`'s consumed-side
            // pin read exactly 0 until the hook moved up one frame).
            if (LibTypeCensus.enabled) {
                if (checker.isLibSymbolForCensus(type.symbol)) LibTypeCensus.memHitLib++
                else LibTypeCensus.memHitOther++
            }
            return // already resolved
        }
        if (PassTiming.detailed) {
            if (mrProbeDepth++ == 0) {
                val t0 = PassTiming.nowNanos()
                try { return resolveStructuredTypeMembersCore(type) }
                finally { PassTiming.memberResolveNanos += PassTiming.nowNanos() - t0; mrProbeDepth-- }
            }
            try { return resolveStructuredTypeMembersCore(type) } finally { mrProbeDepth-- }
        }
        return resolveStructuredTypeMembersCore(type)
    }

    private fun resolveStructuredTypeMembersCore(type: Type.Object) {
        // NOTE (WARM.3): this guard is NOT the consumed-side census point — the
        // wrapper above carries an identical one and absorbs every hit. Reached
        // here only on the re-entrant heritage path described below.
        if (type.properties != null) return // already resolved
        // Cycle guard: mutually-recursive heritage (`interface A extends B`,
        // `interface B extends A`) re-enters here for a type whose member table is
        // not yet planted (it is assigned only at the end of resolution), so the
        // `properties != null` check above does not yet hold. Without this, the
        // resolveStructuredTypeMembers <-> resolveInterfaceMembers <->
        // resolveReferenceMembers chain recurses into a StackOverflowError that the
        // callers silently swallow. Break the cycle here: the OUTER resolution
        // completes and plants the full table; the re-entrant inner request returns
        // with the type still member-less, which is correct for the circular-base
        // error inputs that produce these cycles.
        if (MapCensus.boxedKeyCensus) MapCensus.bk(MapCensus.BK_SYM_INPROG, type.id.toLong())
        val memberInProgressAdded = memberResolutionInProgress.add(type.id)
        if (MapCensus.on) MapCensus.memberEnter(memberInProgressAdded)
        if (!memberInProgressAdded) {
            // (INC.23) THE TRUNCATION, RECORDED. Everything above this line is a
            // comment about why returning member-less is correct for the caller; what
            // was never recorded is that the ANSWER the caller then computes is a
            // function of the resolution ORDER, and `symbolTypes` freezes it.
            checker.memberResolutionTruncated = true
            return
        }
        try {
            if (LibTypeCensus.enabled) resolveStructuredTypeMembersCensused(type)
            else resolveStructuredTypeMembersDispatch(type)
        } finally {
            if (MapCensus.boxedKeyCensus) MapCensus.bk(MapCensus.BK_SYM_INPROG, type.id.toLong())
            memberResolutionInProgress.remove(type.id)
            if (MapCensus.on) MapCensus.memberLeave()
        }
    }

    private fun resolveStructuredTypeMembersDispatch(type: Type.Object) {
        when (type) {
            is Type.Interface -> resolveInterfaceMembers(type)
            is Type.Reference -> resolveReferenceMembers(type)
            else -> resolveAnonymousTypeMembers(type)
        }
    }

    /**
     * (WARM.3) round 849 — the timed twin of the member-table MINT. Reached only
     * while [LibTypeCensus.enabled]; shares [LibTypeCensus.depth] with
     * [getDeclaredTypeOfSymbolCensused] so a nested mint is never timed twice.
     */
    private fun resolveStructuredTypeMembersCensused(type: Type.Object) {
        val outermost = LibTypeCensus.depth == 0
        LibTypeCensus.depth++
        var t0 = 0L
        if (outermost) {
            val e0 = PassTiming.nowNanos()
            LibTypeCensus.boundaryNanos += PassTiming.nowNanos() - e0
            LibTypeCensus.boundaryCalls++
            t0 = PassTiming.nowNanos()
        }
        try {
            resolveStructuredTypeMembersDispatch(type)
        } finally {
            LibTypeCensus.depth--
            val dt = if (outermost) PassTiming.nowNanos() - t0 else 0L
            val lib = checker.isLibSymbolForCensus(type.symbol)
            if (lib) LibTypeCensus.memMintLib++ else LibTypeCensus.memMintOther++
            if (outermost) LibTypeCensus.recordOutermost(lib, dt)
        }
    }

    // B419: cache of every class declaration that lives in a `.js`/checkJs file —
    // such classes declare instance properties via `this.X = expr` in their
    // constructor (tsc synthesizes those as members). Computed lazily.
    private var jsFileClassDeclsCache: MutableSet<Node>? = null
    private fun jsFileClassDecls(): Set<Node> {
        jsFileClassDeclsCache?.let { return it }
        val s = mutableSetOf<Node>()
        for (result in binderResults) {
            if (!checker.isJsLikeFileName(result.sourceFile.fileName)) continue
            collectClassDeclsInto(result.sourceFile.statements, s)
        }
        jsFileClassDeclsCache = s
        return s
    }
    private fun collectClassDeclsInto(stmts: List<Statement>, into: MutableSet<Node>) {
        for (stmt in stmts) {
            when (stmt) {
                is ClassDeclaration -> into.add(stmt)
                is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let { collectClassDeclsInto(it.statements, into) }
                else -> {}
            }
        }
    }

    /**
     * B419: collect `this.<id> = <expr>` assignments from a JS-class constructor body
     * (recursing into top-level if/else branches and blocks, matching the constructor
     * shapes that declare expando instance properties), name → list of RHS exprs.
     */
    fun collectConstructorThisAssignments(
        stmts: List<Statement>, into: MutableMap<String, MutableList<Expression>>,
        jsdocTypes: MutableMap<String, TypeNode>? = null,
    ) {
        for (stmt in stmts) {
            when (stmt) {
                is ExpressionStatement -> {
                    val bin = stmt.expression as? BinaryExpression ?: continue
                    if (bin.operator != SyntaxKind.Equals) continue
                    val pa = bin.left as? PropertyAccessExpression ?: continue
                    if ((pa.expression as? Identifier)?.text != "this") continue
                    val name = (pa.name).text
                    into.getOrPut(name) { mutableListOf() }.add(bin.right)
                    // B419b: a leading JSDoc `@type {T}` on the assignment statement supplies
                    // the member's declared type (`/** @type {number[]} */ this.p = []` → number[]
                    // not the inferred any[]). First-wins per name. Only captured when the caller
                    // requests it (synthesis path) — the name-presence caller passes null.
                    if (jsdocTypes != null && name !in jsdocTypes && stmt.leadingComments != null) {
                        Parser("", "").parseJsDocTypeNodeFromComments(stmt.leadingComments)
                            ?.let { jsdocTypes[name] = it }
                    }
                }
                is IfStatement -> {
                    collectConstructorThisAssignments(listOf(stmt.thenStatement), into, jsdocTypes)
                    stmt.elseStatement?.let { collectConstructorThisAssignments(listOf(it), into, jsdocTypes) }
                }
                is Block -> collectConstructorThisAssignments(stmt.statements, into, jsdocTypes)
                else -> {}
            }
        }
    }

    /**
     * B419: infer a JS expando property's type from its constructor `this.X = …` RHS
     * value(s). Widens literals; when there are multiple assignments, drops nullish
     * members if any non-nullish one exists (tsc's "initial null + later concrete"
     * → concrete) then unions the rest. Returns null if no concrete type resolves.
     */
    private fun inferJsExpandoPropType(rhsList: List<Expression>): Type? {
        val types = rhsList.mapNotNull {
            val t = checker.getWidenedLiteralType(checker.getTypeOfExpression(it))
            if (t === errorType) null else t
        }
        if (types.isEmpty()) return null
        val nonNullish = types.filter {
            !it.flags.hasAny(TypeFlags.Null) && !it.flags.hasAny(TypeFlags.Undefined)
        }
        val pool = if (nonNullish.isNotEmpty()) nonNullish else types
        val distinct = pool.distinctBy { it.id }
        return if (distinct.size == 1) distinct[0] else checker.getUnionType(distinct)
    }

    /** B419: true if a JS-file class's constructor assigns `this.<propName> = …`. */
    fun classHasConstructorThisAssignment(classDecl: ClassDeclaration, propName: String): Boolean {
        if (classDecl !in jsFileClassDecls()) return false
        val body = classDecl.members.filterIsInstance<Constructor>().firstOrNull()?.body ?: return false
        val m = LinkedHashMap<String, MutableList<Expression>>()
        collectConstructorThisAssignments(body.statements, m)
        return propName in m
    }

    /** Resolve members of an interface/class type from its declarations + base types. */
    private fun resolveInterfaceMembers(type: Type.Interface) {
        // B280: an interface declared INSIDE a namespace must resolve its member
        // annotations with the containing namespace's exports in scope (`namespace O {
        // class A; interface I { g(a: A): C } }` — A/C are namespace-local). Without
        // the push those member types silently resolve to errorType, killing
        // downstream overload-return resolution (overload1). The nodeTypes cache is
        // bypassed while inferenceNamespaceStack is non-empty, so no stale entries.
        val nsPushed = type.symbol?.let { checker.pushInferenceNamespaceFor(it) } ?: false
        try {
            resolveInterfaceMembersCore(type)
        } finally {
            if (nsPushed) checker.inferenceNamespaceStack.removeLast()
        }
    }

    private fun resolveInterfaceMembersCore(type: Type.Interface) {
        val symbol = type.symbol ?: run {
            type.properties = emptyList()
            return
        }
        // 16.0n: Re-resolve baseTypes if empty — declarations may have been merged
        // after the first eager resolution (e.g. user's `interface Array<T> extends IFoo<T>`
        // merging with the built-in Array which was cached at init with no heritage).
        if (type.baseTypes == null) checker.resolveBaseTypesLazy(type)
        val members = symbolTable()
        // Static-side mirror of [members]. Step 1 (dual-population): every member
        // we add to [members] that carries ModifierFlag.Static is ALSO added here.
        // Behavior is unchanged at this step — consumers still read from [members].
        val staticMembers = symbolTable()
        val ownCallSignatures = mutableListOf<Signature>()
        val ownConstructSignatures = mutableListOf<Signature>()
        val inheritedCallSignatures = mutableListOf<Signature>()
        val inheritedConstructSignatures = mutableListOf<Signature>()
        var stringIndexInfo: IndexInfo? = null
        var numberIndexInfo: IndexInfo? = null

        // Inherit from base types
        val inheritedMemberNames = mutableSetOf<String>()
        type.baseTypes?.forEach { baseType ->
            if (baseType is Type.Object) {
                resolveStructuredTypeMembers(baseType)
                baseType.members?.forEach { (name, sym) ->
                    if (name !in members) {
                        members[name] = sym
                        inheritedMemberNames.add(name)
                    }
                }
                // Class-side inheritance for `class C extends B`: B's statics are
                // inherited as C's statics (mirrors how members inherit). Interface
                // base types have no statics, so `it.staticMembers` is null there.
                if (baseType is Type.Interface) {
                    baseType.staticMembers?.forEach { (name, sym) ->
                        if (name !in staticMembers) staticMembers[name] = sym
                    }
                }
                baseType.callSignatures?.let { inheritedCallSignatures.addAll(it) }
                baseType.constructSignatures?.let { inheritedConstructSignatures.addAll(it) }
                if (stringIndexInfo == null) stringIndexInfo = baseType.stringIndexInfo
                if (numberIndexInfo == null) numberIndexInfo = baseType.numberIndexInfo
            }
        }

        // Round 938 — (CHK.5)(b): the OWN property declarations already seen in this
        // symbol's declaration group, keyed by (staticness, member name). A DUPLICATE
        // property is FIRST-WINS: see the guard in the PropertyDeclaration arm below.
        val ownPropertyDecls = HashMap<String, PropertyDeclaration>()
        // Collect members from all declarations of this symbol
        for (decl in symbol.declarations) {
            val classMembers = when (decl) {
                is ClassDeclaration -> decl.members
                is InterfaceDeclaration -> decl.members
                is ClassExpression -> decl.members
                else -> continue
            }
            // B85.2: target-version member filter for builtin lib declarations.
            // When `decl` is a top-level statement of the embedded [BUILTIN_LIB_SOURCE]
            // AND a member's `(interfaceName, memberName)` is in [LIB_MIN_TARGET] with
            // a minimum target > the current `options.target`, drop that member. Aligns
            // lib member counts with TypeScript baselines for `@target: es2015` (e.g.
            // `arrayAssignmentTest*` "and 25 more" instead of "and 29 more").
            val ifaceName = symbol.name
            val isBuiltinDecl = decl in checker.builtinLibDecls
            for (member in classMembers) {
                if (isBuiltinDecl) {
                    val memberName = when (member) {
                        is PropertyDeclaration -> checker.getMemberName(member.name)
                        is MethodDeclaration -> checker.getMemberName(member.name)
                        is GetAccessor -> checker.getMemberName(member.name)
                        is SetAccessor -> checker.getMemberName(member.name)
                        else -> null
                    }
                    if (memberName != null) {
                        val minTarget = Checker.LIB_MIN_TARGET["$ifaceName.$memberName"]
                        if (minTarget != null && !checker.libFeatureAvailable(minTarget)) continue
                    }
                }
                when (member) {
                    is PropertyDeclaration -> {
                        val name = checker.declaredMemberName(member.name) ?: continue
                        // Round 938 — (CHK.5)(b): A DUPLICATE PROPERTY IS **FIRST-WINS**.
                        //
                        // `interface I { p: number; p: string }` (and the same in a class,
                        // in a type literal, and across two MERGED `interface I` blocks) is
                        // an error program in both compilers — but the type it leaves behind
                        // is observable independently of the diagnostic, and this map was
                        // LAST-WINS for every duplicate spelling, so `i.p` read `string`
                        // where tsc reads `number`. Measured at HEAD on nine scratch
                        // projects, and pristine tsc's own TS2717 text is the statement of
                        // the rule — `classWithDuplicateIdentifier`'s baseline says
                        // "Property 'c' must be of type 'number', but here has type
                        // 'string'", i.e. the FIRST declaration is the canonical one.
                        // tsc reaches it in the binder: `setValueDeclaration` replaces an
                        // existing `valueDeclaration` only for an ambient-vs-non-ambient,
                        // assignment-declaration or module-kind mismatch, so two same-kind
                        // property declarations leave the FIRST installed.
                        //
                        // Round 937 landed the declaration side of late-bound computed keys
                        // and thereby made `interface Dup { p: number; [K]: string }` a
                        // duplicate for the first time — it emitted a spurious TS2322 naming
                        // `string`. That diagnostic is this rule's, not a computed-key
                        // defect: the same TS2322 was already there for a plain `p; p`.
                        //
                        // **THE GUARD IS THREE-WAY NARROW, and each clause is load-bearing.**
                        //  - only against an OWN property of this declaration group, because
                        //    [members] is PRE-POPULATED with the base types' members above —
                        //    a class property that OVERRIDES an inherited one must still win,
                        //    and testing `members[name] != null` would silently delete every
                        //    override in the program;
                        //  - only against another PropertyDeclaration, so a property beside a
                        //    method or an accessor keeps exactly today's resolution (both are
                        //    already parity: `class C { p: number; p(): void }` reads `number`
                        //    in tsc and here);
                        //  - only at equal STATIC-ness, because a static and an instance member
                        //    of the same name are LEGAL and both live in this one map until the
                        //    [staticMembers] dual-population is consumed — first-wins across
                        //    that boundary would make `c.p` read the static's type.
                        val propKey = (if (ModifierFlag.Static in member.modifiers) "static:" else "") + name
                        if (ownPropertyDecls.put(propKey, member) != null) continue
                        val propSymbol = Symbol(SymbolFlags.Property, name)
                        propSymbol.declarations.add(member)
                        propSymbol.valueDeclaration = member
                        propSymbol.parent = symbol
                        members[name] = propSymbol
                        // Step 1 dual-population: mirror static members onto staticMembers
                        // while keeping them in [members] (no behavior change yet).
                        if (ModifierFlag.Static in member.modifiers) {
                            staticMembers[name] = propSymbol
                        }
                    }
                    is MethodDeclaration -> {
                        val name = checker.declaredMemberName(member.name) ?: continue
                        // Call signatures (empty name) and construct signatures ("new") are
                        // tracked as signatures, not as named properties.
                        if (name.isEmpty() || name == "new") {
                            // 17.153: Push the enclosing interface's typeParameters into scope
                            // so that `T` references in the call/construct signature's return
                            // type and parameter type annotations resolve to the interface's
                            // TypeParam (instead of falling through to errorType). Pre-resolve
                            // param types into [symbolTypes] so later instantiation through
                            // `getTypeOfSymbol(paramSym)` returns the in-scope-resolved type.
                            // Mirrors the named-method branch in `getTypeOfSymbolWorker`
                            // (line ~38876), but uses the enclosing interface's TPs since
                            // call/construct sigs don't declare their own typeParameters.
                            val ifaceTps = type.typeParameters
                            val sigOwnTps = member.typeParameters?.map { tp ->
                                val tpType = Type.TypeParam()
                                tpType.symbol = Symbol(SymbolFlags.TypeParameter, tp.name.text)
                                tpType
                            }
                            val sigScope = if (!ifaceTps.isNullOrEmpty() || !sigOwnTps.isNullOrEmpty()) {
                                val scope = (checker.currentTypeParamScope?.toMutableMap() ?: mutableMapOf())
                                ifaceTps?.forEach { tp -> tp.symbol?.name?.let { scope[it] = tp } }
                                sigOwnTps?.forEachIndexed { i, tp ->
                                    scope[member.typeParameters[i].name.text] = tp
                                }
                                scope
                            } else checker.currentTypeParamScope
                            val (returnType, paramSymbols, sigThisType) = checker.withInstantiationContext(checker.scopeMapper(sigScope)) {
                                // Resolve sig-own TP constraints/defaults under combined scope
                                sigOwnTps?.forEachIndexed { i, tp ->
                                    member.typeParameters[i].constraint?.let { tp.constraint = checker.getTypeFromTypeNode(it) }
                                    member.typeParameters[i].default?.let { tp.default = checker.getTypeFromTypeNode(it) }
                                }
                                val rt = member.type?.let { checker.getTypeFromTypeNode(it) } ?: anyType
                                val ps = checker.getParameterSymbols(member.parameters)
                                checker.resolveParameterTypesInScope(ps, member.parameters)
                                Triple(rt, ps, checker.declaredThisType(member.parameters))
                            }
                            val newSig = Signature(
                                declaration = member,
                                typeParameters = sigOwnTps,
                                parameters = paramSymbols,
                                resolvedReturnType = returnType,
                                minArgumentCount = checker.requiredParameterCount(member.parameters),
                                thisType = sigThisType,
                            )
                            if (name.isEmpty()) ownCallSignatures.add(newSig)
                            else ownConstructSignatures.add(newSig)
                            continue
                        }
                        // 16.4l: If the name came from a base type, this is an override —
                        // create a NEW symbol so we don't mutate the base's symbol's
                        // `declarations` list. Otherwise reuse the symbol to support
                        // overloaded methods declared on the same class.
                        val existingFromBase = name in inheritedMemberNames
                        val methodSymbol = if (existingFromBase) {
                            val fresh = Symbol(SymbolFlags.Property or SymbolFlags.Function, name)
                            members[name] = fresh
                            inheritedMemberNames.remove(name)
                            fresh
                        } else {
                            members.getOrPut(name) {
                                Symbol(SymbolFlags.Property or SymbolFlags.Function, name)
                            }
                        }
                        methodSymbol.declarations.add(member)
                        if (methodSymbol.valueDeclaration == null) {
                            methodSymbol.valueDeclaration = member
                        }
                        if (methodSymbol.parent == null) methodSymbol.parent = symbol
                        // Step 1 dual-population: static methods also live on staticMembers.
                        if (ModifierFlag.Static in member.modifiers) {
                            staticMembers[name] = methodSymbol
                        }
                    }
                    is Constructor -> {
                        val returnType = type as Type
                        val sig = Signature(
                            declaration = member,
                            parameters = checker.getParameterSymbols(member.parameters),
                            resolvedReturnType = returnType,
                            minArgumentCount = checker.requiredParameterCount(member.parameters),
                        )
                        ownConstructSignatures.add(sig)
                        // Also add parameter properties as members
                        for (param in member.parameters) {
                            if (param.modifiers.isNotEmpty() && param.name is Identifier) {
                                val paramName = (param.name).text
                                val propSymbol = Symbol(SymbolFlags.Property, paramName)
                                propSymbol.declarations.add(param)
                                propSymbol.valueDeclaration = param
                                propSymbol.parent = symbol
                                members[paramName] = propSymbol
                            }
                        }
                    }
                    is GetAccessor -> {
                        val name = checker.declaredMemberName(member.name) ?: continue
                        val existing = members[name]
                        val sym = if (existing != null) {
                            // B54.6: accessor-pair declaration merging — if a SetAccessor was
                            // bound first, append this GetAccessor's declaration to the same
                            // symbol so checkPropertyAccessAssignment / typeOfAccessor can find
                            // both. Without this, propSym.declarations carries only the first
                            // accessor, breaking write-context setter-param type extraction.
                            if (member !in existing.declarations) existing.declarations.add(member)
                            existing
                        } else {
                            Symbol(SymbolFlags.Property, name).also {
                                it.declarations.add(member)
                                it.valueDeclaration = member
                                it.parent = symbol
                                members[name] = it
                            }
                        }
                        if (ModifierFlag.Static in member.modifiers) {
                            staticMembers[name] = sym
                        }
                    }
                    is SetAccessor -> {
                        val name = checker.declaredMemberName(member.name) ?: continue
                        val existing = members[name]
                        val sym = if (existing != null) {
                            // B54.6: see comment in GetAccessor branch.
                            if (member !in existing.declarations) existing.declarations.add(member)
                            existing
                        } else {
                            Symbol(SymbolFlags.Property, name).also {
                                it.declarations.add(member)
                                it.valueDeclaration = member
                                it.parent = symbol
                                members[name] = it
                            }
                        }
                        if (ModifierFlag.Static in member.modifiers) {
                            staticMembers[name] = sym
                        }
                    }
                    is IndexSignature -> {
                        // 16.0n: Push this interface's type parameters into scope so that
                        // `[n: number]: T` resolves T to our TypeParam (allowing later
                        // instantiation via the reference mapper).
                        val idxTps = type.typeParameters
                        val idxScope = if (!idxTps.isNullOrEmpty()) {
                            val scope = mutableMapOf<String, Type.TypeParam>()
                            checker.currentTypeParamScope?.let { scope.putAll(it) }
                            for (tp in idxTps) tp.symbol?.name?.let { scope[it] = tp }
                            scope
                        } else checker.currentTypeParamScope
                        val (keyType, valueType) = checker.withInstantiationContext(checker.scopeMapper(idxScope)) {
                            val keyParam = member.parameters.firstOrNull()
                            val kt = keyParam?.type?.let { checker.getTypeFromTypeNode(it) } ?: stringType
                            val vt = member.type?.let { checker.getTypeFromTypeNode(it) } ?: anyType
                            kt to vt
                        }
                        val info = IndexInfo(keyType, valueType, member.modifiers.contains(ModifierFlag.Readonly), member)
                        if (keyType === numberType) {
                            numberIndexInfo = info
                        } else {
                            stringIndexInfo = info
                        }
                    }
                    is SemicolonClassElement, is ClassStaticBlockDeclaration -> { /* skip */ }
                }
            }
        }

        // Also include exports from the symbol (for namespace merges, etc.)
        // Also include exports from the symbol (for namespace merges, etc.)
        // Skip call signature ("") and construct signature ("new") members —
        // these are already tracked as signatures from the member loop above.
        symbol.members?.forEach { (name, sym) ->
            if (name.isEmpty() || name == "new") return@forEach // already handled in member loop
            if (name !in members) members[name] = sym
        }

        // B419: JS expando instance properties. A class in a `.js`/checkJs file
        // declares instance properties via `this.X = <expr>` in its constructor;
        // tsc synthesizes those as members (typed from the RHS) so external
        // `(new C()).X` access sees the property with its inferred type. Gated to
        // JS-file classes + the simple `this.<id> = expr` shape; explicit/inherited
        // members always win (synthesis only fills names not already present).
        if (symbol.declarations.any { it is ClassDeclaration && it in jsFileClassDecls() }) {
            val thisAssigns = LinkedHashMap<String, MutableList<Expression>>()
            val thisAssignJsDocTypes = LinkedHashMap<String, TypeNode>()
            for (decl in symbol.declarations) {
                if (decl !is ClassDeclaration || decl !in jsFileClassDecls()) continue
                val ctor = decl.members.filterIsInstance<Constructor>().firstOrNull() ?: continue
                ctor.body?.let { collectConstructorThisAssignments(it.statements, thisAssigns, thisAssignJsDocTypes) }
            }
            for ((name, rhsList) in thisAssigns) {
                if (name in members) continue
                // B419b: prefer a JSDoc `@type {T}`-declared type (resolving to a concrete,
                // non-error Type) over the RHS-inferred type; fall back to RHS inference.
                val jsdocType = thisAssignJsDocTypes[name]?.let { node ->
                    checker.getTypeFromTypeNode(node).takeIf { it !== errorType }
                }
                val propType = jsdocType ?: inferJsExpandoPropType(rhsList) ?: continue
                val propSym = Symbol(SymbolFlags.Property, name)
                propSym.parent = symbol
                symbolTypes[propSym.id] = propType
                members[name] = propSym
            }
        }

        type.members = members
        type.properties = members.values.toList()
        // Step 1 dual-population: staticMembers is the static-side mirror. Empty
        // staticMembers stays empty (not null) for class declarations so callers
        // can distinguish "no static side" (pure interface — null) from "class
        // without statics" (empty map). Only ClassDeclaration carries a static side.
        type.staticMembers = if (symbol.declarations.any { it is ClassDeclaration }) {
            staticMembers
        } else null
        // Inherited signatures first, then own — matches the implicit ordering before
        // call signatures were resolved from the member loop. getReturnTypeOfCallExpression
        // uses sigs[0], so inherited-first preserves backward compatibility.
        val callSignatures = inheritedCallSignatures + ownCallSignatures
        // 17.91: Constructor overload visibility — when a class declares both
        // body-less overload signatures AND body-having implementation(s), only
        // the overload signatures are externally visible (TypeScript convention).
        // Without this, `new Foo(...)` falls through to the impl sig (often
        // `(x: any)`) and silences TS2769 "no overload matches this call".
        // Construct signatures from interface `new(...)` members keep their
        // existing visibility (they're not Constructor decls).
        val ctorOverloads = ownConstructSignatures.filter {
            val d = it.declaration
            d is Constructor && d.body == null
        }
        val ctorImpls = ownConstructSignatures.filter {
            val d = it.declaration
            d is Constructor && d.body != null
        }
        val nonCtorConstructSigs = ownConstructSignatures.filter { it.declaration !is Constructor }
        val visibleCtorSigs = if (ctorOverloads.isNotEmpty() && ctorImpls.isNotEmpty()) {
            ctorOverloads
        } else {
            ctorOverloads + ctorImpls
        }
        val constructSignatures = inheritedConstructSignatures + visibleCtorSigs + nonCtorConstructSigs
        type.callSignatures = callSignatures.ifEmpty { null }
        type.constructSignatures = constructSignatures.ifEmpty { null }
        type.stringIndexInfo = stringIndexInfo
        type.numberIndexInfo = numberIndexInfo
    }

    /** Resolve members of a generic type reference by instantiating target members. */
    private fun resolveReferenceMembers(type: Type.Reference) {
        resolveStructuredTypeMembers(type.target)
        val typeParams = type.target.typeParameters
        val typeArgs = type.resolvedTypeArguments
        // If we have both type parameters and arguments, instantiate members with mapper
        if (typeParams != null && typeParams.isNotEmpty() && typeArgs != null && typeArgs.isNotEmpty()) {
            val mapper = createTypeMapper(typeParams, typeArgs)
            // Instantiate properties
            val targetProps = type.target.properties
            if (targetProps != null) {
                val newMembers = symbolTable()
                val newProps = mutableListOf<Symbol>()
                for (prop in targetProps) {
                    val propType = checker.getTypeOfSymbol(prop)
                    val instantiated = checker.instantiateType(propType, mapper)
                    if (instantiated !== propType) {
                        // Create new symbol with instantiated type
                        val newProp = Symbol(prop.flags, prop.name)
                        newProp.declarations.addAll(prop.declarations)
                        newProp.valueDeclaration = prop.valueDeclaration
                        symbolTypes[newProp.id] = instantiated
                        newProps.add(newProp)
                        newMembers[newProp.name] = newProp
                    } else {
                        newProps.add(prop)
                        newMembers[prop.name] = prop
                    }
                }
                type.properties = newProps
                type.members = newMembers
            } else {
                type.properties = type.target.properties
                type.members = type.target.members
            }
            // Instantiate call signatures
            type.callSignatures = type.target.callSignatures?.map { checker.instantiateSignature(it, mapper) }
            type.constructSignatures = type.target.constructSignatures?.map { checker.instantiateSignature(it, mapper) }
            // Instantiate index signatures
            type.stringIndexInfo = type.target.stringIndexInfo?.let {
                val instType = checker.instantiateType(it.type, mapper)
                if (instType !== it.type) IndexInfo(it.keyType, instType, it.isReadonly, it.declaration)
                else it
            }
            type.numberIndexInfo = type.target.numberIndexInfo?.let {
                val instType = checker.instantiateType(it.type, mapper)
                if (instType !== it.type) IndexInfo(it.keyType, instType, it.isReadonly, it.declaration)
                else it
            }
        } else {
            // No type parameters or args — just copy from target
            type.members = type.target.members
            type.properties = type.target.properties
            type.callSignatures = type.target.callSignatures
            type.constructSignatures = type.target.constructSignatures
            type.stringIndexInfo = type.target.stringIndexInfo
            type.numberIndexInfo = type.target.numberIndexInfo
        }
    }

    /** Resolve members of an anonymous object type (object literal type, function type, etc.). */
    private fun resolveAnonymousTypeMembers(type: Type.Object) {
        // For anonymous types created by getFunctionTypeFromNode etc., signatures are already set
        if (type.properties == null) {
            type.properties = type.members?.values?.toList() ?: emptyList()
        }
    }
}
