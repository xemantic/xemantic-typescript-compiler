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
 * (CHK.61)(1) AN INTERSECTION SOURCE WAS NEVER MERGED IN THE ACCEPTING DIRECTION.
 *
 * `structuredTypeRelatedTo`'s intersection-source rule is "SOME constituent relates", so
 * `ZzzBase & { zzzEndLine: number; zzzEndOff: number }` failed `ZzzFmt extends ZzzBase
 * { zzzEndLine; zzzEndOff }` — neither half satisfies the target alone, and tsc relates it
 * because the intersection's MEMBERS are merged. Measured on the harness profile at
 * `client.ts:356`; tsc 7.0.2 is silent on both positives below.
 * [Checker.intersectionMergedSatisfiesTarget] is the symmetric twin of the long-standing
 * [Checker.intersectionMergedContradictsTarget].
 *
 * THE SOUNDNESS CONDITION IS A SOURCE-SIDE `| undefined`, AND IT IS WHAT SANK THE EARLIER
 * ATTEMPT. The merge rule is "the intersected member is a subtype of EVERY declaration, so
 * ANY relating declaration suffices" — valid only if each declaration's type is spelled out
 * including its OPTIONALITY. We model an optional member as plain `T` ((CHK.61)(b)), so
 * `FunctionExpression & { name: undefined; … }` was accepted against `{ name: Identifier }`
 * via `FunctionExpression`'s `name?: Identifier`, where the real intersected member is
 * `undefined` — that is `callHierarchy.ts:199 'parent' does not exist on type 'never'`, the
 * false positive that forced the earlier revert. The widening is LOCAL to this rule: it is a
 * suppression rule, so being pessimistic about a source member can only decline to suppress.
 *
 * RESIDUE, deliberately not pinned (round 765): for the missing-required-property control
 * tsc reports TS2741 naming the property where we report TS2322 against the whole target.
 * That divergence predates this rule.
 */
class IntersectionMergedSourceTest {

    private val prelude = """
        interface ZzzBase { zzzFile: string }
        interface ZzzFmt extends ZzzBase { zzzEndLine: number; zzzEndOff: number }
        declare function zzzMk(): ZzzBase & { zzzEndLine: number; zzzEndOff: number };
    """.trimIndent() + "\n"

    @Test
    fun `an intersection whose members TOGETHER satisfy the target relates at a var decl`() {
        diagnose(prelude + "const zzzA: ZzzFmt = zzzMk();") should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `the same intersection relates at a method return`() {
        diagnose(prelude + "class ZzzC { zzzM(): ZzzFmt { return zzzMk() } }") should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `an OPTIONAL declaration cannot satisfy a required target member that another constituent pins to undefined`() {
        // The soundness pin: `ZzzNamed.zzzName?: ZzzIdt` intersected with `{ zzzName:
        // undefined }` is `undefined`, not `ZzzIdt`. Without the source-side `| undefined`
        // this row is silently SUPPRESSED, which is the `callHierarchy.ts:199` false
        // positive one narrowing step later. tsc 7.0.2 reports it too.
        val rows = diagnose(
            """
            interface ZzzIdt { zzzText: string }
            interface ZzzNamed { zzzName?: ZzzIdt }
            interface ZzzReq { zzzName: ZzzIdt }
            declare function zzzMk2(): ZzzNamed & { zzzName: undefined };
            const zzzB: ZzzReq = zzzMk2();
            """,
        ).filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(
            rows[0].message ==
                "Type 'ZzzNamed & { zzzName: undefined; }' is not assignable to type 'ZzzReq'.",
        )
        assert(rows[0].character == 7)
    }

    @Test
    // COVERAGE, not a control: ablation arm b2 (drop the missing-required-property
    // refusal) reddens this row and nothing else.
    fun `a required target property NO constituent supplies is still refused`() {
        diagnose(
            prelude +
                "interface ZzzMiss { zzzFile: string; zzzGone: number }\n" +
                "declare function zzzMk3(): ZzzBase & { zzzEndLine: number };\n" +
                "const zzzC2: ZzzMiss = zzzMk3();",
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `control - a merged member whose type CONTRADICTS the target is still refused`() {
        // The long-standing [intersectionMergedContradictsTarget] direction, unmoved.
        diagnose(
            prelude +
                "interface ZzzBad { zzzFile: number }\n" +
                "declare function zzzMk4(): ZzzBase & { zzzEndLine: number };\n" +
                "const zzzD2: ZzzBad = zzzMk4();",
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
