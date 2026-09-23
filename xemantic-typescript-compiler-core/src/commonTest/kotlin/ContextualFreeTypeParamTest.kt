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
 * (CHK.35c) round P18.176 — a contextual parameter type that mentions a FREE,
 * IN-SCOPE type parameter must be APPLIED, not collapsed to `any`.
 *
 * `applyPulledContextualParamTypes` used to skip a contextual parameter whenever
 * `typeContainsUnresolvedTypeParam` said its type mentioned a type parameter at
 * all. Round 569's intent was narrower — an un-inferred CALLEE type parameter,
 * where OUR inference failed and tsc would have bound it — but the predicate
 * could not tell that from a type parameter an enclosing function, method or
 * class DECLARES, so every such callback parameter stayed `any`. tsgo does not
 * instantiate at all here (`assignContextualParameterTypes`, checker.go:10325,
 * copies `context.typeParameters` onto the inner signature and writes each
 * parameter type verbatim), so a free `R` is an ordinary type there.
 *
 * Every pin asserts a VALUE — the elaboration NAMES the type the parameter was
 * given — because a silence cannot tell "typed `R`" from "typed `any`", which is
 * exactly the distinction this family is about. Every expectation below is
 * tsgo 7.0.2's own row, measured.
 *
 * Note on the probe SHAPE: these use an ARGUMENT (`takeN(t)`), never
 * `const p: number = t`. The variable-declaration reader silently ACCEPTS an
 * UNCONSTRAINED type parameter as a source — measured on a plainly annotated
 * `function q<R>(r: R) { const p: number = r }`, which tsgo reports and we do
 * not — so a var-decl probe is blind for half of this matrix and would read as
 * an unfixed defect.
 */
class ContextualFreeTypeParamTest {

    private val prelude = """
        declare function takeN(n: number): void;
        type Fn<A> = (a: A) => void;
    """.trimIndent() + "\n"

    private fun rows(source: String): List<Diagnostic> =
        diagnose(prelude + source).filter { it.code == 2345 }

    private val namesR = "Argument of type 'R' is not assignable to parameter of type 'number'."
    private val namesString = "Argument of type 'string' is not assignable to parameter of type 'number'."

    @Test
    fun `a free type parameter of the enclosing function types a contextual parameter`() {
        val d = rows(
            """
            function d<R>(run: (cb: (t: R) => void) => void) {
              run(function (t) { takeN(t); });
            }
            """
        )
        val n = d.size
        assert(n == 1)
        val msg = d[0].message
        assert(msg == namesR)
    }

    @Test
    fun `a free type parameter reaches a contextual parameter through a generic alias`() {
        val d = rows(
            """
            function d<R>(holder: { cb?: Fn<R> | null }) {
              holder.cb = function (t) { takeN(t); };
            }
            """
        )
        val n = d.size
        assert(n == 1)
        val msg = d[0].message
        assert(msg == namesR)
    }

    @Test
    fun `an arrow gets the enclosing function's free type parameter`() {
        val d = rows(
            """
            function d<R>(run: (cb: (t: R) => void) => void) {
              run((t) => { takeN(t); });
            }
            """
        )
        val n = d.size
        assert(n == 1)
        val msg = d[0].message
        assert(msg == namesR)
    }

    @Test
    fun `a generic class's own type parameter types a contextual parameter in a method body`() {
        val d = rows(
            """
            class C<R> {
              go(run: (cb: (t: R) => void) => void) {
                run(function (t) { takeN(t); });
              }
            }
            """
        )
        val n = d.size
        assert(n == 1)
        val msg = d[0].message
        assert(msg == namesR)
    }

    @Test
    fun `a constrained free type parameter is named by the elaboration`() {
        val d = rows(
            """
            function d<R extends { x: number }>(run: (cb: (t: R) => void) => void) {
              run(function (t) { takeN(t); });
            }
            """
        )
        val n = d.size
        assert(n == 1)
        val msg = d[0].message
        assert(msg == namesR)
    }

    @Test
    fun `an assignment target's generic alias types the assigned arrow's parameter`() {
        val d = rows(
            """
            function d<R>(holder: { cb: Fn<R> }) {
              holder.cb = (value) => { takeN(value); };
            }
            """
        )
        val n = d.size
        assert(n == 1)
        val msg = d[0].message
        assert(msg == namesR)
    }

    @Test
    fun `a contextual rest parameter carrying a free type parameter is typed`() {
        val d = rows(
            """
            function d<R>(run: (cb: (...xs: R[]) => void) => void) {
              run(function (...xs) { takeN(xs); });
            }
            """
        )
        val n = d.size
        assert(n == 1)
        val msg = d[0].message
        assert(msg == "Argument of type 'R[]' is not assignable to parameter of type 'number'.")
    }

    @Test
    fun `an object literal method's parameter takes the enclosing free type parameter`() {
        val d = rows(
            """
            function d<R>(cfg: (o: { on: (t: R) => void }) => void) {
              cfg({ on(t) { takeN(t); } });
            }
            """
        )
        val n = d.size
        assert(n == 1)
        val msg = d[0].message
        assert(msg == namesR)
    }

    /**
     * Round 569's ACTUAL subject, and the half this round must keep: an
     * un-inferred CALLEE type parameter. `firstDefined`'s `T` binds to `string`
     * from its array argument under tsgo; ours does not infer it, so the
     * contextual parameter type is the bare callee `T` — registering it would
     * report `Argument of type 'T' is not assignable to parameter of type
     * 'string'` on code both references accept. Reduced from
     * `src/compiler/moduleNameResolver.ts:669`; deleting the guard adds 47 such
     * rows to the compiler profile alone.
     *
     * The fixture MIXES the two halves in one body so it cannot pass by the
     * whole mechanism being dead: the enclosing `R` must be applied (one row,
     * tsgo's own) while the callee `T` must be refused (no row).
     */
    @Test
    fun `a callee type parameter our inference did not bind is still refused`() {
        val d = diagnose(
            """
            declare function takeN(n: number): void;
            declare function takeS(s: string): void;
            declare function firstDefined<T, U>(
              array: readonly T[] | undefined,
              cb: (element: T, index: number) => U | undefined
            ): U | undefined;
            declare const roots: string[];
            function outer<R>(run: (cb: (t: R) => void) => void) {
              run(function (t) { takeN(t); });
              firstDefined(roots, (root) => { takeS(root); return undefined; });
            }
            """
        )
        val n = d.size
        assert(n == 1)
        val msg = d[0].message
        assert(msg == namesR)
    }

    /**
     * Why the in-scope test is an IDENTITY test and never a name match: here the
     * callee's own type parameter is ALSO spelled `T`, and the enclosing function
     * declares a `T`. A name match admits it and reports on code both references
     * accept — measured, that alternative costs +7 rows on the compiler profile
     * and +7 on harness.
     */
    @Test
    fun `an out of scope callee type parameter spelled like the enclosing one is refused`() {
        val d = diagnose(
            """
            declare function takeS(s: string): void;
            declare function firstDefined<T, U>(
              array: readonly T[] | undefined,
              cb: (element: T, index: number) => U | undefined
            ): U | undefined;
            declare const roots: string[];
            function outerT<T>(seed: T) {
              firstDefined(roots, (root) => { takeS(root); return undefined; });
            }
            """
        )
        val n = d.size
        assert(n == 0)
    }

    @Test
    fun `a concrete contextual parameter inside a generic function still names string`() {
        val d = rows(
            """
            function d<R>(run: (cb: (t: string) => void) => void) {
              run(function (t) { takeN(t); });
            }
            """
        )
        val n = d.size
        assert(n == 1)
        val msg = d[0].message
        assert(msg == namesString)
    }

    @Test
    fun `a concrete slot in a generic class names string and not the type parameter`() {
        val d = rows(
            """
            class C<R> {
              go(run: (cb: (t: string) => void) => void) {
                run(function (t) { takeN(t); });
              }
            }
            """
        )
        val n = d.size
        assert(n == 1)
        val msg = d[0].message
        assert(msg == namesString)
    }
}
