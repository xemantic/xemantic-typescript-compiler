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
 * A BLOCK-scoped type declaration used as a VALUE — TS2693 and TS2708.
 *
 * `tavListLevel` surveyed a statement `Block`'s `values` ONLY, so an
 * `interface` / `type` alias / value-less `namespace` declared inside a function
 * body, a nested `{ }` or an `if` block reached no level's `typeOnly`/`nsOnly`
 * set and this family could not emit for it ANYWHERE. Both reference compilers
 * report every shape below; measured against tsgo 7.0.2 and pristine
 * `typescript@6.0.3`, which agree row for row on all of them (the block-scoped
 * `namespace` shapes additionally carry a TS1235 in both references and in ours,
 * which is a separate grammar rule and is why these pins assert per CODE rather
 * than over an exact list).
 *
 * The same declarations at FILE level and inside a `ModuleBlock` were already
 * byte-correct — `TavCandidateGateTest` pins those — which is what makes this a
 * LEVEL-CONSTRUCTION gap rather than a missing emitter, and why the fix is two
 * halves that are individually inert: the level must survey the block, AND
 * `spineTavCandidateNode` must admit a `Block`'s names to the (WARM.21) per-file
 * superset gate, which otherwise refuses the name before any level is consulted.
 *
 * ## The negative controls
 *
 * `tavListLevel` classifies with `tavModuleLevel`'s guards verbatim, and the four
 * controls below are the four names those guards keep out of the set. Ablated —
 * all four guards dropped at once — exactly TWO of them redden, and that split
 * is recorded rather than claimed:
 *
 *  - the sub-module value survey (RED): a block-scoped `namespace` that DOES
 *    declare a value, and so is legally usable as one;
 *  - `n !in KNOWN_GLOBALS` (RED): a block-scoped `interface Date`, which shadows
 *    the lib TYPE while the lib VALUE stays reachable;
 *  - `n !in values` (GREEN, i.e. UNDISCRIMINATED) — a block-scoped `interface X`
 *    merged with a block-scoped `function X`;
 *  - `!tavHasValue(parent, n)` (GREEN, i.e. UNDISCRIMINATED) — a block-scoped
 *    `interface X` whose name also names an ENCLOSING value.
 *
 * The last two are a round-927 PAIR, not a lost guard, and the redundancy is a
 * property of a CALLER rather than of this level: `spineTavIdentifierCore`
 * (`Checker.kt:28923`) does `if (tavHasValue(level, name)) return` BEFORE either
 * the `typeOnly` or the `nsOnly` probe, so a name the level chain already carries
 * as a value is answered above them whether or not the level classified it. Both
 * guards stay anyway — they cost nothing, the redundancy belongs to a caller this
 * function does not control, and they are what keeps `tavListLevel`'s
 * classification IDENTICAL to `tavModuleLevel`'s, which is the property that
 * stops the two drifting. Both controls stay too, because what they pin is the
 * OBSERVABLE (such a name remains usable as a value) and not the guard.
 *
 * `KNOWN_GLOBALS` is the one `tavHasValue` demonstrably CANNOT stand in for: a
 * lib global's VALUE declaration is in no level's `values` set, so without that
 * filter a block-scoped `interface Event` — which shadows the global TYPE while
 * `declare var Event` stays reachable — makes the legal `new Event()` a false
 * TS2693. Measured: both references report only TS2554 there (an arity row,
 * i.e. they agree the construction itself is legal), and we report neither
 * TS2693 nor TS2708.
 *
 * Every assertion is over diagnostic CODES and full MESSAGE text — never over an
 * AST node, whose power-assert rendering is its whole subtree.
 */
class BlockScopedTypeUsedAsValueTest {

    @Test
    fun `an interface declared in a function body used as a value emits TS2693`() {
        diagnose(
            """
            export function zzzF(): void {
                interface ZzzIface {
                    a: number
                }
                const taken = ZzzIface
            }
            """.trimIndent(),
        ) should {
            have(
                any {
                    it.code == 2693 &&
                        it.message ==
                        "'ZzzIface' only refers to a type, " +
                        "but is being used as a value here."
                },
            )
        }
    }

    @Test
    fun `an interface declared in a function body used with new emits TS2693`() {
        diagnose(
            """
            export function zzzF(): void {
                interface ZzzIface {
                    a: number
                }
                const taken = new ZzzIface()
            }
            """.trimIndent(),
        ) should {
            have(
                any {
                    it.code == 2693 &&
                        it.message ==
                        "'ZzzIface' only refers to a type, " +
                        "but is being used as a value here."
                },
            )
        }
    }

    @Test
    fun `a type alias declared in a function body used as a value emits TS2693`() {
        diagnose(
            """
            export function zzzF(): void {
                type ZzzAlias = number
                const taken = ZzzAlias
            }
            """.trimIndent(),
        ) should {
            have(
                any {
                    it.code == 2693 &&
                        it.message ==
                        "'ZzzAlias' only refers to a type, " +
                        "but is being used as a value here."
                },
            )
        }
    }

    @Test
    fun `a value-less namespace declared in a function body used as a value emits TS2708`() {
        diagnose(
            """
            export function zzzF(): void {
                namespace ZzzNsOnly {
                    export interface Inner {
                        a: number
                    }
                }
                const taken = ZzzNsOnly
            }
            """.trimIndent(),
        ) should {
            have(
                any {
                    it.code == 2708 &&
                        it.message == "Cannot use namespace 'ZzzNsOnly' as a value."
                },
            )
        }
    }

    @Test
    fun `an interface declared in a NESTED block used as a value emits TS2693`() {
        diagnose(
            """
            export function zzzF(): void {
                {
                    interface ZzzNested {
                        a: number
                    }
                    const taken = ZzzNested
                }
            }
            """.trimIndent(),
        ) should {
            have(
                any {
                    it.code == 2693 &&
                        it.message ==
                        "'ZzzNested' only refers to a type, " +
                        "but is being used as a value here."
                },
            )
        }
    }

    @Test
    fun `a type alias declared in an if block used as a value emits TS2693`() {
        diagnose(
            """
            export function zzzF(flag: boolean): void {
                if (flag) {
                    type ZzzInIf = string
                    const taken = ZzzInIf
                }
            }
            """.trimIndent(),
        ) should {
            have(
                any {
                    it.code == 2693 &&
                        it.message ==
                        "'ZzzInIf' only refers to a type, " +
                        "but is being used as a value here."
                },
            )
        }
    }

    @Test
    fun `negative control - an interface MERGED with a function in the same block is a value`() {
        diagnose(
            """
            export function zzzF(): void {
                interface ZzzMerged {
                    a: number
                }
                function ZzzMerged(): void {}
                const taken = ZzzMerged
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2693 })
            have(none { it.code == 2708 })
        }
    }

    @Test
    fun `negative control - a block-scoped interface whose name is an ENCLOSING value is silent`() {
        diagnose(
            """
            export const ZzzShadowed = 1
            export function zzzF(): void {
                interface ZzzShadowed {
                    a: number
                }
                const taken = ZzzShadowed
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2693 })
            have(none { it.code == 2708 })
        }
    }

    @Test
    fun `negative control - a block-scoped namespace that declares a VALUE is usable as one`() {
        diagnose(
            """
            export function zzzF(): void {
                namespace ZzzNsWithValue {
                    export const v = 1
                }
                const taken = ZzzNsWithValue
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2708 })
            have(none { it.code == 2693 })
        }
    }

    @Test
    fun `negative control - a block-scoped interface named after a lib global is CONSTRUCTIBLE`() {
        diagnose(
            """
            export function zzzF(): void {
                interface Event {
                    zzzA: number
                }
                const taken = new Event("zzz")
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2693 })
            have(none { it.code == 2708 })
        }
    }

    @Test
    fun `negative control - a block-scoped interface merged with a HOISTED var is a value`() {
        diagnose(
            """
            export function zzzF(): void {
                interface ZzzVarMerge {
                    a: number
                }
                var ZzzVarMerge = 1
                const taken = ZzzVarMerge
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2693 })
            have(none { it.code == 2708 })
        }
    }

    @Test
    fun `negative control - a block-scoped interface named after a lib global is silent`() {
        diagnose(
            """
            export function zzzF(): void {
                interface Date {
                    zzzA: number
                }
                const taken = Date
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2693 })
            have(none { it.code == 2708 })
        }
    }
}
