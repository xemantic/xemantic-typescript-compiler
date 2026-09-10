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
 * (INV.0) step 10b-iii(d) — the MEMBER-EXISTENCE walker's receiver is the SAME
 * scope-space symbol [Checker.getTypeOfIdentifierCore] types (B83.5).
 *
 * A `function` / `class` / `enum` / `namespace` declared inside a function body or
 * a block is bound by no conventional table, so the `cmam*` family's
 * `lookupPerFileForNode` — keyed by the FILE, and therefore answering a file-level
 * declaration however deeply the reference is nested — composed the WRONG receiver
 * for one and NO receiver for the other. Both halves are pinned here:
 *
 *  - a SHADOWING inner declaration used to report the INNER member as missing (the
 *    worst failure mode there is: a confident message naming the wrong
 *    declaration) while step 10b had already typed the same receiver as the inner
 *    enum for the assignability readers, so ONE line carried a correct TS2322 and
 *    a false TS2339 from two walkers that disagreed about one receiver;
 *  - a UNIQUE inner declaration reported nothing at all, because the tables held
 *    no symbol for the name and the receiver bailed as `any`.
 *
 * Measured against tsgo 7.0.2 and pristine `typescript@6.0.3`, which agree row for
 * row and column for column on every shape below.
 *
 * ## The two halves are pinned separately on purpose
 *
 * The substitution is UNCONDITIONAL — it fires whether or not the file tables hold
 * an outer declaration of the name — which is the one place it diverges from step
 * 10b's own "override a conventional answer, never replace silence" rule. 10b hands
 * a TYPE to every assignability reader in the program, where replacing an `any`
 * unmasks unrelated inference gaps ((P18.63)); this hands a SYMBOL to one
 * member-existence walker whose only verdict is whether a member exists, so silence
 * here is a TS2339 that never fires rather than an `any` suppressing something else.
 * The `absent…` pins below are exactly what the shadowing-only form would lose, and
 * they are what makes the two forms distinguishable at all.
 *
 * ## What is NOT pinned, and why
 *
 * The NAMESPACE receiver is a recorded RESIDUE: `Binder.declareLexical`'s
 * `ModuleDeclaration` arm publishes no members onto the scope symbol's `exports`
 * where its `enum` arm does ((P18.64)), and the walker's namespace branch is gated
 * on `exports != null`. Substituting the correct receiver therefore removes the
 * false row without producing the true one — and in the one cell where the WRONG
 * receiver had been answering correctly by accident (an absent member on a
 * shadowing namespace, missing from both declarations) it removes that row too.
 * Nothing here pins either, because a pin asserting today's wrong answer is a
 * countdown rather than a guard.
 *
 * Every assertion is over diagnostic CODES and full MESSAGE text — never over an
 * AST node, whose power-assert rendering is its whole subtree.
 */
class BlockScopedValueReceiverMemberTest {

    @Test
    fun `a shadowing block-scoped enum receiver reports the OUTER member as missing`() {
        diagnose(
            """
            enum ZzzE2 { ZOuter = 1 }
            export function zzzHost(): void {
                {
                    enum ZzzE2 { ZInner = 2 }
                    const zzzBad = ZzzE2.ZOuter
                }
            }
            export {}
            """.trimIndent(),
        ) should {
            have(
                any {
                    it.code == 2339 &&
                        it.message ==
                        "Property 'ZOuter' does not exist on type 'typeof ZzzE2'."
                },
            )
        }
    }

    @Test
    fun `a shadowing block-scoped enum receiver does not report the INNER member`() {
        diagnose(
            """
            enum ZzzE2 { ZOuter = 1 }
            export function zzzHost(): void {
                {
                    enum ZzzE2 { ZInner = 2 }
                    const zzzOk = ZzzE2.ZInner
                }
            }
            export {}
            """.trimIndent(),
        ) should {
            have(
                none {
                    it.code == 2339 &&
                        it.message ==
                        "Property 'ZInner' does not exist on type 'typeof ZzzE2'."
                },
            )
        }
    }

    @Test
    fun `a shadowing enum at a function body top reports the OUTER member as missing`() {
        diagnose(
            """
            enum ZzzE2 { ZOuter = 1 }
            export function zzzHost(): void {
                enum ZzzE2 { ZInner = 2 }
                const zzzBad = ZzzE2.ZOuter
            }
            export {}
            """.trimIndent(),
        ) should {
            have(
                any {
                    it.code == 2339 &&
                        it.message ==
                        "Property 'ZOuter' does not exist on type 'typeof ZzzE2'."
                },
            )
        }
    }

    @Test
    fun `a shadowing block-scoped class receiver reports the OUTER static as missing`() {
        diagnose(
            """
            class ZzzC2 { static zzzOuter = 1 }
            export function zzzHost(): void {
                {
                    class ZzzC2 { static zzzInner = 2 }
                    const zzzBad = ZzzC2.zzzOuter
                }
            }
            export {}
            """.trimIndent(),
        ) should {
            have(
                any {
                    it.code == 2339 &&
                        it.message ==
                        "Property 'zzzOuter' does not exist on type 'typeof ZzzC2'."
                },
            )
        }
    }

    @Test
    fun `a shadowing block-scoped class receiver does not report the INNER static`() {
        diagnose(
            """
            class ZzzC2 { static zzzOuter = 1 }
            export function zzzHost(): void {
                {
                    class ZzzC2 { static zzzInner = 2 }
                    const zzzOk = ZzzC2.zzzInner
                }
            }
            export {}
            """.trimIndent(),
        ) should {
            have(
                none {
                    it.code == 2339 &&
                        it.message ==
                        "Property 'zzzInner' does not exist on type 'typeof ZzzC2'."
                },
            )
        }
    }

    @Test
    fun `a unique block-scoped enum receiver reports an absent member`() {
        diagnose(
            """
            export function zzzHost(): void {
                {
                    enum ZzzE { ZA = 1 }
                    const zzzBad = ZzzE.ZNope
                }
            }
            export {}
            """.trimIndent(),
        ) should {
            have(
                any {
                    it.code == 2339 &&
                        it.message ==
                        "Property 'ZNope' does not exist on type 'typeof ZzzE'."
                },
            )
        }
    }

    @Test
    fun `a unique block-scoped class receiver reports an absent static`() {
        diagnose(
            """
            export function zzzHost(): void {
                {
                    class ZzzC { static zzzA = 1 }
                    const zzzBad = ZzzC.zzzNope
                }
            }
            export {}
            """.trimIndent(),
        ) should {
            have(
                any {
                    it.code == 2339 &&
                        it.message ==
                        "Property 'zzzNope' does not exist on type 'typeof ZzzC'."
                },
            )
        }
    }

    @Test
    fun `a unique enum at a function body top reports an absent member`() {
        diagnose(
            """
            export function zzzHost(): void {
                enum ZzzE { ZA = 1 }
                const zzzBad = ZzzE.ZNope
            }
            export {}
            """.trimIndent(),
        ) should {
            have(
                any {
                    it.code == 2339 &&
                        it.message ==
                        "Property 'ZNope' does not exist on type 'typeof ZzzE'."
                },
            )
        }
    }

    @Test
    fun `a member that exists on a unique block-scoped enum is silent`() {
        diagnose(
            """
            export function zzzHost(): void {
                {
                    enum ZzzE { ZA = 1 }
                    const zzzOk = ZzzE.ZA
                }
            }
            export {}
            """.trimIndent(),
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a static that exists on a unique block-scoped class is silent`() {
        diagnose(
            """
            export function zzzHost(): void {
                {
                    class ZzzC { static zzzA = 1 }
                    const zzzOk = ZzzC.zzzA
                }
            }
            export {}
            """.trimIndent(),
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `control - a file-level enum receiver still reports an absent member`() {
        diagnose(
            """
            enum ZzzEf { ZA = 1 }
            const zzzOk = ZzzEf.ZA
            const zzzBad = ZzzEf.ZNope
            export {}
            """.trimIndent(),
        ) should {
            have(
                any {
                    it.code == 2339 &&
                        it.message ==
                        "Property 'ZNope' does not exist on type 'typeof ZzzEf'."
                },
            )
        }
    }

    @Test
    fun `control - a file-level class receiver still reports an absent static`() {
        diagnose(
            """
            class ZzzCf { static zzzA = 1 }
            const zzzOk = ZzzCf.zzzA
            const zzzBad = ZzzCf.zzzNope
            export {}
            """.trimIndent(),
        ) should {
            have(
                any {
                    it.code == 2339 &&
                        it.message ==
                        "Property 'zzzNope' does not exist on type 'typeof ZzzCf'."
                },
            )
        }
    }

    @Test
    fun `control - a member that exists on a file-level enum is still silent`() {
        diagnose(
            """
            enum ZzzEf { ZA = 1 }
            const zzzOk = ZzzEf.ZA
            export {}
            """.trimIndent(),
        ) should {
            have(none { it.code == 2339 })
        }
    }
}
