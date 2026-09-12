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
 * (INV.0) step 5 — the RELATION seam, tsgo's `checkTypeRelatedTo` family of
 * `internal/checker`: deciding whether one [Type] is assignable to, comparable
 * with or identical to another. Extracted VERBATIM from `Checker.kt` (design
 * § 6 Stage 0, "the relater" in the core order), as a final class constructed
 * once per [Checker] — no semantic change, and every call site that survives in
 * `Checker.kt` is a one-line delegation.
 *
 * ## What is here
 *
 * Phase 4's items 4a-4e, whole: the flag-only fast path [isSimpleTypeRelatedTo]
 * (4a); the entry point [checkTypeRelatedTo] with its `--passTiming` reentrance
 * probe and the recursive core [checkTypeRelatedToCore] under it — the depth
 * guard, the `(source.id, target.id)` cycle break, the deeply-nested bail keyed
 * on [relationSourceTargets] / [relationTargetTargets], the INV.5(d1) budget and
 * round 754's [defaultedInstantiationOfOpenGeneric] normalisation; the structural
 * dispatch [structuredTypeRelatedTo] (4b) with the union/intersection rules and
 * [intersectionSourceDistributes]; the object comparison [objectTypeRelatedTo]
 * (4c) and the property comparison [propertiesRelatedTo] (4d); the signature
 * comparison [signaturesRelatedTo] / [signatureRelatedTo] with the
 * bivariant-method rule [methodSignaturesBivariantlyRelated] (4e); and the
 * assignability shorthand [isTypeAssignableTo].
 *
 * The four fields whose ONLY readers were this algorithm moved with it: the probe
 * depth counter, the depth ceiling, the private-brand side channel and the
 * cycle-break flag — which `CheckerState` no longer declares, since it was
 * reached through a `Checker` accessor that is now gone.
 *
 * ## Ambient surface (the ledger row, `docs/inversion-ambient-ledger.md` § 7)
 *
 * The LARGEST ambient row of the arc so far, and stated rather than hidden:
 * **49** [Checker] members are touched — **45 READ** and **5 WRITTEN**, with
 * `relationDepth` in both columns — each reached through the FINAL [Checker]
 * class, a direct call with no interface and no captured lambda (contract § 10).
 *
 * The 45 reads are 40 functions plus 5 pieces of state:
 *
 *  - ENUM RELATION (16): `enumLiteralApparentPrimitive`,
 *    `enumMemberTypeIsStringValued`, `enumMemberTypesAreSameMember`,
 *    `enumMemberTypesOf`, `enumMemberValueEqualsLiteral`, `enumOfMemberTypeSymbol`,
 *    `enumOwnTypeSymbol`, `enumTargetAdmitsNumericSource`,
 *    `enumTargetsAreOwnMembers`, `enumTypesRelation`, `numericLiteralFitsEnum`,
 *    `isNumericEnumObjectType`, `isStringEnumObjectType`,
 *    `intersectionMergedSatisfiesTarget`, `intersectionMergedContradictsTarget`,
 *    `targetIsMemberShaped`;
 *  - MEMBER RESOLUTION (8): `getTypeOfSymbol`, `resolveStructuredTypeMembers`,
 *    `resolveBaseTypesLazy`, `getPropertyTypeForRelation`,
 *    `getStaticMembersOfType`, `getApparentType`, `primitiveApparentWrapper`,
 *    `isOptionalProperty`;
 *  - TYPE CONSTRUCTION (4): `getUnionType`, `getIntersectionType`,
 *    `getOrInternReference`, `instantiateType`;
 *  - PREDICATES and WIDENINGS (12): `isArrayLikeReference`, `isRestTupleMember`,
 *    `tupleRelationElementTypes`, `readonlyToMutableArrayLike`,
 *    `typeContainsUnresolvedTypeParam`, `typeIncludesUndefined`,
 *    `propTypeContainsLiteral`, `literalTypeOfExpression`,
 *    `isPropPrivateBrandMismatch`, `isLibPhantomMemberOfModuleInterface`,
 *    `widenOptionalSourcePropType`, `widenOptionalTargetPropType`;
 *  - STATE (5): `strictNullChecks`, `freshObjLitRange`, `globalArrayType`,
 *    `globalReadonlyArrayType`, `relationDepth`.
 *
 * The 5 writes split in two. `relationDepth` and `genericPropInstantiationBudget`
 * (INV.5(d1)'s per-top-level-relation budget, reached through a `Checker`
 * accessor onto `CheckerState`) are recursion bookkeeping. The other three —
 * `lastMissingPropertyName`, `lastMissingPropertySymbol` and
 * `lastMissingIndexSigKind` — are an ELABORATION RETURN CHANNEL: how the
 * TS2322/TS2345 path learns WHICH property the comparison failed on, which the
 * relation's `Boolean` result has no room to carry.
 *
 * The three containers this algorithm MUTATES ([relationComparisonStack],
 * [relationSourceTargets], [relationTargetTargets]) are handed in as the
 * OBJECTS — `CheckerState` still owns them, this class holds the same
 * references — as are the two [Relation] caches it consults.
 *
 * Two more things are read by NAME rather than through [checker]: four
 * `Checker.companion` name sets (`OBJECT_PROTOTYPE_PROPERTIES`,
 * `WRAPPER_INTERFACE_NAMES`, `FUNCTION_PROTOTYPE_METHODS`,
 * `FUNCTION_RUNTIME_PROPERTIES`), and the file-private (REL.2) ablation switch
 * `REL2_ENUM_TO_MEMBER` in `Checker.kt`, which became `internal` so it stays
 * readable from here — and stays a switch that flipping to `false` exercises.
 *
 * ## What a later stage must make explicit
 *
 * The ambient row gets better as a family completes (row 6's lesson), and this
 * one's cheapest next step is MEASURED rather than assumed: of the 48 members in
 * the read/write set, exactly **seven** have no other user left in `Checker.kt`
 * — `enumLiteralApparentPrimitive`, `enumMemberValueEqualsLiteral`,
 * `enumTargetAdmitsNumericSource`, `numericLiteralFitsEnum`,
 * `intersectionMergedSatisfiesTarget`, `intersectionMergedContradictsTarget` and
 * `targetIsMemberShaped` — so absorbing them is mechanical and takes the reads
 * from 45 to 38 with no design decision at all. The rest of the ENUM-RELATION
 * group is genuinely SHARED (`enumTypesRelation` alone has three other callers,
 * `enumOfMemberTypeSymbol` eight), so it belongs to an enum seam of its own, not
 * to this one — the draft of this KDoc claimed all sixteen were absorbable and a
 * caller census refuted it.
 *
 * The three elaboration WRITES are the opposite kind of debt: they are a RETURN
 * CHANNEL, and paying them means giving the relation a result richer than
 * `Boolean` (tsc's own `errorInfo` chain), which is a semantic change and
 * belongs to a later stage, not to Stage 0. The MEMBER-RESOLUTION group is the
 * seam design § 6 calls "member resolution" and will be a collaborator of its
 * own; once it is, the TYPE-CONSTRUCTION group is all that is left, and rows 1
 * and 3 already own half of it.
 */
internal class Relater(
    private val checker: Checker,
    /**
     * (INV.0) step 7 — the ENUM collaborator, wired DIRECTLY rather than through
     * [Checker]. Eleven of this class's ambient reads were enum questions; routing
     * them here is what takes the row from 45 to 34. Safe by construction order:
     * `Checker` builds `EnumSemantics` before `Relater`.
     */
    private val enumSemantics: EnumSemantics,
    private val assignableRelation: Relation,
    private val identityRelation: Relation,
    /**
     * `CheckerState.relationComparisonStack` — handed in as the OBJECT, so the
     * (source.id, target.id) pairs pushed here are the same pairs every other
     * reader of that field sees. `CheckerState` keeps owning it.
     */
    private val relationComparisonStack: HashSet<Long>,
    /** `CheckerState.relationSourceTargets` — handed in as the OBJECT; see above. */
    private val relationSourceTargets: ArrayList<Int>,
    /** `CheckerState.relationTargetTargets` — handed in as the OBJECT; see above. */
    private val relationTargetTargets: ArrayList<Int>,
) {

    // (f2) round 598 probes: depth-0 reentrance guards for the time split.
    private var relProbeDepth = 0

    private val maxRelationDepth = 100

    /** Tracks whether the current comparison used any cycle-break assumptions. */
    private var relationUsedCycleBreak = false

    /** Tracks private-brand mismatch: source and target both declare a private property
     *  of the given name, but on different parent classes. Consumed by the TS2322/TS2345
     *  elaboration path to produce "Types have separate declarations of a private property 'X'." */
    private var lastPrivateBrandMismatchName: String? = null

    /**
     * (INV.0) step 5 — the recursion RESIDUE, the one thing about this seam only a
     * test at this level can state (`RelaterTest`).
     *
     * Every reaching [checkTypeRelatedToCore] pushes onto the three comparison stacks
     * and onto [Checker.relationDepth], and pops them in a `finally` — B202.3's
     * finally-hygiene, which exists so that a `StackOverflowError` escaping
     * [structuredTypeRelatedTo] cannot leave a stale pair key behind. A stale key does
     * not fail: it makes every LATER comparison of that same pair answer `true` by the
     * cycle break, i.e. it deletes diagnostics silently, in whatever file happens to be
     * checked next. Nothing else in this repo can see that — no corpus baseline, no
     * `cost_gate.py` counter, no `--listAll` diff — so the invariant is asserted
     * directly: after a whole-program check, every stack is empty and both depth
     * counters are back at zero.
     *
     * [Checker.relationDepth] is deliberately part of the sum. It is the ONE piece of
     * recursion bookkeeping this class does not own, because
     * `Checker.resolveGenericPropertyType` gates INV.5(d1)'s 2,000-computation budget on
     * `relationDepth > 0`; a future edit that "tidies" it into a private field here
     * would leave that gate reading 0 forever and re-open the deep-generic blowup the
     * budget exists to bound.
     */
    internal val recursionResidue: Int
        get() = relationComparisonStack.size + relationSourceTargets.size +
            relationTargetTargets.size + relProbeDepth + checker.relationDepth

    /**
     * 4a. Fast flag-based type relatedness check (no recursion).
     * Returns true if the relation holds purely from type flags.
     */
    fun isSimpleTypeRelatedTo(source: Type, target: Type): Boolean {
        val sf = source.flags
        val tf = target.flags
        // Any target accepts everything
        if (tf.hasAny(TypeFlags.Any)) return true
        // Any source is assignable to everything (any is both top and bottom type)
        if (sf.hasAny(TypeFlags.Any)) return true
        // Unknown target accepts everything (for assignability)
        if (tf.hasAny(TypeFlags.Unknown)) return true
        // Never source is assignable to everything
        if (sf.hasAny(TypeFlags.Never)) return true
        // Same type by identity
        if (source === target) return true
        // Literal value comparison (different instances with same value)
        if (source is Type.StringLiteral && target is Type.StringLiteral && source.value == target.value) return true
        if (source is Type.NumberLiteral && target is Type.NumberLiteral && source.value == target.value) return true
        if (source is Type.BigIntLiteral && target is Type.BigIntLiteral && source.value == target.value) return true
        // Primitive widening: string literal → string, number literal → number, etc.
        if (sf.hasAny(TypeFlags.StringLiteral) && tf.hasAny(TypeFlags.String)) return true
        if (sf.hasAny(TypeFlags.NumberLiteral) && tf.hasAny(TypeFlags.Number)) return true
        if (sf.hasAny(TypeFlags.BigIntLiteral) && tf.hasAny(TypeFlags.BigInt)) return true
        if (sf.hasAny(TypeFlags.BooleanLiteral) && tf.hasAny(TypeFlags.Boolean)) return true
        // (REL.1)(c) round 746: an enum MEMBER widens to an enum — but only to an enum
        // its OWN enum relates to. Unconditional, this made `Z.Foo.A` assignable to
        // `X.Foo`, which is the verdict B266 existed to supply.
        if (sf.hasAny(TypeFlags.EnumLiteral) && tf.hasAny(TypeFlags.Enum)) {
            val sourceEnum = enumSemantics.enumOfMemberTypeSymbol(source)
            val targetEnum = enumSemantics.enumOwnTypeSymbol(target)
            if (sourceEnum == null || targetEnum == null) return true
            if (enumSemantics.enumTypesRelation(sourceEnum, targetEnum) == null) return true
        }
        if (sf.hasAny(TypeFlags.UniqueESSymbol) && tf.hasAny(TypeFlags.ESSymbol)) return true
        // String-like types to string
        if (sf.hasAny(TypeFlags.StringLike) && tf.hasAny(TypeFlags.String)) return true
        if (sf.hasAny(TypeFlags.NumberLike) && tf.hasAny(TypeFlags.Number)) return true
        if (sf.hasAny(TypeFlags.BigIntLike) && tf.hasAny(TypeFlags.BigInt)) return true
        // undefined → void
        if (sf.hasAny(TypeFlags.Undefined) && tf.hasAny(TypeFlags.Void)) return true
        // void → undefined (void is treated as undefined for assignability)
        if (sf.hasAny(TypeFlags.Void) && tf.hasAny(TypeFlags.Undefined)) return true
        // When strict null checks are off, null/undefined assignable to everything
        if (!checker.strictNullChecks && sf.hasAny(TypeFlags.Null or TypeFlags.Undefined)) return true
        // number → enum type (TypeScript allows number → enum)
        //
        // (REL.1)(c) round 745: VALUE-AWARE for a numeric LITERAL source. tsc models
        // an all-literal enum as the UNION of its members' literal types, so `4` is
        // assignable to `E` only when some member IS 4; the wide `number` stays
        // assignable either way (the bit-flag rule). [numericLiteralFitsEnum] answers
        // `true` for every non-literal source and for every enum whose domain we
        // cannot fully evaluate, so this can only reject a numeric literal we have
        // proved out of range.
        if (sf.hasAny(TypeFlags.NumberLike) && target is Type.Object && target.symbol != null &&
            target.symbol!!.flags.hasAny(SymbolFlags.Enum)) {
            // (CHK.113)(a): and FLAVOUR-AWARE for the wide `number` too — a pure STRING
            // enum has no numeric constituent to reach, so it is not "any enum" that a
            // number may be assigned to.
            if (enumSemantics.enumTargetAdmitsNumericSource(target) && enumSemantics.numericLiteralFitsEnum(source, target)) return true
        }
        // M3.1 (round 428b): numeric-enum → number (a numeric enum's values ARE
        // numbers — tsc debug.ts `formatEnum(this.flags, …)` where flags: FlowFlags
        // vs a `number` param). String-valued enums excluded by the classifier.
        if (tf.hasAny(TypeFlags.Number) && checker.isNumericEnumObjectType(source)) return true
        // M3.1 (round 429d): string-enum → string, the string sibling (an ALL-string-
        // valued enum is a union of string literals in tsc — `changeAnyExtension(path,
        // Extension.Dts)` vs a `string` param; also cascades to `Extension[]` →
        // `string[]` via the same-target covariant element comparison). Mixed/numeric/
        // unknown-valued enums excluded (conservative: unevaluated → not provable).
        if (tf.hasAny(TypeFlags.String) && checker.isStringEnumObjectType(source)) return true
        // (REL.1)(a) round 741: an enum MEMBER type ↔ its BASE PRIMITIVE, in BOTH
        // directions. This is the whole measured gap of step (a): `SK.A` used to be
        // `anyType`, which related to everything, so every primitive answer around an
        // enum-member annotation was vacuously true. A numeric member relates to and
        // from NumberLike (tsc keeps that both-ways rule for bit-flag enums), a
        // string-valued member to and from StringLike.
        //
        // VALUE-AWARE since (REL.1)(c) round 745, which is what retired
        // `checkEnumLiteralAssignments`: `let a: E.A = 2` where `A === 0` is the
        // relation's error now. Only a numeric LITERAL is judged by value — the wide
        // `number` stays assignable to any numeric member, which is why `a = n`
        // (n: number) is legal in the same test.
        //
        // (REL.1)(b) round 742 REMOVED the string TARGET half. Step (a) had it only for
        // behaviour-preservation symmetry with the former `anyType`; tsc REJECTS
        // `string → Ext.Dts`, because a string enum is NOMINAL and — unlike the numeric
        // direction, which tsc keeps for bit-flag enums — there is no compatibility rule
        // to justify it.
        if (sf.hasAny(TypeFlags.EnumLiteral) || tf.hasAny(TypeFlags.EnumLiteral)) {
            enumSemantics.enumMemberTypeIsStringValued(source)?.let { sourceIsString ->
                if (tf.hasAny(if (sourceIsString) TypeFlags.StringLike else TypeFlags.NumberLike)) {
                    // (CHK.83): a LITERAL target is not the wide primitive. tsc relates an
                    // enum member to a string/number literal ONLY by value —
                    // `s & NumberLiteral && s & EnumLiteral && t & NumberLiteral &&
                    // !(t & EnumLiteral) && s.value === t.value` (and the string twin) —
                    // so `E.A` (`A = 1`) reaches `1` and not `5`, and a member whose value
                    // tsc does not know (a computed or ambient-opaque member) reaches NO
                    // literal at all. `NumberLike` includes `NumberLiteral`, which is how
                    // `const l1: 5 = em` was silent at every position while `const l3: 5 =
                    // ew` (the WHOLE enum) reported. Falling through here is a rejection:
                    // nothing below relates a member-less enum object to a literal.
                    if (target !is Type.StringLiteral && target !is Type.NumberLiteral) return true
                    if (enumSemantics.enumMemberValueEqualsLiteral(source, target)) return true
                }
            }
            enumSemantics.enumMemberTypeIsStringValued(target)?.let { targetIsString ->
                if (!targetIsString && sf.hasAny(TypeFlags.NumberLike) &&
                    enumSemantics.numericLiteralFitsEnum(source, target)
                ) return true
            }
        }
        // The `object` keyword (round 834, (NONPRIM.1)). tsc's rule is POSITIVE —
        // `s & TypeFlags.Object && t & TypeFlags.NonPrimitive` — i.e. the ONLY sources
        // that satisfy `object` are the ones carrying the Object flag. This used to be
        // written as its NEGATION ("not flagged primitive"), which is not the same
        // predicate, because [TypeFlags.Primitive] omits every LITERAL bit: a
        // `Type.StringLiteral`/`NumberLiteral`/boolean-literal source, a bare
        // `Type.TypeParam`, `unknown`, and `keyof`/indexed-access types all read as
        // "not primitive" and were silently accepted. That is what made
        // `{ foo: "bar" }` satisfy `{ foo: object }`'s value position and — one level
        // out — made the conditional `T[P] extends V | object` answer TRUE for a
        // numeric-literal `T[P]`, inventing a whole FP one type away from any `object`.
        //
        // A `false` here is NOT a rejection: [checkTypeRelatedToCore] falls through to
        // the structural engine, where the Union/Intersection source branches decompose
        // and [structuredTypeRelatedTo]'s TypeParam leg consults a constraint. Only
        // shapes that reach neither end up rejected.
        if (tf.hasAny(TypeFlags.NonPrimitive)) {
            return sf.hasAny(TypeFlags.Object or TypeFlags.NonPrimitive)
        }
        return false
    }

    /**
     * 4b. Main entry point for type relation checking with error reporting.
     * Returns true if source is related to target in the given relation.
     */
    fun checkTypeRelatedTo(
        source: Type,
        target: Type,
        relation: Relation,
    ): Boolean {
        if (PassTiming.detailed) {
            if (relProbeDepth++ == 0) {
                val t0 = PassTiming.nowNanos()
                try { return checkTypeRelatedToCore(source, target, relation) }
                finally { PassTiming.relationNanos += PassTiming.nowNanos() - t0; relProbeDepth-- }
            }
            try { return checkTypeRelatedToCore(source, target, relation) } finally { relProbeDepth-- }
        }
        return checkTypeRelatedToCore(source, target, relation)
    }

    /**
     * Round 754: the DEFAULTED INSTANTIATION of an open generic that reaches the
     * relation raw, or null when [type] is not one.
     *
     * A reference that omits the type arguments of a generic whose every parameter
     * carries a default — `EvaluatorResult` for `EvaluatorResult<T extends string |
     * number | undefined = string | number | undefined>` — resolves to the RAW
     * `Type.Interface`, whose members still carry the un-substituted `T`. Nothing
     * relates a `Type.Reference` to that (the trap round 726 hit from the other
     * side), so `EvaluatorResult<number>` was not assignable to a bare
     * `EvaluatorResult` annotation and TS2322 fired on correct code — 16 of them in
     * tsc's own `utilities.ts`.
     *
     * The normalisation is done HERE and not in `getTypeFromTypeReference` (where tsc
     * does it, as `fillMissingTypeArguments`) for one measured reason: filling at
     * resolution makes a bare `TableClass` and an explicit `TableClass<any>` the SAME
     * interned instance, and `aliasDisplayMap` excludes `Type.Reference` on purpose —
     * so `type Table = TableClass` stopped displaying as `Table`
     * (`typeVariableConstraintedToAliasNotAssignableToUnion`, 8 baseline lines, error
     * set otherwise unchanged). At the relation boundary the answer changes and no
     * display does.
     *
     * A parameter WITHOUT a resolved default returns null, which keeps the old
     * behaviour: omitting a REQUIRED type argument is TS2314, reported on its own.
     */
    private fun defaultedInstantiationOfOpenGeneric(type: Type): Type? {
        if (type !is Type.Interface) return null // Type.Reference is NOT an Interface
        val tps = type.typeParameters ?: return null
        if (tps.isEmpty()) return null
        val args = ArrayList<Type>(tps.size)
        for (tp in tps) {
            val d = tp.default ?: return null
            if (d === errorType) return null
            args.add(d)
        }
        return checker.getOrInternReference(type, args)
    }

    private fun checkTypeRelatedToCore(
        source: Type,
        target: Type,
        relation: Relation,
    ): Boolean {
        if (source === target) return true
        // Round 754: normalise an open all-defaulted generic to its defaulted
        // instantiation before anything else looks at it — see
        // [defaultedInstantiationOfOpenGeneric]. The recursion terminates because a
        // `Type.Reference` is not a `Type.Interface`, so the normalisation never
        // fires twice on the same side.
        run {
            val ns = defaultedInstantiationOfOpenGeneric(source)
            val nt = defaultedInstantiationOfOpenGeneric(target)
            if (ns != null || nt != null) {
                return checkTypeRelatedToCore(ns ?: source, nt ?: target, relation)
            }
        }
        // Fast check
        if (isSimpleTypeRelatedTo(source, target)) return true
        // (REL.1)(b): two enum-member types relate ONLY when they are the same
        // member. Both are member-less `Type.Object`s, so without this the structural
        // engine below relates them VACUOUSLY in both directions — which is what made
        // two AST node interfaces differing only in `readonly kind: SK.A` vs `SK.B`
        // mutually assignable. The verdict is STRUCTURAL, not identity: see
        // [enumMemberTypesAreSameMember].
        if (source.flags.hasAny(TypeFlags.EnumLiteral) && target.flags.hasAny(TypeFlags.EnumLiteral)) {
            return enumSemantics.enumMemberTypesAreSameMember(source, target)
        }
        // (REL.1)(c) round 746: two ENUMS, and an enum MEMBER against an enum, relate by
        // tsc's `isEnumTypeRelatedTo` — every source member present in the target with an
        // equal value. BOTH verdicts have to be reached HERE and not in
        // [isSimpleTypeRelatedTo]: every enum-flavored type is a member-less
        // `Type.Object`, so a `false` there merely falls through to the structural engine,
        // which relates two empty objects VACUOUSLY. That vacuous `true` is why
        // `checkEnumToEnumAssignments` (B425) owned all 12 of `enumAssignmentCompat3` and
        // `checkNamespaceEnumUnionAssignments` (B266) the two whole-enum targets of
        // `enumLiteralAssignableToEnumInsideUnion`.
        //
        // (REL.2) round 783: the enum → MEMBER direction, decided HERE for the same reason
        // as the two above. tsc models a literal enum AS the union of its members, so `K`
        // is assignable to a target built out of K's members exactly when those members
        // COVER K — which rejects `K -> K.A` for a multi-member enum and keeps the
        // one-member and fully-covering cases. Contained to a target that is entirely
        // members of THIS enum ([enumTargetsAreOwnMembers], the round-746 owner rule):
        // anything else falls through to the pre-existing answer.
        if (REL2_ENUM_TO_MEMBER) run {
            enumSemantics.enumOwnTypeSymbol(source) ?: return@run
            val targets = if (target is Type.Union) target.types else listOf(target)
            if (targets.any { enumSemantics.enumOfMemberTypeSymbol(it) == null }) return@run
            if (!enumSemantics.enumTargetsAreOwnMembers(source, targets)) return@run
            // A non-decomposable enum cannot be shown to be covered, and an enum whose
            // domain is unknown is not a subtype of a proper subset of it.
            val members = enumSemantics.enumMemberTypesOf(source) ?: return false
            return members.all { m -> targets.any { enumSemantics.enumMemberTypesAreSameMember(m, it) } }
        }
        run {
            val targetEnum = enumSemantics.enumOwnTypeSymbol(target) ?: return@run
            val sourceEnum = enumSemantics.enumOwnTypeSymbol(source) ?: enumSemantics.enumOfMemberTypeSymbol(source) ?: return@run
            return enumSemantics.enumTypesRelation(sourceEnum, targetEnum) == null
        }
        // Check cache
        val cached = relation.get(source.id, target.id)
        if (cached == Ternary.True) return true
        if (cached == Ternary.False) return false
        // Depth limit (safety net)
        if (checker.relationDepth >= maxRelationDepth) return false
        // Cycle detection: if we're already comparing this (source, target) pair,
        // assume compatibility to break the cycle (TypeScript's approach for recursive types).
        val pairKey = packRelationKey(source.id, target.id)
        // (WARM.31) round 904 — three boxed-`Long` set operations per reaching
        // call (contains here, add below, remove in the `finally`), plus the two
        // `countOccurrences` scans over the boxed-`Int` target stacks.
        if (MapCensus.boxedKeyCensus) MapCensus.bk(MapCensus.BK_REL_STACK, pairKey)
        if (pairKey in relationComparisonStack) {
            relationUsedCycleBreak = true
            return true
        }
        // Deeply-nested heuristic (matches TypeScript's `isDeeplyNestedType`): when
        // either side's `target.id` already appears 5+ times on its respective stack,
        // assume compatibility. Catches infinitely-expanding generic comparisons like
        // `A<T> { x: A<()=>T> }` vs `B<T> { x: B<()=>T> }` whose Refs grow unboundedly
        // and never re-occur identically (so id-based cycle detection alone never fires).
        val srcRef = source as? Type.Reference
        val tgtRef = target as? Type.Reference
        if (srcRef != null && countOccurrences(relationSourceTargets, srcRef.target.id) >= 5) {
            relationUsedCycleBreak = true
            return true
        }
        if (tgtRef != null && countOccurrences(relationTargetTargets, tgtRef.target.id) >= 5) {
            relationUsedCycleBreak = true
            return true
        }
        if (MapCensus.boxedKeyCensus) {
            MapCensus.bk(MapCensus.BK_REL_STACK, pairKey)
            MapCensus.bkPush(MapCensus.BK_REL_STACK)
        }
        relationComparisonStack.add(pairKey)
        if (srcRef != null) relationSourceTargets.add(srcRef.target.id)
        if (tgtRef != null) relationTargetTargets.add(tgtRef.target.id)
        if (MapCensus.boxedKeyCensus) {
            if (srcRef != null) {
                MapCensus.bk(MapCensus.BK_REL_TARGETS, srcRef.target.id.toLong())
                MapCensus.bkPush(MapCensus.BK_REL_TARGETS)
            }
            if (tgtRef != null) {
                MapCensus.bk(MapCensus.BK_REL_TARGETS, tgtRef.target.id.toLong())
                MapCensus.bkPush(MapCensus.BK_REL_TARGETS)
            }
        }
        if (checker.relationDepth == 0) checker.genericPropInstantiationBudget = 2_000
        checker.relationDepth++
        val savedCycleBreak = relationUsedCycleBreak
        relationUsedCycleBreak = false
        // B202.3 (finally-hygiene): unwind the relation stacks even when a
        // StackOverflowError propagates out of structuredTypeRelatedTo — without
        // this, the init boundary guard catches the SOE but the stacks stay
        // corrupted (stale pair keys suppress every later comparison of the same pair).
        val result: Boolean
        val usedCycle: Boolean
        try {
            result = structuredTypeRelatedTo(source, target, relation)
            usedCycle = relationUsedCycleBreak
        } finally {
            relationUsedCycleBreak = savedCycleBreak || relationUsedCycleBreak
            checker.relationDepth--
            if (MapCensus.boxedKeyCensus) {
                MapCensus.bk(MapCensus.BK_REL_STACK, pairKey)
                MapCensus.bkPop(MapCensus.BK_REL_STACK)
                if (tgtRef != null) MapCensus.bkPop(MapCensus.BK_REL_TARGETS)
                if (srcRef != null) MapCensus.bkPop(MapCensus.BK_REL_TARGETS)
            }
            if (tgtRef != null) relationTargetTargets.removeAt(relationTargetTargets.lastIndex)
            if (srcRef != null) relationSourceTargets.removeAt(relationSourceTargets.lastIndex)
            relationComparisonStack.remove(pairKey)
        }
        // Only cache "true" results that were NOT influenced by cycle assumptions.
        // A "true" result from cycle detection is speculative — it may be wrong
        // once the full comparison completes (typeComparisonCaching test verifies this).
        // "false" results are always safe to cache — if it failed even with the
        // cycle break giving it the benefit of the doubt, it's genuinely incompatible.
        if (!result || !usedCycle) {
            val ternary = if (result) Ternary.True else Ternary.False
            relation.set(source.id, target.id, ternary)
        }
        return result
    }

    /** (HASH.1)(b) round 890: see [packIdPair]. Load-bearing for
     *  [CheckerState.resolvedPropertyTypes] (10,482 keys, max bucket 10 -> 6);
     *  the three recursion stacks it also keys hold at most 27 entries at once
     *  (measured), so for them the finalizer is free rather than a saving. */
    fun packRelationKey(a: Int, b: Int): Long = packIdPair(a, b)

    private fun countOccurrences(stack: List<Int>, id: Int): Int {
        // (WARM.31) round 904 — each element visit UNBOXES an `Integer`; that, not
        // the add/remove, is where an `ArrayList<Int>` costs more than an `IntArray`,
        // so the scan STEPS are what the census counts here.
        if (MapCensus.boxedKeyCensus) MapCensus.bkScan(MapCensus.BK_REL_TARGETS, stack.size)
        var n = 0
        for (e in stack) if (e == id) n++
        return n
    }

    /**
     * Round 744: `A & (B | C)` DENOTES `(A & B) | (A & C)`, so it relates to a target
     * exactly when BOTH distributed members do. We store the intersection
     * UN-distributed, and every rule below then asks the WHOLE intersection against one
     * union member at a time — `A & (B | C)` against `B` fails, because neither `A → B`
     * nor `B | C → B` holds — so an intersection-over-union source related to nothing.
     *
     * It stayed invisible because the two sides are usually the SAME interned instance
     * (the `source === target` fast path). It shows the moment the union constituents
     * are written in a DIFFERENT ORDER on the two sides — which is exactly how tsc's
     * `getNameOrArgument` writes them (utilities.ts:4175: an
     * `ElementAccessExpression & { argumentExpression: StringLiteralLike | NumericLiteral }`
     * member returned into `MemberName | (Expression & (NumericLiteral | StringLiteralLike))`).
     * Interning a union by SORTED constituent ids would also hide it, and must not be
     * done: union display order is pinned to pristine tsc's source order.
     *
     * Strictly a FALLBACK — consulted only after the plain rule has already answered
     * false, so it can only turn a rejection into an acceptance. Distributes the FIRST
     * union constituent only, and bails on a wide one: the shapes this exists for carry
     * a single small union, and a full cartesian product is not worth paying for on a
     * path that has already failed.
     */
    private fun intersectionSourceDistributes(
        source: Type.Intersection,
        target: Type,
        relation: Relation,
    ): Boolean {
        val unionIndex = source.types.indexOfFirst { it is Type.Union }
        if (unionIndex < 0) return false
        val union = source.types[unionIndex] as Type.Union
        if (union.types.size > 8) return false
        val rest = source.types.filterIndexed { index, _ -> index != unionIndex }
        return union.types.all { member ->
            val distributed = checker.getIntersectionType(rest + member)
            distributed !== source && checkTypeRelatedTo(distributed, target, relation)
        }
    }

    /**
     * 4c. Core structural comparison — handles unions, intersections, and objects.
     */
    private fun structuredTypeRelatedTo(
        source: Type,
        target: Type,
        relation: Relation,
    ): Boolean {
        // Union source: each constituent must be related to the target
        if (source is Type.Union) {
            return source.types.all { checkTypeRelatedTo(it, target, relation) }
        }
        // Union target: source must be related to some constituent
        if (target is Type.Union) {
            // Round 435e: the (source, union) frame's own source-stack entry is
            // REDUNDANT with each member call's re-push of the SAME source instance —
            // pop it around the member iteration so a union decomposition doesn't
            // read as a re-entry to the same-target arg-shortcut below
            // (`NodeArray<TemplateSpan>` vs `NodeArray<Node> | undefined` was
            // deferred into structural comparison, which FPs on Array-method
            // contravariance — tsc's getContainingNodeArray family ×23). A genuine
            // member-recursion re-entry (recursiveTypeComparison's Observable
            // reached through needThisOne) has TWO distinct frames and still defers.
            if (source is Type.Reference) {
                relationSourceTargets.removeAt(relationSourceTargets.lastIndex)
                try {
                    return target.types.any { checkTypeRelatedTo(source, it, relation) }
                } finally {
                    relationSourceTargets.add(source.target.id)
                }
            }
            if (target.types.any { checkTypeRelatedTo(source, it, relation) }) return true
            // Round 744: `A & (B | C)` vs `B | C` — see [intersectionSourceDistributes].
            return source is Type.Intersection &&
                intersectionSourceDistributes(source, target, relation)
        }
        // Intersection target: source must be related to each constituent
        if (target is Type.Intersection) {
            return target.types.all { checkTypeRelatedTo(source, it, relation) }
        }
        // Intersection source: some constituent must be related to target.
        // BUT the "any constituent relates" shortcut is too lenient — tsc merges the
        // intersection's members and checks them structurally. A constituent can satisfy
        // the target while ANOTHER constituent contributes a PRESENT property whose type
        // contradicts the target (intersectionsAndOptionalProperties: `{a:null} & {b:string}`
        // relates via `{b:string}`, but the merged `a:null` ⊄ `number | undefined`). Catch
        // that case: a property present in the merged intersection that fails the target is
        // a genuine mismatch. Bounded to a plain (non-tuple) object target to keep the FP
        // surface minimal (tuple/array targets keep the prior behavior).
        if (source is Type.Intersection) {
            if (target is Type.Object && target.tupleElementTypes == null &&
                checker.intersectionMergedContradictsTarget(source, target, relation)) {
                return false
            }
            if (source.types.any { checkTypeRelatedTo(it, target, relation) }) return true
            // (CHK.61)(1) PROBE: the symmetric ACCEPTANCE twin.
            if (target is Type.Object && target.tupleElementTypes == null &&
                checker.intersectionMergedSatisfiesTarget(source, target, relation)) {
                return true
            }
            // Round 744: `A & (B | C)` vs anything — see [intersectionSourceDistributes].
            return intersectionSourceDistributes(source, target, relation)
        }
        // Generic type references with matching target: compare type arguments directly.
        // For Ref<A> vs Ref<B> (same target), check A → B instead of full structural
        // comparison. Structural comparison would otherwise pass trivially because our
        // built-in lib methods resolve to anyType and property types containing type
        // parameters resolve to errorType when the param isn't in scope. Covariant
        // arg comparison (each source arg assignable to the matching target arg) matches
        // Array's existing behavior and is the pragmatic default for built-in generic
        // containers (Array, Map, Promise, etc.). Cycle detection for self-referential
        // generics like `interface List<T> { next: List<T> }` is handled by
        // `relationComparisonStack` in `checkTypeRelatedTo`.
        //
        // Skip the shortcut when any target arg is `void`: the void-return-type rule
        // (a signature returning T is assignable to one returning void) applies
        // transitively when a type arg appears in a covariant return position inside
        // the generic (e.g. `B<T> { x(): T }` — `B<number>` should be assignable to
        // `B<void>`). We don't have variance info, so fall back to structural
        // comparison in this case — it preserves the prior trivial-pass behavior.
        if (source is Type.Reference && target is Type.Reference &&
            source.target === target.target) {
            val sourceArgs = source.resolvedTypeArguments
            val targetArgs = target.resolvedTypeArguments
            if (sourceArgs != null && targetArgs != null && sourceArgs.size == targetArgs.size &&
                targetArgs.none { it.flags.hasAny(TypeFlags.Void) }) {
                // Skip the arg-shortcut on re-entry: when this target already appears
                // earlier on the comparison stacks, an eager false here would prevent
                // structural recursion from reaching the cycle-break or deeply-nested
                // bail-out (regresses recursiveTypeComparison, infinitelyExpandingTypes,
                // etc. — Observable<{}> vs Observable<number> reached via propertiesRelatedTo
                // must defer to structural so the inner needThisOne self-reference cycle-breaks).
                // count > 1 because the current pair was already pushed by checkTypeRelatedTo.
                // Round 435e: a UNION-target decomposition frame is TRANSPARENT on the
                // source stack (see the Union-target branch above), so reaching here
                // through `NodeArray<Node> | undefined` counts as a single frame and
                // does NOT defer; the recursiveTypeComparison member-recursion pin
                // (source-side-only repeat through needThisOne) still does.
                val tid = source.target.id
                val isReentry = countOccurrences(relationSourceTargets, tid) > 1 ||
                    countOccurrences(relationTargetTargets, tid) > 1
                // Round 446: Array / ReadonlyArray are covariant containers whose
                // STRUCTURAL comparison spuriously FAILS (their `concat` signature takes a
                // contravariant element param `ConcatArray<T>`, and we have no per-TP
                // variance info), so they must ALWAYS use the covariant element shortcut.
                // The isReentry deferral misfired for the extremely common nested shape
                // `Array<X>` inside `Array<Y>` (`{ actions: ActionInfo[] }[]` vs
                // `ApplicableRefactorInfo[]`): the OUTER Array pushes globalArrayType.id, so
                // the INNER Array's same tid counted as a re-entry → structural → `concat`
                // mismatch → FP TS2322. Array recursion still terminates via
                // relationComparisonStack (identical pair) + isDeeplyNested (5+ occurrences);
                // covariant `A ⊄ B ⇒ Array<A> ⊄ Array<B>` is the correct rule, so the eager
                // shortcut is sound here. Non-Array (user recursive) generics keep deferring
                // to structural on re-entry so an eager false can't pre-empt the cycle-break.
                // Gate: only when the TARGET args are TP-free — an UNBOUND type parameter in
                // the target (`flatten<T>(…: T[][] | …)`) is an M3.1 generic-inference gap,
                // and the trivial structural pass currently MASKS it; the eager shortcut
                // would turn that into an FP, so keep deferring there.
                val isArrayLike = (source.target === checker.globalArrayType ||
                    source.target === checker.globalReadonlyArrayType) &&
                    targetArgs.none { checker.typeContainsUnresolvedTypeParam(it) }
                if (!isReentry || isArrayLike) {
                    for (i in sourceArgs.indices) {
                        if (!checkTypeRelatedTo(sourceArgs[i], targetArgs[i], relation)) return false
                    }
                    return true
                }
            }
        }
        // (CHK.93): a TUPLE source relates to an `Array<T>` / `ReadonlyArray<T>` target
        // when EVERY element relates to `T` — in tsc a tuple is a reference whose base
        // type is `Array<union of its elements>`, so `[1, 2]` → `number[]` holds; here a
        // tuple is a `Type.Object` carrying only its numbered members and `length`, and
        // the structural path reported it missing `pop`, `push`, … (a false TS2740 for
        // every declared tuple against an array, surfaced at every `as const` array the
        // moment (CHK.93)(c) built one — `const a: number[] = [1, 2] as const` is SILENT
        // in both references, the contextual array type making the tuple mutable).
        // Acceptance-only: an element that fails falls through to the structural path,
        // which reports exactly what it reported before.
        // (CHK.93) stage 2: …but a READONLY array-like source never relates to a MUTABLE
        // array or tuple target — tsc answers false before any structural comparison
        // (TS4104 at the emitters), so neither the element rule below nor the structural
        // path may accept it.
        if (checker.readonlyToMutableArrayLike(source, target)) return false
        if (source is Type.Object && source.tupleElementTypes != null && target is Type.Reference &&
            (target.target === checker.globalArrayType ||
                (checker.globalReadonlyArrayType != null && target.target === checker.globalReadonlyArrayType))
        ) {
            val elt = target.resolvedTypeArguments?.singleOrNull()
            // (CHK.111): rest-EXPANDED — `[number, ...string[]]` relates to `(number|string)[]`
            // in both references, and comparing the stored `string[]` slot refused it.
            val srcElems = checker.tupleRelationElementTypes(source) ?: source.tupleElementTypes!!
            if (elt != null && srcElems.all { checkTypeRelatedTo(it, elt, relation) }) {
                return true
            }
        }
        // B75.2: Array-derived source → ReadonlyArray<T> target — covariant element shortcut.
        // TypeScript treats `ReadonlyArray<T>` covariantly in T: any `Array<U>`-derived
        // value is assignable to `ReadonlyArray<T>` when `U → T`. Without this, both
        // plain `Array<U>` and SUBCLASS-of-Array (e.g. `class C<T> extends Array<T>`)
        // vs `ReadonlyArray<A>` fall through to `objectTypeRelatedTo` → spurious
        // `concat`-method contravariance mismatch (`(...items: U[]) => U[]` vs
        // `(...items: A[]) => A[]`) because we don't have per-typeparam variance info
        // to mark `concat`'s element-type position as covariant. Pre-B75.1 this worked
        // by accident because ReadonlyArray collapsed onto globalArrayType (same target,
        // covariant arg shortcut applied). Direct extraction via Array's base-type chain
        // restores the behavior cleanly. Plain `Array<U>` source: directly use source.args.
        // Subclass-of-Array: walk source's target's baseTypes to find an Array<U>-shaped
        // ancestor, instantiate with source's args.
        if (source is Type.Reference && target is Type.Reference &&
            checker.globalReadonlyArrayType != null && target.target === checker.globalReadonlyArrayType &&
            source.target !== checker.globalReadonlyArrayType) {
            val targetArgs = target.resolvedTypeArguments
            if (targetArgs != null && targetArgs.size == 1) {
                if (source.target === checker.globalArrayType) {
                    val srcArgs = source.resolvedTypeArguments
                    if (srcArgs != null && srcArgs.size == 1) {
                        if (checkTypeRelatedTo(srcArgs[0], targetArgs[0], relation)) {
                            return true
                        }
                    }
                } else {
                    val srcInterface = source.target
                    if (srcInterface.baseTypes == null) checker.resolveBaseTypesLazy(srcInterface)
                    val srcBaseTypes = srcInterface.baseTypes
                    val srcTypeParams = srcInterface.typeParameters ?: emptyList()
                    val srcInstArgs = source.resolvedTypeArguments ?: emptyList()
                    if (srcBaseTypes != null && srcTypeParams.size == srcInstArgs.size) {
                        val mapper = createTypeMapper(srcTypeParams, srcInstArgs)
                        for (baseType in srcBaseTypes) {
                            val baseRef = baseType as? Type.Reference ?: continue
                            if (baseRef.target !== checker.globalArrayType &&
                                baseRef.target !== checker.globalReadonlyArrayType) continue
                            val baseArgs = baseRef.resolvedTypeArguments ?: continue
                            if (baseArgs.size != 1) continue
                            val instElt = checker.instantiateType(baseArgs[0], mapper)
                            if (checkTypeRelatedTo(instElt, targetArgs[0], relation)) {
                                return true
                            }
                        }
                    }
                }
            }
        }
        // Object types: structural comparison
        if (source is Type.Object && target is Type.Object) {
            if (objectTypeRelatedTo(source, target, relation)) return true
            // (CHK.60) AN ENUM MEMBER IS A STRING OR NUMBER LITERAL IN tsc, SO IT MUST
            // REACH THE APPARENT-TYPE LEGS BELOW. tsc's `TypeFlags.StringLike` is
            // `String | StringLiteral | TemplateLiteral | StringMapping` and an enum
            // literal type carries `StringLiteral | EnumLiteral`, so
            // `getApparentType(E.A)` is `globalStringType`; a numeric member carries
            // `NumberLiteral | EnumLiteral` and answers `globalNumberType`. (REL.1)(b)
            // mints a member-LESS `Type.Object` here instead, which
            // [propertiesRelatedTo] rejects against any target declaring a property —
            // INCLUDING AN ALL-OPTIONAL (weak) ONE, since its `source.members == null`
            // arm answers `targetProps.isEmpty()`. So `zzzG(E.A)` against
            // `{ length?: number }` was a FALSE POSITIVE at every position, and the
            // weak rule is not what fired: it correctly DECLINES a target the source
            // shares a property with.
            //
            // RETRYING AS THE PRIMITIVE — rather than reaching for a wrapper here —
            // is what routes the source through EXACTLY the legs a `string`/`number`
            // source already takes, each with its own measured guards intact: B69.8's
            // wrapper/named-interface leg (which is why `String`, `Object` and a plain
            // `interface I { length?: number }` target accept), round 430's empty-`{}`
            // rule, B418's index-signature rule and (CHK.32)'s anonymous-object leg.
            // Nothing here can turn an ACCEPTANCE into a rejection: it runs only after
            // the structural comparison has already answered false.
            //
            // POSITIVE EVIDENCE ONLY. The member's flavour is read off its COMPUTED
            // VALUE, so a member whose value did not evaluate keeps today's rejection
            // — the FP-safe direction. A MIXED enum needs no special case: the answer
            // is per MEMBER, and tsc agrees (`{ A = 1, B = "b" }` accepts `E.B`
            // against `{ length?: number }` and `E.A` against `{ toFixed?() }`).
            //
            // OUT OF SCOPE, DELIBERATELY: the WHOLE enum type as a source. It carries
            // no `EnumLiteral` flag, and this repo accepts it against every object
            // target VACUOUSLY (member-less source, so `propertiesRelatedTo` is not
            // even consulted for an empty target) — a standing FALSE NEGATIVE tsc does
            // not share, and a different arc.
            //
            // IDENTITY is excluded exactly as tsc excludes it (`structuredTypeRelatedTo`
            // guards its apparent-source work with `relation !== identityRelation`): an
            // enum member is not IDENTICAL to anything the `String` wrapper is.
            if (relation === identityRelation) return false
            val enumPrimitive = enumSemantics.enumLiteralApparentPrimitive(source) ?: return false
            return checkTypeRelatedTo(enumPrimitive, target, relation)
        }
        // Primitive source vs Object target: use the source's apparent (wrapper) type
        // and recurse. `string` → `Object` is assignable because `String` (wrapper)
        // extends `Object`. Without this, primitives fail to satisfy any object
        // constraint that mentions `Object` even though TypeScript treats them as
        // assignable via apparent type.
        // Scope: bare named interfaces only (Type.Interface). Skip Type.Reference
        // (generic instantiations like `Promise<number>` need true assignability —
        // an async function returning `1` should compare via Awaited unwrapping,
        // not via Number-wrapper-extends-Object). Also skip wrapper interfaces
        // themselves so TS2322 keeps firing for primitive→mismatched-wrapper.
        if (target is Type.Interface &&
            (source is Type.Intrinsic || source is Type.StringLiteral ||
                source is Type.NumberLiteral || source is Type.BigIntLiteral)) {
            val targetName = target.symbol?.name
            val isWrapperTarget = targetName in Checker.WRAPPER_INTERFACE_NAMES
            if (isWrapperTarget) {
                // B69.8: Primitive → matching wrapper assignment is allowed via
                // auto-boxing. `number → Number`, `string → String`, etc.
                // `boolean → Boolean` (including literal true/false) is also valid.
                val srcIntrinsicName = (source as? Type.Intrinsic)?.intrinsicName
                val matchingWrapper = when {
                    source is Type.NumberLiteral || srcIntrinsicName == "number" -> "Number"
                    source is Type.StringLiteral || srcIntrinsicName == "string" -> "String"
                    source is Type.BigIntLiteral || srcIntrinsicName == "bigint" -> "BigInt"
                    srcIntrinsicName == "boolean" || source.flags.hasAny(TypeFlags.BooleanLiteral) -> "Boolean"
                    srcIntrinsicName == "symbol" -> "Symbol"
                    else -> null
                }
                if (matchingWrapper != null && targetName == matchingWrapper) return true
            } else {
                val apparent = checker.getApparentType(source)
                if (apparent !== source && apparent is Type.Object) {
                    return checkTypeRelatedTo(apparent, target, relation)
                }
            }
        }
        // M3.1 (round 430): an EMPTY anonymous object target (`{}` — no members, no
        // signatures, no index infos) accepts ANY non-nullish, non-void source (tsc:
        // `{}` is the supertype of everything but null/undefined). Object sources
        // already passed vacuously via propertiesRelatedTo; this adds primitive/
        // literal/enum sources — `T extends {}` constraints (tsc core.ts `append`)
        // previously rejected `string` and killed the whole call-site inference.
        if (target is Type.Object && target !is Type.Interface && target !is Type.Reference &&
            target.symbol == null &&
            target.members.isNullOrEmpty() &&
            target.callSignatures.isNullOrEmpty() && target.constructSignatures.isNullOrEmpty() &&
            target.stringIndexInfo == null && target.numberIndexInfo == null) {
            // A Type.Union's own flags carry no Null/Undefined bits (the documented
            // gotcha) — check members explicitly so `string | null` still fails.
            // A TYPE PARAM source is excluded: an unconstrained T could instantiate
            // to null/undefined, and tsc rejects `T → {}` under strict with the
            // "might need an `extends {}` constraint" hint (genericPrototypeProperty3
            // pins it) — TP sources fall through to the pre-existing paths.
            val nullishBits = TypeFlags.Null or TypeFlags.Undefined or TypeFlags.Void
            fun nonNullishMember(m: Type): Boolean =
                m !is Type.TypeParam && !m.flags.hasAny(nullishBits)
            val sourceNonNullish = if (source is Type.Union)
                source.types.all { nonNullishMember(it) }
            else nonNullishMember(source)
            if (sourceNonNullish) return true
        }
        // B418: primitive source vs an ANONYMOUS object target carrying index
        // signatures (no symbol → not a named interface). A `string`'s apparent
        // type (the `String` wrapper interface) has a NUMERIC index signature, so
        // `string` IS assignable to `{ [n: number]: any }` ("string has numeric
        // indexer") but NOT to `{ [s: string]: any }` (String has no string
        // indexer), and `boolean`/`number` (no index sigs) reject both. The source's
        // apparent type must supply a matching index signature for EACH index sig
        // the target requires (a string-index source also satisfies a numeric-index
        // target since string keys cover numeric ones). Only ADDS `true` results —
        // every such pair previously fell through to `return false`.
        if (target is Type.Object && target.symbol == null && target !is Type.Reference &&
            (target.stringIndexInfo != null || target.numberIndexInfo != null) &&
            (source is Type.Intrinsic || source is Type.StringLiteral ||
                source is Type.NumberLiteral || source is Type.BigIntLiteral)) {
            val apparent = checker.getApparentType(source) as? Type.Interface
            if (apparent != null) {
                checker.resolveStructuredTypeMembers(apparent)
                val tStr = target.stringIndexInfo
                val tNum = target.numberIndexInfo
                val sStr = apparent.stringIndexInfo
                val sNum = apparent.numberIndexInfo
                val strOk = tStr == null ||
                    (sStr != null && checkTypeRelatedTo(sStr.type, tStr.type, relation))
                val numOk = tNum == null ||
                    (sNum != null && checkTypeRelatedTo(sNum.type, tNum.type, relation)) ||
                    (sStr != null && checkTypeRelatedTo(sStr.type, tNum.type, relation))
                if (strOk && numOk) return true
            }
        }
        // (CHK.32): a PRIMITIVE source against a STRUCTURAL (anonymous) object target
        // is decided through the source's WRAPPER INTERFACE. `string` carries
        // `charCodeAt`/`length`/`substring` because `String` declares them, so
        // `isWhitespace(s, 0)` against `(text: { charCodeAt(i: number): number })` is
        // legal — 8 of the 14 rows of a hand-written primitive/target matrix were
        // ours-only against tsgo 7.0.2, and the whole `jsonrepair` scanner is typed
        // this way.
        //
        // WHY IT IS A SEPARATE LEG AND NOT A WIDENING OF THE ROUND-B69.8 ONE ~120 LINES
        // ABOVE. That leg is scoped `target is Type.Interface` — a NAMED interface —
        // and it RETURNS its verdict. Widening its target test would put a `return
        // false` in front of the two legs between it and here: the empty-`{}` rule
        // (round 430) and B418's index-signature rule, both of which accept sources
        // this comparison rejects. Placed here it is strictly a FALLBACK in the
        // round-744 sense — every earlier acceptance path has already had its turn,
        // and this can only turn a rejection into an acceptance.
        //
        // The REFUSAL direction is the one that had to be pinned hardest, and it is
        // carried by the ordinary structural comparison rather than by a gate here: a
        // target member the wrapper does not declare (`{ zzzNotOnString: number }`) or
        // declares at another type (`{ length: string }`) fails `objectTypeRelatedTo`
        // and the TS2345/TS2322 stands, at tsgo's own message and position.
        //
        // Excluded, each for a measured reason: a NAMED interface target (the B69.8 leg
        // already returned for it, so this is unreachable there and nothing moves); a
        // `Type.Reference` target (`Promise<number>` needs true assignability, not
        // wrapper-extends-Object — the B69.8 leg's own note); and every non-primitive
        // source, via [primitiveApparentWrapper]'s shape test.
        //
        // AN INDEX-SIGNATURE TARGET IS B418's, NOT THIS LEG's, AND THAT IS THE SECOND
        // GUARD MEASUREMENT FORCED. `assignmentCompat1` pins `y = "foo"` against
        // `{ [index: string]: any }` as TS2322 — a `string`'s apparent type has a
        // NUMERIC index signature and no string one — and pins `z = false` against
        // `{ [index: number]: any }` likewise. An ordinary structural comparison of the
        // `String` wrapper against such a target passes, because every property it
        // carries conforms to an `any`-typed indexer; the rule tsc actually applies is
        // that the SOURCE must supply a matching index signature, which is exactly what
        // B418 twenty lines above decides. So a target carrying either indexer has
        // already had its answer, and this leg must not overturn it.
        if (target is Type.Object && target !is Type.Interface && target !is Type.Reference &&
            target.flags.hasNone(TypeFlags.Enum or TypeFlags.EnumLiteral)) {
            // Ordered cheapest-first: the source shape test is a class check plus a
            // flag test, where [targetIsMemberShaped] forces the target's lazy tables.
            val wrapper = checker.primitiveApparentWrapper(source)
            if (wrapper != null && checker.targetIsMemberShaped(target) &&
                checkTypeRelatedTo(wrapper, target, relation)) return true
        }
        // TypeParam vs TypeParam: relate via apparent types (constraint, or {} when
        // unconstrained). Matters for generic-method signature comparison where source's
        // K and target's K (separate fresh params from `resolveGenericPropertyType`) would
        // otherwise compare as opaque-and-distinct → spurious TS2322 in tests like
        // `infinitelyExpandingTypes4` (Promise<Q.then<U_src>> vs Promise<Q.then<U_tgt>>).
        if (source is Type.TypeParam && target is Type.TypeParam) {
            val srcConstraint = source.constraint
            val tgtConstraint = target.constraint
            if (srcConstraint == null || tgtConstraint == null) return true
            return checkTypeRelatedTo(srcConstraint, tgtConstraint, relation)
        }
        // A TYPE PARAMETER against `object` is decided by its CONSTRAINT (round 834,
        // (NONPRIM.1)). An UNCONSTRAINED `T` can be instantiated with a primitive, so
        // it is not assignable — the verdict `nonPrimitiveInGeneric`'s `function
        // generic<T>(t: T) { var o: object = t }` wants — while `T extends object` and
        // `T extends {}` both are. Deliberately NOT the general "TypeParam source via
        // apparent type" rule: this is keyed on a NonPrimitive target, so nothing that
        // does not write the `object` keyword can reach it.
        if (source is Type.TypeParam && target.flags.hasAny(TypeFlags.NonPrimitive)) {
            val constraint = source.constraint ?: return false
            if (constraint === errorType) return false
            return checkTypeRelatedTo(constraint, target, relation)
        }
        return false
    }

    /**
     * Structural comparison of two object types.
     * Checks properties, call signatures, and construct signatures.
     * For class instance types, skip construct signature comparison — those belong
     * to the static side (typeof Class), not the instance type.
     */
    private fun objectTypeRelatedTo(
        source: Type.Object,
        target: Type.Object,
        relation: Relation,
    ): Boolean {
        checker.resolveStructuredTypeMembers(source)
        checker.resolveStructuredTypeMembers(target)
        // Check properties
        if (!propertiesRelatedTo(source, target, relation)) return false
        // Check call signatures
        if (!signaturesRelatedTo(source, target, relation, isConstruct = false)) return false
        // Check construct signatures — skip for class/interface instance types where
        // construct signatures come from the class constructor (static side), not the instance.
        val isClassInstance = target is Type.Interface && target.symbol != null &&
            target.symbol!!.flags.hasAny(SymbolFlags.Class or SymbolFlags.Interface)
        if (!isClassInstance) {
            if (!signaturesRelatedTo(source, target, relation, isConstruct = true)) return false
        }
        // Check index signatures: if target has a string/number index signature but source
        // (a named class/interface) doesn't declare one, TypeScript rejects the assignment
        // with "Index signature for type '<kind>' is missing in type 'X'." This only applies
        // to NOMINAL source types (named class/interface) — anonymous object literals use
        // a different rule where properties individually satisfy the index type.
        // Type.Reference's own `symbol` is null; its target's symbol carries the nominal info.
        // Skip when the target's index type accepts `any`/`unknown` — any source satisfies
        // it implicitly so emitting "missing index signature" would be a FP (cf.
        // `assignmentCompatability36_ts`'s `{[k:string]:any}` target).
        val srcNominalSym = source.symbol ?: (source as? Type.Reference)?.target?.symbol
        val isNominalSource = srcNominalSym != null &&
            srcNominalSym.flags.hasAny(SymbolFlags.Class or SymbolFlags.Interface)
        // 17.74: Function-shaped sources (callSignatures only, no nominal members)
        // also trigger the missing-index-sig check — mirrors TypeScript's "Index
        // signature for type 'number' is missing in type '() => void'." for
        // `func: () => void; const a: ArrayLike<any> = func;`. Note: for function
        // sources we DON'T skip when target's index type is `any`/`unknown` — a
        // function type doesn't implicitly satisfy `[n:number]:any` (unlike a
        // nominal class which can be subclassed/extended), so the diagnostic IS
        // appropriate. The `any`-skip remains for nominal sources to avoid FPs
        // (cf. `assignmentCompatability36_ts`).
        val isFunctionShaped = !source.callSignatures.isNullOrEmpty() && source.members.isNullOrEmpty()
        // tsc indexSignaturesRelatedTo: when the TARGET has a STRING index signature whose
        // value type is `any`, EVERY non-primitive source passes ALL index checks
        // (`!sourceIsPrimitive && targetHasStringIndex && targetInfo.type.flags & Any →
        // Ternary.True`, applied per target info). A number-only `[n: number]: any` target
        // (ArrayLike<any>) has NO string index → the function-shaped check below still
        // fires. (intTypeCheck: `var obj39: i4 = function(){}` where i4's `[p1: string];`
        // value defaults to `any` → NO error.)
        val anyStringIndexSkip = target.stringIndexInfo?.type?.flags?.hasAny(TypeFlags.Any) == true
        if ((isNominalSource || isFunctionShaped) && !anyStringIndexSkip) {
            val tgtStr = target.stringIndexInfo
            if (tgtStr != null && source.stringIndexInfo == null &&
                (isFunctionShaped || !tgtStr.type.flags.hasAny(TypeFlags.Any or TypeFlags.Unknown))) {
                checker.lastMissingIndexSigKind = "string"
                return false
            }
            val tgtNum = target.numberIndexInfo
            if (tgtNum != null && source.numberIndexInfo == null &&
                (isFunctionShaped || !tgtNum.type.flags.hasAny(TypeFlags.Any or TypeFlags.Unknown))) {
                checker.lastMissingIndexSigKind = "number"
                return false
            }
        }
        return true
    }

    /**
     * 4d. Property-by-property structural comparison.
     * For each required property in target, source must have a compatible property.
     */
    private fun propertiesRelatedTo(
        source: Type.Object,
        target: Type.Object,
        relation: Relation,
    ): Boolean {
        val targetProps = target.properties ?: return true
        val sourceHasCallSigs = !source.callSignatures.isNullOrEmpty()
        val sourceMembers = source.members ?: if (sourceHasCallSigs) emptyMap() else return targetProps.isEmpty()
        // Statics filter: when target carries a static side (class declaration),
        // skip target.properties entries that live on the static side. Instance-vs-
        // instance shape comparison must not see `static bar()` on either side.
        // Type.Reference defers to its target Interface's staticMembers.
        val targetStatics = checker.getStaticMembersOfType(target)
        for (targetProp in targetProps) {
            val targetName = targetProp.name
            if (targetStatics != null && targetStatics.containsKey(targetName)) continue
            // Inherited Object prototype members (constructor, toString, valueOf, …)
            // are never "missing" — every JS object has them via the prototype chain.
            // Without this filter, an empty `{a:string}` fails to satisfy `Object` because
            // `constructor` etc. are listed as Object's own properties in our embedded lib.
            // B67.3: only skip when source doesn't EXPLICITLY define this property — if
            // source has its own `toString: 5`, fall through to the type-compatibility
            // check (number vs () => string emits TS2322 per `assignmentToObject_ts`).
            if (targetName in Checker.OBJECT_PROTOTYPE_PROPERTIES && !sourceMembers.containsKey(targetName)) continue
            // 17.27: When source is a function type (has callSignatures), Function.prototype
            // methods (call/apply/bind) are implicitly available through the apparent type.
            // Lets `() => void` satisfy `interface Callable { call(blah: any) }` etc.
            if (sourceHasCallSigs && targetName in Checker.FUNCTION_PROTOTYPE_METHODS) continue
            // B479: `Function[Symbol.hasInstance]` is provided by Function.prototype, so a
            // function-typed source satisfies it implicitly (a function IS a Function). Without
            // this skip, adding `[Symbol.hasInstance]` to the embedded Function interface makes
            // `() => void` fail to satisfy `Function` (untypedFunctionCallsWithTypeParameters1's
            // `caller: Function` property).
            if (sourceHasCallSigs && targetName.contains("hasInstance")) continue
            // B66.4: Function-side runtime properties (prototype/arguments/caller) are
            // implicitly available on any function value via Function.prototype.
            // Lets a function expression / arrow satisfy the `Function` interface.
            if (sourceHasCallSigs && targetName in Checker.FUNCTION_RUNTIME_PROPERTIES) continue
            // 17.74: Function.prototype's `length: number` and `name: string` implicitly
            // satisfy the corresponding target property when source has callSignatures.
            // Lets `() => void` satisfy `interface ArrayLike<T> { length: number }` —
            // clears the way for the index-signature-missing check to fire (matching
            // TypeScript's elaboration which prefers "Index signature missing" over
            // "Property 'length' missing" for function-vs-ArrayLike-shape comparisons).
            if (sourceHasCallSigs && (targetName == "length" || targetName == "name")) {
                val targetPropType = checker.getPropertyTypeForRelation(target, targetProp)
                val protoType = if (targetName == "length") numberType else stringType
                if (checkTypeRelatedTo(protoType, targetPropType, relation)) continue
            }
            // Round 471: a lib-phantom member of a lib+module-interface merge (Symbol's
            // es2019 `description` vs tsc compiler/types.ts's `interface Symbol`) is a
            // conflation artifact — skip it in the relation (suppression-only; see
            // isLibPhantomMemberOfModuleInterface). PERF (round 471b): gated to a
            // CLASS-instance source — the class-implements-module-interface family
            // (SymbolObject → Symbol) — because an unconditional skip removes the
            // missing-prop EARLY EXIT from every relation against the merged
            // interface and re-runs full member-type comparisons (services
            // self-compile 39 s → 77 s, measured).
            if (source.symbol?.flags?.hasAny(SymbolFlags.Class) == true &&
                checker.isLibPhantomMemberOfModuleInterface(target, targetName)) continue
            // Check if property is optional (question mark in declaration)
            // (CHK.111): a slot at or after the target's REST slot is not a required member —
            // tsc synthesizes no numbered property there — so `[number]` relates to
            // `[number, ...string[]]`.
            val isOptional = checker.isOptionalProperty(targetProp) || checker.isRestTupleMember(targetProp)
            val sourceProp = sourceMembers[targetName]
            if (sourceProp == null) {
                // B418: a source INDEX SIGNATURE does NOT satisfy a target's required
                // NAMED property — tsc's propertiesRelatedTo looks up the source prop
                // via getPropertyOfType, which never synthesizes a named property from
                // a source index signature. So `{ [k: string]: any }` is NOT assignable
                // to `{ one: number }` (one is missing → TS2741), matching the number
                // index-sig source which already (correctly) failed here.
                if (!isOptional) {
                    checker.lastMissingPropertyName = targetName
                    checker.lastMissingPropertySymbol = targetProp
                    return false // missing required property
                }
                continue
            }
            // (CHK.111): the mirror of the rule above, on the SOURCE side — a slot at or after
            // the source's own REST slot only MAY be present, so it cannot satisfy a REQUIRED
            // target slot (tsc's tuple element loop: `targetFlags & Required` with a source
            // flag that is not, reported as "Source provides no match for required element at
            // position N in target"). Without it `[number, ...string[]]` relates to
            // `[number, string, ...string[]]`, which both references reject.
            if (!isOptional && checker.isRestTupleMember(sourceProp)) {
                return false
            }
            // Private-brand mismatch: if both source and target declare this property as
            // `private` but on different parent classes, TypeScript treats them as unrelated
            // nominal shapes even though the declared types are structurally equal.
            // Matches `typeIdentityConsidersBrands_ts` etc.
            if (checker.isPropPrivateBrandMismatch(sourceProp, targetProp)) {
                lastPrivateBrandMismatchName = targetName
                return false
            }
            // Compare property types
            val sourcePropType = checker.getPropertyTypeForRelation(source, sourceProp)
            val effectiveSource = checker.widenOptionalSourcePropType(sourcePropType, sourceProp, targetProp)
            val targetPropType = checker.widenOptionalTargetPropType(checker.getPropertyTypeForRelation(target, targetProp), targetProp, effectiveSource)
            if (!checkTypeRelatedTo(effectiveSource, targetPropType, relation)) {
                // Round 454: a METHOD member (method syntax `m(...) {…}` / `m(...): T`)
                // compares its parameters BIVARIANTLY (tsc's rule — `strictFunctionTypes`
                // never applies to method members), but our `checkTypeRelatedTo` compares
                // function-typed members contravariantly. When BOTH the source and target
                // member are methods, retry via the bivariant helper already used by
                // TS2416/TS2430. Suppression-only (fires only on a contravariant failure)
                // and FP-safe by construction: a method-param bivariance match is not an
                // error in tsc, so this can only remove a false positive (e.g. an
                // object-literal `canReuseTypeNodeAnnotation(…, symbol: Symbol)` satisfying
                // an interface member `(…, symbol: Symbol | undefined)`). A function-typed
                // PROPERTY (`m: (…) => T`) is NOT a method → stays strictly contravariant.
                if (sourceProp.declarations.any { it is MethodDeclaration } &&
                    targetProp.declarations.any { it is MethodDeclaration } &&
                    methodSignaturesBivariantlyRelated(
                        sourcePropType, checker.getPropertyTypeForRelation(target, targetProp))
                ) continue
                // Round 435 (tsc contextual literal types): a FRESH object-literal prop
                // keeps its literal type against a literal-containing target member —
                // getTypeOfObjectLiteral widens (`kind: "paths"` → string), so retry with
                // the un-widened literal recovered from the member symbol's
                // PropertyAssignment. Gated to the CURRENT fresh literal's source range
                // ([freshObjLitRange]) so a widened var reference still fails (tsc's own
                // freshness rule); a literal absent from the target union also still
                // fails. tsc's moduleSpecifiers.ts `{ kind: "paths", … }` returns /
                // esDecorators.ts `top = { kind: "class", … }` assignments.
                val fresh = checker.freshObjLitRange
                val srcDecl = if (fresh != null && checker.propTypeContainsLiteral(targetPropType))
                    sourceProp.valueDeclaration as? PropertyAssignment else null
                val lit = if (srcDecl != null && srcDecl.pos in fresh!!)
                    checker.literalTypeOfExpression(srcDecl.initializer, checker.isArrayLikeReference(targetPropType)) else null
                if (lit == null || !checkTypeRelatedTo(lit, targetPropType, relation)) return false
            }
        }
        return true
    }

    /**
     * 4e. Compare call or construct signatures.
     * Parameter types compared contravariantly, return types covariantly.
     */
    private fun signaturesRelatedTo(
        source: Type.Object,
        target: Type.Object,
        relation: Relation,
        isConstruct: Boolean,
    ): Boolean {
        val targetSigs = if (isConstruct) target.constructSignatures else target.callSignatures
        if (targetSigs.isNullOrEmpty()) return true // no signatures to match
        val sourceSigs = if (isConstruct) source.constructSignatures else source.callSignatures
        if (sourceSigs.isNullOrEmpty()) return false // target has signatures, source doesn't
        // Each target signature must be matched by some source signature
        for (targetSig in targetSigs) {
            val matched = sourceSigs.any { sourceSig ->
                signatureRelatedTo(sourceSig, targetSig, relation)
            }
            if (!matched) return false
        }
        return true
    }

    /** Compare two individual signatures. */
    /**
     * Method-member override relation with BIVARIANT parameters. Both types must be
     * function-shaped (single call signature). Used only by the class-property-override
     * check (TS2416) — tsc compares METHOD signatures bivariantly. Returns true iff every
     * source call sig relates to some target call sig with bivariant params.
     */
    fun methodSignaturesBivariantlyRelated(derivedType: Type, baseType: Type): Boolean {
        if (derivedType !is Type.Object || baseType !is Type.Object) return false
        val dSigs = derivedType.callSignatures ?: return false
        val bSigs = baseType.callSignatures ?: return false
        if (dSigs.isEmpty() || bSigs.isEmpty()) return false
        // Every target (base) signature must be matched by some source (derived) signature.
        return bSigs.all { bSig ->
            dSigs.any { dSig -> signatureRelatedTo(dSig, bSig, assignableRelation, bivariantParams = true) }
        }
    }

    fun signatureRelatedTo(
        source: Signature,
        target: Signature,
        relation: Relation,
        // When true, parameter positions are compared BIVARIANTLY (source param
        // assignable to target param OR vice versa) instead of strictly
        // contravariantly. tsc compares METHOD signatures bivariantly (only
        // function-type PROPERTIES get strict contravariance under
        // strictFunctionTypes). Used by the class-property-override check for
        // method members. Default false — every other caller keeps strict variance.
        bivariantParams: Boolean = false,
        // (CHK.133)(b) tsc's `SignatureCheckMode.Callback`: this signature is a CALLBACK
        // parameter of the signature being related, so its `this` types compare
        // bivariantly whatever the target's declaration kind. Set only by
        // [callbackParamsRelated]; it affects the `this` leg and nothing else.
        callbackThis: Boolean = false,
    ): Boolean {
        // Source requires more args than target provides → incompatible.
        // Round 475: a REST-param target provides UNBOUNDED args (`(...args: any[]) =>
        // void` accepts a 3-required-param source — tsc host.setTimeout callbacks,
        // server utilities.ts ThrottledOperations.run), so the arity gate is skipped.
        val sourceParams = source.parameters
        val targetParams = target.parameters
        val targetHasRestParam =
            (targetParams.lastOrNull()?.valueDeclaration as? Parameter)?.dotDotDotToken == true
        if (!targetHasRestParam && source.minArgumentCount > targetParams.size) return false
        // 17.10d: Light type-param inference for the SOURCE-generic, TARGET-non-generic
        // case. When source has type parameters and target doesn't, treat source's
        // TypeParam-typed param positions as "wildcards" pinned to the target's
        // concrete type at that position — a single TypeParam appearing multiple times
        // must map to the SAME target type (modulo subtyping); otherwise the comparison
        // fails. Each pinning must also satisfy the TypeParam's constraint. Without
        // this, FunctionType nodes with type params (added in 17.10b) FP-reject
        // legitimate contravariant param assignments like `(g: Giraffe) => void` ←
        // `<T extends Animal>(x: T) => void`.
        val sourceHasTpInParams = !source.typeParameters.isNullOrEmpty() &&
            target.typeParameters.isNullOrEmpty() &&
            sourceParams.any { checker.getTypeOfSymbol(it) is Type.TypeParam }
        val tpAssignments = if (sourceHasTpInParams) mutableMapOf<Int, Type>() else null
        // Round 455: a NAME-keyed companion to tpAssignments — Type.TypeParam instances
        // are interned per AST position (B199), so the sig's own type-param object and
        // the param-annotation's TypeParam may carry different ids; matching the return
        // type's TypeParam by name is the robust fallback for the substitution below.
        val tpAssignByName = if (sourceHasTpInParams) mutableMapOf<String, Type>() else null
        // Check parameter types (contravariant). We do params BEFORE return type so
        // the type-param assignments from param pinning drive return-type substitution
        // (round 455).
        val len = minOf(sourceParams.size, targetParams.size)
        // B63.29: When target's last param is a rest `...t: U[]`, positional comparison
        // beyond target.size-1 (and AT target.size-1 against a non-rest source param)
        // should use the rest element type U, not U[]. Without this, source's typed
        // param like `bar: string` vs target's rest `...tail: any[]` contravariantly
        // checks `any[]` vs `string` → false. Correct: check element `any` vs `string` → true.
        val targetLast = targetParams.lastOrNull()
        val targetLastIsRest = (targetLast?.valueDeclaration as? Parameter)?.dotDotDotToken == true
        // Compute the rest element type, OR a sentinel marking "non-array rest" (which
        // can't actually accept tuple-like spreads and TypeScript treats as accept-all).
        // - Array<U> / ReadonlyArray<U> → element type U
        // - any → anyType (accept all)
        // - Tuple → fall through to standard comparison (don't bail)
        // - Anything else (intersection, named non-array, never) → anyType (accept all,
        //   since the rest type isn't a valid spread target)
        val targetRestRaw = if (targetLastIsRest) checker.getTypeOfSymbol(targetLast) else null
        val targetRestElement: Type? = if (targetLastIsRest && targetRestRaw != null) {
            when {
                targetRestRaw is Type.Reference &&
                    targetRestRaw.target.symbol?.name in setOf("Array", "ReadonlyArray") ->
                    targetRestRaw.resolvedTypeArguments?.firstOrNull()
                targetRestRaw === anyType -> anyType
                // B63.31: Non-array rest type (intersection of object literals, named non-array,
                // etc.) — TypeScript treats these as accept-anything since the rest spread can't
                // actually deliver values of that shape. Fixed-arity comparison bails by using
                // anyType for the element. Tuples (Type.Object with tupleElementTypes) fall
                // through (we don't have per-position tuple-element comparison yet).
                targetRestRaw is Type.Object && targetRestRaw.tupleElementTypes != null -> null
                else -> anyType
            }
        } else null
        // B196: SOURCE-side rest expansion. When the SOURCE's last param is a rest
        // `...x: E[]` and the target side is NON-rest, TypeScript relates target
        // positions at/after the source rest index against the rest ELEMENT type E —
        // `(...x: number[]) => void` is NOT assignable to `(x: number[], y: string) =>
        // void` (number[] vs number fails). Gated to a concrete Array/ReadonlyArray
        // element (never/any/TypeParam rests keep the prior permissive behavior, e.g.
        // genericRestTypes' `...args: never`). Element comparisons are BIVARIANT
        // (tsc's method-param rule) so legitimate contravariant cases stay legal.
        val sourceLastParam = sourceParams.lastOrNull()
        val sourceLastIsRest = (sourceLastParam?.valueDeclaration as? Parameter)?.dotDotDotToken == true
        val sourceRestElement: Type? = if (sourceLastIsRest && !targetLastIsRest) {
            val raw = checker.getTypeOfSymbol(sourceLastParam)
            if (raw is Type.Reference && raw.target.symbol?.name in setOf("Array", "ReadonlyArray"))
                raw.resolvedTypeArguments?.firstOrNull()?.takeIf {
                    it !== anyType && it !== errorType && it !is Type.TypeParam
                }
            else null
        } else null
        for (i in 0 until len) {
            val sourceParamTypeRaw = checker.getTypeOfSymbol(sourceParams[i])
            val sourceIsRest = (sourceParams[i].valueDeclaration as? Parameter)?.dotDotDotToken == true
            // B196: at the source's rest position (target non-rest), compare the
            // target param against the rest ELEMENT bivariantly.
            if (sourceIsRest && i == sourceParams.size - 1 && sourceRestElement != null) {
                val t = checker.getTypeOfSymbol(targetParams[i])
                if (t !== anyType && t !== errorType && t !is Type.TypeParam) {
                    if (!checkTypeRelatedTo(t, sourceRestElement, relation) &&
                        !checkTypeRelatedTo(sourceRestElement, t, relation)) return false
                }
                continue
            }
            // Round 480: a source rest whose ELEMENT is any/error/TypeParam (the
            // shapes sourceRestElement's takeIf rejects) accepts ANY target params —
            // `(...args: any[]) => void` is assignable to `(project: Project) =>
            // void` (tsc harness incrementalUtils.ts withIncrementalVerifierCallbacks);
            // without this the position compared the ARRAY type (`Project <: any[]`
            // contravariantly) and failed.
            if (sourceIsRest && i == sourceParams.size - 1 && !targetLastIsRest) {
                continue
            }
            val sourceParamType = sourceParamTypeRaw
            // For the target's rest param position, if source is also rest, compare U[] vs T[]
            // normally. If source is non-rest at the same position, compare element vs source.
            val targetParamType = if (i == targetParams.size - 1 && targetLastIsRest && !sourceIsRest && targetRestElement != null) {
                targetRestElement
            } else {
                checker.getTypeOfSymbol(targetParams[i])
            }
            if (tpAssignments != null && sourceParamType is Type.TypeParam) {
                val tpId = sourceParamType.id
                val existing = tpAssignments[tpId]
                if (existing != null && existing !== targetParamType) return false
                tpAssignments[tpId] = targetParamType
                sourceParamType.symbol?.name?.let { tpAssignByName?.put(it, targetParamType) }
                // Constraint check: target must satisfy source TypeParam's constraint
                val cons = sourceParamType.constraint
                if (cons != null && cons !== errorType) {
                    if (!checkTypeRelatedTo(targetParamType, cons, relation)) return false
                }
                continue
            }
            // Standard contravariant: target param must be assignable to source param.
            // Under bivariantParams (method members), also accept the covariant direction.
            // (CHK.62) An OPTIONAL source parameter accepts `undefined`, so its type in the
            // contravariant test is `T | undefined` — tsc's `addOptionality`, applied when the
            // parameter symbol's type is computed. Without it `(x?: string) => void` was not
            // assignable to `(x: string | undefined) => void`: the target's `undefined`
            // constituent had nowhere to go. Measured on the server profile at
            // `project.ts:2277` (`ServerHost` -> `GetPackageJsonEntrypointsHost`, whose
            // `readDirectory: CompilerHost["readDirectory"]` has required `extensions` /
            // `includes` where `System.readDirectory`'s are optional). Deliberately SOURCE-side
            // only: widening the TARGET side too is the other half of tsc's model and would
            // REJECT `(x: string) => void` against `(x?: string) => void` — a rejection change
            // measured separately, see the (CHK.62) session note.
            val sourceParamCmp =
                if ((sourceParams[i].valueDeclaration as? Parameter)?.questionToken == true &&
                    sourceParamType !== anyType && sourceParamType !== errorType &&
                    !checker.typeIncludesUndefined(sourceParamType)
                ) checker.getUnionType(listOf(sourceParamType, undefinedType)) else sourceParamType
            if (!checkTypeRelatedTo(targetParamType, sourceParamCmp, relation) &&
                !(bivariantParams && checkTypeRelatedTo(sourceParamType, targetParamType, relation)) &&
                !(!callbackThis && callbackParamsRelated(sourceParamType, targetParamType, relation))) return false
        }
        // B63.29 continuation: Source has MORE params than target.size — target's rest
        // covers them. For each excess source position, check target rest element type
        // contravariantly against the source param type.
        if (targetLastIsRest && targetRestElement != null && sourceParams.size > targetParams.size) {
            for (i in targetParams.size until sourceParams.size) {
                val sourceParamType = checker.getTypeOfSymbol(sourceParams[i])
                val sourceIsRest = (sourceParams[i].valueDeclaration as? Parameter)?.dotDotDotToken == true
                // Source's own rest: compare its element vs target rest element
                val srcCmp = if (sourceIsRest && sourceParamType is Type.Reference &&
                    sourceParamType.target.symbol?.name in setOf("Array", "ReadonlyArray"))
                    sourceParamType.resolvedTypeArguments?.firstOrNull() ?: sourceParamType
                else sourceParamType
                if (!checkTypeRelatedTo(targetRestElement, srcCmp, relation)) return false
            }
        }
        // Round 455: substitute the pinned source type params into the source RETURN
        // type before the covariant check. `identity<T>(x: T): T` (source-generic,
        // target-non-generic) pins T := <target param type> from the param loop above,
        // so the return `T` becomes e.g. `string` and relates to a
        // `(fileName: string) => string` target (`GetCanonicalFileName`). Without this
        // the raw TypeParam return FP-rejects against the concrete target return.
        // (CHK.133)(b): the same pins substitute into the source's `this` type below.
        val pinMapper = if (tpAssignments != null && tpAssignments.isNotEmpty()) {
            TypeMapper { tp ->
                tpAssignments[tp.id] ?: tp.symbol?.name?.let { tpAssignByName?.get(it) }
            }
        } else null
        // (CHK.133)(b) The `this` leg — tsc's `compareSignaturesRelated` compares the two
        // signatures' `this` types before the parameters; it sits AFTER our parameter loop
        // only so that a source-generic `this` (`<T>(this: Box<T>, x: T)`) can read the
        // type parameters the loop above pinned. The VERDICT is order-independent; the
        // elaboration (`Checker.getFunctionMismatchElaborationWorker`) prints the `this`
        // line first, as tsc does.
        if (!signatureThisTypesRelated(source, target, relation, bivariantParams || callbackThis, pinMapper)) {
            return false
        }
        // Check return types (covariant)
        // Void target return accepts any source return type (void means "don't care about return")
        var sourceReturn = source.resolvedReturnType ?: anyType
        val targetReturn = target.resolvedReturnType ?: anyType
        if (pinMapper != null) {
            sourceReturn = checker.instantiateType(sourceReturn, pinMapper)
        }
        if (!targetReturn.flags.hasAny(TypeFlags.Void) &&
            !checkTypeRelatedTo(sourceReturn, targetReturn, relation)) return false
        return true
    }

    /**
     * (CHK.133)(b) tsc's `compareSignaturesRelated` `this` leg, the ONE predicate both the
     * relation ([signatureRelatedTo]) and the elaborations
     * (`Checker.getFunctionMismatchElaborationWorker`, `Checker.addSignatureElaboration`)
     * consult, so the chain line *The 'this' types of each signature are incompatible.*
     * can never disagree with the verdict.
     *
     * A source `this` of `void` — or none — is never checked ("void sources are
     * assignable to anything"), and neither is a target that declares none. Otherwise the
     * TARGET's `this` must be assignable to the SOURCE's (the contravariant direction the
     * parameters take), and — where tsc's `strictVariance` is off — either direction
     * suffices: the caller's bivariance ([bivariant], `bivariantParams` at the two method
     * sites and `callbackThis` for a callback parameter, tsc's `SignatureCheckMode.Callback`)
     * or, exactly tsc's `kind !== MethodDeclaration && kind !== MethodSignature` test on the
     * TARGET's declaration (an interface method signature and a class method are both a
     * [MethodDeclaration] node in this parser). `strictFunctionTypes: false` is not
     * modelled by this checker's parameter leg either (`CompilerOptions` carries no such
     * field), so the `this` leg inherits that divergence rather than adding a second one.
     *
     * A `this` type that still mentions a type parameter after [pinMapper] (a
     * source-generic signature whose `T` occurs only in `this`, or a generic target) is
     * NOT compared — tsc instantiates the source in the target's context first and this
     * checker has no inference at the relation — so such a pair relates, which can only
     * lose a diagnostic, never invent one.
     */
    fun signatureThisTypesRelated(
        source: Signature,
        target: Signature,
        relation: Relation,
        bivariant: Boolean,
        pinMapper: TypeMapper? = null,
    ): Boolean {
        val rawSourceThis = source.thisType ?: return true
        if (rawSourceThis === voidType) return true
        val targetThis = target.thisType ?: return true
        val sourceThis = if (pinMapper != null) checker.instantiateType(rawSourceThis, pinMapper) else rawSourceThis
        if (checker.typeMentionsAnyTypeParam(sourceThis) || checker.typeMentionsAnyTypeParam(targetThis)) return true
        val bivariantThis = bivariant || target.declaration is MethodDeclaration
        return checkTypeRelatedTo(targetThis, sourceThis, relation) ||
            (bivariantThis && checkTypeRelatedTo(sourceThis, targetThis, relation))
    }

    /**
     * (CHK.133)(b) tsc's callback rule in `compareSignaturesRelated`: when the source and
     * target PARAMETER types are both single-call-signature function types (its
     * `getSingleCallSignature`, nullish-stripped), the two callbacks are related under
     * `SignatureCheckMode.Callback`, in which the callback's own `this` types compare
     * bivariantly. Everything else that mode decides — covariant parameters, no nested
     * callback detection — is what the ordinary contravariant comparison of the two
     * parameter types already does here, so this is a SECOND CHANCE on the rejecting
     * path (round 784's shape): it can only turn a refusal that came from the `this` leg's
     * strictness into an acceptance, and it goes straight to the signatures so no
     * mode-dependent verdict ever reaches the type-pair relation cache.
     */
    private fun callbackParamsRelated(sourceParam: Type, targetParam: Type, relation: Relation): Boolean {
        val (sourceSig, sourceNullish) = singleCallSignatureOf(sourceParam) ?: return false
        val (targetSig, targetNullish) = singleCallSignatureOf(targetParam) ?: return false
        if (sourceNullish != targetNullish) return false
        if (sourceSig.thisType == null && targetSig.thisType == null) return false
        return signatureRelatedTo(targetSig, sourceSig, relation, callbackThis = true)
    }

    /**
     * tsc's `getSingleCallSignature(getNonNullableType(type))`: the one call signature of a
     * function type carrying nothing else, or null. The second component says whether a
     * `null`/`undefined` constituent was stripped (tsc compares the two sides' nullishness).
     */
    private fun singleCallSignatureOf(type: Type): Pair<Signature, Boolean>? {
        var nullish = false
        val obj: Type.Object = when (type) {
            is Type.Object -> type
            is Type.Union -> {
                val rest = type.types.filter { it !== undefinedType && it !== nullType }
                nullish = rest.size != type.types.size
                rest.singleOrNull() as? Type.Object ?: return null
            }
            else -> return null
        }
        val sigs = obj.callSignatures ?: return null
        if (sigs.size != 1 || !obj.constructSignatures.isNullOrEmpty()) return null
        if (obj.stringIndexInfo != null || obj.numberIndexInfo != null) return null
        checker.resolveStructuredTypeMembers(obj)
        if (!obj.properties.isNullOrEmpty()) return null
        return sigs[0] to nullish
    }

    /**
     * Check if source type is assignable to target type using the new Type-based engine.
     * This is the replacement for the string-based isAssignableTo.
     */
    fun isTypeAssignableTo(source: Type, target: Type): Boolean {
        return checkTypeRelatedTo(source, target, assignableRelation)
    }
}
