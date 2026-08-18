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
 * Round 936 — the three routes round 935 left open on the OBJECT-LITERAL side of late
 * binding, each measured against `tsc 7.0.2` on a scratch project in BOTH directions
 * before anything was written, and each a false POSITIVE one way and a false NEGATIVE
 * the other — the signature round 935 recorded for one missing capability.
 *
 *  - **QUALIFIED keys**: `NS.K`, `NS.Inner.IK`, `NS.CE.P`. All bind in tsc; here the
 *    supply direction was TS2741 (a program tsc accepts, rejected) and the excess
 *    direction was silent (a program tsc rejects, accepted).
 *  - **TYPE-ANNOTATION spellings**: a no-substitution template-literal TYPE
 *    (``declare const TT: `p` ``) and a TYPE ALIAS to a literal, including a chain of
 *    them. Round 935 read `decl.type as? LiteralType` and nothing else.
 *  - **WELL-KNOWN SYMBOL keys** in the EXCESS check: `{ [Symbol.iterator]: 1 }` against
 *    `{ p?: number }` is TS2353 in tsc and was silent here. The SUPPLY direction has
 *    been right since round 723 — the literal's type and an interface's own member are
 *    both named `[Symbol.iterator]` by `computedSymbolKey` — so only the excess naming,
 *    which round 934 excluded that helper from wholesale, could not see the key.
 *
 * **THE NEGATIVE CONTROLS ARE THE CORRECTNESS ARGUMENT, NOT DECORATION.** tsc is SILENT
 * for every computed key it cannot late-bind — measured this round over seven of them —
 * so admitting `computedSymbolKey`'s invented `"[<dotted>]"` name generally would turn
 * each one into a false positive. That is why the well-known-symbol route demands the
 * receiver be the identifier `Symbol` with no local binding of that name, and why a
 * widened namespace `let`, a substituting template type and an alias to a union must
 * all still refuse.
 *
 * STILL OPEN and deliberately NOT pinned (round 765 — a known-open gap is a countdown,
 * not a guard), with tsc's answer measured for each: a `unique symbol` binding
 * (`[S]`/`[S2]` are ONE name here, and the declaration side declares no member at all,
 * so naming the key would be a false positive against an interface that has it); a
 * CLASS's `static readonly` const (`[C.B]`); a const imported from another FILE; and
 * the whole DECLARATION side — an interface's or class's own `[K]` member and the
 * duplicate-key TS1117. See (CHK.5).
 */
class LateBoundQualifiedKeyTest {

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

    // ── SUPPLY, qualified: the false positives — tsc is silent on every one ──

    @Test
    fun `a namespace qualified const supplies the member`() {
        val d = check(
            "namespace NS { export const K = \"p\"; }\n" + req + "const r: Req = { [NS.K]: 1 };"
        )
        assert(!missingP(d))
    }

    @Test
    fun `a const in a NESTED namespace supplies the member`() {
        val d = check(
            "namespace NS { export namespace In { export const K = \"p\"; } }\n" + req +
                "const r: Req = { [NS.In.K]: 1 };"
        )
        assert(!missingP(d))
    }

    @Test
    fun `a const in a DOTTED namespace declaration supplies the member`() {
        // `namespace A.B { }` is ONE ModuleDeclaration whose name is a dotted path in
        // this parser, not nested declarations - so the descent matches a whole PATH.
        val d = check(
            "namespace DD.EE { export const K = \"p\"; }\n" + req + "const r: Req = { [DD.EE.K]: 1 };"
        )
        assert(!missingP(d))
    }

    @Test
    fun `a namespace declared AFTER the key still supplies the member`() {
        val d = check(
            req + "const r: Req = { [After.K]: 1 };\nnamespace After { export const K = \"p\"; }"
        )
        assert(!missingP(d))
    }

    @Test
    fun `a MERGED namespace's second block supplies the member`() {
        val d = check(
            "namespace M { export const A = \"a\"; }\nnamespace M { export const K = \"p\"; }\n" +
                req + "const r: Req = { [M.K]: 1 };"
        )
        assert(!missingP(d))
    }

    @Test
    fun `a const enum member declared inside a namespace supplies the member`() {
        val d = check(
            "namespace NS { export const enum CE { P = \"p\" } }\n" + req +
                "const r: Req = { [NS.CE.P]: 1 };"
        )
        assert(!missingP(d))
    }

    @Test
    fun `a plain enum member declared inside a namespace supplies the member`() {
        val d = check(
            "namespace NS { export enum SE { P = \"p\" } }\n" + req +
                "const r: Req = { [NS.SE.P]: 1 };"
        )
        assert(!missingP(d))
    }

    @Test
    fun `a declared const with a literal annotation inside a namespace supplies the member`() {
        val d = check(
            "namespace NS { export declare const D: \"p\"; }\n" + req + "const r: Req = { [NS.D]: 1 };"
        )
        assert(!missingP(d))
    }

    // ── SUPPLY, the annotation spellings ─────────────────────────────────────

    @Test
    fun `a template literal TYPE annotation supplies the member`() {
        val d = check("declare const TT: `p`;\n" + req + "const r: Req = { [TT]: 1 };")
        assert(!missingP(d))
    }

    @Test
    fun `a type alias to a string literal supplies the member`() {
        val d = check("type LP = \"p\";\ndeclare const A: LP;\n" + req + "const r: Req = { [A]: 1 };")
        assert(!missingP(d))
    }

    @Test
    fun `a type alias CHAIN supplies the member`() {
        val d = check(
            "type LP = \"p\";\ntype LP2 = LP;\ndeclare const A: LP2;\n" + req +
                "const r: Req = { [A]: 1 };"
        )
        assert(!missingP(d))
    }

    @Test
    fun `a type alias to a template literal type supplies the member`() {
        val d = check("type TL = `p`;\ndeclare const A: TL;\n" + req + "const r: Req = { [A]: 1 };")
        assert(!missingP(d))
    }

    // ── SUPPLY, the refusals — tsc reports TS2741 for every one of these ──────

    @Test
    fun `negative control - a widened namespace let does NOT supply the member`() {
        val d = check(
            "namespace NS { export let LW = \"p\"; }\n" + req + "const r: Req = { [NS.LW]: 1 };"
        )
        assert(missingP(d))
    }

    @Test
    fun `negative control - a SUBSTITUTING template literal type does NOT supply the member`() {
        // The parser stores every template TYPE with EMPTY spans and the whole raw slice
        // in `head.rawText` (B65.1), so `templateSpans.isEmpty()` is true for this one
        // too and reading `head.text` answers "" - a name matching no member, which is
        // worse than refusing. The raw text is the only discriminator that exists.
        val d = check("declare const T2: `p\${string}`;\n" + req + "const r: Req = { [T2]: 1 };")
        assert(missingP(d))
    }

    @Test
    fun `negative control - a type alias to a UNION does NOT supply the member`() {
        val d = check(
            "type LU = \"p\" | \"q\";\ndeclare const A: LU;\n" + req + "const r: Req = { [A]: 1 };"
        )
        assert(missingP(d))
    }

    // ── EXCESS: the false negatives — tsc names the key AS WRITTEN ────────────

    @Test
    fun `a namespace qualified const key is excess when it names no member`() {
        val d = check(
            "namespace NS { export const KZ = \"zz\"; }\n" + opt + "const o: Opt = { [NS.KZ]: 1 };"
        )
        assert(excess(d, "[NS.KZ]"))
    }

    @Test
    fun `a nested namespace const key is excess when it names no member`() {
        val d = check(
            "namespace NN { export namespace II { export const Q = \"zz\"; } }\n" + opt +
                "const o: Opt = { [NN.II.Q]: 1 };"
        )
        assert(excess(d, "[NN.II.Q]"))
    }

    @Test
    fun `an enum member declared inside a namespace is excess when it names no member`() {
        val d = check(
            "namespace NS { export const enum CE { Q = \"zz\" } }\n" + opt +
                "const o: Opt = { [NS.CE.Q]: 1 };"
        )
        assert(excess(d, "[NS.CE.Q]"))
    }

    @Test
    fun `a template literal TYPE annotated key is excess when it names no member`() {
        val d = check("declare const TZ: `zz`;\n" + opt + "const o: Opt = { [TZ]: 1 };")
        assert(excess(d, "[TZ]"))
    }

    @Test
    fun `a type alias annotated key is excess when it names no member`() {
        val d = check("type LZ = \"zz\";\ndeclare const A: LZ;\n" + opt + "const o: Opt = { [A]: 1 };")
        assert(excess(d, "[A]"))
    }

    @Test
    fun `a well known symbol key is excess when the target lacks it`() {
        val d = check(opt + "const o: Opt = { [Symbol.iterator]: 1 };")
        assert(excess(d, "[Symbol.iterator]"))
    }

    // ── EXCESS: the refusals — tsc is SILENT for every key it cannot late-bind ─

    @Test
    fun `negative control - a well known symbol key the target HAS is not excess`() {
        val d = check(
            "interface HasI { [Symbol.iterator]: number }\n" +
                "const o: HasI = { [Symbol.iterator]: 1 };"
        )
        assert(d.none { it.code == 2353 })
    }

    @Test
    fun `negative control - a locally shadowed Symbol makes the key dynamic again`() {
        // `Symbol` resolving to a local binding is not the global well-known-symbol
        // object, so the key spells nothing fixed and tsc reports nothing.
        val d = check(
            "function f() {\n  const Symbol = { iterator: \"zz\" };\n" + opt +
                "  const o: Opt = { [Symbol.iterator]: 1 };\n  return o;\n}\nvoid f;"
        )
        assert(d.none { it.code == 2353 })
    }

    @Test
    fun `negative control - a widened namespace let key is NOT reported excess`() {
        val d = check(
            "namespace NS { export let LW = \"zz\"; }\n" + opt + "const o: Opt = { [NS.LW]: 1 };"
        )
        assert(d.none { it.code == 2353 })
    }

    @Test
    fun `negative control - an ordinary dotted key is NOT reported excess`() {
        val d = check(
            "declare const obj: { k: string };\n" + opt + "const o: Opt = { [obj.k]: 1 };"
        )
        assert(d.none { it.code == 2353 })
    }

    @Test
    fun `negative control - a namespace qualified key naming an existing member is not excess`() {
        val d = check(
            "namespace NS { export const K = \"p\"; }\n" + opt + "const o: Opt = { [NS.K]: 1 };"
        )
        assert(d.none { it.code == 2353 })
    }

    // ── the ambient-determinism pin, for the routes this round adds ───────────

    @Test
    fun `a qualified late-bound key is one member in every pass`() {
        // Round 935's core pin, re-asked of the namespace route: a name a member table is
        // built from must be a function of the PROGRAM. The head could have been resolved
        // through `currentFileLocals`, which is AMBIENT - not the same map in every pass -
        // and this fixture is what a per-pass answer breaks: the correct TS2322 together
        // with a contradictory `Property 'p' does not exist on type '{}'`.
        val d = check(
            "namespace NS { export const K = \"p\"; }\nconst obj = { [NS.K]: 1 };\n" +
                "const probe: string = obj.p;\nvoid probe;"
        )
        assert(d.none { it.code == 2339 })
        assert(d.count { it.code == 2322 } == 1)
    }

    @Test
    fun `a template literal TYPE annotated key is one member in every pass`() {
        val d = check(
            "declare const TT: `p`;\nconst obj = { [TT]: 1 };\nconst probe: string = obj.p;\nvoid probe;"
        )
        assert(d.none { it.code == 2339 })
        assert(d.count { it.code == 2322 } == 1)
    }
}
