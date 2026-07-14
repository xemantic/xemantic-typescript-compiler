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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * INV.4(b) batch 3 (round 515): two more passes migrated onto the check spine —
 * TS2495 (for-of over a non-iterable under an es5-only `@lib`, from the deleted
 * `checkForOfNonIterable` walk family) and TS7033 (bodyless get accessor
 * without a return type annotation, from the deleted
 * `checkAbstractAccessorReturnTypes`).
 *
 * TS2495 keeps its per-run option gate (lib explicitly excludes es2015+) and
 * per-file dts skip; TS7033 keeps its noImplicitAny/strict gate, its
 * `.js`/`.jsx` skip, and its ClassDeclaration-parent gate (class EXPRESSION
 * members stay deliberately unchecked — a bodyless accessor there is a
 * parse-recovery shape).
 */
class Inv4SpineBatch3Test {

    // ── TS2495: for-of over a non-iterable under es5-only lib ───────────────

    @Test
    fun `for-of over a number fires TS2495 under es5 lib`() {
        diagnose(
            """
            declare const n: number;
            for (const x of n);
            """,
            directives = "// @lib: es5",
        ) should {
            have(any { it.code == 2495 })
        }
    }

    @Test
    fun `negative control - for-of over an array is legal under es5 lib`() {
        diagnose(
            """
            declare const a: string[];
            for (const x of a);
            """,
            directives = "// @lib: es5",
        ) should {
            have(none { it.code == 2495 })
        }
    }

    @Test
    fun `negative control - es2015 lib provides iterables`() {
        diagnose(
            """
            declare const n: number;
            for (const x of n);
            """,
            directives = "// @lib: es2015",
        ) should {
            have(none { it.code == 2495 })
        }
    }

    @Test
    fun `widening - for-of inside a parameter default arrow fires TS2495`() {
        // The old statement/expression walk never visited parameter
        // INITIALIZERS; the spine does — faithful widening.
        diagnose(
            """
            declare const n: number;
            function f(cb = () => { for (const x of n); }) {}
            """,
            directives = "// @lib: es5",
        ) should {
            have(any { it.code == 2495 })
        }
    }

    // ── TS7033: bodyless get accessor without return type annotation ────────

    @Test
    fun `abstract get accessor without return annotation fires TS7033`() {
        diagnose(
            """
            abstract class C {
                abstract get p();
            }
            """,
        ) should {
            have(any { it.code == 7033 })
        }
    }

    @Test
    fun `negative control - annotated abstract get accessor is fine`() {
        diagnose(
            """
            abstract class C {
                abstract get p(): number;
            }
            """,
        ) should {
            have(none { it.code == 7033 })
        }
    }

    @Test
    fun `negative control - TS7033 is gated on noImplicitAny or strict`() {
        diagnose(
            """
            abstract class C {
                abstract get p();
            }
            """,
            directives = "// @strict: false",
        ) should {
            have(none { it.code == 7033 })
        }
    }

    @Test
    fun `widening - class declaration inside an arrow body is checked`() {
        // The old statement walk had no expression descent, so a class
        // DECLARATION nested in an arrow/function-expression body was
        // invisible; the spine visits it — faithful widening (the
        // ClassDeclaration-parent gate is position-independent).
        diagnose(
            """
            const f = () => {
                abstract class C {
                    abstract get p();
                }
            };
            """,
        ) should {
            have(any { it.code == 7033 })
        }
    }

    @Test
    fun `negative control - class expression accessors stay unchecked`() {
        // Deliberate non-widening: the old walker never looked at class
        // EXPRESSION members (a bodyless accessor there is a parse-recovery
        // shape) — the ClassDeclaration-parent gate preserves that.
        diagnose(
            """
            const c = class {
                get p();
            };
            """,
        ) should {
            have(none { it.code == 7033 })
        }
    }
}
