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
 * INV.4(b) batch 5 (round 516): three passes migrated onto the check spine —
 * TS1155 const-without-initializer (from the deleted
 * `checkConstWithoutInitializer` walk), TS1182/TS7031
 * destructuring-without-initializer (from the deleted
 * `checkDestructuringWithoutInitializer` walk; `emitTs1182IfMissingInit`
 * retained), and TS1166/TS1169 computed property names + the class-expression
 * TS1206 decorator short-circuit (from the deleted
 * `checkComputedPropertyNameLiteral` walk family; `isLiteralLikeExpr` +
 * `emitComputedPropNameNonLiteral` retained).
 */
class Inv4SpineBatch5Test {

    // ── TS1155: 'const' declarations must be initialized ────────────────────

    @Test
    fun `const without initializer fires TS1155`() {
        diagnose(
            """
            function f() {
                const x;
            }
            """,
        ) should {
            have(any { it.code == 1155 })
        }
    }

    @Test
    fun `const without initializer in a for statement fires TS1155`() {
        diagnose(
            """
            for (const i; ;) {}
            """,
        ) should {
            have(any { it.code == 1155 })
        }
    }

    @Test
    fun `negative control - declare const with annotation is fine`() {
        diagnose(
            """
            declare const x: number;
            declare namespace N {
                const y: string;
            }
            """,
        ) should {
            have(none { it.code == 1155 })
        }
    }

    @Test
    fun `negative control - for-of and for-in iteration consts are fine`() {
        diagnose(
            """
            declare const arr: number[];
            declare const obj: Record<string, number>;
            for (const x of arr) {}
            for (const k in obj) {}
            """,
        ) should {
            have(none { it.code == 1155 })
        }
    }

    @Test
    fun `widening - const without initializer inside a for-of BODY fires TS1155`() {
        // The old statement walk had no ForOfStatement case at all, so bodies
        // of for-of/for-in loops were never descended — the spine visits them.
        diagnose(
            """
            declare const arr: number[];
            for (const x of arr) {
                const broken;
            }
            """,
        ) should {
            have(any { it.code == 1155 })
        }
    }

    // ── TS1182: destructuring declaration must have an initializer ──────────

    @Test
    fun `destructuring declaration without initializer fires TS1182`() {
        diagnose(
            """
            let { a, b };
            """,
        ) should {
            have(any { it.code == 1182 })
        }
    }

    @Test
    fun `uninitialized destructuring under strict also fires TS7031 per element`() {
        val diags = diagnose(
            """
            let { a, b };
            """,
        )
        val count = diags.count { it.code == 7031 }
        diags should {
            have(count == 2)
        }
    }

    @Test
    fun `negative control - initialized destructuring is fine`() {
        diagnose(
            """
            declare const o: { a: number; b: string };
            let { a, b } = o;
            """,
        ) should {
            have(none { it.code == 1182 })
        }
    }

    @Test
    fun `negative control - for-of destructuring iteration variable is fine`() {
        diagnose(
            """
            declare const pairs: [number, string][];
            for (const [n, s] of pairs) {}
            """,
        ) should {
            have(none { it.code == 1182 })
        }
    }

    // ── TS1166/TS1169: computed property names must be literal-like ─────────

    @Test
    fun `non-literal computed class property name fires TS1166`() {
        diagnose(
            """
            class C {
                ["a" + "b"]: number;
            }
            """,
        ) should {
            have(any { it.code == 1166 })
        }
    }

    @Test
    fun `non-literal computed interface property name fires TS1169`() {
        diagnose(
            """
            interface I {
                ["a" + "b"]: number;
            }
            """,
        ) should {
            have(any { it.code == 1169 })
        }
    }

    @Test
    fun `negative control - literal computed names are fine`() {
        diagnose(
            """
            class C {
                ["lit"]: number;
            }
            interface I {
                ["lit"]: number;
            }
            """,
        ) should {
            have(none { it.code == 1166 || it.code == 1169 })
        }
    }

    @Test
    fun `widening - class declaration in an arrow body is checked for TS1166`() {
        // The old walk reached function-declaration bodies but never arrow
        // bodies (no expression descent); the spine visits them.
        diagnose(
            """
            const f = () => {
                class C {
                    ["a" + "b"]: number;
                }
            };
            """,
        ) should {
            have(any { it.code == 1166 })
        }
    }

    @Test
    fun `void-wrapped class expression computed property fires TS1166`() {
        diagnose(
            """
            void class {
                ["a" + "b"]: number;
            };
            """,
        ) should {
            have(any { it.code == 1166 })
        }
    }

    @Test
    fun `negative control - class expression in an initializer stays unchecked`() {
        // Deliberate non-widening: the old walker reached class EXPRESSIONS
        // only at expression-statement position (void/paren wrapped) — the
        // position gate preserves that.
        diagnose(
            """
            const c = class {
                ["a" + "b"]: number;
            };
            """,
        ) should {
            have(none { it.code == 1166 })
        }
    }

    @Test
    fun `decorated class expression member under legacy decorators fires TS1206 not TS1166`() {
        val diags = diagnose(
            """
            declare function dec(target: any, key: any): void;
            void class {
                @dec ["a" + "b"]: number;
            };
            """,
            directives = "// @strict: true\n// @experimentalDecorators: true",
        )
        diags should {
            have(any { it.code == 1206 })
            have(none { it.code == 1166 })
        }
    }
}
