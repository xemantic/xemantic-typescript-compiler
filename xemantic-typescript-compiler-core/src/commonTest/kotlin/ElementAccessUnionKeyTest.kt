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
 */


package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.139): an element access whose INDEX TYPE is a union of literals resolved to `anyType`.
 *
 * `mem[k]` where `k: keyof M` answered `any` — silently, and for the shape real code reaches
 * for most often (`marked`'s `tokenizer[tokenizerProp]`, key
 * `Exclude<keyof _Tokenizer<…>, 'options'|'rules'|'lexer'>`). Every branch of
 * `elementAccessResultType` tested the index EXPRESSION's syntax or the index type's
 * String/Number-LIKE flags, and a `Type.Union` carries neither.
 *
 * tsc's rule, fitted to 12 tsgo 7.0.2 fixtures and re-adjudicated cell by cell here:
 * distribute over the KEY union with a UNION for a READ. It keys on the index TYPE and never
 * on the syntax — `keyof T`, `"a" | "b"`, a literal-union ALIAS and `Exclude<keyof T, "zzz">`
 * are measured IDENTICAL, which is what the first four pins assert.
 *
 * The probes are deliberate MIS-ASSIGNMENTS, not silences: the resolved type is printed in the
 * message, so a pin that passed because the access still answered `any` would be visible.
 */
class ElementAccessUnionKeyTest {

    private val members = """
        interface M { a: string; b: number }
        declare const m: M;
    """.trimIndent() + "\n"

    private fun readType(d: List<Diagnostic>): String =
        d.single { it.code == 2322 }.message
            .substringAfter("Type '").substringBefore("' is not assignable")

    @Test
    fun `a keyof-typed index resolves to the union of the member types`() {
        val d = diagnose(members + """
            declare const k: keyof M;
            const probe: boolean = m[k];
        """.trimIndent())
        assert(readType(d) == "string | number")
    }

    @Test
    fun `an explicit literal union index resolves the same way`() {
        val d = diagnose(members + """
            declare const k: "a" | "b";
            const probe: boolean = m[k];
        """.trimIndent())
        assert(readType(d) == "string | number")
    }

    @Test
    fun `a literal-union ALIAS index resolves the same way`() {
        val d = diagnose(members + """
            type Alias = "a" | "b";
            declare const k: Alias;
            const probe: boolean = m[k];
        """.trimIndent())
        assert(readType(d) == "string | number")
    }

    @Test
    fun `an Exclude-derived index resolves the same way`() {
        // The rule is a property of the index TYPE, never of how it was spelled — so all four
        // of these must agree, and that agreement is the pin rather than any one value.
        // `Exclude` is declared locally: the EMBEDDED lib this harness uses has no utility
        // types, so the lib spelling is only reachable under `@useRealLibs`. The pin is about
        // the index TYPE, and a locally-declared conditional produces the same one.
        val d = diagnose(members + """
            type Excl<T, U> = T extends U ? never : T;
            declare const k: Excl<keyof M, "zzz">;
            const probe: boolean = m[k];
        """.trimIndent())
        assert(readType(d) == "string | number")
    }

    @Test
    fun `an ABSENT key makes the whole access any, not the union of the keys that exist`() {
        // THE BINDING CONSTRAINT. tsgo answers `any` for the WHOLE access (plus a TS7053 naming
        // the first absent key, which we do not emit — a stated gap, not a regression here).
        // Answering "the union of the keys that DO exist" would be strictly NARROWER than `any`
        // and so a false-positive generator: this probe would then report `Type 'string'`.
        val d = diagnose(
            """
            interface R { a: string }
            declare const r: R;
            declare const bad: "a" | "zzz";
            const probe: boolean = r[bad];
            """.trimIndent()
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `an OPTIONAL member contributes undefined to the union`() {
        // Inherited for free by delegating per constituent to `getIndexedAccessType`, whose
        // string-literal arm carries (CHK.96)'s `| undefined`. `elementAccessResultType`'s OWN
        // literal arm does not, which is a pre-existing asymmetry this arm deliberately routes
        // around rather than copying.
        val d = diagnose(
            """
            interface O { a: string; b?: number }
            declare const o: O;
            declare const k: keyof O;
            const probe: boolean = o[k];
            """.trimIndent()
        )
        assert(readType(d) == "string | number | undefined")
    }

    @Test
    fun `keyof a type WITH an index signature is not a literal union and takes the old path`() {
        // Risk R9 from the sizing brief, pinned. `keyof Ix` is `string | number` — not literal —
        // so this must NOT take the new arm; the answer is the plain index-signature lookup, and
        // tsgo agrees exactly.
        val d = diagnose(
            """
            interface Ix { a: string; b: number; [k: string]: string | number }
            declare const ix: Ix;
            declare const k: keyof Ix;
            const probe: boolean = ix[k];
            """.trimIndent()
        )
        assert(readType(d) == "string | number")
    }

    @Test
    fun `a CLASS instance receiver resolves methods and fields alike`() {
        val d = diagnose(
            """
            class K { m1(x: number): string { return "" } f = 1 }
            declare const kk: K;
            declare const k: keyof K;
            const probe: boolean = kk[k];
            """.trimIndent()
        )
        assert(readType(d) == "number | ((x: number) => string)")
    }

    @Test
    fun `a WRITE through a union key reports against the INTERSECTION, not the union`() {
        // (CHK.139)(b). The value written has to satisfy EVERY key the index could name, so tsc
        // distributes the KEY union with an INTERSECTION for a write where a read takes a UNION.
        // Measured against tsgo 7.0.2 down to the parentheses.
        val d = diagnose(
            """
            interface M2 { a: (t: { type: string }) => string; b: (t: { type: string }) => string }
            declare const m2: M2;
            declare const k: keyof M2;
            m2[k] = "nope";
            """.trimIndent()
        )
        assert(d.single { it.code == 2322 }.message ==
            "Type 'string' is not assignable to type " +
            "'((t: { type: string; }) => string) & ((t: { type: string; }) => string)'.")
    }

    @Test
    fun `an intersection of UNRELATED member types reduces to never, as tsgo renders it`() {
        // `string & number` is `never` by the ordinary primitive-domain reduction
        // `getIntersectionType` already performs — so the rendering comes out right with no
        // special case, which is the receipt that the write slot is built with the real helper.
        val d = diagnose(
            """
            interface D2 { a: string; b: number }
            declare const d2: D2;
            declare const k: keyof D2;
            d2[k] = { z: 1 };
            """.trimIndent()
        )
        assert(d.single { it.code == 2322 }.message ==
            "Type '{ z: number; }' is not assignable to type 'never'.")
    }

    @Test
    fun `negative control - a write that satisfies every key is silent`() {
        val d = diagnose(
            """
            interface S2 { a: string; b: string }
            declare const s2: S2;
            declare const k: keyof S2;
            s2[k] = "fine";
            """.trimIndent()
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `a SINGLETON literal key type is the same question as a union of them`() {
        // `Exclude<keyof Tok, 'options'>` on a class with ONE remaining member is `"space"`, a
        // bare literal and not a union — and the first cut of this round, which matched only
        // `Type.Union`, left exactly that shape answering `anyType`. Found by bisecting a
        // `marked`-shaped fixture one ingredient at a time; tsgo reports this row.
        val d = diagnose(
            """
            class Tok { options: number = 1; space(src: string): string { return "" } }
            type Excl<T, U> = T extends U ? never : T;
            declare const tok: Tok;
            declare const k: Excl<keyof Tok, 'options'>;
            tok[k] = (...args: unknown[]) => { return 1; };
            """.trimIndent()
        )
        assert(d.single { it.code == 2322 }.message ==
            "Type '(...args: unknown[]) => number' is not assignable to type '(src: string) => string'.")
    }

    @Test
    fun `an enclosing class TYPE PARAMETER in the receiver no longer blocks the write check`() {
        // CLOSED 2026-09-22 by (CHK.140), and this pin is the inverted countdown (P18.166)
        // left behind. That round bisected the shape correctly — only a receiver whose type
        // argument is the ENCLOSING class's own type parameter failed — but attributed it to
        // round 761's globally-`any` cached member type. It was not the member table at all:
        // `P` simply was not in scope inside a class member's body, because `ctaFnBodyFrame`
        // fed the enclosing class's type parameters to `fnTpDecls` (the AST map) and never to
        // `fnTpScope` (the map that types a name). With the name in scope the member resolves
        // to `(s: string) => P`, the write is compared against it, and the row appears.
        //
        // The previous comment also recorded that tsgo is SILENT here. Re-measured on tsgo
        // 7.0.2, it REPORTS — and our message is now byte-identical to it, which is what this
        // pin asserts. `marked`'s two `@ts-expect-error` shadows at Instance.ts:206 and :219
        // are the same mechanism and closed with it.
        val d = diagnose(
            """
            type Excl3<T, U> = T extends U ? never : T;
            class T3<P> { options: number = 1; a(s: string): P { return null! } b(s: string): number { return 1 } }
            class Host3<P> {
              use() {
                const t = new T3<P>();
                const k = "a" as Excl3<keyof T3<P>, 'options'>;
                t[k] = (...args: unknown[]) => { return 1; };
              }
            }
            """.trimIndent()
        )
        assert(d.single { it.code == 2322 }.message ==
            "Type '(...args: unknown[]) => number' is not assignable to type " +
            "'((s: string) => P) & ((s: string) => number)'.")
    }

    @Test
    fun `negative control - a non-union index is untouched`() {
        val d = diagnose(
            """
            interface Bag { [k: string]: string }
            declare const bag: Bag;
            declare const key: string;
            const probe: boolean = bag[key];
            """.trimIndent()
        )
        assert(readType(d) == "string")
    }
}
