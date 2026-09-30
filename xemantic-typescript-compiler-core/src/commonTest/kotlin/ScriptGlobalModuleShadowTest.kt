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
 * (CHK.186) A MODULE FILE'S TOP-LEVEL DECLARATION IS MODULE-SCOPED EVEN WHEN A **SCRIPT**
 * FILE DECLARES THE SAME NAME — (CHK.49)'s defect, fixed there for LIB names, for SCRIPT
 * names.
 *
 * `init:mergeSharedKeepNames` put every script-file local name into the merge's keep set,
 * so a module's `class D` merged into the script's global `D`. `mergeSingleSymbol` ADOPTS,
 * so the fusion was PROGRAM-WIDE: the module's `D` answered the script's members (a WRONG
 * type — `z.q` typed `string` instead of TS2339), and the script file, and every third
 * module, answered the module's members too.
 *
 * The fix is (CHK.49)'s pair — the two sets are ONE observable — plus the per-meaning half:
 *
 *  * the merge no longer keeps a module local because a script shares its name;
 *  * `ensurePerFileVisibility` no longer counts script names as non-module-visible, so such
 *    a name is module-only and routes through the per-file scope (the declaring module sees
 *    its own symbol, every other file the global one);
 *  * a SCRIPT file's per-file scope has an EMPTY own layer (its locals ARE the globals, so
 *    the merged instance must win over its own un-merged binder symbol);
 *  * a module's TYPE-only declaration leaves the global VALUE reachable (the value second
 *    chance and the TS2693 table read `globals`, not only the lib), and a module's
 *    VALUE-only declaration leaves the global TYPE reachable (type-name resolution and the
 *    TS2749 gate);
 *  * two raw `globals[name]` consults in the `new` emitters defer to the module's own
 *    declaration ([NameResolver.fileShadowsGlobal]).
 *
 * Every expectation is tsgo 7.0.2's output for the same files, row for row; the fixtures
 * are the (P18.242) matrix (`build/bench/p18242-agent/cells`). The cross-file shape — a
 * script-file `interface` used as a VALUE from another MODULE file, TS2693 in tsgo — was a
 * separate gap, closed by (CHK.188) and pinned in [GlobalTypeOnlyValueUseTest].
 */
class ScriptGlobalModuleShadowTest {

    private fun rowsOf(source: String): List<String> =
        diagnose(source, directives = "// @strict: true\n// @target: es2022")
            .map { "${it.fileName}(${it.line},${it.character}): TS${it.code}: ${it.message}" }
            .sorted()

    @Test
    fun `a module class and a script interface - ci`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            class D { q = 1 }
            declare const z: D; const a1: string = z.q; z.p;
            const n = new D(); n.p;
            // @Filename: s.ts
            interface D { p: number }
            declare const y: D; const b1: string = y.p; y.q;
            new D();
            // @Filename: u.ts
            export {};
            declare const w: D; const c1: string = w.p; w.q;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "m.ts(3,47): TS2339: Property 'p' does not exist on type 'D'.",
            "m.ts(4,22): TS2339: Property 'p' does not exist on type 'D'.",
            "s.ts(2,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "s.ts(2,47): TS2339: Property 'q' does not exist on type 'D'.",
            "s.ts(3,5): TS2693: 'D' only refers to a type, but is being used as a value here.",
            "u.ts(2,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,47): TS2339: Property 'q' does not exist on type 'D'."
        ))
    }

    @Test
    fun `a module interface and a script class - ic`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            interface D { q: number }
            declare const z: D; const a1: string = z.q; z.p;
            const n = new D(); const a2: string = n.p; n.q;
            const x: D = new D();
            // @Filename: s.ts
            class D { p = 0 }
            declare const y: D; const b1: string = y.p; y.q;
            // @Filename: u.ts
            export {};
            declare const w: D; const c1: string = w.p; w.q;
            const n2 = new D(); n2.q;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "m.ts(3,47): TS2339: Property 'p' does not exist on type 'D'.",
            "m.ts(4,26): TS2322: Type 'number' is not assignable to type 'string'.",
            "m.ts(4,46): TS2339: Property 'q' does not exist on type 'D'.",
            "m.ts(5,7): TS2741: Property 'q' is missing in type 'D' but required in type 'D'.",
            "s.ts(2,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "s.ts(2,47): TS2339: Property 'q' does not exist on type 'D'.",
            "u.ts(2,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,47): TS2339: Property 'q' does not exist on type 'D'.",
            "u.ts(3,24): TS2339: Property 'q' does not exist on type 'D'."
        ))
    }

    @Test
    fun `a module interface and a script interface - ii`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            interface D { q: number }
            declare const z: D; const a1: string = z.q; z.p;
            // @Filename: s.ts
            interface D { p: number }
            declare const y: D; const b1: string = y.p; y.q;
            // @Filename: u.ts
            export {};
            declare const w: D; const c1: string = w.p; w.q;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "m.ts(3,47): TS2339: Property 'p' does not exist on type 'D'.",
            "s.ts(2,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "s.ts(2,47): TS2339: Property 'q' does not exist on type 'D'.",
            "u.ts(2,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,47): TS2339: Property 'q' does not exist on type 'D'."
        ))
    }

    @Test
    fun `a module class and a script class - cc`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            class D { q = 1 }
            const z = new D(); const a1: string = z.q; z.p;
            // @Filename: s.ts
            class D { p = 0 }
            const y = new D(); const b1: string = y.p; y.q;
            // @Filename: u.ts
            export {};
            const w = new D(); const c1: string = w.p; w.q;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,26): TS2322: Type 'number' is not assignable to type 'string'.",
            "m.ts(3,46): TS2339: Property 'p' does not exist on type 'D'.",
            "s.ts(2,26): TS2322: Type 'number' is not assignable to type 'string'.",
            "s.ts(2,46): TS2339: Property 'q' does not exist on type 'D'.",
            "u.ts(2,26): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,46): TS2339: Property 'q' does not exist on type 'D'."
        ))
    }

    @Test
    fun `a module enum and a script enum - ee`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            enum E { A }
            const a1: string = E.A; E.B;
            // @Filename: s.ts
            enum E { B }
            const b1: string = E.B; E.A;
            // @Filename: u.ts
            export {};
            const c1: string = E.B; E.A;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,27): TS2339: Property 'B' does not exist on type 'typeof E'.",
            "m.ts(3,7): TS2322: Type 'E' is not assignable to type 'string'.",
            "s.ts(2,27): TS2339: Property 'A' does not exist on type 'typeof E'.",
            "s.ts(2,7): TS2322: Type 'E' is not assignable to type 'string'.",
            "u.ts(2,27): TS2339: Property 'A' does not exist on type 'typeof E'.",
            "u.ts(2,7): TS2322: Type 'E' is not assignable to type 'string'."
        ))
    }

    @Test
    fun `a module namespace and a script namespace - nn`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            namespace N { export const a = 1 }
            const a1: string = N.a; N.b;
            // @Filename: s.ts
            namespace N { export const b = 2 }
            const b1: string = N.b; N.a;
            // @Filename: u.ts
            export {};
            const c1: string = N.b; N.a;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,27): TS2339: Property 'b' does not exist on type 'typeof N'.",
            "m.ts(3,7): TS2322: Type 'number' is not assignable to type 'string'.",
            "s.ts(2,27): TS2339: Property 'a' does not exist on type 'typeof N'.",
            "s.ts(2,7): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,27): TS2339: Property 'a' does not exist on type 'typeof N'.",
            "u.ts(2,7): TS2322: Type 'number' is not assignable to type 'string'."
        ))
    }

    @Test
    fun `a module type alias and a script type alias - tt`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            type T = { q: number }
            declare const z: T; const a1: string = z.q; z.p;
            // @Filename: s.ts
            type T = { p: number }
            declare const y: T; const b1: string = y.p; y.q;
            // @Filename: u.ts
            export {};
            declare const w: T; const c1: string = w.p; w.q;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "m.ts(3,47): TS2339: Property 'p' does not exist on type 'T'.",
            "s.ts(2,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "s.ts(2,47): TS2339: Property 'q' does not exist on type 'T'.",
            "u.ts(2,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,47): TS2339: Property 'q' does not exist on type 'T'."
        ))
    }

    @Test
    fun `a module function and a script function - ff`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            function f(a: string): string { return a }
            const a1: number = f("x"); f(1);
            // @Filename: s.ts
            function f(a: number): number { return a }
            const b1: string = f(1); f("x");
            // @Filename: u.ts
            export {};
            const c1: string = f(1); f("x");
            """,
        )
        assert(rows == listOf(
            "m.ts(3,30): TS2345: Argument of type 'number' is not assignable to parameter of type 'string'.",
            "m.ts(3,7): TS2322: Type 'string' is not assignable to type 'number'.",
            "s.ts(2,28): TS2345: Argument of type 'string' is not assignable to parameter of type 'number'.",
            "s.ts(2,7): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,28): TS2345: Argument of type 'string' is not assignable to parameter of type 'number'.",
            "u.ts(2,7): TS2322: Type 'number' is not assignable to type 'string'."
        ))
    }

    @Test
    fun `a module const and a script const - kk`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            const c = "s";
            const a1: number = c;
            // @Filename: s.ts
            const c = 1;
            const b1: string = c;
            // @Filename: u.ts
            export {};
            const c1: string = c;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,7): TS2322: Type 'string' is not assignable to type 'number'.",
            "s.ts(2,7): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,7): TS2322: Type 'number' is not assignable to type 'string'."
        ))
    }

    @Test
    fun `a module let and a script let - ll`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            let v = "s";
            const a1: number = v;
            // @Filename: s.ts
            let v = 1;
            const b1: string = v;
            // @Filename: u.ts
            export {};
            const c1: string = v;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,7): TS2322: Type 'string' is not assignable to type 'number'.",
            "s.ts(2,7): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,7): TS2322: Type 'number' is not assignable to type 'string'."
        ))
    }

    @Test
    fun `a module class and a script const - ck`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            class D { q = 1 }
            const a1: string = new D().q; const a2: D = new D();
            // @Filename: s.ts
            const D = 1;
            const b1: string = D;
            // @Filename: u.ts
            export {};
            const c1: string = D;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,7): TS2322: Type 'number' is not assignable to type 'string'.",
            "s.ts(2,7): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,7): TS2322: Type 'number' is not assignable to type 'string'."
        ))
    }

    @Test
    fun `a module interface and a script const - ik`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            interface D { q: number }
            declare const z: D; const a1: string = z.q;
            const a2: string = D;
            // @Filename: s.ts
            const D = 1;
            const b1: string = D;
            // @Filename: u.ts
            export {};
            const c1: string = D;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "m.ts(4,7): TS2322: Type 'number' is not assignable to type 'string'.",
            "s.ts(2,7): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,7): TS2322: Type 'number' is not assignable to type 'string'."
        ))
    }

    @Test
    fun `a module type alias and a script class - tc`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            type D = { q: number }
            declare const z: D; const a1: string = z.q; z.p;
            const n = new D(); const a2: string = n.p;
            // @Filename: s.ts
            class D { p = 0 }
            // @Filename: u.ts
            export {};
            declare const w: D; w.q;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "m.ts(3,47): TS2339: Property 'p' does not exist on type 'D'.",
            "m.ts(4,26): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,23): TS2339: Property 'q' does not exist on type 'D'."
        ))
    }

    @Test
    fun `a module enum and a script interface - ei`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            enum D { A }
            const a1: string = D.A; declare const z: D; const a2: string = z;
            // @Filename: s.ts
            interface D { p: number }
            declare const y: D; const b1: string = y.p;
            // @Filename: u.ts
            export {};
            declare const w: D; w.q;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,51): TS2322: Type 'D' is not assignable to type 'string'.",
            "m.ts(3,7): TS2322: Type 'D' is not assignable to type 'string'.",
            "s.ts(2,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,23): TS2339: Property 'q' does not exist on type 'D'."
        ))
    }

    @Test
    fun `a module function and a script interface - fi`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            function D(): string { return "" }
            const a1: number = D();
            declare const z: D; const a3: string = z.p;
            // @Filename: s.ts
            interface D { p: number }
            declare const y: D; const b1: string = y.p;
            // @Filename: u.ts
            export {};
            declare const w: D; const c1: string = w.p;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,7): TS2322: Type 'string' is not assignable to type 'number'.",
            "m.ts(4,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "s.ts(2,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,27): TS2322: Type 'number' is not assignable to type 'string'."
        ))
    }

    @Test
    fun `a module generic class and a script class - gc`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            class Box<T> { v!: T }
            const b = new Box<number>(); const a1: string = b.v; b.w;
            // @Filename: s.ts
            class Box { w = 0 }
            const y = new Box(); const b1: string = y.w; y.v;
            // @Filename: u.ts
            export {};
            const u2 = new Box(); u2.v;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,36): TS2322: Type 'number' is not assignable to type 'string'.",
            "m.ts(3,56): TS2339: Property 'w' does not exist on type 'Box<number>'.",
            "s.ts(2,28): TS2322: Type 'number' is not assignable to type 'string'.",
            "s.ts(2,48): TS2339: Property 'v' does not exist on type 'Box'.",
            "u.ts(2,26): TS2339: Property 'v' does not exist on type 'Box'."
        ))
    }

    @Test
    fun `a module import alias and a script class - ia`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            import { D } from "./u";
            declare const z: D; const a1: string = z.q; z.p; new D();
            // @Filename: s.ts
            class D { p = 0 }
            declare const y: D; y.q;
            // @Filename: u.ts

            export class D { q = 1 }
            """,
        )
        assert(rows == listOf(
            "m.ts(3,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "m.ts(3,47): TS2339: Property 'p' does not exist on type 'D'.",
            "s.ts(2,23): TS2339: Property 'q' does not exist on type 'D'."
        ))
    }

    @Test
    fun `two merging script interfaces and a module interface - sm`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            interface D { q: number }
            declare const z: D; z.p; z.r;
            // @Filename: s.ts
            interface D { p: number }
            declare const y: D; const b1: string = y.r; y.q;
            // @Filename: s2.ts
            interface D { r: number }
            declare const y2: D; const b2: string = y2.p; y2.q;
            // @Filename: u.ts
            export {};
            declare const w: D; const c1: string = w.p; const c2: string = w.r; w.q;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,23): TS2339: Property 'p' does not exist on type 'D'.",
            "m.ts(3,28): TS2339: Property 'r' does not exist on type 'D'.",
            "s.ts(2,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "s.ts(2,47): TS2339: Property 'q' does not exist on type 'D'.",
            "s2.ts(2,28): TS2322: Type 'number' is not assignable to type 'string'.",
            "s2.ts(2,50): TS2339: Property 'q' does not exist on type 'D'.",
            "u.ts(2,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,51): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,71): TS2339: Property 'q' does not exist on type 'D'."
        ))
    }

    @Test
    fun `two script lets and a module let - sd`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            let v = "s";
            // @Filename: s.ts
            let v = 1;
            // @Filename: s2.ts
            let v = 2;
            // @Filename: u.ts
            export {};
            const c1: string = v;
            """,
        )
        assert(rows == listOf(
            "s.ts(1,5): TS2451: Cannot redeclare block-scoped variable 'v'.",
            "s2.ts(1,5): TS2451: Cannot redeclare block-scoped variable 'v'.",
            "u.ts(2,7): TS2322: Type 'number' is not assignable to type 'string'."
        ))
    }

    @Test
    fun `a module declare global interface and a script interface - dg`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            declare global { interface D { q: number } }
            declare const z: D; const a1: string = z.p;
            // @Filename: s.ts
            interface D { p: number }
            declare const y: D; const b1: string = y.q;
            // @Filename: u.ts
            export {};
            declare const w: D; const c1: string = w.q;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "s.ts(2,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,27): TS2322: Type 'number' is not assignable to type 'string'."
        ))
    }

    @Test
    fun `a module namespace and a script interface - ni`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            namespace D { export const a = 1 }
            const a1: string = D.a;
            declare const z: D; z.p;
            // @Filename: s.ts
            interface D { p: number }
            declare const y: D; const b1: string = y.p;
            // @Filename: u.ts
            export {};
            declare const w: D; const c1: string = w.p;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,7): TS2322: Type 'number' is not assignable to type 'string'.",
            "s.ts(2,27): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,27): TS2322: Type 'number' is not assignable to type 'string'."
        ))
    }

    @Test
    fun `a module class and a script const holding an instance - cn`() {
        val rows = rowsOf(
            """
            // @Filename: m.ts
            export {};
            class D { q = 1 }
            const a1: string = new D().q; const a2: D = new D(); new D;
            // @Filename: s.ts
            class Other { p = 0 }
            const D = new Other();
            const b1: string = D.p;
            // @Filename: u.ts
            export {};
            const c1: string = D.p;
            """,
        )
        assert(rows == listOf(
            "m.ts(3,7): TS2322: Type 'number' is not assignable to type 'string'.",
            "s.ts(3,7): TS2322: Type 'number' is not assignable to type 'string'.",
            "u.ts(2,7): TS2322: Type 'number' is not assignable to type 'string'."
        ))
    }
}
