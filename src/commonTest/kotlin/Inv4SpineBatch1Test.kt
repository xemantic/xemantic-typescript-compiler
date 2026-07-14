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
 * INV.4(b) batch 1 (round 514): two more passes migrated onto the check spine —
 * TS2669/TS2670 (`global {}` misplacement, from the deleted
 * `checkInvalidGlobalAugmentations`) and TS7051/TS7006 (reserved-word interface
 * method params, from the deleted `checkReservedWordInterfaceParams`).
 *
 * Both old walkers descended ONLY through module bodies, so their reachability
 * is reproduced as a module-chain parent-walk gate ("every ancestor is a
 * ModuleBlock/ModuleDeclaration") — deliberately NOT widened to
 * function/class-nested shapes; these pins assert both the preserved firing
 * shapes and the preserved non-firing gates.
 */
class Inv4SpineBatch1Test {

    // ── TS2669/TS2670: global-augmentation misplacement ────────────────────

    @Test
    fun `global block nested in a regular namespace fires TS2669 and TS2670`() {
        diagnose(
            """
            namespace N {
                global {
                    interface G { x: number; }
                }
            }
            export {};
            """,
        ) should {
            have(any { it.code == 2669 })
            have(any { it.code == 2670 })
        }
    }

    @Test
    fun `declare global at top level of a NON-module file fires TS2669`() {
        diagnose(
            """
            declare global {
                interface G { x: number; }
            }
            const x = 1;
            """,
        ) should {
            have(any { it.code == 2669 })
            have(none { it.code == 2670 })
        }
    }

    @Test
    fun `negative control - declare global at top level of a MODULE file is legal`() {
        diagnose(
            """
            export {};
            declare global {
                interface G { x: number; }
            }
            """,
        ) should {
            have(none { it.code == 2669 })
            have(none { it.code == 2670 })
        }
    }

    @Test
    fun `negative control - global block directly inside declare module is legal`() {
        diagnose(
            """
            declare module "m" {
                global {
                    interface G { x: number; }
                }
            }
            const keep = 1;
            """,
        ) should {
            have(none { it.code == 2669 })
            have(none { it.code == 2670 })
        }
    }

    @Test
    fun `global block in a DEEPLY nested namespace still fires TS2669`() {
        diagnose(
            """
            namespace A {
                export namespace B {
                    global {
                        interface G { x: number; }
                    }
                }
            }
            export {};
            """,
        ) should {
            have(any { it.code == 2669 })
        }
    }

    // ── TS7051/TS7006: reserved-word interface method params ───────────────

    @Test
    fun `reserved-word interface param draws TS7006 in default mode`() {
        diagnose(
            """
            interface I {
                m(package): void;
            }
            """,
            directives = "",
        ) should {
            have(any { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - a type-keyword param is NOT a strict reserved word`() {
        // `string` is a type keyword, not a STRICT_MODE_RESERVED_WORD — the
        // handler's first gate excludes it, so neither TS7051 nor TS7006 comes
        // from this handler in default mode (same as the old walker).
        diagnose(
            """
            interface I {
                m(string): void;
            }
            """,
            directives = "",
        ) should {
            have(none { it.code == 7051 })
        }
    }

    @Test
    fun `namespace-nested interface still checked`() {
        diagnose(
            """
            namespace N {
                export interface I {
                    m(package): void;
                }
            }
            """,
            directives = "",
        ) should {
            have(any { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - strict mode hands these params to the implicit-any pass`() {
        // Under strict, checkImplicitAnyParameters owns the emission — the spine
        // handler must stay silent (its old double-emission firewall).
        val d = diagnose(
            """
            interface I {
                m(package): void;
            }
            """,
        )
        val count = d.count { it.code == 7006 && it.message == "Parameter 'package' implicitly has an 'any' type." }
        d should {
            have(count <= 1)
        }
    }

    @Test
    fun `negative control - function-nested interface stays unchecked (old-walk reachability)`() {
        diagnose(
            """
            function f(): void {
                interface I {
                    m(package): void;
                }
            }
            """,
            directives = "",
        ) should {
            have(none { it.code == 7006 })
            have(none { it.code == 7051 })
        }
    }
}
