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
 * (CHK.98)(d) TS2556 for a NON-TUPLE spread argument — tsc's `hasCorrectArity` spread
 * clause over `getEffectiveCallArguments`' tuple expansion, at the three arity
 * walkers this checker has (an identifier callee, a `new` on a class, a method
 * callee).
 *
 * The rule, transcribed and measured against tsgo 7.0.2 and pristine 6.0.3 on 33
 * one-shape fixtures: a spread whose operand is a FIXED tuple (or an array literal)
 * expands into that many arguments and the call is arity-checked on the effective
 * count; any other spread SURVIVES, and then the call is legal exactly when the
 * surviving spread's index is at or past the signature's minimum and either the
 * signature has a rest parameter or the index is inside its parameter list —
 * otherwise it is TS2556 at the spread, and NEVER a count. Before this the checker
 * printed `Expected 1 arguments, but got 3.` for `f(1, 2, ...xs)`, reported
 * `f(...xs)` against an all-optional signature, and was silent for `new`, methods,
 * strings, `Set`s, rest-tail tuples and body-local arrays.
 *
 * What stays silent is decided by what this checker can classify: an `any` operand,
 * a type parameter and an inner-spread array literal make the spread's index a LOWER
 * BOUND, which licenses only the "already past the parameter list" verdict — those
 * are the `residue -` pins, each with the reference row in its KDoc.
 */
class SpreadArgumentTupleTest {

    private val msg2556 = "A spread argument must either have a tuple type or be passed to a rest parameter."

    private fun d2556(source: String): List<Diagnostic> = diagnose(source).filter { it.code == 2556 }

    @Test
    fun `a plain array spread into a fixed-arity function is TS2556 at the spread`() {
        val src = """
            declare function zf(a: number, b: number): void;
            declare const zarr: number[];
            zf(...zarr);
        """
        val d = diagnose(src)
        assert(d.count { it.code == 2556 } == 1)
        val row = d.first { it.code == 2556 }
        assert(row.message == msg2556)
        assert(row.start == src.trimIndent().indexOf("...zarr"))
        assert(row.length == "...zarr".length)
        assert(d.none { it.code == 2554 })
    }

    @Test
    fun `a readonly array spread into fixed parameters is TS2556`() {
        val d = d2556("""
            declare function zf(a: number, b: number): void;
            declare const zro: readonly number[];
            zf(...zro);
        """)
        assert(d.size == 1)
    }

    @Test
    fun `a spread at index zero into a signature with one required parameter is TS2556`() {
        val d = d2556("""
            declare function zo(a: number, b?: number): void;
            declare const zarr: number[];
            zo(...zarr);
        """)
        assert(d.size == 1)
    }

    /** The first of the four measured false positives: index 1 reaches the minimum of
     *  1 and is inside a three-parameter list, so tsc accepts it. */
    @Test
    fun `negative control - a spread after a fixed argument that reaches the minimum inside the list is legal`() {
        val d = diagnose("""
            declare function zq(a: number, b?: number, c?: number): void;
            declare const zarr: number[];
            zq(1, ...zarr);
        """)
        assert(d.none { it.code == 2556 || it.code == 2554 })
    }

    /** The second: an all-optional signature has minimum 0, and index 0 is inside the list. */
    @Test
    fun `negative control - a spread at index zero into an all-optional signature is legal`() {
        val d = diagnose("""
            declare function zo(a?: number, b?: number): void;
            declare const zarr: number[];
            zo(...zarr);
        """)
        assert(d.none { it.code == 2556 || it.code == 2554 })
    }

    /** The third: a zero-parameter function has no parameter list for index 0 to be
     *  inside of, and tsc's arity error with a spread present is TS2556, not a count. */
    @Test
    fun `a spread into a zero-parameter function is TS2556 and not a count`() {
        val d = diagnose("""
            declare function zz(): void;
            declare const zarr: number[];
            zz(...zarr);
        """)
        assert(d.count { it.code == 2556 } == 1)
        assert(d.none { it.code == 2554 })
    }

    /** The fourth: fixed arguments already past the list. Both references print TS2556
     *  alone; this checker printed `Expected 1 arguments, but got 3.` */
    @Test
    fun `fixed arguments already past the parameter list are TS2556 and never a count`() {
        val d = diagnose("""
            declare function zf(a: number): void;
            declare const zarr: number[];
            zf(1, 2, ...zarr);
        """)
        assert(d.count { it.code == 2556 } == 1)
        assert(d.none { it.code == 2554 })
    }

    @Test
    fun `negative control - a spread into a rest parameter is legal`() {
        val d = diagnose("""
            declare function zr(...a: number[]): void;
            declare const zarr: number[];
            zr(...zarr);
        """)
        assert(d.none { it.code == 2556 || it.code == 2554 })
    }

    @Test
    fun `negative control - an exact tuple spread expands and is legal`() {
        val d = diagnose("""
            declare function zf(a: number, b: number): void;
            declare const zt: [number, number];
            zf(...zt);
        """)
        assert(d.none { it.code == 2556 || it.code == 2554 })
    }

    @Test
    fun `a tuple spread one element short reports the count`() {
        val d = diagnose("""
            declare function zf(a: number, b: number): void;
            declare const zt: [number];
            zf(...zt);
        """)
        assert(d.none { it.code == 2556 })
        assert(d.count { it.code == 2554 } == 1)
        assert(d.first { it.code == 2554 }.message == "Expected 2 arguments, but got 1.")
    }

    @Test
    fun `a tuple spread one element long reports the count anchored at the spread`() {
        val src = """
            declare function zf(a: number, b: number): void;
            declare const zt: [number, number, number];
            zf(...zt);
        """
        val d = diagnose(src)
        assert(d.none { it.code == 2556 })
        assert(d.count { it.code == 2554 } == 1)
        val row = d.first { it.code == 2554 }
        assert(row.message == "Expected 2 arguments, but got 3.")
        assert(row.start == src.trimIndent().indexOf("...zt"))
    }

    /** tsc pushes one synthetic argument per tuple element, optional or not
     *  (`getEffectiveCallArguments`), so `[number, number?]` is TWO arguments. */
    @Test
    fun `an optional tuple slot counts as an argument`() {
        val d = diagnose("""
            declare function zf(a: number, b: number, c: number): void;
            declare function zg(a: number): void;
            declare const zt: [number, number?];
            zf(...zt);
            zg(...zt);
        """)
        assert(d.none { it.code == 2556 })
        val msgs = d.filter { it.code == 2554 }.map { it.message }.sorted()
        assert(msgs == listOf("Expected 1 arguments, but got 2.", "Expected 3 arguments, but got 2."))
    }

    /** A rest-tail tuple expands its fixed prefix and then SURVIVES as a spread at that
     *  index — 1 here, short of the minimum of 2. */
    @Test
    fun `a tuple with a rest tail is a surviving spread at its fixed prefix`() {
        val d = d2556("""
            declare function zf(a: number, b: number): void;
            declare const zt: [number, ...number[]];
            zf(...zt);
        """)
        assert(d.size == 1)
    }

    @Test
    fun `a string spread is TS2556`() {
        val d = d2556("""
            declare function zf(a: string, b: string): void;
            declare const zs: string;
            zf(...zs);
        """)
        assert(d.size == 1)
    }

    @Test
    fun `an array literal spread has a known length and is arity-checked on it`() {
        val d = diagnose("""
            declare function zf(a: number, b: number): void;
            zf(...[1, 2]);
            zf(...[1, 2, 3]);
        """)
        assert(d.none { it.code == 2556 })
        assert(d.count { it.code == 2554 } == 1)
        assert(d.first { it.code == 2554 }.message == "Expected 2 arguments, but got 3.")
    }

    // ── `new` ───────────────────────────────────────────────────────────────

    @Test
    fun `a new expression spreading an array into a fixed-arity constructor is TS2556`() {
        val d = diagnose("""
            class ZC { constructor(a: number, b: number) {} }
            declare const zarr: number[];
            new ZC(...zarr);
        """)
        assert(d.count { it.code == 2556 } == 1)
        assert(d.first { it.code == 2556 }.message == msg2556)
        assert(d.none { it.code == 2554 })
    }

    @Test
    fun `negative control - a new expression spreading into a rest constructor is legal`() {
        val d = diagnose("""
            class ZC { constructor(...a: number[]) {} }
            declare const zarr: number[];
            new ZC(...zarr);
        """)
        assert(d.none { it.code == 2556 || it.code == 2554 })
    }

    @Test
    fun `a new expression on a constructor-less class with a spread is TS2556`() {
        val d = diagnose("""
            class ZN {}
            declare const zarr: number[];
            new ZN(...zarr);
        """)
        assert(d.count { it.code == 2556 } == 1)
        assert(d.none { it.code == 2554 })
    }

    @Test
    fun `a new expression with an expanding tuple spread reports the count`() {
        val d = diagnose("""
            class ZC { constructor(a: number, b: number) {} }
            declare const zt: [number, number, number];
            new ZC(...zt);
        """)
        assert(d.none { it.code == 2556 })
        assert(d.count { it.code == 2554 } == 1)
        assert(d.first { it.code == 2554 }.message == "Expected 2 arguments, but got 3.")
    }

    // ── a method callee ─────────────────────────────────────────────────────

    @Test
    fun `a method call spreading an array into fixed parameters is TS2556`() {
        val d = diagnose("""
            class ZM { m(a: number, b: number) {} }
            declare const zm: ZM;
            declare const zarr: number[];
            zm.m(...zarr);
        """)
        assert(d.count { it.code == 2556 } == 1)
        assert(d.first { it.code == 2556 }.message == msg2556)
        assert(d.none { it.code == 2554 })
    }

    @Test
    fun `a method call with an expanding tuple spread reports the count`() {
        val d = diagnose("""
            class ZM { m(a: number, b: number) {} }
            declare const zm: ZM;
            declare const zt: [number, number, number];
            zm.m(...zt);
        """)
        assert(d.none { it.code == 2556 })
        assert(d.count { it.code == 2554 } == 1)
        assert(d.first { it.code == 2554 }.message == "Expected 2 arguments, but got 3.")
    }

    @Test
    fun `negative control - a method call spreading into a rest parameter is legal`() {
        val d = diagnose("""
            class ZM { m(...a: number[]) {} }
            declare const zm: ZM;
            declare const zarr: number[];
            zm.m(...zarr);
        """)
        assert(d.none { it.code == 2556 || it.code == 2554 })
    }

    // ── overloads ───────────────────────────────────────────────────────────

    @Test
    fun `an overloaded callee with no candidate of correct arity is TS2556`() {
        val d = diagnose("""
            declare function zov(a: number): void;
            declare function zov(a: number, b: number): void;
            declare const zarr: number[];
            zov(...zarr);
        """)
        assert(d.count { it.code == 2556 } == 1)
        assert(d.none { it.code == 2554 || it.code == 2575 })
    }

    @Test
    fun `negative control - an overloaded callee with one rest candidate is legal`() {
        val d = diagnose("""
            declare function zov(a: number): void;
            declare function zov(...a: number[]): void;
            declare const zarr: number[];
            zov(...zarr);
        """)
        assert(d.none { it.code == 2556 || it.code == 2554 || it.code == 2575 })
    }

    // ── the declaration route (the file-level ambient cannot see a body local) ──

    @Test
    fun `a body-local annotated array spread is TS2556`() {
        val d = d2556("""
            declare function zf(a: number, b: number): void;
            function zg() { const arr: number[] = [1, 2]; zf(...arr); }
        """)
        assert(d.size == 1)
    }

    @Test
    fun `an un-annotated body-local array literal spread is TS2556`() {
        val d = d2556("""
            declare function zf(a: number, b: number): void;
            function zg() { const arr = [1, 2]; zf(...arr); }
        """)
        assert(d.size == 1)
    }

    /**
     * THE HAZARD PIN. The arity walkers run at the spine hook under the FILE-LEVEL
     * ambient, where `getTypeOfExpression` answers a body-local by whatever same-named
     * file-level binding exists — measured: the resolver route read this file-level
     * `number[]` for the body-local tuple and reported a false TS2556. The declaration
     * route reads the local's own annotation.
     */
    @Test
    fun `negative control - a body-local tuple shadowing a file-level array is legal`() {
        val d = diagnose("""
            declare function zf(a: number, b: number): void;
            declare const zarr: number[];
            function zg() { const zarr: [number, number] = [1, 2]; zf(...zarr); }
        """)
        assert(d.none { it.code == 2556 || it.code == 2554 })
    }

    @Test
    fun `a body-local array shadowing a file-level tuple is TS2556`() {
        val d = d2556("""
            declare function zf(a: number, b: number): void;
            declare const zt: [number, number];
            function zg() { const zt: number[] = [1, 2]; zf(...zt); }
        """)
        assert(d.size == 1)
    }

    @Test
    fun `a rest parameter of the enclosing function spread into fixed parameters is TS2556`() {
        val d = d2556("""
            declare function zf(a: number, b: number): void;
            function zg(...xs: number[]) { zf(...xs); }
        """)
        assert(d.size == 1)
    }

    @Test
    fun `two spreads where the first expands and the second survives past the list is TS2556 at the second`() {
        val src = """
            declare function zf(a: number, b: number, c: number): void;
            declare const zt: [number, number];
            declare const zarr: number[];
            zf(...zt, ...zarr);
        """
        val d = diagnose(src)
        assert(d.count { it.code == 2556 } == 1)
        assert(d.first { it.code == 2556 }.start == src.trimIndent().indexOf("...zarr"))
    }

    /** The corpus-unique noImplicitAnyLoopCrash walker owns its TS2556 (plus the
     *  not-iterable row); the general rule must not report that call a second time. */
    @Test
    fun `a call the not-iterable walker already reported is reported once`() {
        val d = diagnose("""
            let x;
            x = ~x;
            const zf = () => {};
            zf(...x);
        """)
        assert(d.count { it.code == 2556 } == 1)
    }

    // ── residues, each with the reference row ───────────────────────────────

    /** Both references: `t.ts(3,4): error TS2556` — tsc reports a spread of `any`. This
     *  checker cannot tell a declared `any` from a resolution it failed, so the index is
     *  a lower bound and the call stays silent. */
    @Test
    fun `residue - a spread of an any operand is not reported`() {
        val d = d2556("""
            declare function zf(a: number, b: number): void;
            declare const za: any;
            zf(...za);
        """)
        assert(d.isEmpty())
    }

    /** Both references: `t.ts(3,4): error TS2556`. A VARIABLE callee is not arity-checked
     *  by this checker at all ((CHK.97)'s recorded gap); the spread rule inherits it. */
    @Test
    fun `residue - a variable callee is not arity-checked`() {
        val d = d2556("""
            declare const zv: (a: number, b: number) => void;
            declare const zarr: number[];
            zv(...zarr);
        """)
        assert(d.isEmpty())
    }

    /** Both references: `t.ts(2,42): error TS2556` — an array literal with an inner
     *  spread is a rest-tail tuple there. Here it is undecided, and index 0 is not past
     *  the list, so nothing is licensed. */
    @Test
    fun `residue - an array literal spread carrying an inner spread is not reported`() {
        val d = d2556("""
            declare function zf(a: number, b: number): void;
            declare const zarr: number[];
            zf(...[1, ...zarr]);
        """)
        assert(d.isEmpty())
    }
}
