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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test
import org.intellij.lang.annotations.Language

/**
 * Round 940 — (CHK.7) and (CHK.5)(f): three PRISTINE divergences, each with the pristine
 * fixture that shows it, all three FALSE POSITIVES or a message the source never contains.
 *
 * Every row here was read from PRISTINE tsc offline with `scripts/pristine_oracle.py`
 * (round 939's instrument) rather than from `tools/tsgo-7.0.2/lib/tsc`, because round 938
 * measured the two references parting on precisely this territory. Each fixture was also
 * re-read at a SECOND target before being believed — round 939's method note, paid for
 * twice: a fixed scratch tsconfig manufactures false positives.
 *
 *  - **(CHK.7)(i)** `symbolProperty1` / `symbolProperty3`: `var s: symbol; ({ [s]: 0,
 *    [s]() {}, get [s]() {} })` was TS1117 x2 here and silent there, because
 *    `evaluateComputedPropertyName` named a reference key by its SPELLING.
 *  - **(CHK.7)(iii)** `privateNameDuplicateField`: `class { get #foo() {…}; #foo = "foo" }`
 *    was TS2300 at BOTH declarations and is TS2300 at the FIELD alone in pristine.
 *  - **(CHK.5)(f)** `dynamicNamesErrors` / `duplicateIdentifierComputedName` /
 *    `assignmentCompatWithEnumIndexer` / `symbolProperty21`: pristine names a missing
 *    LATE-BOUND member by the key AS WRITTEN (`'[K]'`), where we printed the value (`'p'`).
 *
 * **Every negative pin here is paired with a POSITIVE control**, because an FP fix that
 * disables the check outright passes every "this must be silent" assertion — and this
 * family has produced a blind pin in five consecutive rounds.
 */
class PristineDivergenceRound940Test {

    private fun check(@Language("typescript") source: String): List<Diagnostic> =
        diagnose(source.trimIndent(), directives = "// @strict: false")

    private fun codes(d: List<Diagnostic>, code: Int) = d.count { it.code == code }

    // ── (CHK.7)(i) TS1117 must not be keyed on a key's SPELLING ──────────────

    /** `symbolProperty1`, verbatim: pristine emits only TS2454 (a check we do not have). */
    @Test
    fun `a repeated symbol-typed computed key is NOT a duplicate object literal property`() {
        val d = check(
            """
            var s: symbol;
            var x = {
                [s]: 0,
                [s]() { },
                get [s]() {
                    return 0;
                }
            }
            """
        )
        assert(codes(d, 1117) == 0)
    }

    /** `symbolProperty3`, verbatim — a `var` initialized from `Symbol` itself. */
    @Test
    fun `a repeated key bound to the Symbol constructor is NOT a duplicate`() {
        val d = check(
            """
            var s = Symbol;
            var x = {
                [s]: 0,
                [s]() { },
                get [s]() {
                    return 0;
                }
            }
            """
        )
        assert(codes(d, 1117) == 0)
    }

    /** A widened `let` denotes no fixed name either — tsc gives the literal an index
     *  signature rather than two members, so there is nothing to duplicate. */
    @Test
    fun `a repeated widened let key is NOT a duplicate`() {
        val d = check("let w = \"a\";\nvar x = { [w]: 1, [w]: 2 };")
        assert(codes(d, 1117) == 0)
    }

    // POSITIVE CONTROLS — the check must still fire everywhere pristine says it does.

    /** `duplicateObjectLiteralProperty_computedName2`'s four rows, which are an ACTIVE
     *  corpus gate: a const, a string const, a string enum member and a numeric one. */
    @Test
    fun `a repeated LATE-BOUND computed key is still a duplicate`() {
        assert(codes(check("const n = 1;\nvar t = { [n]: 1, [n]: 1 };"), 1117) == 1)
        assert(codes(check("const s = \"s\";\nvar t = { [s]: 1, [s]: 1 };"), 1117) == 1)
        assert(codes(check("enum E1 { A = \"K\" }\nvar t = { [E1.A]: 1, [E1.A]: 1 };"), 1117) == 1)
        assert(codes(check("enum E2 { B }\nvar t = { [E2.B]: 1, [E2.B]: 1 };"), 1117) == 1)
    }

    /** `duplicateObjectLiteralProperty_computedName1`: the literal spellings still collapse
     *  onto ONE name, numeric normalization included. */
    @Test
    fun `a repeated LITERAL computed key is still a duplicate`() {
        assert(codes(check("const t1 = { 1: 1, [1]: 0 };"), 1117) == 1)
        assert(codes(check("const t2 = { 1: 1, [+1]: 0 };"), 1117) == 1)
        assert(codes(check("const t3 = { \"1\": 1, [+1]: 0 };"), 1117) == 1)
        assert(codes(check("const t5 = { \"+1\": 1, [\"+1\"]: 0 };"), 1117) == 1)
    }

    /** **THE DISCRIMINATING CONTROL FOR STEP (2).** A spelling key and a VALUE key agree on
     *  every `[x]` / `[x]` shape, so `duplicateObjectLiteralProperty_computedName2` alone
     *  cannot tell them apart — what does is that pristine answers DIFFERENTLY for two keys
     *  of the same spelling shape: `[s]` / `[s]` with `var s: symbol` is silent
     *  (`symbolProperty1`) and `[n]` / `[n]` with `const n = 1` is TS1117
     *  (`duplicateObjectLiteralProperty_computedName2`, an ACTIVE gate). The discriminator
     *  is therefore the key's VALUE, and `1` is the member `[n]` names — so it collides with
     *  a plainly-written `1`, which a spelling key can never see. */
    @Test
    fun `a late-bound key collides with the plainly-written member of the same name`() {
        assert(codes(check("const n = 1;\nvar t = { 1: 1, [n]: 0 };"), 1117) == 1)
        assert(codes(check("const s = \"p\";\nvar t = { p: 1, [s]: 0 };"), 1117) == 1)
    }

    /** …and it must NOT collide with a member of a different name, which is the same
     *  control run the other way. */
    @Test
    fun `negative control - a late-bound key does not collide with a different member`() {
        assert(codes(check("const n = 1;\nvar t = { 2: 1, [n]: 0 };"), 1117) == 0)
        assert(codes(check("const s = \"p\";\nvar t = { q: 1, [s]: 0 };"), 1117) == 0)
    }

    /** `symbolProperty36`: a WELL-KNOWN-symbol key repeated IS a duplicate in pristine, so
     *  the abstain must not swallow it — the step (2) chain ends at [wellKnownSymbolKey]. */
    @Test
    fun `a repeated well-known symbol key is still a duplicate`() {
        assert(codes(check("var x = { [Symbol.isConcatSpreadable]: 0, [Symbol.isConcatSpreadable]: 1 };"), 1117) == 1)
    }

    /** The refusal is bounded by EVIDENCE: a key whose declaration we cannot see keeps the
     *  pre-940 syntactic comparison. This is what `duplicateObjectLiteralProperty_computedName3`
     *  (an ACTIVE gate, `[keys.n]` through an `import * as keys`) rides on — modelled here
     *  with a dotted path whose head resolves to no variable declaration in this file. */
    @Test
    fun `a repeated UNRESOLVABLE dotted key is still compared syntactically`() {
        assert(codes(check("declare const keys: any;\nvar t = { [keys.n]: 1, [keys.n]: 1 };"), 1117) == 1)
    }

    /** `duplicateObjectLiteralProperty_computedNameNegative1`'s shape: two DIFFERENT
     *  unresolvable keys are not a duplicate, which the syntactic fallback also answers. */
    @Test
    fun `two different unresolvable keys are not a duplicate`() {
        assert(codes(check("declare const keys: any;\nvar t = { [keys.a]: 1, [keys.b]: 2 };"), 1117) == 0)
    }

    // ── (CHK.7)(iii) accessor-then-property is reported at the PROPERTY only ──

    /** `privateNameDuplicateField` lines 106-107: `get #foo` then `#foo = "foo"`. */
    @Test
    fun `a getter followed by a field is TS2300 at the FIELD alone`() {
        val d = check("class C {\n    get p() { return \"\" }\n    p = \"foo\";\n}")
        assert(codes(d, 2300) == 1)
    }

    /** `privateNameDuplicateField` lines 156-157: the `set` twin. */
    @Test
    fun `a setter followed by a field is TS2300 at the FIELD alone`() {
        val d = check("class C {\n    set p(v: string) { }\n    p = \"foo\";\n}")
        assert(codes(d, 2300) == 1)
    }

    /** `privateNameDuplicateField` lines 381-382: the STATIC twin. */
    @Test
    fun `a static setter followed by a static field is TS2300 at the FIELD alone`() {
        val d = check("class C {\n    static set p(v: string) { }\n    static p = \"foo\";\n}")
        assert(codes(d, 2300) == 1)
    }

    /** The same three shapes spelled with a PRIVATE name, which is where pristine's
     *  fixture actually lives — a private identifier is an `Identifier` whose text starts
     *  with `#` in this parser, so it must reach the same scan. */
    @Test
    fun `a private getter followed by a private field is TS2300 at the FIELD alone`() {
        val d = check("class C {\n    get #foo() { return \"\" }\n    #foo = \"foo\";\n}")
        assert(codes(d, 2300) == 1)
    }

    // POSITIVE CONTROLS — the MIRRORED order still reports both, and so do the
    // accessor/accessor and accessor/method groups pristine flags in full.

    /** `privateNameDuplicateField` lines 17-18: field FIRST, then the accessor. */
    @Test
    fun `a field followed by a getter is still TS2300 at BOTH`() {
        val d = check("class C {\n    p = \"foo\";\n    get p() { return \"\" }\n}")
        assert(codes(d, 2300) == 2)
    }

    /** `privateNameDuplicateField` lines 23-24: field then SETTER. */
    @Test
    fun `a field followed by a setter is still TS2300 at BOTH`() {
        val d = check("class C {\n    p = \"foo\";\n    set p(v: string) { }\n}")
        assert(codes(d, 2300) == 2)
    }

    /** `privateNameDuplicateField` lines 118-119, and `duplicateClassElements`' `z2`: two
     *  getters flag the whole group however the property sits. */
    @Test
    fun `two getters are still TS2300 at BOTH`() {
        val d = check("class C {\n    get p() { return \"\" }\n    get p() { return \"\" }\n}")
        assert(codes(d, 2300) == 2)
    }

    /** `privateNameDuplicateField` lines 112-113: getter then METHOD — pristine flags both,
     *  and a method is exactly the kind the new order rule must NOT absorb. */
    @Test
    fun `a getter followed by a method is still TS2300 at BOTH`() {
        val d = check("class C {\n    get p() { return \"\" }\n    p() { }\n}")
        assert(codes(d, 2300) == 2)
    }

    /** `duplicateClassElements`' `x`: property FIRST, then a complete accessor pair — all
     *  three flagged; and its `x2`: the pair first, then the property — the property alone.
     *  The complete-pair arm is the one this round MERGED, so both directions are pinned. */
    @Test
    fun `a complete accessor pair keeps its order-sensitive answer`() {
        assert(codes(check(
            "class C {\n    p;\n    get p() { return 1 }\n    set p(v: number) { }\n}"
        ), 2300) == 3)
        assert(codes(check(
            "class C {\n    get p() { return 1 }\n    set p(v: number) { }\n    p;\n}"
        ), 2300) == 1)
    }

    /** A clean get/set pair with no property is not a duplicate at all. */
    @Test
    fun `negative control - a clean accessor pair is silent`() {
        assert(codes(check("class C {\n    get p() { return 1 }\n    set p(v: number) { }\n}"), 2300) == 0)
    }

    // ── (CHK.5)(f) TS2741 names a late-bound key AS WRITTEN ──────────────────

    private fun ts2741(d: List<Diagnostic>): String {
        val hits = d.filter { it.code == 2741 }
        assert(hits.size == 1)
        return hits[0].message
    }

    @Test
    fun `a missing member declared by a const-keyed computed name is named as written`() {
        assert(ts2741(check(
            "const K = \"p\";\ninterface I { [K]: number }\nconst x: I = {};"
        )) == "Property '[K]' is missing in type '{}' but required in type 'I'.")
    }

    @Test
    fun `a missing member declared by an ENUM-keyed computed name is named as written`() {
        assert(ts2741(check(
            "enum E { A = \"a\" }\ninterface I { [E.A]: number }\nconst x: I = {};"
        )) == "Property '[E.A]' is missing in type '{}' but required in type 'I'.")
    }

    @Test
    fun `a missing member declared by a QUOTED computed name is named as written`() {
        assert(ts2741(check(
            "interface I { [\"a\"]: number }\nconst x: I = {};"
        )) == "Property '[\"a\"]' is missing in type '{}' but required in type 'I'.")
    }

    /** THE NEGATIVE CONTROL: a plain member keeps its bare name, so the renderer cannot
     *  have been wired in unconditionally. */
    @Test
    fun `negative control - a NON-computed missing member keeps its bare name`() {
        assert(ts2741(check("interface J { p: number }\nconst y: J = {};"))
            == "Property 'p' is missing in type '{}' but required in type 'J'.")
    }

    /** …and so does a quoted STRING member name, whose own display rule (round B291's
     *  source-quote preservation) sits immediately after the new computed arm. */
    @Test
    fun `negative control - a quoted string member name keeps its quoted display`() {
        assert(ts2741(check("interface J { \"a-b\": number }\nconst y: J = {};"))
            == "Property '\"a-b\"' is missing in type '{}' but required in type 'J'.")
    }
}
