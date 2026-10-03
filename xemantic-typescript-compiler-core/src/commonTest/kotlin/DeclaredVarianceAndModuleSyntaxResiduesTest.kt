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

/**
 * (P18.280) (CHK.223) and (CHK.224). Every expected row is tsgo 7.0.2's (cells under
 * `build/bench/p18280-agent/{r1,m,m2,m3}`, 1-based column, heads only — tsgo additionally
 * prints an argument-level elaboration chain under a variance failure that this engine does
 * not, which is a pre-existing TEXT residue and not what these pins measure).
 *
 * (CHK.223): the same-target type-reference shortcut compared every type argument covariantly,
 * so zod's `$ZodCheck<in T>` judged `$ZodCheck<boolean>` NOT assignable to `$ZodCheck<never>`
 * and every `ZodMiniString` / `ZodMiniBoolean` failed `SomeType` through `_zod.def.checks`.
 * tsgo takes a declared `in` / `out` annotation as the variance outright.
 *
 * (CHK.224): TS2661 for a name declared inside `declare global { }` (its declaration container
 * is the augmentation block, not a global file), and TS2440 for an import-equals meeting a
 * variable of a namespace block it does not share a symbol table with.
 */
class DeclaredVarianceAndModuleSyntaxResiduesTest {

    private fun rows(source: String, directives: String = "// @strict: true\n// @target: es2020\n// @module: esnext"): List<String> =
        diagnose(source, directives = directives)
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    // ---- (CHK.223) declared variance ----

    @Test
    fun `an in-annotated parameter relates its arguments contravariantly`() {
        val r = rows(
            """
            interface K<in T> { f: (x: T) => void; }
            declare const k1: K<boolean>; export const e1: K<never> = k1;
            declare const k2: K<string>; export const e2: K<"a"> = k2;
            declare const k3: K<"a">; export const e3: K<string> = k3;
            """
        )
        assert(r == listOf("4:40 TS2322 Type 'K<\"a\">' is not assignable to type 'K<string>'."))
    }

    @Test
    fun `an in out parameter is invariant and an out parameter stays covariant`() {
        val r = rows(
            """
            interface I<in out T> { f: (x: T) => T; }
            declare const i1: I<string>; export const e4: I<"a"> = i1;
            declare const i2: I<"a">; export const e5: I<string> = i2;
            declare const i3: I<string>; export const e6: I<string> = i3;
            interface O<out T> { v: T }
            declare const o1: O<"a">; export const e7: O<string> = o1;
            declare const o2: O<string>; export const e8: O<"a"> = o2;
            """
        )
        assert(r == listOf(
            "2:43 TS2322 Type 'I<string>' is not assignable to type 'I<\"a\">'.",
            "3:40 TS2322 Type 'I<\"a\">' is not assignable to type 'I<string>'.",
            "7:43 TS2322 Type 'O<string>' is not assignable to type 'O<\"a\">'.",
        ))
    }

    @Test
    fun `a class type parameter annotated in is contravariant too`() {
        val r = rows(
            """
            class C<in T> { f(x: T) {} }
            declare const c1: C<string>; export const e9: C<"a"> = c1;
            declare const c2: C<"a">; export const e10: C<string> = c2;
            """
        )
        assert(r == listOf("3:40 TS2322 Type 'C<\"a\">' is not assignable to type 'C<string>'."))
    }

    /** The zod shape reduced: `$ZodCheck<in T>` reached through an array member of the def. */
    @Test
    fun `the reduced zod schema satisfies its constraint through an in-annotated check array`() {
        val r = rows(
            """
            interface CheckInternals<T> { check(payload: { value: T }): void; }
            interface Check<in T = never> { _zod: CheckInternals<T>; }
            interface TypeDef { type: string; checks?: Check<never>[]; }
            interface BoolDef extends TypeDef { type: "boolean"; checks?: Check<boolean>[]; }
            interface Internals { def: TypeDef; }
            interface BoolInternals extends Internals { def: BoolDef; }
            type SomeType = { _zod: Internals };
            interface Bool { _zod: BoolInternals; }
            interface Codec<A extends SomeType, B extends SomeType = SomeType> { a: A; b: B; }
            export type C1 = Codec<Bool, Bool>;
            declare const b: Bool; export const s: SomeType = b;
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - a wrong argument under an in parameter still fails its constraint`() {
        val r = rows(
            """
            interface Check<in T = never> { run(v: T): void; }
            interface Holder { checks: Check<string>[]; }
            interface Bad { checks: Check<number>[]; }
            interface Codec<A extends Holder> { a: A; }
            export type C1 = Codec<Bad>;
            """
        )
        assert(r == listOf("5:24 TS2344 Type 'Bad' does not satisfy the constraint 'Holder'."))
    }

    // ---- (CHK.224) TS2661 ----

    @Test
    fun `a declare global declaration is exportable`() {
        val r = rows(
            """
            declare global { var gv: number; let gl: number; const gc: number; function gf(): void; interface GI { a: number } namespace GN { const z: number } }
            export { gv, gl, gc, gf, GI, GN };
            export { gv as renamed };
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a global script declaration and a lib global are still not exportable`() {
        val r = rows(
            """
            // @Filename: script.ts
            var sv = 1;
            declare var sv2: number;

            // @Filename: t.ts
            declare global { var sv2: number; interface Array<T> { extra: T } }
            export { sv, sv2, Array };
            """
        )
        assert(r == listOf(
            "2:10 TS2661 Cannot export 'sv'. Only local declarations can be exported from a module.",
            "2:14 TS2661 Cannot export 'sv2'. Only local declarations can be exported from a module.",
            "2:19 TS2661 Cannot export 'Array'. Only local declarations can be exported from a module.",
        ))
    }

    @Test
    fun `negative control - an undeclared export is still TS2304`() {
        assert(rows("export { undeclared };") == listOf("1:10 TS2304 Cannot find name 'undeclared'."))
    }

    // ---- (CHK.224) TS2440 across namespace blocks ----

    @Test
    fun `a local import never meets a variable of another namespace block`() {
        val r = rows(
            """
            namespace Q { var X = 1; }
            namespace Q { import X = Q; }
            namespace R { export var X = 1; }
            namespace R { import X = R; }
            namespace S { import X = S; }
            namespace S { export var X = 1; }
            export {};
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `an exported import meets only an exported variable`() {
        val r = rows(
            """
            namespace Q { var X = 1; }
            namespace Q { export import X = Q; }
            namespace R { var X = 1; export import X = R; }
            namespace S { export var X = 1; }
            namespace S { export import X = S; }
            namespace T { export import X = T; }
            namespace T { export let X = 1; }
            namespace U { export var X = 1; export import X = U; }
            export {};
            """
        )
        assert(r == listOf(
            "5:15 TS2440 Import declaration conflicts with local declaration of 'X'.",
            "6:15 TS2440 Import declaration conflicts with local declaration of 'X'.",
            "8:33 TS2440 Import declaration conflicts with local declaration of 'X'.",
        ))
    }

    @Test
    fun `a local import meets every variable of its own block`() {
        val r = rows(
            """
            namespace Q { var X = 1; import X = Q; }
            export {};
            """
        )
        assert(r == listOf("1:26 TS2440 Import declaration conflicts with local declaration of 'X'."))
    }
}
