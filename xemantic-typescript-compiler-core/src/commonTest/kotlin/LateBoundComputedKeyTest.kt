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
 * Round 935 — LATE BINDING: a computed object-literal key names a member through the
 * key expression's VALUE, not through its spelling. `{ [K]: 1 }`, `{ [E.P]: 1 }`.
 *
 * ONE MECHANISM, TWO DEFECTS, MEASURED IN BOTH DIRECTIONS AGAINST `tsc 7.0.2` BEFORE
 * ANYTHING WAS WRITTEN. Supply: `const K = "p"; const r: Req = { [K]: 1 }` satisfies a
 * required `p` in tsc and was TS2741 here — a false POSITIVE, a program tsc accepts.
 * Excess: `{ p: 1, [E.Q]: 2 }` against `{ p?: number }` is TS2353 in tsc naming the key
 * as written and was SILENT here — a false NEGATIVE. Rounds 933 and 934 each drew the
 * line at "the key is a LITERAL spelling one fixed name" and left both rows open; they
 * are the same missing capability seen from its two sides.
 *
 * **THE ROUND'S PRODUCT IS THAT tsc's OWN RULE IS NOT PORTABLE AS WRITTEN.** tsc asks
 * `isTypeUsableAsPropertyName` — the key expression's TYPE — and the first draft here
 * did the same, through [Checker.getTypeOfExpression]. It measured as a name that is
 * NOT A FUNCTION OF THE PROGRAM: a file-level un-annotated `const K = "p"` answers the
 * literal `"p"` in the pass that checks assignability and the widened `string` in the
 * pass behind TS2339, so `const obj = { [K]: 1 }; obj.p` produced the correct TS2322
 * AND `Property 'p' does not exist on type '{}'` in ONE compile — round 933's
 * two-extraction-sites signature, reached through ambient state rather than through a
 * second `when` (round 911: a literal is typed in more than one ambient and
 * `currentLocalTypes` is not the same map in both). The landed resolution is therefore
 * SYNTACTIC: an enum member's value, or the declaration the name resolves to by an
 * innermost-first walk of the enclosing statement lists. `a late-bound key is one
 * member in every pass` is the pin that fails if the type route ever returns.
 *
 * WHAT STAYS OPEN, with tsc's answer measured for each and NOT pinned (round 765 — a
 * known-open gap is a countdown, not a guard), all of it (CHK.4): a `unique symbol`
 * has no type of its own here, so `[S]` and `[S2]` are one name; an INTERFACE's or a
 * CLASS's own `[K]` member, and the duplicate-key check TS1117, late-bind in tsc
 * through member tables computed after type resolution; a `NS.K` namespace const;
 * and a template-literal TYPE annotation with no initializer.
 */
class LateBoundComputedKeyTest {

    private fun check(@Language("typescript") source: String): List<Diagnostic> =
        diagnose(source.trimIndent(), directives = "// @strict: true")

    private val req = "interface Req { p: number }\n"
    private val opt = "interface Opt { p?: number }\n"

    private fun missingP(d: List<Diagnostic>) = d.any { it.code == 2741 }

    private fun excess(d: List<Diagnostic>, key: String, target: String = "Opt") = d.any {
        it.code == 2353 &&
            it.message == "Object literal may only specify known properties, and " +
            "'$key' does not exist in type '$target'."
    }

    // ── SUPPLY: the false positives - tsc is silent on every one of these ──

    @Test
    fun `a const with a string literal initializer supplies the member`() {
        val d = check("const K = \"p\";\n" + req + "const r: Req = { [K]: 1 };")
        assert(!missingP(d))
    }

    @Test
    fun `a const alias chain supplies the member`() {
        val d = check("const K = \"p\";\nconst K2 = K;\n" + req + "const r: Req = { [K2]: 1 };")
        assert(!missingP(d))
    }

    @Test
    fun `a let with a literal type annotation supplies the member - constness is not the criterion`() {
        val d = check("let L: \"p\" = \"p\";\n" + req + "const r: Req = { [L]: 1 };")
        assert(!missingP(d))
    }

    @Test
    fun `a declared const with a literal type annotation supplies the member`() {
        val d = check("declare const D: \"p\";\n" + req + "const r: Req = { [D]: 1 };")
        assert(!missingP(d))
    }

    @Test
    fun `a const initializer wins over a union annotation - the reference is narrowed to it`() {
        val d = check("const U: \"p\" | \"q\" = \"p\";\n" + req + "const r: Req = { [U]: 1 };")
        assert(!missingP(d))
    }

    @Test
    fun `a const enum member supplies the member`() {
        val d = check("const enum CE { P = \"p\" }\n" + req + "const r: Req = { [CE.P]: 1 };")
        assert(!missingP(d))
    }

    @Test
    fun `a plain string enum member supplies the member too`() {
        val d = check("enum SE { P = \"p\" }\n" + req + "const r: Req = { [SE.P]: 1 };")
        assert(!missingP(d))
    }

    @Test
    fun `a numeric const supplies a numerically named member by its VALUE`() {
        // tsc names `[N]` where `N = 1e3` "1000", not "1e3" - the value, not the text.
        // ASSERTING `none { 2741 }` ALONE IS BLIND, measured: a misnamed key is reported by
        // the EXCESS emitter instead, which short-circuits the missing-property report
        // exactly as tsc's does, so the arm that names the key by its source text left this
        // green (round 902's trap - a zero arm is as often a blind pin as a redundant guard).
        val d = check("const N = 1e3;\ninterface ReqN { 1000: number }\nconst r: ReqN = { [N]: 1 };")
        assert(d.none { it.code == 2741 || it.code == 2353 })
    }

    @Test
    fun `a numeric const does NOT name the member its SOURCE TEXT spells`() {
        // The positive half of the row above: `{ "1e3": number }` is the target a
        // text-named key would satisfy, and tsc reports it excess - measured.
        val d = check("const N = 1e3;\ninterface ReqT { \"1e3\": number }\nconst r: ReqT = { [N]: 1 };")
        assert(excess(d, "[N]", target = "ReqT"))
    }

    @Test
    fun `a function body local const supplies the member`() {
        val d = check(req + "function f() { const K = \"p\"; const r: Req = { [K]: 1 }; return r; }\nvoid f;")
        assert(!missingP(d))
    }

    @Test
    fun `an inner const shadows an outer one of the same name`() {
        val d = check(
            "const K = \"p\";\ninterface ReqQ { q: number }\n" +
                "function f() { const K = \"q\"; const r: ReqQ = { [K]: 1 }; return r; }\nvoid f;"
        )
        assert(d.none { it.code == 2741 })
    }

    // ── SUPPLY negative controls: tsc reports TS2741 for every one of these ──

    @Test
    fun `a widened let key names nothing - negative control`() {
        val d = check("let L = \"p\";\n" + req + "const r: Req = { [L]: 1 };")
        assert(missingP(d))
    }

    @Test
    fun `a genuine string literal union key names nothing - negative control`() {
        val d = check("declare const U2: \"p\" | \"q\";\n" + req + "const r: Req = { [U2]: 1 };")
        assert(missingP(d))
    }

    @Test
    fun `a plain symbol key names nothing - negative control`() {
        val d = check("declare const PS: symbol;\n" + req + "const r: Req = { [PS]: 1 };")
        assert(missingP(d))
    }

    @Test
    fun `a bare type parameter key names nothing - negative control`() {
        val d = check(
            req + "function f<KK extends string>(k: KK) { const r: Req = { [k]: 1 }; return r; }\nvoid f;"
        )
        assert(missingP(d))
    }

    @Test
    fun `an ambient non-const enum member with no initializer has no value and names nothing`() {
        // Round 746: tsc gives such a member no value at all - we auto-number it for the
        // Transformer only. tsc reports TS2741 here too - measured.
        val d = check("declare enum AE { X }\ninterface R0 { 0: number }\nconst r: R0 = { [AE.X]: 1 };")
        assert(d.any { it.code == 2741 })
    }

    // ── EXCESS: the mirror false negatives, named as written ────────────────

    @Test
    fun `a late-bound const key that names nothing in the target is excess`() {
        val d = check("const KZ = \"zz\";\n" + opt + "const o: Opt = { p: 1, [KZ]: 2 };")
        assert(excess(d, "[KZ]"))
    }

    @Test
    fun `a const enum member key that names nothing in the target is excess`() {
        val d = check("const enum CE { Q = \"zz\" }\n" + opt + "const o: Opt = { p: 1, [CE.Q]: 2 };")
        assert(excess(d, "[CE.Q]"))
    }

    @Test
    fun `a plain enum member key that names nothing in the target is excess`() {
        val d = check("enum SE { Q = \"zz\" }\n" + opt + "const o: Opt = { p: 1, [SE.Q]: 2 };")
        assert(excess(d, "[SE.Q]"))
    }

    @Test
    fun `a numeric enum member key is excess under its VALUE`() {
        val d = check("enum NE { P = 0 }\n" + opt + "const o: Opt = { p: 1, [NE.P]: 2 };")
        assert(excess(d, "[NE.P]"))
    }

    @Test
    fun `a late-bound excess key is reported in an argument and in a nested literal`() {
        val arg = check(
            "const KZ = \"zz\";\n" + opt +
                "declare function g(o: Opt): void;\ng({ p: 1, [KZ]: 2 });"
        )
        assert(excess(arg, "[KZ]"))
        val nested = check(
            "const KZ = \"zz\";\ninterface OptN { p?: { q?: number } }\n" +
                "const o: OptN = { p: { q: 1, [KZ]: 2 } };"
        )
        assert(nested.any { it.code == 2353 && it.message.contains("'[KZ]'") })
    }

    // ── EXCESS negative controls ────────────────────────────────────────────

    @Test
    fun `a const enum key naming an existing member is NOT excess - tsc accepts it`() {
        // The false positive `computedSymbolKey`'s invented "[CE.P]" placeholder
        // manufactures (round 934's arm A4): "[CE.P]" is in no target's member table.
        val d = check("const enum CE { P = \"p\" }\n" + opt + "const o: Opt = { [CE.P]: 1 };")
        assert(d.none { it.code == 2353 })
    }

    @Test
    fun `a late-bound const key naming an existing member is NOT excess`() {
        val d = check("const K = \"p\";\n" + opt + "const o: Opt = { [K]: 1 };")
        assert(d.none { it.code == 2353 })
    }

    @Test
    fun `a numeric index signature absorbs a late-bound numeric key`() {
        val d = check("const N = 7;\ninterface NI { [k: number]: string }\nconst o: NI = { [N]: \"a\" };")
        assert(d.none { it.code == 2353 })
    }

    // ── the ambient-determinism pin: ONE key is ONE member in EVERY pass ─────

    @Test
    fun `a late-bound key is one member in every pass`() {
        // THE ROUND'S CORE PIN. A type-based name (tsc's own rule, ported directly)
        // answers `"p"` in the assignability pass and the widened `string` in the pass
        // behind TS2339 for a FILE-LEVEL un-annotated const, so this fixture produced
        // the correct TS2322 and a contradictory TS2339 in one compile.
        val d = check("const K = \"p\";\nconst obj = { [K]: 1 };\nconst probe: string = obj.p;\nvoid probe;")
        assert(d.none { it.code == 2339 })
        assert(d.count { it.code == 2322 } == 1)
    }
}
