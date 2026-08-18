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
 * Round 937 — (CHK.5)(a): the DECLARATION side of late binding. `interface I { [K]: number }`,
 * `class C { [K]: number }` and `type T = { [K]: number }` declare a member `p` in tsc, and
 * this compiler declared NOTHING — so the same missing capability was a false NEGATIVE for an
 * interface and a type literal (silent where tsc reports) and a false POSITIVE for a class
 * (`c.p` was TS2339, on a member the type has).
 *
 * **THE ROW THAT MAKES THIS ONE ROUND RATHER THAN TWO IS THE ONE WITH THE KEY ON BOTH SIDES.**
 * Round 935 landed `[K]` for the object LITERAL alone, so `const x: I = { [K]: 1 }` had the
 * literal naming a member `p` and the interface declaring none — reported as the excess key
 * `'[K]'`, a false positive on a program both compilers accept, which round 936 predicted for
 * `unique symbol` and which was already live for a plain const. Naming one side of a member
 * comparison is not half a fix; it is a new defect.
 *
 * Every row below was READ from `tools/tsgo-7.0.2/lib/tsc --noEmit -p .` on a scratch project
 * and from `MainKt --noEmit --listAll` on the SAME directory, before anything was written.
 *
 * **REFUSED, and the refusals are measured parity rather than omissions.** A key whose type is
 * `string`, a literal UNION, or a dotted path through a VALUE gives tsc's interface a STRING
 * INDEX SIGNATURE rather than a named member — a different modelling gap, and one late binding
 * must not pretend to close. A `unique symbol` key stays refused on BOTH sides at once, which is
 * why the pin here is that `{ [S]: 1 }` against an interface declaring `[S]` is SILENT: that is
 * the row both compilers agree on today and the row a one-sided fix would break — see (CHK.5)(d).
 *
 * NOT pinned, deliberately (round 765 — a known-open gap is a countdown, not a guard): a
 * `unique symbol` key's own TS2741, a CLASS `static readonly` key, a key imported from another
 * FILE, the string-index-signature rows, and the duplicate `{ p: number; [K]: string }`, where
 * tsc reports TS2300/TS2717 and keeps the FIRST type while this compiler's member map is
 * last-wins for EVERY duplicate spelling, computed or not. See (CHK.5)(b) and (c).
 */
class LateBoundDeclarationKeyTest {

    private fun check(@Language("typescript") source: String): List<Diagnostic> =
        diagnose(source.trimIndent(), directives = "// @strict: true")

    private val k = "const K = \"p\";\n"

    /** The member RESOLVED and carries its declared type: reading it into a `string` is the
     *  one TS2322, and no TS2339 anywhere says the same key was two members in two passes. */
    private fun readsAsNumber(d: List<Diagnostic>) {
        assert(d.none { it.code == 2339 })
        assert(d.count { it.code == 2322 } == 1)
    }

    // ── the READ direction: the member exists and has its declared type ───────

    @Test
    fun `an interface's own late-bound key declares the member`() {
        readsAsNumber(check(k + "interface I { [K]: number }\ndeclare const i: I;\nconst s: string = i.p;"))
    }

    @Test
    fun `a class's own late-bound key declares the member`() {
        // The TS2339 false positive: `lookupInstanceMemberInResolvableChain` answered
        // "definitely no such member" through `classMemberNameText`, which refused the key.
        readsAsNumber(check(k + "class C { [K]: number = 1; }\ndeclare const c: C;\nconst s: string = c.p;"))
    }

    @Test
    fun `a type literal's own late-bound key declares the member`() {
        readsAsNumber(check(k + "type TL = { [K]: number };\ndeclare const t: TL;\nconst s: string = t.p;"))
    }

    @Test
    fun `an interface's late-bound METHOD key declares the member with its return type`() {
        // The quietest of the four extraction sites: the member was DECLARED (a missing one
        // was TS2741) and typed `anyType`, so the call's result related to everything.
        readsAsNumber(check(k + "interface I { [K](): number }\ndeclare const i: I;\nconst s: string = i.p();"))
    }

    @Test
    fun `a class's late-bound METHOD key declares the member with its return type`() {
        readsAsNumber(check(k + "class C { [K](): number { return 1; } }\ndeclare const c: C;\nconst s: string = c.p();"))
    }

    @Test
    fun `an interface's late-bound GET ACCESSOR key declares the member`() {
        readsAsNumber(check(k + "interface I { get [K](): number }\ndeclare const i: I;\nconst s: string = i.p;"))
    }

    @Test
    fun `a class's late-bound STATIC key declares the static member`() {
        readsAsNumber(check(k + "class C { static [K]: number; }\nconst s: string = C.p;"))
    }

    @Test
    fun `a const enum member key declares the member`() {
        readsAsNumber(check(
            "const enum CE { P = \"p\" }\ninterface I { [CE.P]: number }\ndeclare const i: I;\nconst s: string = i.p;"
        ))
    }

    @Test
    fun `a plain enum member key declares the member`() {
        readsAsNumber(check(
            "enum SE { Q = \"q\" }\ninterface I { [SE.Q]: number }\ndeclare const i: I;\nconst s: string = i.q;"
        ))
    }

    @Test
    fun `a namespace qualified key declares the member`() {
        readsAsNumber(check(
            "namespace NS { export const K = \"p\"; }\ninterface I { [NS.K]: number }\n" +
                "declare const i: I;\nconst s: string = i.p;"
        ))
    }

    @Test
    fun `a template literal TYPE annotated key declares the member`() {
        readsAsNumber(check(
            "declare const TT: `p`;\ninterface I { [TT]: number }\ndeclare const i: I;\nconst s: string = i.p;"
        ))
    }

    @Test
    fun `a numeric key is declared under its canonical value`() {
        // tsc names `[N]` where `N = 1e3` the member "1000", never the source text.
        readsAsNumber(check(
            "const N = 1e3;\ninterface I { [N]: number }\ndeclare const i: I;\nconst s: string = i[1000];"
        ))
    }

    @Test
    fun `a const ALIAS chain key declares the member`() {
        readsAsNumber(check(
            k + "const K2 = K;\ninterface I { [K2]: number }\ndeclare const i: I;\nconst s: string = i.p;"
        ))
    }

    @Test
    fun `a late-bound member is INHERITED through an interface extends`() {
        readsAsNumber(check(
            k + "interface Base { [K]: number }\ninterface Derived extends Base { }\n" +
                "declare const d: Derived;\nconst s: string = d.p;"
        ))
    }

    @Test
    fun `a late-bound member is INHERITED through a class extends`() {
        readsAsNumber(check(
            k + "class Base { [K]: number = 1; }\nclass Derived extends Base { }\n" +
                "declare const d: Derived;\nconst s: string = d.p;"
        ))
    }

    @Test
    fun `an interface inside a namespace binds its namespace's own const`() {
        readsAsNumber(check(
            "namespace NS { const K6 = \"p\"; export interface I { [K6]: number } }\n" +
                "declare const i: NS.I;\nconst s: string = i.p;"
        ))
    }

    // ── the SUPPLY direction: the declared member is REQUIRED ─────────────────

    @Test
    fun `an empty literal is missing a required late-bound member`() {
        val d = check(k + "interface I { [K]: number }\nconst x: I = {};")
        assert(d.any { it.code == 2741 })
    }

    @Test
    fun `the SAME key on both sides is neither missing nor excess`() {
        // The round-936 inversion, live for a plain const since round 935: the literal named
        // `p` and the interface named nothing, so a program both compilers accept was TS2353.
        val d = check(k + "interface I { [K]: number }\nconst x: I = { [K]: 1 };")
        assert(d.none { it.code == 2353 })
        assert(d.none { it.code == 2741 })
    }

    @Test
    fun `a plainly spelled key satisfies a late-bound member`() {
        val d = check(k + "interface I { [K]: number }\nconst x: I = { p: 1 };")
        assert(d.none { it.code == 2353 })
        assert(d.none { it.code == 2741 })
    }

    @Test
    fun `an unrelated key is still excess against a late-bound target`() {
        val d = check(k + "interface I { [K]?: number }\nconst x: I = { q: 1 };")
        assert(d.any { it.code == 2353 })
    }

    // ── the refusals: tsc's own answer on every one ──────────────────────────

    @Test
    fun `negative control - a widened let key declares no member`() {
        // tsc gives such an interface a STRING INDEX SIGNATURE, which `{}` satisfies; a member
        // named `p` would make this TS2741. The index signature itself is a separate gap.
        val d = check("let LW = \"p\";\ninterface I { [LW]: number }\nconst x: I = {};\nvoid x;")
        assert(d.none { it.code == 2741 })
    }

    @Test
    fun `negative control - a genuine literal union key declares no member`() {
        val d = check("declare const U: \"p\" | \"q\";\ninterface I { [U]: number }\nconst x: I = {};\nvoid x;")
        assert(d.none { it.code == 2741 })
    }

    @Test
    fun `negative control - a plain symbol key declares no member`() {
        val d = check("declare const PS: symbol;\ninterface I { [PS]: number }\nconst x: I = {};\nvoid x;")
        assert(d.none { it.code == 2741 })
    }

    @Test
    fun `negative control - a dotted path through a VALUE declares no member`() {
        val d = check(
            "declare const obj: { k: string };\ninterface I { [obj.k]: number }\nconst x: I = {};\nvoid x;"
        )
        assert(d.none { it.code == 2741 })
    }

    @Test
    fun `negative control - a unique symbol key stays refused on BOTH sides`() {
        // (CHK.5)(d). Naming `[S]` on the literal side alone INVERTS the defect: this program
        // is silent in both compilers today and would become a false positive. The pin is the
        // agreement, not the gap.
        val d = check(
            "declare const S: unique symbol;\ninterface HasS { [S]: number }\nconst x: HasS = { [S]: 1 };"
        )
        assert(d.none { it.code == 2353 })
        assert(d.none { it.code == 2741 })
    }

    @Test
    fun `negative control - a string index signature is not read as a member named k`() {
        val d = check("interface J { [k: string]: number }\nconst x: J = {};\nvoid x;")
        assert(d.none { it.code == 2741 })
    }

    @Test
    fun `negative control - a mapped type placeholder is untouched`() {
        val d = check("type M<T> = { [P in keyof T]: number };\nconst x: M<{ a: string }> = { a: 1 };\nvoid x;")
        assert(d.none { it.code == 2741 })
        assert(d.none { it.code == 2353 })
    }

    @Test
    fun `a well-known symbol member still matches the object literal's`() {
        // Round 723's route: [getMemberName] names `[Symbol.iterator]` and keeps winning, so
        // the declaration side and the literal side agree exactly as they did.
        val d = check(
            "interface HasIt { [Symbol.iterator]: number }\nconst x: HasIt = {};\nvoid x;"
        )
        assert(d.any { it.code == 2741 })
    }

    // ── the cross-pass determinism pins: a member name is a function of the PROGRAM ──

    @Test
    fun `an interface's late-bound member is one member in every pass`() {
        // Round 935's core pin asked of the DECLARATION table, which — unlike an object
        // literal's type — is BUILT ONCE AND CACHED, so a name that depended on the ambient
        // would freeze whichever pass touched the type first (round 776's first-touch law).
        val d = check(k + "interface I { [K]: number }\nconst i: I = { p: 1 };\nconst probe: string = i.p;\nvoid probe;")
        assert(d.none { it.code == 2339 })
        assert(d.none { it.code == 2353 })
        assert(d.count { it.code == 2322 } == 1)
    }

    @Test
    fun `a class's late-bound member is one member in every pass`() {
        val d = check(
            k + "class C { [K]: number = 1; }\ndeclare const c: C;\nconst probe: string = c.p;\n" +
                "const probe2: string = c.p;\nvoid probe;\nvoid probe2;"
        )
        assert(d.none { it.code == 2339 })
        assert(d.count { it.code == 2322 } == 2)
    }

    @Test
    fun `a type literal's late-bound member is one member in every pass`() {
        val d = check(
            k + "type TL = { [K]: number };\nconst t: TL = { p: 1 };\nconst probe: string = t.p;\nvoid probe;"
        )
        assert(d.none { it.code == 2339 })
        assert(d.none { it.code == 2353 })
        assert(d.count { it.code == 2322 } == 1)
    }
}
