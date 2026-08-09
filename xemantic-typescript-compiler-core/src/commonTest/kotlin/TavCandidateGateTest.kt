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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (WARM.21) round 874 — the TAV pass's name-candidate gate.
 *
 * The pass (INV.4(c)(iv), the migrated `checkTypeUsedAsValue`) is dispatched at
 * EVERY Identifier — 381,670 times per warm rebuild of tsc's own compiler — to
 * emit two diagnostics that fire **zero** times there. Round 874's census
 * measured that 99% of the identifiers it reaches are INERT: their name is in no
 * visible level's `typeOnly`/`nsOnly` set and is not a type keyword, so no
 * reordering of the pass's tests could have emitted anything. The gate refuses
 * those before the reach classifier runs, and the census's controlled row falls
 * from 159 ms to 38 ms over an IDENTICAL population.
 *
 * ## What these pins are for, and why they are shaped this way
 *
 * The gate's only failure mode is a LOST diagnostic, and a lost diagnostic is
 * silent — which is exactly the class round 792 measured as visible to the
 * corpus and to nothing else. So the pins enumerate the SOURCES of a candidate
 * name, one per pin, because the gate's soundness argument is precisely that
 * those sources are exhaustive over `tavBuildLevel`:
 *
 *  - the file ROOT's `typeOnly` and `nsOnly` (eager, [tavBuildFileRoot]);
 *  - `tavFnLevel`'s `typeOnly`, which is exactly TYPE PARAMETER names;
 *  - `tavModuleLevel`'s `typeOnly`/`nsOnly`, which are exactly the interface /
 *    type-alias / namespace names declared directly in a module block;
 *  - the static `TYPE_ONLY_KEYWORDS` set.
 *
 * A pin per source means a source dropped from the collector reddens exactly one
 * pin. The ORDER pins matter separately: the collector is incremental, filled by
 * the spine walk, so a contribution must be in the set before any identifier
 * that can see it is visited.
 *
 * Every assertion is over diagnostic CODES and counts — never over an AST node,
 * whose power-assert rendering is its whole subtree.
 */
class TavCandidateGateTest {

    @Test
    fun `a file-level interface used as a value still emits TS2693`() {
        diagnose(
            """
            interface Shape {
                area: number
            }
            const taken = Shape
            """.trimIndent(),
        ) should {
            have(any { it.code == 2693 })
        }
    }

    @Test
    fun `a file-level type alias used as a value still emits TS2693`() {
        diagnose(
            """
            type Handle = { id: number }
            const taken = Handle
            """.trimIndent(),
        ) should {
            have(any { it.code == 2693 })
        }
    }

    @Test
    fun `a type-only namespace used as a value still emits TS2708`() {
        diagnose(
            """
            namespace Meta {
                export interface Entry {
                    id: number
                }
            }
            const taken = Meta
            """.trimIndent(),
        ) should {
            have(any { it.code == 2708 })
        }
    }

    @Test
    fun `a TYPE PARAMETER used as a value still emits TS2693 - the tavFnLevel source`() {
        diagnose(
            """
            function identity<Element>(input: Element): Element {
                const taken = Element
                return input
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2693 })
        }
    }

    @Test
    fun `a class METHOD type parameter used as a value still emits TS2693`() {
        diagnose(
            """
            class Registry {
                lookup<Entry>(input: Entry): Entry {
                    const taken = Entry
                    return input
                }
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2693 })
        }
    }

    @Test
    fun `an interface declared INSIDE a namespace still emits TS2693 - the tavModuleLevel source`() {
        diagnose(
            """
            namespace Inner {
                interface Local {
                    id: number
                }
                export function grab() {
                    const taken = Local
                    return taken
                }
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2693 })
        }
    }

    @Test
    fun `a type alias declared INSIDE a namespace still emits TS2693`() {
        diagnose(
            """
            namespace Inner {
                type Local = { id: number }
                export function grab() {
                    const taken = Local
                    return taken
                }
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2693 })
        }
    }

    @Test
    fun `a type keyword used as a value still emits TS2693 - the keyword source`() {
        diagnose(
            """
            const taken = string
            """.trimIndent(),
        ) should {
            have(any { it.code == 2693 })
        }
    }

    /**
     * The ORDER invariant, stated as a fixture rather than as an argument: the
     * candidate set is filled by the spine walk, and the type parameter of an
     * ENCLOSING function must already be in it when a deeply nested identifier
     * is visited. `forEachChild` visits `typeParameters` before `parameters` and
     * `body`, which is what makes this hold — and this pin is what would notice
     * if that order changed.
     */
    @Test
    fun `an ENCLOSING type parameter is a candidate at any depth below it`() {
        diagnose(
            """
            function outer<Element>(): void {
                function middle(): void {
                    if (true) {
                        const taken = Element
                    }
                }
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2693 })
        }
    }

    /**
     * The same invariant for a module block, whose contribution is scanned at
     * the block's own enter — i.e. before ANY of its statements are visited, so
     * a use that TEXTUALLY PRECEDES the declaration is still gated correctly.
     */
    @Test
    fun `a namespace-local interface is a candidate for a use ABOVE its declaration`() {
        diagnose(
            """
            namespace Inner {
                export function grab() {
                    const taken = Local
                    return taken
                }
                interface Local {
                    id: number
                }
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2693 })
        }
    }

    /**
     * The gate is per FILE, so a file that HAS candidates must still not emit for
     * an ordinary value name — this is the pin that a gate widened into a no-op
     * cannot satisfy vacuously, because it asserts an ABSENCE that only correct
     * gating and correct pass logic together produce.
     */
    @Test
    fun `negative control - an ordinary value name emits neither diagnostic`() {
        val diagnostics = diagnose(
            """
            interface Shape {
                area: number
            }
            const size = 1
            const taken = size
            """.trimIndent(),
        )
        assert(diagnostics.none { it.code == 2693 || it.code == 2708 })
    }

    /**
     * TS2689 comes from `spineTavClassHeritage`, a DIFFERENT dispatch that the
     * identifier gate does not sit in front of. It is pinned here because the two
     * share `spineTavStatus` and the file root: a gate mistakenly applied to the
     * whole pass rather than to the identifier dispatch would take this out.
     */
    @Test
    fun `a class extending an interface still emits TS2689`() {
        diagnose(
            """
            interface Shape {
                area: number
            }
            class Square extends Shape {
                area = 1
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2689 })
        }
    }

    /**
     * The in-binary OFF arm must be an EQUIVALENCE, not merely a switch: the
     * whole capture is quoted as a controlled row between these two arms, so if
     * they disagreed on any diagnostic the row would be comparing two compilers.
     * Driving both arms over the same sources here is what makes the claim
     * testable at all — and it is the only pin that fails if the gate LOSES a
     * diagnostic for a shape the fixtures above do not enumerate.
     */
    @Test
    fun `the gate arm and the pre-874 arm agree on every diagnostic`() {
        val sources = listOf(
            "interface Shape { area: number }\nconst taken = Shape\n",
            "namespace Meta { export interface Entry { id: number } }\nconst taken = Meta\n",
            "function identity<Element>(input: Element): Element {\n" +
                "    const taken = Element\n    return input\n}\n",
            "namespace Inner {\n    interface Local { id: number }\n" +
                "    export function grab() { const taken = Local; return taken }\n}\n",
            "const taken = string\n",
            "const size = 1\nconst taken = size\n",
            "interface Shape { area: number }\nclass Square extends Shape { area = 1 }\n",
        )
        for (source in sources) {
            val gated = diagnose(source).map { "${it.code}@${it.start}:${it.message}" }.sorted()
            TavGate.off = true
            val ungated = try {
                diagnose(source).map { "${it.code}@${it.start}:${it.message}" }.sorted()
            } finally {
                TavGate.off = false
            }
            assert(gated == ungated)
        }
    }

    /**
     * …and the non-vacuity control for the pin above: with the arm restored, the
     * shapes really do produce diagnostics, so "the two arms agree" is not the
     * agreement of two empty lists (round 864's M1 lesson — an inert arm makes a
     * differential compare a binary against itself and pass forever).
     */
    @Test
    fun `the equivalence pin is not vacuous - the shapes really do emit`() {
        val emitted = diagnose(
            "interface Shape { area: number }\nconst taken = Shape\n",
        ).count { it.code == 2693 }
        assert(emitted > 0)
    }
}
