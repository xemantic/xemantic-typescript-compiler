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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (LIB.5) G3 — TS2302 *Static members cannot reference class type parameters* must not fire
 * for a type parameter a NESTED function-like container declares itself, because such a
 * parameter SHADOWS the enclosing class's.
 *
 * tsgo has no walker for this rule at all: TS2302 is a name-RESOLUTION outcome in
 * `internal/binder/nameresolver.go:170-186` — the resolver walks containers OUTWARD, and a
 * generic arrow / function expression / function type is itself a container whose own type
 * parameters are found FIRST, so the class arm is never reached.  Ours carries a flat name
 * set down a hand-written walk, so the exclusion has to be spelled at each boundary
 * (`Checker.shadowedTypeParamNames`); before this it existed only for a `MethodDeclaration`
 * CLASS MEMBER, and every other boundary reported falsely.
 *
 * Measured on `build/bench/lib-rxjs-7.8.2`: 4 ours-only rows, tsgo silent at all four
 * (`Observable.ts:46:84` and `Subject.ts:36:70/93/115`), all of them
 * `static create: (...args: any[]) => any = <T>(...) => {...}` inside a `class X<T>`.
 */
class StaticMemberNestedGenericShadowTest {

    /**
     * THE DECISIVE PIN — it fails in BOTH directions on one line, which is why it asserts the
     * whole row list rather than a silence.  `S` at column 31 is the ARROW's own parameter and
     * must be silent; `T` at column 37 is the CLASS's and must report.  A fix that
     * over-suppresses loses the second row and a fix that does nothing keeps two.
     */
    @Test
    fun `a shadowing arrow type parameter is silent while the class one still reports`() {
        val diagnostics = diagnose(
            """
            export class B4<T, S> {
              static create: any = <S>(x: S, y: T) => x;
            }
            """
        )
        val ts2302 = diagnostics.filter { it.code == 2302 }
        // Exactly the one row tsgo prints, and it is the CLASS's `T` at column 37 —
        // asserting the count alone would be satisfiable by the wrong single row.
        assert(ts2302.size == 1)
        assert(ts2302[0].character == 37)
        // ... and the arrow's own `S` at column 31 is the row that must be gone.
        assert(ts2302.none { it.character == 31 })
    }

    /** The rxjs shape itself — a generic arrow in a static property initializer. */
    @Test
    fun `a generic arrow in a static property initializer may shadow the class type parameter`() {
        diagnose(
            """
            export class A1<T> {
              static create: (...args: any[]) => any = <T>(x: (s: T) => void) => { return x; };
            }
            """
        ) should { have(none { it.code == 2302 }) }
    }

    /** The RENAME control — with no collision there is nothing to shadow and nothing to report. */
    @Test
    fun `a generic arrow whose type parameter does not collide is silent`() {
        diagnose(
            """
            export class A2<T> {
              static create: (...args: any[]) => any = <U>(x: (s: U) => void) => { return x; };
            }
            """
        ) should { have(none { it.code == 2302 }) }
    }

    /** The same shape spelled as a function expression rather than an arrow. */
    @Test
    fun `a generic function expression in a static property initializer may shadow`() {
        diagnose(
            """
            export class C1<T> {
              static create: any = function <T>(x: (s: T) => void) { return x; };
            }
            """
        ) should { have(none { it.code == 2302 }) }
    }

    /** Nesting depth is not the axis — a generic arrow inside a static METHOD body shadows too. */
    @Test
    fun `a generic arrow nested in a static method body may shadow`() {
        diagnose(
            """
            export class D1<T> {
              static m(): void { const f = <T>(x: T) => x; }
            }
            """
        ) should { have(none { it.code == 2302 }) }
    }

    /** A generic function TYPE is the same boundary one walker over. */
    @Test
    fun `a generic function type annotation on a static member may shadow`() {
        diagnose(
            """
            export class E1<T> {
              static f: <T>(x: T) => void = null!;
            }
            """
        ) should { have(none { it.code == 2302 }) }
    }

    /** A generic method SIGNATURE inside a type literal is the same boundary again. */
    @Test
    fun `a generic method signature in a static member type literal may shadow`() {
        diagnose(
            """
            export class E2<T> {
              static g: { m<T>(a: T): void } = null!;
            }
            """
        ) should { have(none { it.code == 2302 }) }
    }

    /** And a generic construct signature. */
    @Test
    fun `a generic constructor type on a static member may shadow`() {
        diagnose(
            """
            export class E3<T> {
              static h: new <T>(x: T) => void = null!;
            }
            """
        ) should { have(none { it.code == 2302 }) }
    }

    /**
     * THE TRUE POSITIVE THAT MUST SURVIVE — a NON-generic arrow has nothing to shadow with, so
     * the class's `T` is still an error.  This is `genericClassWithStaticsUsingTypeArguments`'s
     * own `static a = (n: T) => {}` shape, i.e. the corpus's control for over-suppression.
     */
    @Test
    fun `a non generic arrow in a static initializer still reports the class type parameter`() {
        diagnose(
            """
            export class K1<T> {
              static a = (n: T) => { };
            }
            """
        ) should { have(any { it.code == 2302 }) }
    }

    /** The same true positive spelled as a non-generic function expression. */
    @Test
    fun `a non generic function expression in a static initializer still reports`() {
        diagnose(
            """
            export class K2<T> {
              static e = function (x: T) { return null; };
            }
            """
        ) should { have(any { it.code == 2302 }) }
    }

    /** A NON-generic function type still reports — `typeParametersInStaticAccessors`' shape. */
    @Test
    fun `a non generic function type on a static accessor still reports`() {
        diagnose(
            """
            export class K3<T> {
              static get Foo(): () => T { return null!; }
            }
            """
        ) should { have(any { it.code == 2302 }) }
    }

    /**
     * A generic arrow that shadows ONE of the class's parameters must keep reporting the OTHER
     * — the subtraction is per NAME, never a whole-subtree suppression.
     */
    @Test
    fun `an arrow shadowing one parameter still reports the other`() {
        diagnose(
            """
            export class G1<T> {
              static create: any = <U>(x: (s: T) => void) => x;
            }
            """
        ) should { have(any { it.code == 2302 }) }
    }

    /**
     * The INSTANCE control — TS2302 is static-only, so a generic arrow in an instance property
     * is silent on a broken binary too.  It is here to say the axis is the SHADOWING and not
     * the member kind.
     */
    @Test
    fun `a generic arrow in an instance property is silent`() {
        diagnose(
            """
            export class F1<T> {
              p: any = <T>(x: (s: T) => void) => x;
            }
            """
        ) should { have(none { it.code == 2302 }) }
    }
}
