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
 * (INV.0) step 7 — the ENUM seam: everything this checker knows about an
 * `enum`'s MEMBERS, their constant VALUES, the relation between two enums, and
 * the way an enum or one of its members is DISPLAYED in a diagnostic. Extracted
 * VERBATIM from `Checker.kt` (design § 6 Stage 0), as a final class constructed
 * once per [Checker]; no semantic change, and each of the 24 call sites that
 * survives in `Checker.kt` is a one-line delegation.
 *
 * ## Why this is a seam at all
 *
 * tsc models a literal enum AS the union of its members, so most of what is here
 * is ordinary union machinery there. This checker mints one member-LESS
 * `Type.Object` for the whole enum ((REL.1)(a) round 741), which is why the
 * DECOMPOSITION ([enumMemberTypesOf], [enumMinusMembers]), the VALUE domain
 * ([enumMemberEntries], [enumKnownDomainValues], [enumValueDomainIsComplete])
 * and the cross-enum IDENTITY question ([enumTypesRelation]) all have to be
 * answered explicitly — and why they are answered in one place.
 *
 * ## What is here
 *
 * ONE contiguous span of `Checker.kt` (115064-116076), whole — 1,013 lines,
 * 40 declarations, in four groups:
 *
 *  - DECOMPOSITION: [enumMemberTypesOf], [enumTargetsAreOwnMembers],
 *    [enumNarrowIsOwnMemberSubset], [enumMinusMembers], [enumComparisonAtoms],
 *    [enumComparisonBaseType] — the member list a subtractive narrow needs, in
 *    DECLARATION order (which is what makes a narrowed display read
 *    `K.B | K.C | K.D` rather than id-ordered);
 *  - VALUES: [enumMemberEntries] (the tsc view, where an ambient member with no
 *    initializer is OPAQUE), [enumDomainValues], [enumKnownDomainValues],
 *    [enumValueDomainIsComplete], [enumDeclIsAmbient], [numericLiteralFitsEnum],
 *    [enumTargetAdmitsNumericSource], [enumMemberValueEqualsLiteral];
 *  - RELATION: [enumTypesRelation] and its [EnumRelFailure] result, plus the
 *    flavour predicates [isEnumFlavoredObjectType],
 *    [enumMemberTypeIsStringValued], [enumLiteralApparentPrimitive],
 *    [enumMemberTypesAreSameMember], and the symbol readers [enumOwnTypeSymbol]
 *    / [enumOfMemberTypeSymbol] / [enumTypeOfMemberType] /
 *    [enumMemberAccessType];
 *  - DISPLAY: the TS2367 no-overlap pair ([enumComparisonNoOverlapDisplays],
 *    [enumLiteralComparisonNoOverlapDisplays], [enumCannotHoldLiteral]), the
 *    relation-error collapse ([relationErrorTargetDisplay],
 *    [oneMemberEnumCollapsedDisplay]), the qualified-name family
 *    ([enumTypeQualifiedDisplay], [enumModuleImportPrefix],
 *    [enumCollisionQualifiedDisplays], [enumQualifiedRelationDisplays]) and the
 *    elaboration ([enumRelationElaboration], [enumUnionTargetDisplay]).
 *
 * ## What it owns, and what it still reads
 *
 * The family owns its four memos and the display flag
 * ([enumDisplayFullyQualified]); they moved in with it, which is what takes the
 * ambient row from 17 to 12. The twelve that remain are reached through
 * [checker]: the type system it displays and unions with
 * (`typeToString`, `getUnionType`, `getDeclaredTypeOfSymbol`,
 * `getDeclaredTypeOfEnumMember`, `baseTypeOfLiteralType`,
 * `literalTypeOfExpression`, `isLiteralAssignableToMember`, `fmtEnumAsgVal`),
 * the canonicalizer `canonicalEnumSymbol`, the binder-side `enumValues` table,
 * and `moduleFiles` / `isDtsFile`.
 *
 * ## Wiring
 *
 * [Relater] and [MemberNames] call this collaborator DIRECTLY rather than
 * through `Checker` — the first cross-collaborator edge of the arc, and the
 * reason `Relater`'s ambient row falls from 45 to 34. It is safe because the
 * construction block of `Checker` is an explicit ordered graph and this class is
 * constructed before both; see the note in `docs/inversion-ambient-ledger.md`.
 */
internal class EnumSemantics(
    private val checker: Checker,
) {

    /** (REL.1)(c) round 745: memo for [enumValueDomainIsComplete], keyed by enum symbol id.
     *  Declared before `init` per the init-order trap. */
    private val enumDomainCompleteCache = HashMap<Int, Boolean>()

    /** (REL.1)(c) round 746: memo for [enumMemberEntries], keyed by enum symbol id.
     *  Declared before `init` per the init-order trap. */
    private val enumMemberEntriesCache = HashMap<Int, List<Pair<String, ConstantValue?>>?>()

    /** (REL.2) round 764: memo for [enumMemberTypesOf], keyed by enum symbol id.
     *  Declared before `init` per the init-order trap. */
    private val enumMemberTypesCache = HashMap<Int, List<Type>?>()

    /** (REL.1)(c) round 746: memo for [enumTypesRelation], keyed by the canonical
     *  (source, target) enum symbol id pair. Declared before `init` per the
     *  init-order trap. */
    private val enumTypesRelationCache = HashMap<Long, EnumRelFailure?>()

    /** (REL.1)(c) round 747: tsc's `TypeFormatFlags.UseFullyQualifiedType`, restricted to
     *  ENUM names — while set, [typeToString] renders an enum (or enum-member) type with its
     *  namespace path, at ANY nesting depth, so a colliding pair inside a rendered function
     *  type prints `(param: second.E) => void` rather than `(param: E) => void`. Set only by
     *  [enumQualifiedRelationDisplays], the collision retry; declared before `init` per the
     *  init-order trap. */
    var enumDisplayFullyQualified = false


    /**
     * (REL.2) round 764: the MEMBER TYPES of an enum's own type, in declaration order,
     * or `null` when [type] is not a decomposable enum.
     *
     * This is the thing tsc has for free and we do not. tsc models a literal enum AS
     * the union of its members, so every SUBTRACTIVE narrowing (`k !== K.A`, a type
     * guard's false branch, an exhaustive `default:`) is ordinary union filtering
     * there. We mint one member-LESS `Type.Object` for the whole enum, so those
     * narrows had nothing to subtract FROM and answered the whole enum — round 763
     * closed the POSITIVE direction (which needs no decomposition: the narrowed type
     * is the tested member) and left this one, because it is the half that changes
     * type DISPLAY.
     *
     * DELIBERATELY NOT a `Type` — the caller decides whether to build the union, so
     * the "nothing was removed" path can hand back the original enum type and keep
     * displaying as `K`. Member ORDER is [enumMemberEntries]'s declaration order,
     * which is what makes the display `K.B | K.C | K.D` rather than id-ordered.
     *
     * `null` for a member name absent from the canonical enum's exports or for a
     * member whose type does not mint as an `EnumLiteral`: an incomplete
     * decomposition would SUBTRACT from a domain that is not the real one, and a
     * partial answer here is a wrong narrow, not a conservative one.
     */
    fun enumMemberTypesOf(type: Type): List<Type>? {
        val sym = (type as? Type.Object)?.symbol ?: return null
        if (sym.flags.hasNone(SymbolFlags.Enum)) return null
        return enumMemberTypesCache.getOrPut(sym.id) {
            val canonical = checker.canonicalEnumSymbol(sym)
            val names = enumMemberEntries(canonical) ?: return@getOrPut null
            if (names.isEmpty()) return@getOrPut null
            val exports = canonical.exports ?: return@getOrPut null
            val out = mutableListOf<Type>()
            for ((name, _) in names) {
                val memberSym = exports[name] ?: return@getOrPut null
                if (memberSym.flags.hasNone(SymbolFlags.EnumMember)) return@getOrPut null
                val memberType = checker.getDeclaredTypeOfEnumMember(memberSym)
                if (memberType.flags.hasNone(TypeFlags.EnumLiteral)) return@getOrPut null
                if (out.none { it === memberType }) out.add(memberType)
            }
            out
        }
    }

    /**
     * (REL.2) round 765: is every constituent of [targets] a MEMBER of [t]'s own enum?
     *
     * The round-746 owner rule, factored out of [enumMinusMembers] so the POSITIVE
     * direction can share it: `Z.Foo.A` is not a member of an `X.Foo`, and a plain
     * literal (`case "z":`, `case 0:`) is not a member of anything — either bails, which
     * is what keeps a mixed enum/literal case list and a foreign enum's member out of
     * both the subtraction and the substitution.
     */
    fun enumTargetsAreOwnMembers(t: Type, targets: List<Type>): Boolean =
        targets.isNotEmpty() &&
            targets.all { it.flags.hasAny(TypeFlags.EnumLiteral) && checker.isLiteralAssignableToMember(it, t) }

    /**
     * (REL.2) round 783: did a flow walk narrow the ENUM [raw] to a proper subset of its
     * OWN members?
     *
     * The object-literal value path (round 438/462/468) accepts a flow-narrowed value only
     * when the CONTEXTUAL property type accepts it and the raw type does not — and an
     * object literal in a ternary BRANCH has no contextual type at all (`ctxObj` is null
     * there), so `{ importKind }` after `importKind === ImportKind.Named ||
     * importKind === ImportKind.Default` kept the whole enum where tsc keeps the two
     * members. A non-enum literal union is unaffected by that gap; the enum is, because
     * until (REL.2) the whole enum related to the member target vacuously and the loss was
     * invisible.
     *
     * This third acceptance needs no contextual type because it is monotone by
     * CONSTRUCTION rather than by relation test: [enumTargetsAreOwnMembers] is the
     * round-746 owner rule, so the narrowed value is always a sub-union of the very type
     * being replaced — every target that accepted the enum accepts the subset.
     */
    fun enumNarrowIsOwnMemberSubset(raw: Type, narrowed: Type): Boolean {
        if (!R783_OBJLIT_ENUM_NARROW) return false
        if (narrowed === raw || narrowed === neverType) return false
        if (enumOwnTypeSymbol(raw) == null) return false
        val parts = if (narrowed is Type.Union) narrowed.types else listOf(narrowed)
        return enumTargetsAreOwnMembers(raw, parts)
    }

    /**
     * (REL.2) round 764: subtract [remove] from the enum type [t], as tsc's union
     * filtering would — or `null` when that cannot be answered.
     *
     * The gate is deliberately narrow: every constituent of [remove] must be a MEMBER
     * of [t]'s own enum ([isLiteralAssignableToMember] carries the round-746 owner
     * rule, so `Z.Foo.A` never subtracts from an `X.Foo`), and the enum must decompose
     * completely. A subtraction that removes NOTHING returns [t] itself rather than an
     * equivalent union, which keeps every unaffected message displaying `K`.
     */
    fun enumMinusMembers(t: Type, remove: List<Type>): Type? {
        if (!enumTargetsAreOwnMembers(t, remove)) return null
        val members = enumMemberTypesOf(t) ?: return null
        val kept = members.filter { m -> remove.none { enumMemberTypesAreSameMember(m, it) } }
        return when {
            kept.size == members.size -> t
            kept.isEmpty() -> neverType
            kept.size == 1 -> kept[0]
            else -> checker.getUnionType(kept)
        }
    }

    /**
     * (REL.1)(a): is [type] an enum-MEMBER type, and are its runtime values STRINGS?
     *
     * `null` means "not an enum-member type at all". `false` covers both a numeric
     * member and a member whose value is not evaluable — the same auto-numeric
     * default [isNumericEnumObjectType] applies, since an enum member without a
     * computed value is an implicitly-numbered one.
     */
    /**
     * (CHK.60) The APPARENT PRIMITIVE of an enum MEMBER type — `stringType` for a
     * string-valued member, `numberType` for a numeric one — or `null` for anything
     * else.
     *
     * tsc has no need of this: an enum literal type there IS a `StringLiteral` /
     * `NumberLiteral` type carrying `EnumLiteral` beside it, so `getApparentType`
     * answers `String` / `Number` without asking anyone. (REL.1)(b) mints a member-LESS
     * [Type.Object] instead, and this is what recovers the flavour the flags no longer
     * carry.
     *
     * **POSITIVE EVIDENCE ONLY, WHICH IS WHY THIS IS NOT
     * [isStringEnumObjectType]/[isNumericEnumObjectType].** Those two answer a
     * three-valued question with two values — `isNumericEnumObjectType` defaults an
     * UNEVALUATED member to numeric, which is right for its arithmetic caller (tsc's
     * own default for a member with no initializer) and wrong here: in a RELATION that
     * default would relate an enum member of unknown value to every target `Number`
     * satisfies, i.e. it would answer in the FALSE-NEGATIVE direction, which has no
     * gate. A member whose value did not evaluate keeps the rejection it has today.
     *
     * The WHOLE enum type answers `null` by construction — it carries [TypeFlags.Enum],
     * not [TypeFlags.EnumLiteral], and has no member name to look up.
     */
    fun enumLiteralApparentPrimitive(source: Type): Type? {
        if (source.flags.hasNone(TypeFlags.EnumLiteral)) return null
        val memberSym = (source as? Type.Object)?.symbol ?: return null
        val owner = memberSym.parent ?: return null
        return when (checker.enumValues[checker.canonicalEnumSymbol(owner).id]?.get(memberSym.name)) {
            is ConstantValue.StringValue -> stringType
            is ConstantValue.NumberValue -> numberType
            else -> null
        }
    }

    fun enumMemberTypeIsStringValued(type: Type): Boolean? {
        if (type.flags.hasNone(TypeFlags.EnumLiteral)) return null
        val memberSym = (type as? Type.Object)?.symbol ?: return null
        val owner = memberSym.parent ?: return null
        val value = checker.enumValues[checker.canonicalEnumSymbol(owner).id]?.get(memberSym.name)
        return value is ConstantValue.StringValue
    }

    /**
     * (CHK.83): does the enum-MEMBER type [source] carry exactly the value of the
     * string/number literal [literal]?
     *
     * The value is read through [enumMemberEntries] — the view that answers `null` for
     * a member tsc treats as OPAQUE (an ambient non-const member with no initializer,
     * which [enumValues] auto-numbers for the Transformer) — because tsc's rule needs
     * a `NumberLiteral`/`StringLiteral` flag on the member and an opaque member has
     * neither: `declare enum Amb { P }` puts `Amb.P` beside NO literal, measured on
     * both references as `Type 'Amb.P' is not assignable to type '0'`. A computed
     * member answers `false` the same way.
     */
    fun enumMemberValueEqualsLiteral(source: Type, literal: Type): Boolean {
        if (source.flags.hasNone(TypeFlags.EnumLiteral)) return false
        val memberSym = (source as? Type.Object)?.symbol ?: return false
        val owner = memberSym.parent ?: return false
        val value = enumMemberEntries(owner)?.firstOrNull { it.first == memberSym.name }?.second ?: return false
        return when (literal) {
            is Type.NumberLiteral -> value is ConstantValue.NumberValue && value.value == literal.value
            is Type.StringLiteral -> value is ConstantValue.StringValue && value.value == literal.value
            else -> false
        }
    }

    /**
     * (REL.1)(a): is [type] an enum-flavored `Type.Object` — an enum's OWN type, or
     * one of its MEMBER types?
     *
     * Every classifier whose rule is "an enum value is a number/string at runtime,
     * never an object" must accept BOTH. Before round 741 an enum-member annotation
     * was `anyType`, so those classifiers only ever saw the enum's own type and could
     * test `SymbolFlags.Enum` alone; minting the member type turns that test into a
     * silent misclassification (`ModuleKind.ESNext` surviving a
     * `typeof result === "object"` narrow, which is how program.ts:1341 FP'd TS2339).
     */
    fun isEnumFlavoredObjectType(type: Type): Boolean =
        (type as? Type.Object)?.symbol?.flags?.hasAny(SymbolFlags.Enum or SymbolFlags.EnumMember) == true

    /**
     * (REL.1)(b): do two enum-MEMBER types denote the SAME member?
     *
     * **Deliberately NOT an identity test**, which is the trap this answers.
     * [getDeclaredTypeOfEnumMember] interns on [canonicalEnumSymbol], but that helper
     * can only canonicalize through `globals[name]` — and since INV.3(d) retired the
     * merge for module-only names, a MODULE-scoped enum (every enum in tsc's own
     * sources, `SyntaxKind` included) has no global instance to canonicalize to. Its
     * per-file `Symbol` instances therefore key DIFFERENT member types for the same
     * member, and an identity verdict declares `SyntaxKind.StringLiteral` disjoint
     * from itself.
     *
     * So compare the way tsc's `isEnumTypeRelatedTo` does: same member NAME, and an
     * owning enum that is the same symbol, canonicalizes to the same symbol, or shares
     * an `EnumDeclaration` NODE (identity compare on the node — never data-class
     * equality, which would deep-recurse the whole declaration).
     */
    fun enumMemberTypesAreSameMember(source: Type, target: Type): Boolean {
        val sourceMember = (source as? Type.Object)?.symbol ?: return false
        val targetMember = (target as? Type.Object)?.symbol ?: return false
        if (sourceMember === targetMember) return true
        if (sourceMember.name != targetMember.name) return false
        val sourceEnum = sourceMember.parent ?: return false
        val targetEnum = targetMember.parent ?: return false
        if (sourceEnum === targetEnum) return true
        if (checker.canonicalEnumSymbol(sourceEnum).id == checker.canonicalEnumSymbol(targetEnum).id) return true
        if (sourceEnum.name != targetEnum.name) return false
        if (sourceEnum.declarations.any { d ->
                d is EnumDeclaration && targetEnum.declarations.any { it === d }
            }
        ) return true
        // (REL.1)(c) round 746: two DISTINCT enums of the same name — tsc pairs the
        // member comparison with `isEnumTypeRelatedTo` on the OWNING enums, so
        // `Y.Foo.A` is `X.Foo.A` (both `A = 0`) while `Z.Foo.A` (`A = 1 << 1`) is not.
        // Without this the name match alone made every same-named member of every
        // same-named enum interchangeable, which is the half of
        // `enumLiteralAssignableToEnumInsideUnion` B266 still owned.
        return enumTypesRelation(sourceEnum, targetEnum) == null
    }

    /**
     * (REL.1)(b0) round 742: the CONSTANT VALUES an enum-flavored type can hold —
     * every member's value for an enum's own type, the single member's value for a
     * member type. `null` when [type] is not enum-flavored or its values were never
     * evaluated (an unknown domain must stay unknown, never become empty: an empty
     * set would read as "no value matches" and narrow a member away).
     */
    fun enumDomainValues(type: Type): List<ConstantValue>? {
        val sym = (type as? Type.Object)?.symbol ?: return null
        return when {
            sym.flags.hasAny(SymbolFlags.Enum) ->
                checker.enumValues[checker.canonicalEnumSymbol(sym).id]?.values?.toList()
            sym.flags.hasAny(SymbolFlags.EnumMember) -> {
                val owner = sym.parent?.takeIf { it.flags.hasAny(SymbolFlags.Enum) } ?: return null
                checker.enumValues[checker.canonicalEnumSymbol(owner).id]?.get(sym.name)?.let { listOf(it) }
            }
            else -> null
        }
    }

    /**
     * (REL.1)(c) round 745: the value domain of an enum-flavored [type], but ONLY
     * when it is COMPLETE — every member of the owning enum has a value we
     * evaluated to a literal. `null` otherwise, and `null` must be read as "this
     * enum can hold anything numeric", never as "the empty domain".
     *
     * This is the gate [enumDomainValues] deliberately does not apply: that helper
     * answers "which values have we evaluated", which is the right question for
     * NARROWING (dropping a constituent needs only the values we know) and the
     * wrong one for REJECTION. An enum with one un-evaluable member is tsc's
     * *computed* enum: its member types carry `TypeFlags.Enum` rather than a
     * literal value, so every numeric literal stays assignable to it.
     */
    private fun enumKnownDomainValues(type: Type): List<ConstantValue>? {
        val sym = (type as? Type.Object)?.symbol ?: return null
        val owner = when {
            sym.flags.hasAny(SymbolFlags.Enum) -> sym
            sym.flags.hasAny(SymbolFlags.EnumMember) ->
                sym.parent?.takeIf { it.flags.hasAny(SymbolFlags.Enum) }
            else -> null
        } ?: return null
        if (!enumValueDomainIsComplete(owner)) return null
        return enumDomainValues(type)
    }

    /**
     * (REL.1)(c) round 745: is EVERY member of [enumSym] backed by an evaluated
     * literal value?
     *
     * Three ways to answer no, each of them a shape tsc models as a *computed*
     * enum (`createComputedEnumType`), i.e. one that keeps accepting any number:
     * a member whose initializer the constant evaluator could not fold; a member
     * with a name we cannot key ([enumValues] skips it); and — the non-obvious
     * one — a member with NO initializer in an AMBIENT non-const enum, which tsc
     * explicitly declines to auto-number (`declare enum D { X, Y }` has undefined
     * member values, so `let d: D = 7` is legal). We DO auto-number those in
     * [computeEnumSymbolValues], because the Transformer needs a value to emit;
     * this predicate is where that difference must not leak into the relation.
     *
     * An enum with no members at all answers `false`: an empty domain would read
     * as "nothing is assignable" and reject everything.
     */
    private fun enumValueDomainIsComplete(enumSym: Symbol): Boolean =
        enumDomainCompleteCache.getOrPut(enumSym.id) {
            val canonical = checker.canonicalEnumSymbol(enumSym)
            val values = checker.enumValues[canonical.id] ?: return@getOrPut false
            val decls = canonical.declarations.filterIsInstance<EnumDeclaration>()
                .ifEmpty { enumSym.declarations.filterIsInstance<EnumDeclaration>() }
            var memberCount = 0
            for (decl in decls) {
                val ambient = enumDeclIsAmbient(decl)
                val isConst = ModifierFlag.Const in decl.modifiers
                for (member in decl.members) {
                    val name = when (val n = member.name) {
                        is Identifier -> n.text
                        is StringLiteralNode -> n.text
                        is NumericLiteralNode -> n.text
                        else -> return@getOrPut false
                    }
                    memberCount++
                    if (name !in values) return@getOrPut false
                    if (member.initializer == null && ambient && !isConst) return@getOrPut false
                }
            }
            memberCount > 0
        }

    /**
     * (REL.1)(c) round 745: is [decl] in an ambient context — its own `declare`, an
     * enclosing `declare namespace`, or a `.d.ts` file?
     *
     * An UNINDEXED declaration (no [NodeBase.parent] chain, so no owning
     * `SourceFile`) answers `true`: the caller uses this to decide whether a value
     * domain may be trusted, and "we could not tell" must degrade to "do not
     * trust it".
     */
    private fun enumDeclIsAmbient(decl: EnumDeclaration): Boolean {
        if (ModifierFlag.Declare in decl.modifiers) return true
        var cur: Node? = decl.parent
        var hops = 0
        while (cur != null && hops++ < 4096) {
            if (cur is SourceFile) return checker.isDtsFile(cur.fileName)
            if (cur is ModuleDeclaration && ModifierFlag.Declare in cur.modifiers) return true
            cur = (cur as NodeBase).parent
        }
        return true
    }

    /**
     * (REL.1)(c) round 745: may a NUMERIC-LITERAL [source] be assigned to the
     * enum-flavored [target]?
     *
     * tsc's rule, verbatim from `isSimpleTypeRelatedTo`: the WIDE `number` is
     * assignable to any numeric enum or enum member (the bit-flag compatibility
     * rule — `flags & Mask` types as `number`), but a numeric LITERAL is
     * assignable only to a member with the SAME value, or to any member of an
     * enum whose domain is not fully known.
     *
     * Anything that is not a numeric literal answers `true`, so this only ever
     * TIGHTENS the numeric-literal case and no other source can start failing.
     */
    fun numericLiteralFitsEnum(source: Type, target: Type): Boolean {
        val lit = source as? Type.NumberLiteral ?: return true
        val domain = enumKnownDomainValues(target) ?: return true
        return domain.any { it is ConstantValue.NumberValue && it.value == lit.value }
    }

    /**
     * (CHK.113)(a): may a `number`-flavoured source be assigned to the enum-flavoured
     * [target] at all?
     *
     * tsc models an all-literal enum as the UNION of its members' literal types, and
     * `isSimpleTypeRelatedTo` accepts a `Number`/`NumberLiteral` source only against a
     * constituent that is itself a NUMERIC enum literal (`t & NumberLiteral &&
     * t & EnumLiteral`) or against a *computed* enum, which carries no literal domain
     * at all. A pure STRING enum therefore has no constituent a number can reach —
     * measured on both references, `const m: SOne = <number>` is `TS2322` for
     * `enum SOne { A = "a" }` — while [numericLiteralFitsEnum] answers `true` for
     * every non-literal source, so before this the wide `number` was accepted against
     * every enum whatever its flavour.
     *
     * **POSITIVE EVIDENCE OF STRING-NESS ONLY, because this can only ever ADD a
     * diagnostic.** The values are read through [enumMemberEntries] — the view that
     * answers `null` for a member tsc treats as OPAQUE (an ambient non-`const` member
     * with no initializer, which [enumValues] auto-numbers for the Transformer) — and
     * an opaque member, an enum with no comparable declaration and an empty enum all
     * keep today's acceptance. Only an enum EVERY one of whose members is proved
     * string-valued rejects, which is exactly [isStringEnumObjectType]'s conservatism
     * with the ambient case additionally honoured. A MIXED enum accepts, and tsc
     * agrees: its numeric member is a constituent the number reaches.
     */
    fun enumTargetAdmitsNumericSource(target: Type): Boolean {
        val sym = (target as? Type.Object)?.symbol ?: return true
        val entries = enumMemberEntries(sym) ?: return true
        if (entries.isEmpty()) return true
        return entries.any { it.second !is ConstantValue.StringValue }
    }

    /**
     * (REL.1)(c) round 746: why two DISTINCT enums do not relate — tsc's
     * `isEnumTypeRelatedTo` error reporter, as a value rather than a callback.
     *
     * [Plain] is "not related, and tsc reports no elaboration"; the other two carry
     * the elaboration's ingredients but NOT its text, because the target's display
     * name depends on the source it is being compared against (see
     * [enumRelationChainLine]).
     */
    internal sealed class EnumRelFailure {
        /** Different simple name, or a `const` enum — nominal mismatch, no chain. */
        object Plain : EnumRelFailure()
        class Missing(val member: String) : EnumRelFailure()
        class ValueDiffers(val member: String, val expected: String, val given: String) : EnumRelFailure()
        class StringVsUnknown(val member: String, val stringValue: String) : EnumRelFailure()
    }

    /**
     * (REL.1)(c) round 746: an enum's members in DECLARATION order (merged across
     * every declaration block), paired with the value **tsc** gives them.
     *
     * A `null` value is tsc's *opaque* member, and there are two ways to get one: an
     * initializer the constant evaluator could not fold, and a member with no
     * initializer in an AMBIENT non-const enum, which tsc declines to auto-number
     * (`declare enum D { X, Y }`). We DO auto-number the latter — the Transformer
     * needs a value to emit — so [enumValues] cannot be read directly here; this is
     * the same difference [enumValueDomainIsComplete] guards, at member granularity.
     *
     * `null` for the whole enum means "not comparable at all" (no declaration, or a
     * member name we cannot key), which every caller must read as "relate leniently".
     */
    fun enumMemberEntries(enumSym: Symbol): List<Pair<String, ConstantValue?>>? =
        enumMemberEntriesCache.getOrPut(enumSym.id) {
            val canonical = checker.canonicalEnumSymbol(enumSym)
            val values = checker.enumValues[canonical.id]
            val decls = canonical.declarations.filterIsInstance<EnumDeclaration>()
                .ifEmpty { enumSym.declarations.filterIsInstance<EnumDeclaration>() }
            if (decls.isEmpty()) return@getOrPut null
            val out = mutableListOf<Pair<String, ConstantValue?>>()
            for (decl in decls) {
                val ambient = enumDeclIsAmbient(decl)
                val isConst = ModifierFlag.Const in decl.modifiers
                for (member in decl.members) {
                    val name = when (val n = member.name) {
                        is Identifier -> n.text
                        is StringLiteralNode -> n.text
                        is NumericLiteralNode -> n.text
                        else -> return@getOrPut null
                    }
                    val opaque = member.initializer == null && ambient && !isConst
                    out.add(name to if (opaque) null else values?.get(name))
                }
            }
            out
        }

    /**
     * (REL.1)(c) round 746: tsc's `isEnumTypeRelatedTo` — is every member of
     * [sourceEnum] present in [targetEnum] with an EQUAL value? `null` means related.
     *
     * This is the question step (b)'s [enumMemberTypesAreSameMember] deliberately did
     * not ask, and the reason `checkEnumToEnumAssignments` (B425) and
     * `checkNamespaceEnumUnionAssignments` (B266) outlived round 745: the relation
     * could tell one enum's member from another's, but not whether the two ENUMS are
     * value-identical, which is what makes `Y.Foo.A` assignable to `X.Foo` and
     * `Z.Foo.A` not.
     *
     * Three shapes answer "related" WITHOUT comparing values, and each is a
     * multi-instance guard rather than a semantic rule: the same canonical symbol, a
     * shared `EnumDeclaration` NODE (identity compare — never data-class equality,
     * which deep-recurses the declaration), and an enum we cannot enumerate at all.
     * Post-INV.3(d) a module-scoped enum has no global instance to canonicalize to,
     * so a strict port of tsc's `sourceSymbol === targetSymbol` would declare
     * `SyntaxKind` unrelated to itself — the same trap [enumMemberTypesAreSameMember]
     * records.
     *
     * An OPAQUE member on either side is compatible with anything numeric (tsc:
     * "at least one of the values is undefined ... just return"), which is why a
     * `declare enum` relates to a concrete one until a member's value is known on
     * BOTH sides and differs.
     */
    /**
     * (CHK.86): the two DISPLAY strings of a `TS2367` for an equality comparison whose
     * operands are both enum-flavored and provably cannot overlap, or null when either
     * operand is not enum-flavored or an overlap is possible.
     *
     * tsc reaches this through the general operator reporter, whose comparability test is
     * `isTypeEqualityComparableTo(l, r) || isTypeEqualityComparableTo(r, l)` — symmetric,
     * which is why [enumEnumsOverlap] asks [enumTypesRelation] in BOTH directions (that
     * helper is directional: every SOURCE member must be present in the TARGET).
     *
     * The display is tsc's `getBaseTypesIfUnrelated`, transcribed:
     *
     * ```
     * const leftBase = getBaseTypeOfLiteralType(leftType);
     * const rightBase = getBaseTypeOfLiteralType(rightType);
     * if (!isRelated(leftBase, rightBase)) { effectiveLeft = leftBase; effectiveRight = rightBase; }
     * ```
     *
     * i.e. widen BOTH operands to their enums and, if they are STILL unrelated, print the
     * enums; otherwise print the originals. That single line accounts for every display
     * measured on both references: `ka === kb` prints `'K.A' and 'K.B'` (the bases are one
     * enum, so they ARE related and the members survive) while `ka === jx` prints
     * `'K' and 'J'` and not `'K.A' and 'J.X'`, and `ka === j` — a member against a whole
     * enum — prints `'K' and 'J'`.
     *
     * SCOPE. Both operands must decompose into enum-flavored atoms ([enumComparisonAtoms]
     * admits a UNION of them, which is what a narrowed reference is), so nothing else can
     * reach this rule: an enum against `number`, against a numeric literal, or against
     * `string` keeps whatever answer it has today. The enum-against-numeric-LITERAL sibling
     * is a real and separately measured gap — both references report `ka === 1` as
     * `'K.A' and '1'` and we are silent — but deciding it needs the member VALUES
     * ([enumDomainValues] / the round-745 `numericLiteralFitsEnum` machinery) rather than
     * enum identity, so it is left to its own round.
     */
    fun enumComparisonNoOverlapDisplays(leftType: Type, rightType: Type): Pair<String, String>? {
        val leftAtoms = enumComparisonAtoms(leftType) ?: return null
        val rightAtoms = enumComparisonAtoms(rightType) ?: return null
        if (enumAtomListsOverlap(leftAtoms, rightAtoms)) return null
        val leftBase = enumComparisonBaseType(leftType, leftAtoms)
        val rightBase = enumComparisonBaseType(rightType, rightAtoms)
        val basesRelated = enumComparisonAtoms(leftBase)?.let { lb ->
            enumComparisonAtoms(rightBase)?.let { rb -> enumAtomListsOverlap(lb, rb) }
        } == true
        return if (basesRelated) checker.typeToString(leftType) to checker.typeToString(rightType)
        else checker.typeToString(leftBase) to checker.typeToString(rightBase)
    }

    /**
     * (CHK.88): the two DISPLAY strings of a `TS2367` for an equality comparison between an
     * ENUM-flavored operand and a LITERAL whose value the enum provably cannot hold, or
     * `null` when no such pair is present.
     *
     * Measured against tsgo 7.0.2 AND pristine `typescript@6.0.3`, which agree on every
     * row: `ka === 1` (`ka: K.A`, `A = 0`) reads
     * `… the types 'K.A' and '1' have no overlap.` there and was SILENT here, as were
     * `ka === -1`, `k === 5` (a whole enum), `cp === 4` (a `const` enum), `m === 4` (an enum
     * whose members are foldable bit shifts), `kab === 5` (a union of members) and
     * `sp === "z"` (a string enum).
     *
     * WHY IT IS NOT (CHK.86)'s RULE. That one is gated to BOTH operands being enum-flavored
     * and answers from enum IDENTITY; the verdict here needs the member VALUES, which is
     * round 745's [enumKnownDomainValues] machinery — and that helper's whole point is that
     * it answers `null` (i.e. "this enum can hold anything") for a *computed* enum, which is
     * exactly why `declare enum D { X, Y }` and `enum Comp { X = "ab".length }` keep
     * accepting every literal in all three compilers.
     *
     * DISPLAY. tsc's `getBaseTypesIfUnrelated` widens both operands and prints the pair only
     * when the WIDENED pair is still unrelated; an enum's base is its own primitive
     * (`number`/`string`), which IS related to the literal's base, so the ORIGINALS always
     * survive here. That is why `ka === 1` prints `'K.A'` and not `'K'`.
     *
     * FLAVOUR. A literal whose kind does not match every value in the enum's domain is left
     * to the CATEGORY rule below, which is what both references do too: `ka === "z"` reads
     * `'K' and 'string'` there, NOT `'K.A' and '"z"'`.
     */
    fun enumLiteralComparisonNoOverlapDisplays(
        leftType: Type, left: Expression, rightType: Type, right: Expression,
    ): Pair<String, String>? {
        enumCannotHoldLiteral(leftType, rightType, right)?.let { return enumOperandDisplay(leftType) to it }
        enumCannotHoldLiteral(rightType, leftType, left)?.let { return it to enumOperandDisplay(rightType) }
        return null
    }

    /**
     * (CHK.88): the display of the ENUM-flavored operand. A union covering EVERY member of
     * one enum prints as the bare enum, because in tsc a numeric enum **is** the union of
     * its members and `K.A | K.B` is therefore literally the type `K` — measured, both
     * references print `'K' and '5'` for `kab === 5` with `kab: K.A | K.B`.
     * [enumUnionTargetDisplay] is round 746's transcription of `formatUnionTypes` and
     * already owns that collapse; it answers `null` for a non-union, which is the ordinary
     * single-member/whole-enum case.
     */
    private fun enumOperandDisplay(t: Type): String =
        enumUnionTargetDisplay(t)
            // (CHK.92)(d): NO bypass is needed here — the ONE-member collapse lives on the
            // relation-error target path ([relationErrorTargetDisplay]) and not in
            // [typeToString], so this operand keeps `Cmp.X` by construction. That is the
            // right answer for the FRESH cases and the wrong one for the widened ones:
            // measured on both references, `Cmp.X === 5` and `const cx = Cmp.X; cx === 5`
            // read `'Cmp.X'` while `let lx = Cmp.X; lx === 5` and an annotated
            // `declare const av: Cmp.X` read `'Cmp'`. TS2367's rule is literal FRESHNESS,
            // which this checker does not model (it mints no fresh enum-member type), so the
            // four shapes share ONE type and the split is not expressible; the two widened
            // rows stay divergent and are recorded rather than closed by a syntactic proxy.
            ?: checker.typeToString(t)

    /**
     * (CHK.92)(d): the string a RELATION ERROR prints for a TARGET — [typeToString] plus the
     * ONE-member enum collapse.
     *
     * A one-member enum's declared type IS its member's regular type in tsc
     * (`getDeclaredTypeOfEnum` unions the member types; `getUnionType` of a single type
     * returns that type), so `const a: Cmp.X = <string>`, `f(x: Cmp.X)`, a `Cmp.X`-typed
     * property and a `Cmp.X`-annotated return all read `'Cmp'` in tsgo 7.0.2 and pristine
     * 6.0.3, while a TWO-member enum keeps `'Two.P'`.
     *
     * IT LIVES HERE AND NOT IN [typeToString] BECAUSE THAT RENDERER HAS CONSUMERS BEYOND
     * DIAGNOSTICS. It feeds the language service's `quickInfoAt` and the externals
     * generator's markers, and the collapse is measurably wrong for the first: asked through
     * `tools/tsgo-7.0.2/lib/tsc --lsp -stdio`, a caret on the sole member of
     * `enum Valued { Gamma = 5 }` answers `(enum member) Valued.Gamma = 5` — the qualified
     * name, exactly as a two-member enum's member does. A first attempt DID put it in
     * `typeToString` with a bypass at the TS2367 operand; the second bypass the language
     * service then needed is the signal that the rule was in the wrong place.
     */
    fun relationErrorTargetDisplay(t: Type): String =
        oneMemberEnumCollapsedDisplay(t) ?: checker.typeToString(t)

    /**
     * (CHK.92)(d): the PARENT enum's name when [memberSym]'s enum declares exactly one
     * member, else null — see [relationErrorTargetDisplay].
     */
    fun oneMemberEnumCollapsedDisplay(t: Type): String? {
        val sym = (t as? Type.Object)?.symbol ?: return null
        if (!sym.flags.hasAny(SymbolFlags.EnumMember)) return null
        return oneMemberEnumParentName(sym)
    }

    private fun oneMemberEnumParentName(memberSym: Symbol): String? {
        val parent = memberSym.parent ?: return null
        if (!parent.flags.hasAny(SymbolFlags.Enum)) return null
        if ((enumMemberEntries(parent)?.size ?: 0) != 1) return null
        return parent.name
    }

    /**
     * (CHK.88): the literal's display when [enumType] is enum-flavored, [otherExpr] is a
     * literal of the SAME flavour as every value the enum can hold, and that value is
     * outside the enum's KNOWN domain. `null` in every other case — including every case
     * this checker cannot decide, so the rule is refusal-shaped exactly as (CHK.86)'s is.
     *
     * [otherType] must NOT be enum-flavored: such a pair belongs to
     * [enumComparisonNoOverlapDisplays], which runs first and returns.
     *
     * The literal is read off the AST through [literalTypeOfExpression] and not from
     * [otherType], because `getTypeOfExpression` answers the BASE primitive for a literal
     * node — there is no fresh-literal expression type in this checker, so `1` arrives here
     * as `number` and the value would be gone.
     */
    private fun enumCannotHoldLiteral(enumType: Type, otherType: Type, otherExpr: Expression): String? {
        val atoms = enumComparisonAtoms(enumType) ?: return null
        if (enumComparisonAtoms(otherType) != null) return null
        val lit = checker.literalTypeOfExpression(otherExpr) ?: return null
        val domain = ArrayList<ConstantValue>()
        for (atom in atoms) domain.addAll(enumKnownDomainValues(atom) ?: return null)
        if (domain.isEmpty()) return null
        return when (lit) {
            is Type.NumberLiteral -> {
                if (domain.any { it !is ConstantValue.NumberValue }) return null
                if (domain.any { (it as ConstantValue.NumberValue).value == lit.value }) null
                else checker.typeToString(lit)
            }
            is Type.StringLiteral -> {
                if (domain.any { it !is ConstantValue.StringValue }) return null
                if (domain.any { (it as ConstantValue.StringValue).value == lit.value }) null
                else checker.typeToString(lit)
            }
            else -> null
        }
    }

    /**
     * (CHK.86): the enum-flavored constituents of [t] — the type itself, or a union's
     * members — or null when ANY constituent is not enum-flavored.
     *
     * The all-or-nothing answer is the firewall: a union that mixes an enum with
     * `undefined` (the ordinary optional shape) must not be judged here at all, because
     * the nullish half overlaps things this rule knows nothing about.
     */
    fun enumComparisonAtoms(t: Type): List<Type>? {
        val parts = if (t is Type.Union) t.types else listOf(t)
        if (parts.isEmpty()) return null
        for (p in parts) {
            if (enumOwnTypeSymbol(p) == null && enumOfMemberTypeSymbol(p) == null) return null
        }
        return parts
    }

    /** (CHK.86): tsc's `getBaseTypeOfLiteralType` over a comparison operand — every enum
     *  member widened to its enum, deduplicated, so `K.A | K.B` becomes `K`. */
    fun enumComparisonBaseType(t: Type, atoms: List<Type>): Type {
        val widened = atoms.map { checker.baseTypeOfLiteralType(it) }
        val distinct = mutableListOf<Type>()
        for (w in widened) if (distinct.none { it === w }) distinct.add(w)
        return when {
            distinct.size == 1 -> distinct[0]
            distinct.size == atoms.size && t is Type.Union -> t
            else -> checker.getUnionType(distinct)
        }
    }

    /** (CHK.86): some pair of atoms can hold a common value. */
    private fun enumAtomListsOverlap(left: List<Type>, right: List<Type>): Boolean =
        left.any { l -> right.any { r -> enumAtomsOverlap(l, r) } }

    /**
     * (CHK.86): whether one enum-flavored atom and another can hold a common value.
     *
     * Two atoms of DIFFERENT enums never can; two MEMBERS of one enum can only when they
     * are the same member; anything involving a whole enum and one of its own members
     * always can. Unknown answers ALL read as "overlap", so the rule is refusal-shaped:
     * it emits only where it can prove the comparison impossible.
     */
    private fun enumAtomsOverlap(a: Type, b: Type): Boolean {
        val aMemberEnum = enumOfMemberTypeSymbol(a)
        val bMemberEnum = enumOfMemberTypeSymbol(b)
        val aEnum = aMemberEnum ?: enumOwnTypeSymbol(a) ?: return true
        val bEnum = bMemberEnum ?: enumOwnTypeSymbol(b) ?: return true
        val sameEnum = checker.canonicalEnumSymbol(aEnum).id == checker.canonicalEnumSymbol(bEnum).id ||
            enumTypesRelation(aEnum, bEnum) == null || enumTypesRelation(bEnum, aEnum) == null
        if (!sameEnum) return false
        if (aMemberEnum != null && bMemberEnum != null) return enumMemberTypesAreSameMember(a, b)
        return true
    }

    fun enumTypesRelation(sourceEnum: Symbol, targetEnum: Symbol): EnumRelFailure? {
        val src = checker.canonicalEnumSymbol(sourceEnum)
        val tgt = checker.canonicalEnumSymbol(targetEnum)
        if (src.id == tgt.id) return null
        val key = packIdPair(src.id, tgt.id)
        return enumTypesRelationCache.getOrPut(key) {
            if (src.name != tgt.name) return@getOrPut EnumRelFailure.Plain
            // tsc requires BOTH to be RegularEnum: a `const` enum relates only to itself.
            if (!src.flags.hasAny(SymbolFlags.RegularEnum) || !tgt.flags.hasAny(SymbolFlags.RegularEnum)) {
                return@getOrPut EnumRelFailure.Plain
            }
            if (src.declarations.any { d -> d is EnumDeclaration && tgt.declarations.any { it === d } }) {
                return@getOrPut null
            }
            val sourceMembers = enumMemberEntries(src) ?: return@getOrPut null
            val targetMembers = enumMemberEntries(tgt) ?: return@getOrPut null
            val targetValues = HashMap<String, ConstantValue?>()
            for ((n, v) in targetMembers) targetValues[n] = v
            for ((name, sv) in sourceMembers) {
                if (name !in targetValues) return@getOrPut EnumRelFailure.Missing(name)
                val tv = targetValues[name]
                if (sv == tv) continue
                if (sv != null && tv != null) {
                    return@getOrPut EnumRelFailure.ValueDiffers(name, enumConstantDisplay(tv), enumConstantDisplay(sv))
                }
                val str = (sv ?: tv) as? ConstantValue.StringValue ?: continue
                return@getOrPut EnumRelFailure.StringVsUnknown(name, enumConstantDisplay(str))
            }
            null
        }
    }

    /** (REL.1)(c) round 746: an enum member's value as tsc renders it in the
     *  `Each declaration of …` elaboration — a plain number, a double-quoted string. */
    private fun enumConstantDisplay(v: ConstantValue): String = when (v) {
        is ConstantValue.NumberValue -> checker.fmtEnumAsgVal(v.value)
        is ConstantValue.StringValue -> "\"${v.value}\""
    }

    /**
     * (REL.1)(c) round 746: the elaboration line tsc appends under the TS2322 when two
     * enums fail to relate. [targetDisplay] must be the SAME string the top-level
     * message used for the target, which is why this cannot be built inside
     * [enumTypesRelation] — the pair decides whether either side prints qualified.
     */
    private fun enumRelationChainLine(
        failure: EnumRelFailure, enumSimpleName: String, targetDisplay: String,
    ): String? = when (failure) {
        is EnumRelFailure.Plain -> null
        is EnumRelFailure.Missing -> "  Property '${failure.member}' is missing in type '$targetDisplay'."
        is EnumRelFailure.ValueDiffers ->
            "  Each declaration of '$enumSimpleName.${failure.member}' differs in its value, " +
                "where '${failure.expected}' was expected but '${failure.given}' was given."
        is EnumRelFailure.StringVsUnknown ->
            "  One value of '$enumSimpleName.${failure.member}' is the string '${failure.stringValue}', " +
                "and the other is assumed to be an unknown numeric value."
    }

    /** (REL.1)(c) round 746: the enum SYMBOL behind an enum's OWN type (never a member
     *  type — [TypeFlags.Enum] and [TypeFlags.EnumLiteral] are disjoint by construction
     *  in [getDeclaredTypeOfEnumMember]). */
    fun enumOwnTypeSymbol(type: Type): Symbol? {
        if (type.flags.hasNone(TypeFlags.Enum)) return null
        return (type as? Type.Object)?.symbol?.takeIf { it.flags.hasAny(SymbolFlags.Enum) }
    }

    /** (REL.1)(c) round 746: the enum SYMBOL owning an enum-MEMBER type. */
    fun enumOfMemberTypeSymbol(type: Type): Symbol? {
        if (type.flags.hasNone(TypeFlags.EnumLiteral)) return null
        return (type as? Type.Object)?.symbol?.parent?.takeIf { it.flags.hasAny(SymbolFlags.Enum) }
    }

    /**
     * (REL.1)(c) round 746: an enum-flavored type rendered with its NAMESPACE path —
     * `First.E`, `Abcd.E`. `null` for anything that is not enum-flavored.
     *
     * This is tsc's `TypeFormatFlags.UseFullyQualifiedType`, which the relation's error
     * reporter turns on only through [enumCollisionQualifiedDisplays].
     *
     * (REL.1)(c) round 749: the path continues into the FILE when the enum has no
     * namespace container — see [enumModuleImportPrefix].
     */
    fun enumTypeQualifiedDisplay(type: Type): String? {
        val sym = (type as? Type.Object)?.symbol ?: return null
        val isMember = sym.flags.hasAny(SymbolFlags.EnumMember)
        val enumSym = (if (isMember) sym.parent else sym)
            ?.takeIf { it.flags.hasAny(SymbolFlags.Enum) } ?: return null
        val segments = mutableListOf(enumSym.name)
        var cur = enumSym.parent
        var hops = 0
        while (cur != null && hops++ < 64 &&
            cur.flags.hasAny(SymbolFlags.Module or SymbolFlags.NamespaceModule or SymbolFlags.ValueModule)
        ) {
            segments.add(0, cur.name)
            cur = cur.parent
        }
        if (segments.size == 1) enumModuleImportPrefix(enumSym)?.let { segments.add(0, it) }
        if (isMember) segments.add(sym.name)
        return segments.joinToString(".")
    }

    /**
     * (REL.1)(c) round 749: the `import("<base>")` head of a fully-qualified enum name —
     * tsc's `getFullyQualifiedName` walking one more step, from the enum symbol into the
     * SOURCE-FILE module symbol that owns it, which `symbolToString` renders as
     * `import("f")`. That step is why `enumAssignmentCompat6`'s `f.ts` reads
     * `Type 'DiagnosticCategory' is not assignable to type 'import("f").DiagnosticCategory'.`
     * — at the error position the bare name belongs to the enum declared inside the IIFE, so
     * the module-scoped one can only be named through its module.
     *
     * The condition IS tsc's, transcribed: in tsc a symbol carries a `parent` only when it
     * sits in a container's `exports`/`members`, so a top-level EXPORTED declaration of an
     * external module reaches the file's module symbol while a file-local one (and every
     * function-body declaration, INV.2(c) scope-space symbols included) has no parent and
     * stays bare. We have no module symbol to walk to, so the same question is asked of the
     * declaration: top level, `export`ed, in a file with module syntax.
     *
     * DELIBERATELY not applied to a NAMESPACE-nested enum of a module file (the caller only
     * consults it when the namespace walk produced nothing). tsc would print
     * `import("f").ns.E` there; no corpus baseline asks for it, and widening the rule would
     * move every namespace-qualified enum display in a module file for nothing.
     */
    private fun enumModuleImportPrefix(enumSym: Symbol): String? {
        val decl = enumSym.declarations.firstOrNull { it is EnumDeclaration } as? EnumDeclaration
            ?: return null
        if (ModifierFlag.Export !in decl.modifiers) return null
        val file = owningSourceFile(decl) ?: return null
        if (decl.parent !== file) return null
        if (file.fileName !in checker.moduleFiles) return null
        return "import(\"${checker.moduleFileBaseNoExt(file.fileName)}\")"
    }

    /**
     * (REL.1)(c) round 746: tsc's `getTypeNamesForErrorDisplay` — when the two sides of a
     * TS2322 print the SAME string, BOTH are re-rendered fully qualified, which is how
     * `Type 'E' is not assignable to type 'E'` becomes
     * `Type 'Abcd.E' is not assignable to type 'First.E'` while
     * `Type 'Nope' is not assignable to type 'E'` keeps its bare names.
     *
     * Deliberately restricted to a pair that is enum-flavored on BOTH sides. tsc applies
     * the retry to every type, but our display paths reach the same string for unrelated
     * reasons (an annotation rendered from source text, an alias kept unfolded), so the
     * general form is a separate, measured change — the round-745 rule that a shared
     * display predicate gets SPLIT rather than widened.
     */
    fun enumCollisionQualifiedDisplays(
        sourceType: Type, targetType: Type, sourceDisplay: String, targetDisplay: String,
    ): Pair<String, String>? {
        if (sourceDisplay != targetDisplay) return null
        val qualifiedSource = enumTypeQualifiedDisplay(sourceType) ?: return null
        val qualifiedTarget = enumTypeQualifiedDisplay(targetType) ?: return null
        if (qualifiedSource == qualifiedTarget) return null
        return qualifiedSource to qualifiedTarget
    }

    /**
     * (REL.1)(c) round 747: the same `getTypeNamesForErrorDisplay` retry as
     * [enumCollisionQualifiedDisplays], for a relation-error pair whose types are NOT
     * themselves enum-flavored but CONTAIN an enum — a method signature, above all.
     * `(param: E) => void` prints identically on both sides of
     * `enumAssignmentCompat7`'s TS2416, and re-rendering under
     * [enumDisplayFullyQualified] separates them into `(param: second.E) => void` and
     * `(param: first.E) => void`.
     *
     * SPLIT from [enumCollisionQualifiedDisplays] rather than folded into it (round 745's
     * rule): that one is fed displays which may come from ANNOTATION TEXT rather than
     * [typeToString], so re-rendering there would change strings for reasons that have
     * nothing to do with the enum. This one re-renders both sides through [typeToString]
     * and is self-gating — with no enum anywhere in the pair the flag changes nothing, the
     * two renders stay equal, and the caller keeps its original displays.
     *
     * Both callers must pass displays that ARE [typeToString] output; a caller whose
     * display came from a signature/annotation renderer must not use this.
     */
    fun enumQualifiedRelationDisplays(
        sourceType: Type, targetType: Type, sourceDisplay: String, targetDisplay: String,
    ): Pair<String, String>? {
        if (sourceDisplay != targetDisplay) return null
        val saved = enumDisplayFullyQualified
        enumDisplayFullyQualified = true
        val qualifiedSource: String
        val qualifiedTarget: String
        try {
            qualifiedSource = checker.typeToString(sourceType)
            qualifiedTarget = checker.typeToString(targetType)
        } finally {
            enumDisplayFullyQualified = saved
        }
        if (qualifiedSource == qualifiedTarget) return null
        return qualifiedSource to qualifiedTarget
    }

    /**
     * (REL.1)(c) round 746: the TS2322 elaboration for two enums that do not relate —
     * tsc's `isEnumTypeRelatedTo` error reporter, rendered against the target display
     * the top-level message actually used. `null` when the pair is not enum-flavored,
     * relates, or is a nominal mismatch tsc reports without elaboration.
     */
    fun enumRelationElaboration(sourceType: Type, targetType: Type, targetDisplay: String): String? {
        val targetEnum = enumOwnTypeSymbol(targetType) ?: return null
        val sourceEnum = enumOwnTypeSymbol(sourceType) ?: enumOfMemberTypeSymbol(sourceType) ?: return null
        val failure = enumTypesRelation(sourceEnum, targetEnum) ?: return null
        return enumRelationChainLine(failure, checker.canonicalEnumSymbol(sourceEnum).name, targetDisplay)
    }

    /**
     * (REL.1)(c) round 746: tsc's union display for a target whose constituents include
     * enum-flavored types — `boolean | Foo`, not the annotation's `X.Foo.A | X.Foo.B |
     * boolean`. `null` when no constituent is enum-flavored, which leaves every other
     * union on the annotation-text path untouched.
     *
     * This is the DISPLAY rule `checkNamespaceEnumUnionAssignments` (B266) owned, moved
     * here so the pass can retire — round 744 already recorded that the pass's verdict
     * was reproducible but its display was not. Three ingredients, all of them tsc's:
     *
     *  - a MEMBER prints qualified (`Foo.B`), which the annotation path cannot do
     *    because it reduces a `QualifiedName` to its last name;
     *  - a CONSECUTIVE run covering every member of one enum COLLAPSES to the bare enum
     *    name (tsc's `formatUnionTypes`, which is why `X.Foo.A | X.Foo.B` prints `Foo`);
     *  - constituents are ID-ORDERED in tsc, and every intrinsic predates every enum
     *    type, so non-enum constituents come FIRST while each group keeps its own
     *    relative order. Deliberately narrower than a full id sort: only the enum split
     *    is measured, and a general re-sort would move union displays this round never
     *    looked at.
     */
    fun enumUnionTargetDisplay(targetType: Type): String? {
        val union = targetType as? Type.Union ?: return null
        val parts = union.types
        if (parts.none { it.flags.hasAny(TypeFlags.EnumLike) }) return null
        val out = mutableListOf<String>()
        // (CHK.83): tsc's `formatUnionTypes` skips `null`/`undefined` while walking the
        // constituents and appends them LAST (`null` before `undefined`), whatever their
        // ids — `E | null | undefined` on both references, where the id order this
        // helper otherwise transcribes printed `undefined | null | E`. `void` is not
        // Nullable there and keeps its place.
        val nullish = TypeFlags.Null or TypeFlags.Undefined
        for (p in parts) if (p.flags.hasNone(TypeFlags.EnumLike or nullish)) out.add(checker.typeToString(p))
        val enumParts = parts.filter { it.flags.hasAny(TypeFlags.EnumLike) }
        var i = 0
        while (i < enumParts.size) {
            val head = enumParts[i]
            val owner = enumOfMemberTypeSymbol(head)
            if (owner == null) { out.add(checker.typeToString(head)); i++; continue }
            var j = i
            val seen = mutableSetOf<String>()
            while (j < enumParts.size) {
                val m = enumParts[j]
                val o = enumOfMemberTypeSymbol(m) ?: break
                if (checker.canonicalEnumSymbol(o).id != checker.canonicalEnumSymbol(owner).id) break
                seen.add((m as? Type.Object)?.symbol?.name ?: break)
                j++
            }
            val all = enumMemberEntries(owner)?.map { it.first }
            if (all != null && all.isNotEmpty() && seen.containsAll(all)) {
                out.add(checker.canonicalEnumSymbol(owner).name)
            } else {
                for (k in i until j) out.add(checker.typeToString(enumParts[k]))
            }
            i = if (j > i) j else i + 1
        }
        if (parts.any { it.flags.hasAny(TypeFlags.Null) }) out.add("null")
        if (parts.any { it.flags.hasAny(TypeFlags.Undefined) }) out.add("undefined")
        return out.joinToString(" | ")
    }


    /**
     * (REL.1)(b0) round 742: the enum a MEMBER type belongs to, as a `Type`.
     *
     * `null` for anything that is not an enum-member type. An enum-member type widens
     * to its own enum exactly as a string literal widens to `string`, which is what
     * keeps `let x = E.A; x = E.B` legal — see [widenEnumMemberTypes].
     */
    fun enumTypeOfMemberType(type: Type): Type? {
        if (type.flags.hasNone(TypeFlags.EnumLiteral)) return null
        val owner = (type as? Type.Object)?.symbol?.parent ?: return null
        if (!owner.flags.hasAny(SymbolFlags.Enum)) return null
        return checker.getDeclaredTypeOfSymbol(checker.canonicalEnumSymbol(owner))
    }

    /**
     * (REL.1)(b0) round 742: the type of an enum-member ACCESS EXPRESSION — `SK.A`
     * in VALUE position, as opposed to the `SK.A` ANNOTATION step (a) already
     * handled.
     *
     * Before this, such an access resolved to **`anyType`**: an enum's own
     * `Type.Object` carries no member table, so [getPropertyOfType] missed and
     * [computeRawTypeOfPropertyAccess] fell through to its `anyType` tail. That is
     * why round 741 could flip three of `EnumMemberRelationTest`'s four
     * expectations from the relation but not `const e: E.X = E.Y` — the relation
     * was being handed `any`, not a member.
     *
     * Deliberately a targeted branch rather than planting members on the enum's
     * type: giving the enum type a member table would also make it a structurally
     * NON-empty relation target, so `x: E = <anything>` would start demanding
     * those members. This changes the type of the ACCESS and nothing else.
     */
    fun enumMemberAccessType(objectType: Type, propName: String): Type? {
        val enumSym = (objectType as? Type.Object)?.symbol ?: return null
        if (!enumSym.flags.hasAny(SymbolFlags.Enum)) return null
        val memberSym = enumSym.exports?.get(propName)
            ?: checker.canonicalEnumSymbol(enumSym).exports?.get(propName)
            ?: return null
        if (!memberSym.flags.hasAny(SymbolFlags.EnumMember)) return null
        val member = checker.getDeclaredTypeOfEnumMember(memberSym)
        return if (member === anyType) null else member
    }
}
