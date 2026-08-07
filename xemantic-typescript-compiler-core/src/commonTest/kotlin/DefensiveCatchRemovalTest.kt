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
 * (CATCH.1) batch 1 — the `getApparentType` / `getPropertyOfType` cluster.
 *
 * Those two helpers are thin dispatchers (a `when` over type kinds; a members
 * lookup) whose only deep call is member resolution, and every call site used
 * to be wrapped in a defensive `try { … } catch (_: Exception) { <default> }`
 * left over from the era of inline `StackOverflowError` guards. The catches are
 * gone; these pins exercise the shapes that would exercise the throwing surface
 * — apparent types with no wrapper interface available, recursive type-parameter
 * constraints, intersection and union discriminant reading, and destructuring
 * property lookups — and assert the SHARP signal: the compile completes and
 * never reports TS2589, which is what the `init` boundary guard turns a stack
 * overflow into.
 */
class DefensiveCatchRemovalTest {

    /** TS2589 is the init boundary guard's marker for an escaped stack overflow. */
    private fun List<Diagnostic>.hasNoDepthBail() = none { it.code == 2589 }

    @Test
    fun `an indirect circular type parameter constraint does not blow the apparent-type walk`() {
        // Found BY this audit: getApparentType used to recurse type-param →
        // constraint with no cycle guard, so the two-line indirect cycle below
        // overflowed the stack and reached the init boundary guard — aborting the
        // whole file's checking and reporting TS2589 at 0:0. The general TS2313
        // walker only catches the DIRECT `<T extends T>` form, so nothing else
        // stood between this shape and the overflow.
        val diagnostics = diagnose(
            """
            function f<T extends U, U extends T>(t: T, u: U): void {
                t.toString();
                u.toString();
            }
            """,
        )
        assert(diagnostics.hasNoDepthBail())
    }

    @Test
    fun `a direct circular type parameter constraint still reports TS2313`() {
        val diagnostics = diagnose("function f<T extends T>(t: T): void { t.toString(); }")
        assert(diagnostics.hasNoDepthBail())
        assert(diagnostics.any { it.code == 2313 })
    }

    @Test
    fun `a constrained type parameter still resolves its constraint's members`() {
        val diagnostics = diagnose(
            """
            interface Named { name: string }
            function f<T extends Named>(t: T): number { return t.name.length; }
            """,
        )
        assert(diagnostics.hasNoDepthBail())
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `a chained type parameter constraint resolves through to the interface`() {
        val diagnostics = diagnose(
            """
            interface Named { name: string }
            function f<T extends U, U extends Named>(t: T): number { return t.name.length; }
            """,
        )
        assert(diagnostics.hasNoDepthBail())
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `primitive receivers resolve through their wrapper interfaces under an es5 lib`() {
        // Each primitive receiver routes getApparentType through
        // getBuiltinWrapperType, whose members come from the lib.
        val diagnostics = diagnose(
            """
            function f(s: string, n: number, b: boolean) {
                return s.length + n.toFixed(2).length + (b.valueOf() ? 1 : 0);
            }
            """,
            directives = "// @strict: true\n// @lib: es5",
        )
        assert(diagnostics.hasNoDepthBail())
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `a self-referential type parameter constraint resolves its apparent type`() {
        val diagnostics = diagnose(
            """
            interface Comparable<T> { compareTo(other: T): number }
            function max<T extends Comparable<T>>(a: T, b: T): T {
                return a.compareTo(b) >= 0 ? a : b;
            }
            """,
        )
        assert(diagnostics.hasNoDepthBail())
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `discriminant reading over a union of intersections resolves every member`() {
        // The intersection-arm discriminant fold is a documented M3 residue: the
        // `switch` does NOT narrow this union today, so the case bodies still draw
        // TS2339. What this pin guards is the apparent-type walk over the
        // intersection members — it must complete, never bail on depth.
        val diagnostics = diagnose(
            """
            interface A { kind: "a"; a: number }
            interface B { kind: "b"; b: string }
            type Node = (A & { extra: true }) | (B & { extra: false });
            function f(n: Node): number {
                switch (n.kind) {
                    case "a": return n.a;
                    case "b": return n.b.length;
                }
            }
            """,
        )
        assert(diagnostics.hasNoDepthBail())
    }

    @Test
    fun `destructuring an initializer looks its properties up through the apparent type`() {
        val diagnostics = diagnose(
            """
            interface Span { start: number; length: number }
            declare function makeSpan(): Span;
            function f(): number {
                const { start, length } = makeSpan();
                return start + length;
            }
            """,
        )
        assert(diagnostics.hasNoDepthBail())
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `a recursive object type's property chain resolves without a depth bail`() {
        val diagnostics = diagnose(
            """
            interface Cell { next: Cell; value: number }
            declare const head: Cell;
            const v: number = head.next.next.next.value;
            """,
        )
        assert(diagnostics.hasNoDepthBail())
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `negative control - a genuinely missing property still reports TS2339`() {
        val diagnostics = diagnose(
            """
            interface Span { start: number }
            declare const s: Span;
            const v = s.missing;
            """,
        )
        assert(diagnostics.any { it.code == 2339 })
    }
}
