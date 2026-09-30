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
 * (CHK.188) A GLOBAL THAT HAS NO VALUE MEANING, READ AS A VALUE FROM A FILE THAT DOES NOT
 * BIND IT ITSELF, IS TS2693 (a type-only name) OR TS2708 (a non-instantiated namespace) —
 * tsgo's `onFailedToResolveSymbol`, which asks the namespace question first.
 *
 * The spine's type-used-as-value pass built its table from the reading file's OWN
 * declarations only, so a script-file `interface D`, a `declare global` one, a lib
 * type-only name (`PropertyKey`, `Partial`, `EventListenerObject`) or a script's
 * type-only `namespace N` were never consulted from any other file. The consult is
 * `NameResolver.globalValuelessNames` (the candidate set) plus a per-identifier check that
 * NOTHING lexical binds the name (`spineScopeLookup`, which includes the file's own locals
 * and imports) and that the global this file sees has no value meaning.
 *
 * Every expectation is tsgo 7.0.2's output for the same files; the fixtures are the
 * (P18.243) matrix (`build/bench/p18243-agent/cells`). Only the family's codes are
 * compared: after a TS2693 tsgo types the read as `error` and stays silent, where this
 * checker still types it as the interface and adds a TS2349 / TS2339 / TS2351 — a
 * pre-existing follow-on residue that the same-FILE case has as well.
 */
class GlobalTypeOnlyValueUseTest {

    private val family = setOf(2693, 2708, 2585)

    private fun rowsOf(source: String, directives: String = "// @strict: true\n// @target: es2022"): List<String> =
        diagnose(source, directives = directives)
            .filter { it.code in family }
            .map { "${it.fileName}(${it.line},${it.character}): TS${it.code}: ${it.message}" }
            .sorted()

    private fun ts2693(file: String, line: Int, vararg cols: Int, name: String = "D"): List<String> =
        cols.map { "$file($line,$it): TS2693: '$name' only refers to a type, but is being used as a value here." }

    private fun ts2708(file: String, line: Int, vararg cols: Int, name: String): List<String> =
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
    fun `a script interface read as a value from a module and from another script - si`() {
        val rows = rowsOf("// @Filename: s.ts\ninterface D { p: number }\n" + uses.trimIndent())
        assert(rows == allUses)
    }

    @Test
    fun `a script type alias read as a value from other files - st`() {
        val rows = rowsOf("// @Filename: s.ts\ntype D = { p: number }\n" + uses.trimIndent())
        assert(rows == allUses)
    }

    @Test
    fun `a declare global interface read as a value from other files - gi`() {
        val rows = rowsOf(
            "// @Filename: m.ts\nexport {};\ndeclare global { interface D { p: number } }\n" + uses.trimIndent(),
        )
        assert(rows == allUses)
    }

    @Test
    fun `a module class elsewhere does not hide the script interface - sc`() {
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
    fun `lib type-only names read as values - pk`() {
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
    fun `an ES2015 constructor name that is type-only under lib es5 is TS2585 - l6`() {
        val rows = rowsOf(
            """
            // @Filename: s.ts
            interface Map<K, V> { k: K; v: V }
            interface WeakSet<T> { t: T }
            interface Foo {}
            // @Filename: u.ts
            export {};
            new Map(); let w = WeakSet; Foo;
            """.trimIndent(),
            directives = "// @strict: true\n// @target: es2022\n// @lib: es5\n// @useRealLibs: true",
        )
        val hint = " Do you need to change your target library? Try changing the 'lib' compiler option to es2015 or later."
        assert(rows == listOf(
            "u.ts(2,20): TS2585: 'WeakSet' only refers to a type, but is being used as a value here.$hint",
            "u.ts(2,29): TS2693: 'Foo' only refers to a type, but is being used as a value here.",
            "u.ts(2,5): TS2585: 'Map' only refers to a type, but is being used as a value here.$hint",
        ))
    }

    @Test
    fun `a script non-instantiated namespace read as a value is TS2708 in every position - ns`() {
        val rows = rowsOf(
            """
            // @Filename: s.ts
            namespace N { interface I {} }
            // @Filename: u.ts
            export {};
            N.x; let n1 = N; new N();
            // @Filename: v.ts
            N.x; let n2 = N;
            """.trimIndent(),
        )
        assert(rows == (ts2708("u.ts", 2, 1, 15, 22, name = "N") + ts2708("v.ts", 1, 1, 15, name = "N")).sorted())
    }

    @Test
    fun `an interface merged with a non-instantiated namespace is reported as the namespace - in`() {
        val rows = rowsOf(
            """
            // @Filename: s.ts
            interface D { p: number }
            namespace D { interface I {} }
            // @Filename: u.ts
            export {};
            new D(); D.x; let a = D;
            """.trimIndent(),
        )
        assert(rows == ts2708("u.ts", 2, 5, 10, 23, name = "D").sorted())
    }

    @Test
    fun `an ambient namespace holding a value is not TS2708 - am`() {
        val rows = rowsOf(
            """
            // @Filename: s.ts
            declare namespace A { function f(): void }
            declare namespace B { interface I {} }
            declare namespace C { namespace D { const x: number } }
            // @Filename: u.ts
            export {};
            A.f; let a = A; B.x; let b = B; C.D;
            """.trimIndent(),
        )
        assert(rows == ts2708("u.ts", 2, 17, 30, name = "B").sorted())
    }

    @Test
    fun `assignment targets are value reads too - as`() {
        // tsgo also reports the shorthand `({ N } = …)` target (col 37); this checker's reach
        // classifier never descends into a shorthand property, the same-FILE case included.
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
        assert(rows == (ts2693("u.ts", 2, 1, 15, 24, 54) + ts2708("u.ts", 2, 8, name = "N")).sorted())
    }

    @Test
    fun `negative control - a script interface merged with a declare var has a value - sv`() {
        val rows = rowsOf(
            "// @Filename: s.ts\ninterface D { p: number }\ndeclare var D: any;\n" + uses.trimIndent(),
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - a module or a parameter binding the name shadows the global - sh`() {
        val rows = rowsOf(
            """
            // @Filename: s.ts
            interface D { p: number }
            // @Filename: u.ts
            export {};
            const D: any = 1;
            new D(); D.x; f(D); declare function f(a: any): void;
            // @Filename: v.ts
            function h(D: any) { new D(); D.x; }
            // @Filename: w.ts
            export {};
            class D { static x = 1 }
            new D(); D.x;
            // @Filename: i.ts
            export {};
            import { D } from "./w";
            new D(); D.x;
            """.trimIndent(),
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - local bindings of every kind shadow the global - lx`() {
        val rows = rowsOf(
            """
            // @Filename: s.ts
            interface D { p: number }
            // @Filename: u.ts
            export {};
            declare const o: any;
            try {} catch (D) { D; }
            for (const D of [1]) { D; }
            const f1 = (D: any) => D;
            const c1 = class D { m() { return D; } };
            const f2 = function D() { return D; };
            { const D = 1; D; }
            namespace Q { const D = 1; D; }
            function g<D>() { return D; }
            o.D; const ob = { D: 1 }; class K { D = 1; m() { this.D; } }
            declare function h(D: number): void;
            enum E { D } E.D;
            if (o) { function D() {} D(); }
            """.trimIndent(),
        )
        // The one row is the TYPE PARAMETER `D` read as a value — tsgo reports it too.
        assert(rows == ts2693("u.ts", 10, 26))
    }

    @Test
    fun `negative control - a module-local interface is not a global - mi`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            interface D { p: number }
            // @Filename: u.ts
            export {};
            new D(); D.x;
            """.trimIndent(),
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - the embedded test lib's interfaces carry no value declaration but are values`() {
        // The embedded lib declares `interface String` & co. without their `declare var`.
        val rows = rowsOf(
            """
            // @Filename: u.ts
            export {};
            String(1); new Array<number>(); Number.MAX_VALUE; let o = Object; new Date();
            """.trimIndent(),
        )
        assert(rows.isEmpty())
    }
}
