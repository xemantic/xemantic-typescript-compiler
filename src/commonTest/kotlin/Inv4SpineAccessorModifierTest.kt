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
 * INV.4(a) round 514: the single-pass check spine's pilot migration —
 * TS18045 (`accessor` properties require target ES2015+), moved from the
 * deleted `checkAccessorModifierTarget` full-tree walker into a
 * [PropertyDeclaration] case of the spine's per-node dispatch.
 *
 * Pins three things: (1) the old walker's behavior is preserved (top-level /
 * nested-class / namespace shapes fire; ambient shapes and ES2015+ targets do
 * not); (2) the spine's FULL coverage widens faithfully where the old
 * hand-walk under-visited (class expressions, arrow bodies — tsc emits
 * TS18045 there too, a position-independent grammar rule); (3) the spine walk
 * is iterative — a 10k-term binary chain neither crashes nor masks the
 * diagnostic (the sharp-signal rule: assert TS18045 present AND TS2589
 * absent, since a boundary-guard-masked overflow still "passes").
 */
class Inv4SpineAccessorModifierTest {

    private val es5 = "// @target: es5"

    @Test
    fun `accessor property in a top-level class fires TS18045 under es5`() {
        diagnose(
            """
            class C {
                accessor x = 1;
            }
            """,
            directives = es5,
        ) should {
            have(any { it.code == 18045 })
        }
    }

    @Test
    fun `negative control - es2015 target draws no TS18045`() {
        diagnose(
            """
            class C {
                accessor x = 1;
            }
            """,
            directives = "// @target: es2015",
        ) should {
            have(none { it.code == 18045 })
        }
    }

    @Test
    fun `negative control - declare class is ambient and draws no TS18045`() {
        diagnose(
            """
            declare class C {
                accessor x: number;
            }
            """,
            directives = es5,
        ) should {
            have(none { it.code == 18045 })
        }
    }

    @Test
    fun `negative control - class nested in declare namespace draws no TS18045`() {
        diagnose(
            """
            declare namespace N {
                class C {
                    accessor x: number;
                }
            }
            """,
            directives = es5,
        ) should {
            have(none { it.code == 18045 })
        }
    }

    @Test
    fun `class inside a non-ambient namespace fires TS18045`() {
        diagnose(
            """
            namespace N {
                class C {
                    accessor x = 1;
                }
            }
            """,
            directives = es5,
        ) should {
            have(any { it.code == 18045 })
        }
    }

    @Test
    fun `class nested in a method body fires TS18045`() {
        diagnose(
            """
            class Outer {
                m(): void {
                    class Inner {
                        accessor y = 2;
                    }
                }
            }
            """,
            directives = es5,
        ) should {
            have(any { it.code == 18045 })
        }
    }

    @Test
    fun `spine coverage widening - class expression fires TS18045`() {
        // The old hand-walk never visited class EXPRESSIONS; tsc emits TS18045
        // there too (position-independent grammar rule) — the spine's full
        // coverage is the deliberate fix.
        diagnose(
            """
            const C = class {
                accessor x = 1;
            };
            """,
            directives = es5,
        ) should {
            have(any { it.code == 18045 })
        }
    }

    @Test
    fun `spine coverage widening - class declared in an arrow body fires TS18045`() {
        diagnose(
            """
            const f = () => {
                class K {
                    accessor y = 2;
                }
            };
            """,
            directives = es5,
        ) should {
            have(any { it.code == 18045 })
        }
    }

    @Test
    fun `iterative walk survives a 10k-term binary chain without masking the diagnostic`() {
        val chain = (1..10_000).joinToString(" + ") { "1" }
        val source = "class C {\n    accessor x = 1;\n}\nconst big = $chain;\n"
        diagnose(source, directives = es5) should {
            have(any { it.code == 18045 })
            have(none { it.code == 2589 })
        }
    }
}
