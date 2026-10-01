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
 * (CHK.189) A NAME WITH NO VALUE MEANING, READ AS A VALUE, IS REPORTED ONCE AND THEN IS AN
 * ERROR TYPE — tsgo's `checkIdentifier` answers `errorType` for a failed Value resolution,
 * so a TS2693 / TS2708 is never followed by a TS2349 on `D()`, a TS2339 on `D.x`, a TS2351
 * on `new D()` or a TS2365 on `D += 1`. This checker typed the read as the INTERFACE and
 * reported all four again, in the same file as well as across files; the value typer
 * ([Checker.getTypeOfIdentifier]), the callee typer and the member-access receiver now
 * answer the value-less symbol as `errorType` / silence ([NameResolver.symbolValuelessKind]).
 *
 * The second half is the pass itself: a `new N()` on a value-less namespace, a shorthand
 * property (`{ N }`, `({ N } = …)`) and a destructuring target nested in an assignment's
 * LHS were never reported, and a compound-assignment / `++` / `--` target was reported TWICE
 * (the const-assignment pass owns those).
 *
 * Every expectation is tsgo 7.0.2's full output for the same files (all codes compared).
 */
class TypeOnlyValueFollowOnTest {

    private fun rowsOf(source: String, directives: String = "// @strict: true\n// @target: es2022"): List<String> =
        diagnose(source, directives = directives)
            .map { "${it.fileName}(${it.line},${it.character}): TS${it.code}: ${it.message}" }
            .sorted()

    private fun ts2693(file: String, line: Int, vararg cols: Int, name: String = "D"): List<String> =
        cols.map { "$file($line,$it): TS2693: '$name' only refers to a type, but is being used as a value here." }

    private fun ts2708(file: String, line: Int, vararg cols: Int, name: String = "N"): List<String> =
        cols.map { "$file($line,$it): TS2708: Cannot use namespace '$name' as a value." }

    private val uses = """
        // @Filename: u.ts
        export {};
        declare function f(a: any): void;
        new D(); D(); D.x; typeof D; let x1 = D; f(D);
        // @Filename: v.ts
        declare function g(a: any): void;
        new D(); D(); D.x; typeof D; let y1 = D; g(D);
        """

    private val allUses = (ts2693("u.ts", 3, 5, 10, 15, 27, 39, 44) +
        ts2693("v.ts", 2, 5, 10, 15, 27, 39, 44)).sorted()

    @Test
    fun `a script interface read as a value is followed by nothing - si`() {
        val rows = rowsOf("// @Filename: s.ts\ninterface D { p: number }\n" + uses.trimIndent())
        assert(rows == allUses)
    }

    @Test
    fun `a script type alias read as a value is followed by nothing - st`() {
        val rows = rowsOf("// @Filename: s.ts\ntype D = { p: number }\n" + uses.trimIndent())
        assert(rows == allUses)
    }

    @Test
    fun `a declare global interface read as a value is followed by nothing - gi`() {
        val rows = rowsOf(
            "// @Filename: m.ts\nexport {};\ndeclare global { interface D { p: number } }\n" + uses.trimIndent(),
        )
        assert(rows == allUses)
    }

    @Test
    fun `the same-file read is followed by nothing - ss`() {
        val rows = rowsOf(
            """
            // @Filename: s.ts
            interface D { p: number }
            new D(); D.x;
            """.trimIndent(),
        )
        assert(rows == ts2693("s.ts", 2, 5, 10).sorted())
    }

    @Test
    fun `a module class elsewhere leaves the script interface value-less - sc`() {
        val rows = rowsOf(
            """
            // @Filename: s.ts
            interface D { p: number }
            // @Filename: m.ts
            export {};
            class D {}
            // @Filename: u.ts
            export {};
            declare function f(a: any): void;
            new D(); D(); D.x; typeof D; let x1 = D; f(D);
            """.trimIndent(),
        )
        assert(rows == ts2693("u.ts", 3, 5, 10, 15, 27, 39, 44).sorted())
    }

    @Test
    fun `lib type-only names read as values are followed by nothing - pk`() {
        val rows = rowsOf(
            """
            // @Filename: u.ts
            export {};
            declare function f(a: any): void;
            new PropertyKey(); PropertyKey.x; f(Partial); let r = Record; typeof EventListenerObject; f(ArrayLike);
            // @Filename: v.ts
            PropertyKey.x; let r2 = Partial;
            """.trimIndent(),
            directives = "// @strict: true\n// @target: es2022\n// @useRealLibs: true",
        )
        assert(rows == (
            ts2693("u.ts", 3, 5, 20, name = "PropertyKey") + ts2693("u.ts", 3, 37, name = "Partial") +
                ts2693("u.ts", 3, 55, name = "Record") + ts2693("u.ts", 3, 70, name = "EventListenerObject") +
                ts2693("u.ts", 3, 93, name = "ArrayLike") + ts2693("v.ts", 1, 1, name = "PropertyKey") +
                ts2693("v.ts", 1, 25, name = "Partial")
            ).sorted())
    }

    @Test
    fun `an operator on a type-only target is not reported again - as`() {
        val rows = rowsOf(
            """
            // @Filename: s.ts
            interface D { p: number }
            namespace N { interface I {} }
            // @Filename: u.ts
            export {};
            D = 1; N = 1; D += 1; [D] = [1]; ({ N } = { N: 1 }); D++;
            """.trimIndent(),
        )
        assert(rows == (ts2693("u.ts", 2, 1, 15, 24, 54) + ts2708("u.ts", 2, 8, 37)).sorted())
    }

    @Test
    fun `negative control - names with a value keep their types and their follow-on errors - vk`() {
        val rows = rowsOf(
            """
            // @Filename: s.ts
            class C { static x = 1 } C.y; C();
            enum E { A } E.B;
            interface V { p: number } declare var V: { q: number }; V.z; V();
            namespace M { export const a = 1 } M.b;
            interface D { p: number }
            function h(D: any) { D.x; D(); new D(); D += 1; }
            try {} catch (D) { D.x; D(); }
            """.trimIndent(),
        )
        assert(rows == listOf(
            "s.ts(1,28): TS2339: Property 'y' does not exist on type 'typeof C'.",
            "s.ts(1,31): TS2348: Value of type 'typeof C' is not callable. Did you mean to include 'new'?",
            "s.ts(2,16): TS2339: Property 'B' does not exist on type 'typeof E'.",
            "s.ts(3,59): TS2339: Property 'z' does not exist on type '{ q: number; }'.",
            "s.ts(3,62): TS2349: This expression is not callable.",
            "s.ts(4,38): TS2339: Property 'b' does not exist on type 'typeof M'.",
            "s.ts(7,20): TS18046: 'D' is of type 'unknown'.",
            "s.ts(7,25): TS18046: 'D' is of type 'unknown'.",
        ))
    }

    @Test
    fun `negative control - a block-scoped class shadowing a global interface is still the value - nb`() {
        // The value-less answer must not pre-empt the scope-space override (B83.5): a nested
        // `class D` is in no conventional table, so the read resolves to the global interface
        // first. tsgo's TS2345 on the constructor argument is the observable.
        val rows = rowsOf(
            """
            // @Filename: s.ts
            interface D { p: number }
            function t() {
              class D { static x = 1; constructor(a: number) {} }
              new D("x");
            }
            """.trimIndent(),
        )
        assert(rows == listOf(
            "s.ts(4,9): TS2345: Argument of type 'string' is not assignable to parameter of type 'number'.",
        ))
    }

    @Test
    fun `negative control - the embedded test lib's interfaces stay values - booleanAssignment`() {
        // The EMBEDDED lib declares `interface Boolean` without its `declare var`, so a
        // symbol it declares is never value-less ((CHK.188)'s exclusion, shared here):
        // `new Boolean()` must keep its `Boolean` type (tsgo's corpus baseline).
        val rows = diagnose(
            """
            var b = new Boolean();
            b = 1;
            """,
            directives = "",
        ).map { "${it.line},${it.character}: TS${it.code}: ${it.message}" }
        assert(rows == listOf("2,1: TS2322: Type 'number' is not assignable to type 'Boolean'."))
    }

    @Test
    fun `new on an own-file value-less namespace is TS2708 - nss`() {
        val rows = rowsOf(
            """
            // @Filename: s.ts
            namespace N { interface I {} }
            N.x; let n1 = N; new N();
            """.trimIndent(),
        )
        assert(rows == ts2708("s.ts", 2, 1, 15, 22).sorted())
    }

    @Test
    fun `new on a module's own value-less namespace is TS2708 - nsm`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            namespace N { interface I {} }
            N.x; let n1 = N; new N();
            """.trimIndent(),
        )
        assert(rows == ts2708("m.ts", 3, 1, 15, 22).sorted())
    }

    @Test
    fun `a shorthand destructuring target is a value read - ao`() {
        val rows = rowsOf(
            """
            // @Filename: s.ts
            namespace N { interface I {} }
            N = 1; ({ N } = { N: 1 });
            """.trimIndent(),
        )
        assert(rows == ts2708("s.ts", 2, 1, 11).sorted())
    }

    @Test
    fun `shorthand properties and nested targets are value reads - sh1`() {
        val rows = rowsOf(
            """
            // @Filename: s.ts
            namespace N { interface I {} }
            interface D { p: number }
            let o1 = { N }; let o2 = { D }; ({ N } = { N: 1 }); ({ D } = { D: 1 }); ({ N: N } = { N: 1 }); [N] = [1];
            new N(); new D(); N++; D++;
            """.trimIndent(),
        )
        assert(rows == (
            ts2708("s.ts", 3, 12, 36, 79, 97) + ts2693("s.ts", 3, 28, 56) +
                ts2708("s.ts", 4, 5, 19) + ts2693("s.ts", 4, 14, 24)
            ).sorted())
    }

    @Test
    fun `every other assignment target shape is a value read - as4`() {
        val rows = rowsOf(
            """
            // @Filename: s.ts
            namespace N { interface I {} }
            N.x = 1; (N) = 1; N[0] = 1; ({ N = 2 } = {}); ({ p: N } = { p: 1 }); [N] = [1];
            function g<T>() { return { T }; }
            """.trimIndent(),
        )
        assert(rows == (ts2708("s.ts", 2, 1, 11, 19, 32, 53, 71) + ts2693("s.ts", 3, 28, name = "T")).sorted())
    }

    @Test
    fun `a compound or increment target is reported once - as2`() {
        val rows = rowsOf(
            """
            // @Filename: s.ts
            namespace N { interface I {} }
            function f() { N++; N += 1; N = 1; }
            { N++; N -= 1; }
            const h = () => { --N; };
            class K { m() { N **= 2; } }
            """.trimIndent(),
        )
        assert(rows == (ts2708("s.ts", 2, 16, 21, 29) + ts2708("s.ts", 3, 3, 8) +
            ts2708("s.ts", 4, 21) + ts2708("s.ts", 5, 17)).sorted())
    }
}
