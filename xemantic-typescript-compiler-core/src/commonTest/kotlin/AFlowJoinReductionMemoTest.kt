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
 * (PERF.1) round 949: [Checker.flowJoinUnion]'s SUBTYPE REDUCTION is memoized, and the
 * memo must be TRANSPARENT — every join answers what the un-memoized reduction answered.
 *
 * WHY THERE IS A MEMO. (CHK.66) put an `isTypeAssignableTo` grid at the two flow joins,
 * quadratic in the member count, entered only when some member is FOREIGN to the
 * declaration — "free on almost every join". (CHK.85)(b) took that away for a whole class
 * of joins without meaning to, and it cost **9.9 s of a 37.0 s** compiler-profile check
 * (measured; forcing the free path read 27.1 s with all 46 diagnostics unchanged). No
 * counter in `cost_gate.py` moves for it — the walk COUNT barely changes — which is why
 * a bench row and not a gate is what found it.
 *
 * WHY THESE SHAPES. Every case here must REACH the memo, i.e. carry a foreign member: a
 * type-guard narrow to a strict subtype the declaration does not itself contain. A join
 * whose members are all declared returns ABOVE the cache probe ([flowJoinUnion]'s
 * `anyForeign` early exit), so the obvious "declaration contains the member" fixture is
 * VACUOUS here and is not used — measured, an ablation dropping `declaredType` from the
 * key left it green.
 *
 * P1/P2 are the SAME (joined, declaredType) pair asked twice, which is the only shape a
 * memo can get wrong on its own: P1 is the computing ask and P2 the SERVED one. P3 is a
 * second, DISTINCT key, so a cache that ignores its key (a single slot) reddens too.
 * All three read the flow type through a deliberate mis-assignment — the only instrument
 * that PRINTS it — and all three are byte-identical to tsgo 7.0.2 on the same fixture.
 *
 * Ablation: storing `joined` instead of the reduced type reddens P2 alone (P1 computes
 * before the poisoned entry exists), which is exactly the served-answer claim.
 */
class AFlowJoinReductionMemoTest {

    private val prelude =
        """
        interface ZBase { a: number }
        interface ZSub extends ZBase { b: number }
        interface ZOther { c: number }
        interface ZOtherSub extends ZOther { d: number }
        declare function zzzIsSub(x: ZBase): x is ZSub;
        declare function zzzIsOtherSub(x: ZOther): x is ZOtherSub;
        declare function zzzSink(x: unknown): void;

        """.trimIndent() + "\n"

    @Test
    fun `P1 - the computing ask - a guard-introduced subtype is dropped at the join`() {
        diagnose(
            prelude +
                """
                export function zzzDrop1(v: ZBase): void {
                  if (zzzIsSub(v)) { zzzSink(v); }
                  const p: boolean = v;
                }
                """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'ZBase' is not assignable") })
            have(none { it.code == 2322 && it.message.contains("ZSub") })
        }
    }

    @Test
    fun `P2 - the SERVED ask - the same join asked twice answers the same`() {
        diagnose(
            prelude +
                """
                export function zzzDrop1(v: ZBase): void {
                  if (zzzIsSub(v)) { zzzSink(v); }
                  const p: boolean = v;
                }
                export function zzzDrop1Again(v: ZBase): void {
                  if (zzzIsSub(v)) { zzzSink(v); }
                  const q: boolean = v;
                }
                """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'ZBase' is not assignable") })
            // the SECOND reading is the served one; a memo storing the un-reduced union
            // reddens here and only here.
            have(none { it.code == 2322 && it.message.contains("ZSub") })
        }
    }

    @Test
    fun `P3 - a distinct key in the same compile keeps its own answer`() {
        diagnose(
            prelude +
                """
                export function zzzDrop1(v: ZBase): void {
                  if (zzzIsSub(v)) { zzzSink(v); }
                  const p: boolean = v;
                }
                export function zzzDrop2(v: ZOther): void {
                  if (zzzIsOtherSub(v)) { zzzSink(v); }
                  const q: boolean = v;
                }
                """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'ZBase' is not assignable") })
            have(any { it.code == 2322 && it.message.contains("Type 'ZOther' is not assignable") })
            have(none { it.code == 2322 && it.message.contains("ZSub") })
            have(none { it.code == 2322 && it.message.contains("ZOtherSub") })
        }
    }
}
