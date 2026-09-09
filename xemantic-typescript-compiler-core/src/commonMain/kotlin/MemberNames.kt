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
 * (INV.0) step 6b — the MEMBER-NAME / LATE-BINDING seam: given a member
 * DECLARATION, answer the NAME its member table is keyed by. Extracted VERBATIM
 * from `Checker.kt` (design § 6 Stage 0; the seam ledger row 8 named when it
 * deliberately left this family behind — "the member-NAME family
 * (`getMemberName` / `declaredMemberName` and the late-bound key machinery)
 * deliberately did NOT move: it is a later row's seam"), as a final class
 * constructed once per [Checker]; no semantic change, and each of the twelve
 * call sites that survives in `Checker.kt` is a one-line delegation.
 *
 * ## What is here
 *
 * ONE contiguous span of `Checker.kt`, whole — 627 lines, 24 declarations:
 *
 *  - the PLAIN case, [getMemberName]: an `Identifier` / string / numeric name
 *    node, plus the `[Symbol.X]` well-known-symbol arm that gives a lib member
 *    like `readonly [Symbol.toStringTag]: string` a canonical key;
 *  - the DECLARATION-side entry [declaredMemberName] (round 937, (CHK.5)(a)) and
 *    its computed-only twin [declaredComputedMemberName], which exist so the
 *    declaration side and the object-literal side cannot drift;
 *  - the STATIC computed key [computedLiteralKey] (B451: `[2]`, `["bar"]`, a
 *    no-substitution template) and [computedSymbolKey];
 *  - tsc's LATE BINDING, rounds 935-936 — [lateBoundComputedKeyName] and the
 *    value ladder under it ([lateBoundKeyValue], [lateBoundValueOfVarDecl],
 *    [lateBoundTypeNodeValue], [templateLiteralTypeFixedText],
 *    [qualifiedLateBoundKeyValue], [literalKeyValueOf], [lateBindResolveVarDecl],
 *    [enumMemberLateBoundKeyName]), i.e. the value a computed key's expression
 *    has when it is a `const`, an enum member, a well-known symbol or an alias
 *    chain, bounded at [LATE_BIND_ALIAS_HOPS] hops;
 *  - the DOTTED-NAME resolution under that ladder ([resolveDottedInStatements],
 *    [moduleNamePath], [lateBindStatementsOf], [lateBindScanEnclosing]) and the
 *    enum-value readers it needs ([enumMemberValueFromDecl], [enumMemberValueOf]);
 *  - the OBJECT-LITERAL / static namers [objLitElementMemberName] and
 *    [staticMemberNameOf] (round 935), [wellKnownSymbolKey];
 *  - and [writtenMemberNameSpan] (round 934), the span of the name AS WRITTEN,
 *    which a diagnostic needs when the cooked name is not what the source spells.
 *
 * No field moved with the family: it owns no memo and no state, which is the
 * other half of why this is the arc's cleanest seam.
 *
 * ## Two traps this file is the home of
 *
 * **A member name derived from a TYPE is not a function of the program here, so
 * a late-bound key must be resolved SYNTACTICALLY** (round 935). tsc's
 * `isTypeUsableAsPropertyName` reads the key expression's TYPE; ours cannot,
 * because a file-level un-annotated `const K = "p"` answers the literal `"p"` in
 * the assignability pass and the widened `string` in the pass behind TS2339. When
 * the two drifted, ONE compile emitted a correct TS2322 beside a false
 * `Property 'p' does not exist on type '{}'` for the same member. Hence the ladder
 * here reads the AST — an enum member's value, or an innermost-first walk of the
 * enclosing statement lists — and never a resolved type.
 *
 * **[declaredMemberName] is the SINGLE SOURCE OF TRUTH for a member's name**
 * ((CHK.40)(c)). A second `decl.name as? Identifier` left behind is not a coarser
 * answer, it is a different one: `getTypeOfSymbolWorker`'s `MethodDeclaration` arm
 * did exactly that and typed every STRING-named method `any` while
 * `resolveInterfaceMembersCore` had registered it correctly — the member PRESENT
 * and useless. The property form of the same member is byte-correct, which is what
 * makes the divergence invisible to any fixture that does not spell the method
 * form. B451 records that member-NAME extraction has >= 5 independent sites; this
 * class is the answer to that, and a new site belongs here rather than beside its
 * caller.
 *
 * ## Ambient surface (the ledger row, `docs/inversion-ambient-ledger.md` § 9)
 *
 * **FIVE** [Checker] members over **13** call sites, all READ, **ZERO WRITTEN** —
 * the SMALLEST ambient row of the arc, against row 7's 45 and row 8's 21. Each is
 * reached through the FINAL [Checker] class, a direct call with no interface and
 * no captured lambda (contract § 10):
 *
 *  - ENUM VALUE (3): `canonicalEnumSymbol` (1), `enumMemberEntries` (1),
 *    `resolveEnumSymbolForDiscriminant` (1) — the three questions
 *    [enumMemberValueOf] and [enumMemberLateBoundKeyName] ask to turn `E.P` into
 *    the string `"P"` names;
 *  - SYNTAX (2): `unwrapParensExpr` (6), `expressionTrueEnd` (4).
 *
 * That is an ENUM seam and a SYNTAX seam, and neither is this family's business:
 * both are questions asked ON THE WAY to a name, not part of naming. Everything
 * else the span needs is either a constructor input ([fileResults]) or a public
 * top-level declaration of the module (`nodeKey`, `owningSourceFile`,
 * `ConstantValue`) — plus the file-private hop limit [LATE_BIND_ALIAS_HOPS],
 * widened to `internal` and referenced unqualified.
 *
 * **ZERO absorption candidates**, measured rather than assumed: every one of the
 * five has other callers left in `Checker.kt` — `expressionTrueEnd` 274,
 * `unwrapParensExpr` 59, `canonicalEnumSymbol` 30, `enumMemberEntries` 8,
 * `resolveEnumSymbolForDiscriminant` 3. Row 7's cheap mechanical win has no
 * counterpart here either.
 *
 * ## Why `MemberResolver` still goes through `Checker`
 *
 * [MemberResolver] (step 6a) calls `checker.getMemberName` ×4 and
 * `checker.declaredMemberName` ×4, and it is deliberately NOT rewired to call this
 * class directly. The two delegations exist anyway — those two names have 17 and
 * 15 surviving call sites in `Checker.kt` — so routing row 8 through them costs
 * one already-inlinable hop and keeps the two collaborators' CONSTRUCTION ORDER
 * free of a dependency on each other. That is why both stay `internal` in
 * [Checker] where the other ten delegations are `private`.
 */
internal class MemberNames(
    private val checker: Checker,
    /**
     * (INV.0) step 7 — the ENUM collaborator, wired DIRECTLY rather than through
     * [Checker]; [enumMemberValueFromDecl]'s value lookup is the one site.
     */
    private val enumSemantics: EnumSemantics,
    /**
     * `Checker.fileResults` — the per-file binder results, handed in as the OBJECT
     * (it is a `val` of `Checker` declared above the `Checker.kt:666` constructor
     * boundary, so it is a legal constructor input rather than an ambient read).
     * Read at exactly one site: [enumMemberValueFromDecl] needs the OWNING file's
     * `nodeToSymbol` to turn an `EnumDeclaration` node back into its symbol.
     */
    private val fileResults: Map<String, BinderResult>,
) {

    /** Extract the name string from a member name node (Identifier, StringLiteral, NumericLiteral). */
    fun getMemberName(name: NameNode): String? {
        return when (name) {
            is Identifier -> name.text
            is StringLiteralNode -> name.text
            is NumericLiteralNode -> name.text
            // Well-known-symbol computed name `[Symbol.X]` → canonical key
            // `"[Symbol.X]"` (matches TypeScript's display + member identity).
            // This lets lib members like `readonly [Symbol.toStringTag]: string;`
            // participate in structural / missing-property comparison. Other
            // computed names stay dynamic (null — can't resolve statically).
            is ComputedPropertyName -> {
                val e = name.expression
                if (e is PropertyAccessExpression) {
                    val recv = e.expression
                    if (recv is Identifier && recv.text == "Symbol") "[Symbol.${e.name.text}]" else null
                } else null
            }
            else -> null
        }
    }

    /**
     * Round 937 — (CHK.5)(a): the member name a DECLARATION-side name node spells,
     * including a LATE-BOUND computed key.
     *
     * This is the declaration-side twin of [staticMemberNameOf] (the object-literal
     * namer round 935 landed), and it exists so the two sides cannot drift. The whole
     * of rounds 933-936 is one lesson restated: **B451 records that member-NAME
     * extraction has >= 5 INDEPENDENT sites, and a key named at one and dropped at
     * another produces two contradictory diagnostics in ONE compile.** Round 936 landed
     * `[K]` on the object-literal side alone, and that is exactly what
     * `interface I { [K]: number }; const x: I = { [K]: 1 }` then measured as: the
     * literal named the member `p`, the interface declared nothing, and the excess
     * check reported a key both compilers accept (TS2353 `'[K]'`, a false positive
     * this fixes).
     *
     * Every row was READ from tsc 7.0.2 on a scratch project before this was written,
     * in both directions:
     *
     *  - `interface I { [K]: number }` / `class C { [K]: number }` / `type T = { [K]: number }`
     *    all declare a member `p` in tsc — reading it is TS2322 against a `string`,
     *    where this compiler was SILENT for the interface and type literal (a false
     *    NEGATIVE) and **TS2339, a false POSITIVE, for the class**;
     *  - `const x: I = {}` is TS2741 in tsc (it names the key AS WRITTEN, `'[K]'`) and
     *    was silent here;
     *  - every key spelling round 935/936 already resolves works here identically —
     *    `[CE.P]`, `[SE.Q]`, `[NS.K]`, `` [TT] `` for a template-literal type, a
     *    numeric `[N]`, an alias chain — because this asks the SAME helper.
     *
     * **[getMemberName] is deliberately asked first and deliberately not changed.**
     * B451 is explicit that it feeds ~20 callers including duplicate detection and
     * abstract tracking; the widening belongs at the member-BUILDING call sites, which
     * is what this wrapper is. Its `[Symbol.X]` arm keeps winning for a well-known
     * symbol, which is what makes an interface's `[Symbol.iterator]` member match the
     * object literal's (round 723).
     *
     * **REFUSED, with tsc's own answer measured, and the refusals are not omissions.**
     * A key whose type is `string` (`let LW = "p"`), a literal UNION, or a dotted path
     * through a value (`obj.k`) gives tsc's interface a STRING INDEX SIGNATURE rather
     * than a named member — a different modelling gap, recorded in (CHK.5), and one
     * that late binding must not pretend to close. A `unique symbol` key is (CHK.5)(d)
     * and MUST stay refused on both sides at once: naming `[S]` here alone would make
     * `{ [S]: 1 }` against an interface that has it — silent in both compilers today —
     * a false positive. A key imported from another FILE is (CHK.5)(c).
     */
    fun declaredMemberName(name: NameNode): String? =
        getMemberName(name) ?: computedLiteralKey(name) ?: lateBoundComputedKeyName(name)

    /**
     * Round 937 — (CHK.5)(a): the COMPUTED half of [declaredMemberName], for the sibling
     * walkers that collect a class's OWN member names from the AST and compare them
     * against a resolved TYPE's member table.
     *
     * Those walkers read `(m.name as? Identifier)?.text` and nothing else, which was
     * consistent while the type side dropped a computed key too — and became B451's drift
     * the moment it stopped: `interface T8 { [c4]: number }` / `declare class T9 implements
     * T8 { [c4]: number }` (the corpus's `dynamicNames`) had the INTERFACE's members named
     * `a` and `1` from its type and the CLASS's named nothing from its AST, so T9 "did not
     * implement" a member it declares one line down — TS2420/TS2720 false positives that
     * tsc does not have.
     *
     * Deliberately the computed arm ONLY: a `"p"`-spelled or numeric member name is left
     * refusing at those sites exactly as it was, because widening THAT is a separate
     * pre-existing gap with its own population and not this stage's.
     */
    fun declaredComputedMemberName(name: Node?): String? =
        (name as? ComputedPropertyName)?.let { declaredMemberName(it) }

    /** B451: a computed member/property name `[<literal>]` whose inner expression is a
     *  numeric, string or NO-SUBSTITUTION TEMPLATE literal is a STATIC key (`[2]`→"2",
     *  `["bar"]`→"bar", a backtick-quoted `bar`→"bar") — tsc treats all three like the
     *  bare `2`/`"bar"` forms, because each spells one fixed name at parse time. Returns
     *  null for genuinely dynamic computed names: `[expr]`, `[Symbol.x]`, `[K]` where `K`
     *  is a binding, and a SUBSTITUTING template — those name no fixed member and tsc
     *  reports the literal as failing to supply one (round 933 measured all of them).
     *
     *  Applied ONLY at the type-BUILDING sites (object-literal / interface-class member
     *  maps), NOT the shared [getMemberName] (which feeds duplicate-detection /
     *  abstract-tracking). Unblocks `literalsInComputedProperties1` (`x[2]`/`y[2]`/`z[2]`
     *  resolving).
     *
     *  Round 933 added the template arm: without it a backtick-quoted key was the ONE
     *  fixed-name spelling this compiler could not see, so `{ [`p`]: v }` did not supply
     *  a required `p` (TS2741) and an interface's or class's own backtick-quoted member
     *  did not resolve (TS2339) — three false positives tsc does not have, where the
     *  quote-spelled twin one character away was already correct. */
    fun computedLiteralKey(name: NameNode): String? {
        val cpn = name as? ComputedPropertyName ?: return null
        return when (val e = cpn.expression) {
            is NumericLiteralNode -> e.text
            is StringLiteralNode -> e.text
            is NoSubstitutionTemplateLiteralNode -> e.text
            else -> null
        }
    }

    /**
     * Round 723: the member name for a computed WELL-KNOWN-SYMBOL key — `[Symbol.iterator]`
     * for `[Symbol.iterator]: …`. Symbol-keyed members are named by their bracketed dotted
     * text throughout (it is what TS2739 prints), so an object literal has to produce the
     * SAME string or its member is invisible to the target's member lookup. That is exactly
     * how tsc's own `Set<TElement>` literal — which declares `[Symbol.iterator]` and
     * `[Symbol.toStringTag]` — came to be reported as MISSING both of them.
     *
     * Deliberately narrow: a DOTTED path only (`Symbol.iterator`, `NS.wellKnown`). A bare
     * `[foo]` is a genuinely dynamic key whose value is unknown, and inventing the name
     * `[foo]` for it would let it satisfy an unrelated member; the caller keeps skipping
     * those, which is the pre-existing behaviour.
     */
    fun computedSymbolKey(name: NameNode): String? {
        val cpn = name as? ComputedPropertyName ?: return null
        val access = cpn.expression as? PropertyAccessExpression ?: return null
        fun dotted(e: Expression): String? = when (e) {
            is Identifier -> e.text
            is PropertyAccessExpression -> dotted(e.expression)?.let { "$it.${e.name.text}" }
            else -> null
        }
        return dotted(access)?.let { "[$it]" }
    }

    /**
     * Round 935 — LATE BINDING: the member name a computed key spells through its
     * key expression's **TYPE** rather than through its spelling.
     *
     * tsc's rule is `isTypeUsableAsPropertyName` — a computed key names a fixed member
     * when the key expression's type is a string-literal, number-literal or
     * unique-symbol type — and every row below was READ from `tsc 7.0.2` on a scratch
     * project, in BOTH directions, before this was written:
     *
     *  - `const K = "p"` / `const K2 = K` / `let L2: "p"` / `declare const D: "p"`:
     *    late-binds to `p` (a `const`'s CONST-NESS is not the criterion — a `let` with
     *    a literal ANNOTATION binds too, and a `const` whose type widened does not).
     *  - a string ENUM member, `const` or not (`[E.P]` where `P = "p"`): binds to `p`.
     *  - a numeric enum member and a number-literal-typed binding: bind to the VALUE's
     *    canonical string (`7`, `1000` — not the source text).
     *  - REFUSED, with tsc agreeing on every one: a widened `let L` (`string`), a
     *    string-literal UNION (`declare const U: "p" | "q"`), a plain `symbol`, a bare
     *    type parameter, and a SUBSTITUTING template. All of those are TS2741 in tsc.
     *
     * **This is asked BEFORE [computedSymbolKey], and that order is the whole point.**
     * That helper INVENTS `"[<dotted>]"` so a well-known-symbol member can match
     * STRUCTURALLY (round 723) and cannot tell `Symbol.iterator` from `E.P`; round 934
     * measured the false positive it manufactures when used as a claim about what a key
     * SPELLS (`{ [E.P]: 1 }` reported as an excess key `'[E.P]'` against `{ p?: number }`,
     * which tsc late-binds and accepts). Late binding answers `p` for that key, so the
     * invented placeholder is reached only by the dotted paths that really are dynamic.
     *
     * **A refusal is the pre-935 behaviour, never a wrong name** — this returns null
     * whenever the key's type is not a literal, so an unresolved or contextually
     * unavailable key degrades to exactly what it did before.
     *
     * OUT OF SCOPE and stated rather than hidden: `unique symbol` has no type of its own
     * here (`declare const S: unique symbol` types as plain `symbol`, measured), so
     * `[S]` and `[S2]` are still one name to this compiler; and tsc late-binds INTERFACE
     * and CLASS members and its duplicate-key check (TS1117) through the same rule, which
     * needs member tables computed after type resolution — see (CHK.4).
     */
    fun lateBoundComputedKeyName(name: NameNode): String? {
        val cpn = name as? ComputedPropertyName ?: return null
        return lateBoundKeyValue(checker.unwrapParensExpr(cpn.expression), 0)
    }

    /**
     * Round 935: the literal a late-bindable key EXPRESSION denotes, resolved
     * SYNTACTICALLY — an enum member's value, or the declaration a name resolves to.
     *
     * **It is syntactic because a TYPE-based answer is not a function of the program,
     * and this round measured what that costs.** The first draft asked
     * [getTypeOfExpression] for the key's type, exactly as tsc's `isTypeUsableAsPropertyName`
     * does — and a FILE-LEVEL un-annotated `const K = "p"` then answered `"p"` in the
     * assignability pass and the widened `string` in the pass behind TS2339, so ONE key
     * was two different members in ONE compile: `const obj = { [K]: 1 }; obj.p` produced
     * the correct TS2322 *and* `Property 'p' does not exist on type '{}'` together. That
     * is round 933's two-extraction-sites signature reached through ambient state rather
     * than through a second `when` (round 911: a literal's type is computed in more than
     * one ambient, and [currentLocalTypes] is not the same map in both).
     *
     * The resolution, innermost first, mirroring [collectUniqueSymbolConstNames]' shape:
     *  - a `const` whose INITIALIZER is a literal — regardless of its annotation, because
     *    a `const` reference is narrowed to that literal at every use (which is what makes
     *    `const U: "p" | "q" = "p"` a late-bindable key in tsc, measured);
     *  - any declaration whose ANNOTATION is a literal type (`let L2: "p"`,
     *    `declare const D: "p"`, `declare const N: 7`) — const-ness is not the criterion;
     *  - a `const` alias chain (`const K2 = K`), bounded at [LATE_BIND_ALIAS_HOPS].
     *
     * Everything else answers null, i.e. the pre-935 behaviour: a widened `let L`, a
     * genuine literal UNION (`declare const U2: "p" | "q"`), a plain `symbol`, a bare type
     * parameter and a SUBSTITUTING template are all refused — and tsc reports TS2741 for
     * every one of them (measured), so the refusals are parity, not omissions.
     */
    private fun lateBoundKeyValue(e: Expression, hops: Int): String? {
        if (hops > LATE_BIND_ALIAS_HOPS) return null
        enumMemberLateBoundKeyName(e)?.let { return it }
        // Round 936: a QUALIFIED key — `NS.K`, `NS.Inner.K`, `NS.E.P`. tsc late-binds all
        // three (measured) and this compiler answered TS2741 for every one of them, which
        // is the same false positive round 935 closed for the unqualified spellings.
        if (e is PropertyAccessExpression) return qualifiedLateBoundKeyValue(e, hops)
        val id = e as? Identifier ?: return null
        return lateBoundValueOfVarDecl(lateBindResolveVarDecl(id) ?: return null, hops)
    }

    /**
     * Round 935, extracted round 936: the literal a resolved variable DECLARATION denotes.
     *
     * Shared by the unqualified route ([lateBindResolveVarDecl]) and the namespace one
     * ([qualifiedLateBoundKeyValue]) so a namespace member obeys exactly the rules an
     * outer-scope binding does — which is tsc's answer, measured on 7.0.2: `NS.K` for
     * `export const K = "p"` binds, `NS.D` for `export declare const D: "p"` binds, and
     * `NS.LW` for `export let LW = "p"` does NOT (it widened).
     */
    private fun lateBoundValueOfVarDecl(decl: VariableDeclaration, hops: Int): String? {
        if (hops > LATE_BIND_ALIAS_HOPS) return null
        // A `const` initializer wins over an annotation: the reference is narrowed to it.
        val list = (decl as NodeBase).parent as? VariableDeclarationList
        if (list?.flags == SyntaxKind.ConstKeyword) {
            decl.initializer?.let { init ->
                literalKeyValueOf(init)?.let { return it }
                val inner = checker.unwrapParensExpr(init)
                if (inner is Identifier || inner is PropertyAccessExpression) {
                    lateBoundKeyValue(inner, hops + 1)?.let { return it }
                }
            }
        }
        return lateBoundTypeNodeValue(decl.type, decl, hops)
    }

    /**
     * Round 936: the literal a TYPE ANNOTATION denotes, through a bounded ALIAS chain.
     *
     * Round 935 read `decl.type as? LiteralType` and nothing else, which left two shapes
     * tsc binds and this compiler reported TS2741 for (both measured on 7.0.2, both false
     * positives, and both false NEGATIVES in the excess direction at the same time):
     *
     *  - a no-substitution TEMPLATE-LITERAL TYPE — ``declare const TT: `p` `` — which is a
     *    string-literal type spelled the third way (the same asymmetry round 933 found for
     *    the EXPRESSION spellings, one type-node level up);
     *  - a TYPE ALIAS to either spelling, and a chain of them (`type LP = "p"`,
     *    `type LP2 = LP`, `declare const A: LP2`).
     *
     * REFUSED, with tsc agreeing: a SUBSTITUTING template type (`` `p${string}` ``, whose
     * spans are non-empty), an alias to a UNION (`type LU = "p" | "q"`), and a GENERIC
     * alias or a type reference carrying arguments — none of those denotes one fixed name.
     * The alias hop is syntactic for round 935's reason: a type-derived name is a function
     * of the PASS, not of the program.
     */
    private fun lateBoundTypeNodeValue(t: TypeNode?, at: Node, hops: Int): String? {
        if (hops > LATE_BIND_ALIAS_HOPS) return null
        return when (t) {
            is LiteralType -> literalKeyValueOf(t.literal)
            is TemplateLiteralType -> templateLiteralTypeFixedText(t)
            is ParenthesizedType -> lateBoundTypeNodeValue(t.type, at, hops + 1)
            is TypeReference -> {
                if (!t.typeArguments.isNullOrEmpty()) return null
                val name = (t.typeName as? Identifier)?.text ?: return null
                val alias = lateBindScanEnclosing(at) { st ->
                    (st as? TypeAliasDeclaration)?.takeIf { it.name.text == name && it.typeParameters == null }
                } ?: return null
                lateBoundTypeNodeValue(alias.type, alias, hops + 1)
            }
            else -> null
        }
    }

    /**
     * Round 936: the ONE fixed string a template-literal TYPE denotes, or null.
     *
     * **`TemplateLiteralType` is NOT a structured node in this parser** — B65.1 builds it
     * with `templateSpans = emptyList()` and the whole raw source slice (backticks and
     * all) in `head.rawText`, because the checker's display path only ever needed the
     * rendered text. So the obvious `templateSpans.isEmpty()` test is TRUE for every
     * template type, substituting or not, and reading `head.text` answers `""` — a name
     * that matches no member, which is worse than refusing: it reached the excess check
     * as a real member and reported `` `p${string}` `` keys as excess, where tsc is silent.
     * (Measured, this round, on the first build; the raw text is the only discriminator
     * that exists here.)
     *
     * Refused, therefore: a slice carrying `${` (substituting — tsc says TS2741 for such a
     * key, measured) and one carrying a BACKSLASH, where the raw and cooked texts come
     * apart and this node keeps only the raw one.
     */
    private fun templateLiteralTypeFixedText(t: TemplateLiteralType): String? {
        val raw = t.head.rawText ?: return null
        if (raw.length < 2 || raw.first() != '`' || raw.last() != '`') return null
        val inner = raw.substring(1, raw.length - 1)
        if (inner.contains("\${") || inner.contains('\\')) return null
        return inner
    }

    /**
     * Round 936: the value a QUALIFIED late-bindable key denotes — `NS.K`, `NS.Inner.IK`,
     * `NS.CE.P`, `NS.SE.Q` — resolved by descending NAMESPACE BODIES syntactically.
     *
     * Every row was READ from tsc 7.0.2 before this was written: a namespace-qualified
     * const, a nested/dotted namespace's const, and a const-or-plain ENUM member declared
     * inside a namespace all late-bind there, and every one of them was a TS2741 false
     * positive here (supply) and a silent TS2353 false negative there (excess) — one
     * missing capability showing up as opposite defects in the two directions, exactly as
     * round 935 recorded for the unqualified case.
     *
     * SYNTACTIC, and deliberately so. The head could be resolved through
     * [currentFileLocals], which is what [resolveEnumSymbolFileLevel] does — but that map
     * is AMBIENT (round 911: it is not the same map in every pass), and round 935 measured
     * what an ambient input costs a member name: the same key became two different members
     * in ONE compile. Descending `ModuleBlock` statements is a function of the program.
     * Namespace MERGING is covered because every statement of a level is scanned, not the
     * first match; a dotted declaration (`namespace A.B { … }`) is matched by its whole
     * name PATH. The one symbol-table consult left is the enum leaf, whose VALUES live in
     * the binder's frozen tables and nowhere in the AST ([enumMemberValueFromDecl]).
     */
    private fun qualifiedLateBoundKeyValue(pa: PropertyAccessExpression, hops: Int): String? {
        val segs = ArrayList<String>(4)
        var cur: Expression = pa
        while (cur is PropertyAccessExpression) {
            segs.add(cur.name.text)
            cur = checker.unwrapParensExpr(cur.expression)
        }
        val head = cur as? Identifier ?: return null
        segs.add(head.text)
        segs.reverse()
        var node: Node? = (pa as NodeBase).parent
        while (node != null) {
            lateBindStatementsOf(node)?.let { stmts ->
                resolveDottedInStatements(stmts, segs, 0, hops)?.let { return it }
            }
            node = (node as NodeBase).parent
        }
        return null
    }

    /** Round 936: [path] from index [from] resolved against one statement list — a
     *  namespace descent, an enum member, or the variable declaration at the end. */
    private fun resolveDottedInStatements(
        stmts: List<Statement>,
        path: List<String>,
        from: Int,
        hops: Int,
    ): String? {
        if (hops > LATE_BIND_ALIAS_HOPS) return null
        val remaining = path.size - from
        if (remaining <= 0) return null
        if (remaining == 1) {
            for (st in stmts) {
                if (st !is VariableStatement) continue
                for (d in st.declarationList.declarations) {
                    if ((d.name as? Identifier)?.text == path[from]) {
                        return lateBoundValueOfVarDecl(d, hops + 1)
                    }
                }
            }
            return null
        }
        for (st in stmts) {
            when (st) {
                is EnumDeclaration ->
                    if (remaining == 2 && st.name.text == path[from]) {
                        enumMemberValueFromDecl(st, path[from + 1])?.let { return it }
                    }
                is ModuleDeclaration -> {
                    val np = moduleNamePath(st) ?: continue
                    if (np.isEmpty() || np.size >= remaining) continue
                    var matches = true
                    for (i in np.indices) if (np[i] != path[from + i]) { matches = false; break }
                    if (!matches) continue
                    val body = st.body as? ModuleBlock ?: continue
                    resolveDottedInStatements(body.statements, path, from + np.size, hops + 1)
                        ?.let { return it }
                }
                else -> {}
            }
        }
        return null
    }

    /** Round 936: a `namespace` declaration's name as a PATH — `["A", "B"]` for
     *  `namespace A.B { … }`, which the parser stores as a `PropertyAccessExpression`
     *  name rather than as nested declarations. A string-named ambient module answers
     *  null: its name is a module specifier, not a dotted path. */
    fun moduleNamePath(m: ModuleDeclaration): List<String>? {
        val out = ArrayList<String>(2)
        var cur: Expression = m.name
        while (cur is PropertyAccessExpression) {
            out.add(cur.name.text)
            cur = cur.expression
        }
        val head = cur as? Identifier ?: return null
        out.add(head.text)
        out.reverse()
        return out
    }

    /**
     * Round 936: the string an ENUM MEMBER declared by [decl] denotes, reached through the
     * binder's node->symbol table because the VALUES are not in the AST (an auto-numbered
     * member has no initializer to read). [enumMemberEntries] is the reader that already
     * knows the one member with no value at all — a value-less member of an AMBIENT
     * non-`const` enum, round 746 — and it answers null for it rather than inventing one.
     */
    private fun enumMemberValueFromDecl(decl: EnumDeclaration, member: String): String? {
        val res = owningSourceFile(decl)?.fileName?.let { fileResults[it] } ?: return null
        val sym = res.nodeToSymbol[nodeKey(decl)] ?: return null
        return enumMemberValueOf(checker.canonicalEnumSymbol(sym), member)
    }

    /** Round 935, extracted round 936: an enum member's VALUE as a property name. */
    private fun enumMemberValueOf(enumSym: Symbol, member: String): String? {
        val entries = enumSemantics.enumMemberEntries(enumSym) ?: return null
        return when (val v = entries.firstOrNull { it.first == member }?.second) {
            is ConstantValue.StringValue -> v.value
            is ConstantValue.NumberValue -> v.toString()
            null -> null
        }
    }

    /** Round 936: the statement list a node OWNS, for the innermost-first late-binding
     *  scan — the node set [lateBindResolveVarDecl] has walked since round 935. */
    fun lateBindStatementsOf(n: Node): List<Statement>? = when (n) {
        is SourceFile -> n.statements
        is ModuleBlock -> n.statements
        is Block -> n.statements
        is CaseClause -> n.statements
        is DefaultClause -> n.statements
        else -> null
    }

    /** Round 936: the innermost-first scan of the enclosing statement lists that
     *  [lateBindResolveVarDecl] performs, with the pick left to the caller. */
    private inline fun <T : Any> lateBindScanEnclosing(from: Node, pick: (Statement) -> T?): T? {
        var cur: Node? = (from as NodeBase).parent
        while (cur != null) {
            lateBindStatementsOf(cur)?.let { stmts ->
                for (st in stmts) pick(st)?.let { return it }
            }
            cur = (cur as NodeBase).parent
        }
        return null
    }

    /** Round 935: the property name a literal NODE spells for late binding — the string
     *  as written, or a number's CANONICAL text (tsc names `[N]` where `N: 1e3` "1000",
     *  not "1e3"), which is why this reads the value rather than the source text. */
    private fun literalKeyValueOf(e: Expression): String? = when (val u = checker.unwrapParensExpr(e)) {
        is StringLiteralNode -> u.text
        is NoSubstitutionTemplateLiteralNode -> u.text
        is NumericLiteralNode -> u.text.toDoubleOrNull()?.let { Type.NumberLiteral(it).toString() }
        is PrefixUnaryExpression -> {
            val n = u.operand as? NumericLiteralNode
            if (u.operator == SyntaxKind.Minus && n != null) {
                n.text.toDoubleOrNull()?.let { Type.NumberLiteral(-it).toString() }
            } else null
        }
        else -> null
    }

    /**
     * Round 935: the VariableDeclaration a late-bindable key name resolves to, by an
     * INNERMOST-FIRST scan of the enclosing statement lists ([ctaM3NearestList]'s node
     * set). A scope-chain consult would be ambient (INV.4(c)(i)'s `spineCurrentScope` is
     * maintained by the walk) and [lookupPerFileForNode] cannot see a function-body local
     * at all (B83.5 — block-scoped declarations are not bound), so the walk up the parent
     * chain is both the deterministic answer and the only one that covers both scopes.
     */
    fun lateBindResolveVarDecl(id: Identifier): VariableDeclaration? =
        lateBindScanEnclosing(id) { st ->
            (st as? VariableStatement)?.declarationList?.declarations
                ?.firstOrNull { (it.name as? Identifier)?.text == id.text }
        }

    /**
     * Round 935: the late-bound name of an `E.P` key — the enum member's VALUE.
     *
     * An enum member could not answer through its TYPE even if the type route were
     * deterministic: since round 741 its type is a member-less `Type.Object` interned on
     * `"<enumSymId>#<member>"` and carries no value, which is why `const z: "q" = E.P` is
     * silent here where tsc says `Type 'E' is not assignable`. The value lives in the enum
     * tables, and [enumMemberEntries] is the reader that already knows the one case with
     * NO value: a member with no initializer in an AMBIENT non-`const` enum (round 746 —
     * we auto-number it for the Transformer, tsc gives it none). Such a member answers
     * null here rather than binding to an invented number.
     */
    private fun enumMemberLateBoundKeyName(e: Expression): String? {
        val pa = e as? PropertyAccessExpression ?: return null
        val enumIdent = (pa.expression as? Identifier)?.text ?: return null
        val member = pa.name.text
        if (member.isEmpty()) return null
        val sym = checker.resolveEnumSymbolForDiscriminant(enumIdent, pa) ?: return null
        return enumMemberValueOf(sym, member)
    }

    /**
     * Round 934: the STATIC member name an OBJECT-LITERAL element declares, exactly as
     * [getTypeOfObjectLiteral] names it — a bare Identifier, a string or numeric literal,
     * or a computed key whose inner expression is a literal ([computedLiteralKey]) or a
     * well-known-symbol path ([computedSymbolKey]).
     *
     * **This exists so the excess-property check cannot drift from the type builder.**
     * B451 records that member-NAME extraction has >= 5 INDEPENDENT sites which each drop
     * `ComputedPropertyName` by default, and round 933 measured what that costs: a class's
     * backtick-quoted member resolved for one site and FP'd TS2339 from the other IN ONE
     * COMPILE. Here the two sites failed in the opposite direction — [getTypeOfObjectLiteral]
     * had named `["zz"]`/`` [`zz`] ``/`7` for a long time, so the literal's TYPE carried the
     * member, and [checkExcessProperties] then looked for the AST node that declared it with
     * a `when` that knew only `Identifier` and `StringLiteralNode`, found nothing, and
     * emitted nothing. A false NEGATIVE (tsc reports TS2353 for every one of those
     * spellings, measured on 7.0.2) that no profile and no corpus baseline could see.
     */
    fun objLitElementMemberName(prop: Node): String? = when (prop) {
        is PropertyAssignment -> staticMemberNameOf(prop.name)
        is ShorthandPropertyAssignment -> prop.name.text
        is MethodDeclaration -> staticMemberNameOf(prop.name)
        else -> null
    }

    /**
     * The member name a NAME node SPELLS, or null when it spells nothing fixed.
     *
     * **The computed arm is [computedLiteralKey] ONLY, and dropping [computedSymbolKey]
     * here is load-bearing rather than an omission.** That helper INVENTS the name
     * `"[<dotted path>]"` for any dotted computed key, which is a placeholder that lets a
     * well-known-symbol member match STRUCTURALLY (round 723) — it is not a claim about
     * what the key spells. Used as an excess-check name it manufactures a false positive
     * on the one shape tsc late-binds: `const enum E { P = "p" }; const o: { p?: number }
     * = { [E.P]: 1 }` is SILENT in tsc (measured) and would be reported here as the excess
     * key `'[E.P]'`, because "[E.P]" is in no target's member table. A key whose name
     * needs the key's TYPE — a binding `[K]`, an enum member `[E.P]`, a `unique symbol`
     * `[S]`, a well-known symbol `[Symbol.iterator]` — is therefore OUT of the excess
     * check in both directions, which is the same line round 933 drew for the supply
     * direction, and the same open item: late binding.
     */
    private fun staticMemberNameOf(n: NameNode): String? = when (n) {
        is Identifier -> n.text
        is StringLiteralNode -> n.text
        is NumericLiteralNode -> n.text
        is ComputedPropertyName ->
            computedLiteralKey(n) ?: lateBoundComputedKeyName(n) ?: wellKnownSymbolKey(n)
        else -> null
    }

    /**
     * Round 936: the member name a WELL-KNOWN-SYMBOL key spells — `[Symbol.iterator]` —
     * for the excess-property check, which is the ONE part of [computedSymbolKey]'s
     * answer that is a claim about what the key spells rather than a placeholder.
     *
     * tsc reports `{ [Symbol.iterator]: 1 }` against `{ p?: number }` as **TS2353
     * '[Symbol.iterator]'** and this compiler was silent (measured on 7.0.2, both
     * directions), while the SUPPLY direction has been right since round 723: the object
     * literal's TYPE names such a member through [computedSymbolKey], and so does an
     * interface's own `[Symbol.iterator]` member, so the two sides already match — only
     * the excess check could not see the key at all.
     *
     * **NARROW ON PURPOSE, and the narrowness is the whole correctness argument.** Round
     * 934 excluded [computedSymbolKey] here because it invents `"[<dotted>]"` for ANY
     * dotted path, and tsc is SILENT for every computed key it cannot late-bind — measured
     * this round over seven of them (`[LW]` for a `string`, a substituting template, a
     * literal union, a `number`, a plain `symbol`, `[NS.LW]` for a widened namespace
     * `let`, and `[obj.k]`), all of which would become false positives under the general
     * helper. A well-known symbol is different because it IS a fixed name on both sides.
     * So: the receiver must be the identifier `Symbol` itself, and it must not be a local
     * binding of that name — a `const Symbol = …` in scope makes the key dynamic again,
     * which the same innermost-first walk that resolves every other late-bound key answers.
     *
     * A `unique symbol` binding (`declare const S: unique symbol; { [S]: 1 }`) is NOT
     * covered and cannot be until it has a type of its own: `[S]` and `[S2]` are one name
     * to this compiler, and its DECLARATION side (`interface I { [S]: number }`) declares
     * no member at all, so naming the key here would report `{ [S]: 1 }` against that
     * interface as excess — a false positive where tsc is silent. See (CHK.5).
     */
    fun wellKnownSymbolKey(n: ComputedPropertyName): String? {
        val pa = checker.unwrapParensExpr(n.expression) as? PropertyAccessExpression ?: return null
        val recv = checker.unwrapParensExpr(pa.expression) as? Identifier ?: return null
        if (recv.text != "Symbol") return null
        if (lateBindResolveVarDecl(recv) != null) return null
        if (pa.name.text.isEmpty()) return null
        return "[Symbol.${pa.name.text}]"
    }

    /**
     * Round 934: the `[start, end)` of a property name AS WRITTEN — `zz`, `"zz"`, `` `zz` ``,
     * `7`, `["zz"]`, `[ "zz" ]`, `[Symbol.iterator]`. tsc's TS2353/TS2561 names the key with
     * its delimiters kept and squiggles exactly this span (measured: `indexSignatures1`'s
     * `~~~~~` under `[sym]` and `~~~~~~~~~` under `'someKey'`), so the message text and the
     * diagnostic length are both read from the source rather than from the cooked name.
     *
     * The computed arm scans for the closing `]` starting PAST the inner expression, never
     * from the `[` — a string key may itself contain one (`["a]b"]`, which tsc renders whole).
     * Returns null rather than guessing when the span cannot be established; the caller then
     * falls back to the cooked name, which is what every pre-934 emission used.
     */
    fun writtenMemberNameSpan(n: NameNode, source: String): Pair<Int, Int>? {
        val start = n.pos
        if (start < 0 || start >= source.length) return null
        val end = when (n) {
            is Identifier -> start + n.text.length
            is StringLiteralNode -> checker.expressionTrueEnd(n)
            is NumericLiteralNode -> checker.expressionTrueEnd(n)
            is NoSubstitutionTemplateLiteralNode -> checker.expressionTrueEnd(n)
            is ComputedPropertyName -> {
                var i = checker.expressionTrueEnd(n.expression)
                while (i < source.length && source[i] != ']') i++
                if (i >= source.length) return null
                i + 1
            }
            else -> return null
        }
        return if (end > start && end <= source.length) start to end else null
    }
}
