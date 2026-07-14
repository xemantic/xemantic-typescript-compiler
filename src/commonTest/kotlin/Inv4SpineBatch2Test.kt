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
 * INV.4(b) batch 2 (round 515): three more passes migrated onto the check
 * spine — TS2370 (rest-param-must-be-array, from the deleted
 * `checkNonArrayRestParameters` pair of walks), TS2488/TS2504 (iterator
 * method requiring a parameter, from the deleted
 * `checkIteratorMethodExtraParameters`), and TS1320 (`yield*` of a
 * non-promise thenable in an async generator, from the deleted
 * `checkAsyncYieldStarThenable`).
 *
 * TS2370 dispatches on the parameter's PARENT kind (value-position parents
 * get the keyword rule, type-position parents the optional-rest rule) and
 * widens faithfully to positions the old hand-walks missed (a
 * position-independent per-signature tsc grammar rule). The iterator/yield
 * checks collect on the spine and resolve BUFFERED positions at file end,
 * preserving the old prepasses' use-before-decl semantics.
 */
class Inv4SpineBatch2Test {

    // ── TS2370 value-position: keyword-typed rest params ────────────────────

    @Test
    fun `function rest param with keyword type fires TS2370`() {
        diagnose(
            """
            function f(...args: number) {}
            """,
        ) should {
            have(any { it.code == 2370 })
        }
    }

    @Test
    fun `class method and constructor rest params fire TS2370`() {
        val d = diagnose(
            """
            class C {
                constructor(...a: string) {}
                m(...b: boolean): void {}
            }
            """,
        )
        val count = d.count { it.code == 2370 }
        d should {
            have(count == 2)
        }
    }

    @Test
    fun `interface method rest param with keyword type fires TS2370`() {
        diagnose(
            """
            interface I {
                m(...args: symbol): void;
            }
            """,
        ) should {
            have(any { it.code == 2370 })
        }
    }

    @Test
    fun `object-literal method rest param fires TS2370`() {
        diagnose(
            """
            const o = {
                m(...args: number) { return 0; }
            };
            """,
        ) should {
            have(any { it.code == 2370 })
        }
    }

    @Test
    fun `negative control - array-typed rest param is legal`() {
        diagnose(
            """
            function f(...args: number[]) {}
            class C { m(...b: string[]): void {} }
            """,
        ) should {
            have(none { it.code == 2370 })
        }
    }

    @Test
    fun `negative control - keyword rest in a function TYPE stays unchecked`() {
        // B71.2: the type-position walk deliberately allows the
        // `...args: any/never` match-all-functions supertype pattern.
        diagnose(
            """
            type T = (...args: never) => void;
            """,
        ) should {
            have(none { it.code == 2370 })
        }
    }

    @Test
    fun `widening - rest param of an arrow in a parameter default value fires TS2370`() {
        // The old value-position hand-walk never visited parameter
        // INITIALIZERS; the spine does — faithful widening (tsc checks every
        // signature declaration).
        diagnose(
            """
            function g(cb = (...a: number) => 0) {}
            """,
        ) should {
            have(any { it.code == 2370 })
        }
    }

    // ── TS2370 type-position: optional rest params ──────────────────────────

    @Test
    fun `optional rest param in a function type fires TS2370`() {
        diagnose(
            """
            type T = (...args?: any[]) => void;
            """,
        ) should {
            have(any { it.code == 2370 })
        }
    }

    @Test
    fun `optional rest param in a type-literal method fires TS2370`() {
        diagnose(
            """
            type T = { m(...args?: any[]): void };
            """,
        ) should {
            have(any { it.code == 2370 })
        }
    }

    @Test
    fun `negative control - well-formed rest in a function type is legal`() {
        diagnose(
            """
            type T = (...args: any[]) => void;
            """,
        ) should {
            have(none { it.code == 2370 })
        }
    }

    @Test
    fun `widening - optional rest in a function type inside a cast fires TS2370`() {
        // The old type-context walk was rooted at annotation positions only;
        // a FunctionType inside an expression cast was invisible.
        diagnose(
            """
            const x = ((a: number) => a) as unknown as (...args?: any[]) => number;
            """,
        ) should {
            have(any { it.code == 2370 })
        }
    }

    // ── TS2488/TS2504: iterator method requiring a parameter ────────────────

    private val badIter = """
        const iter = {
            *[Symbol.iterator](_: number) {
                yield 0;
            }
        };
    """.trimIndent()

    @Test
    fun `iterator method requiring a parameter fires TS2488 on for-of`() {
        diagnose(
            badIter + "\nfunction f() { for (const q of iter); }",
            directives = "// @target: esnext",
        ) should {
            have(any { it.code == 2488 })
        }
    }

    @Test
    fun `spread of a bad iterable fires TS2488`() {
        diagnose(
            badIter + "\nconst arr = [...iter];",
            directives = "// @target: esnext",
        ) should {
            have(any { it.code == 2488 })
        }
    }

    @Test
    fun `use-before-declaration still matches (deferred file-end resolution)`() {
        // The old pass collected bad vars in a FULL prepass before its
        // position walk — a for-of textually BEFORE the declaration still
        // fires. The spine buffers positions and resolves at file end.
        diagnose(
            "function f() { for (const q of iter); }\n" + badIter,
            directives = "// @target: esnext",
        ) should {
            have(any { it.code == 2488 })
        }
    }

    @Test
    fun `async iterator method requiring a parameter fires TS2504 on yield-star`() {
        diagnose(
            """
            const iter = {
                async *[Symbol.asyncIterator](_: number) {
                    yield 0;
                }
            };
            async function* f() {
                yield* iter;
            }
            """,
            directives = "// @target: esnext",
        ) should {
            have(any { it.code == 2504 })
        }
    }

    @Test
    fun `negative control - parameterless iterator method is a valid iterable`() {
        diagnose(
            """
            const iter = {
                *[Symbol.iterator]() {
                    yield 0;
                }
            };
            function f() { for (const q of iter); }
            """,
            directives = "// @target: esnext",
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `widening - for-of inside a class property initializer arrow fires TS2488`() {
        // The old position walk skipped class PROPERTY initializers; the
        // spine visits them — faithful widening (tsc checks every iteration
        // position).
        diagnose(
            badIter + "\nclass C { p = () => { for (const q of iter); }; }",
            directives = "// @target: esnext",
        ) should {
            have(any { it.code == 2488 })
        }
    }

    // ── TS1320: yield-star of a non-promise thenable in an async generator ──

    private val thenableObj = """
        var obj = {
            [Symbol.asyncIterator]() {
                return {
                    next() {
                        return { then() { } };
                    }
                };
            }
        };
    """.trimIndent()

    @Test
    fun `yield-star of a thenable-yielding object in an async generator fires TS1320`() {
        diagnose(
            thenableObj + "\nasync function* g() {\n    yield* obj;\n}",
            directives = "// @target: esnext",
        ) should {
            have(any { it.code == 1320 })
        }
    }

    @Test
    fun `widening - yield-star nested in an if block fires TS1320`() {
        // The old walker only checked statement-level `yield*` directly in the
        // async-generator body; the spine's nearest-function-ancestor gate
        // reaches nested statements — faithful widening.
        diagnose(
            thenableObj + "\nasync function* g(c: boolean) {\n    if (c) {\n        yield* obj;\n    }\n}",
            directives = "// @target: esnext",
        ) should {
            have(any { it.code == 1320 })
        }
    }

    @Test
    fun `negative control - sync generator yield-star draws no TS1320`() {
        diagnose(
            thenableObj + "\nfunction* g() {\n    yield* obj;\n}",
            directives = "// @target: esnext",
        ) should {
            have(none { it.code == 1320 })
        }
    }

    @Test
    fun `negative control - then as a data property is not a callable thenable`() {
        diagnose(
            """
            var obj = {
                [Symbol.asyncIterator]() {
                    return {
                        next() {
                            return { then: 5 };
                        }
                    };
                }
            };
            async function* g() {
                yield* obj;
            }
            """,
            directives = "// @target: esnext",
        ) should {
            have(none { it.code == 1320 })
        }
    }
}
