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
 * (CHK.66) A flow JOIN unions its arms with a SUBTYPE REDUCTION, as tsc's
 * `getTypeAtFlowBranchLabel` does with `UnionReduction.Subtype`.
 *
 * [Checker.getUnionType] performs none (INV.5(a) interns a union by its member-id
 * list and by nothing else), so before this a join whose one arm had been narrowed
 * kept the narrowed member BESIDE the declaration's own: on
 * `const x = zzzMk(); if (x === "a") { } x` we answered `string | number | "a"`
 * where tsc 7.0.2 answers `string | number` — a SHIPPED divergence reachable in four
 * lines with no loop and no partition.
 *
 * Every positive names the READER it is measured at, because the readers differ:
 * P1/P2/P3/P6 are the DECLARATION reader (TS2322 on a deliberate mis-assignment,
 * which is the only instrument that PRINTS the flow type), P4/P5 the CALL-ARGUMENT
 * reader (TS2345; live only when the source is a PARAMETER).
 *
 * The controls pin the conservatism and the shape: only a member the DECLARATION
 * does not itself contain may be dropped (C2), and an unrelated union keeps every
 * member and its order (C1). Both are green on the parent binary and on this one;
 * P7 — a REAL drop that must preserve the order of the survivors, union member
 * order being pinned byte-for-byte across the corpus — is a POSITIVE, measured RED
 * on the parent, and is named as one.
 */
class AFlowJoinReducesANarrowedSubtypeTest {

    private val prelude =
        """
        declare function zzzMk(): string | number;
        declare function zzzB(): boolean;
        declare function zzzSink(x: unknown): void;

        """.trimIndent() + "\n"

    @Test
    fun `P1 - DECLARATION reader - a literal narrowed in one arm is dropped at the join`() {
        diagnose(
            prelude +
                """
                export function zzzP1(): void {
                  const x = zzzMk();
                  if (x === "a") { zzzSink(x); }
                  const p: boolean = x;
                }
                """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'string | number' is not assignable") })
            have(none { it.code == 2322 && it.message.contains("| \"a\"") })
        }
    }

    @Test
    fun `P2 - DECLARATION reader - the if-else form of the same join`() {
        diagnose(
            prelude +
                """
                export function zzzP2(): void {
                  const x = zzzMk();
                  if (x === "a") { zzzSink(x); } else { zzzSink(1); }
                  const p: boolean = x;
                }
                """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'string | number' is not assignable") })
            have(none { it.code == 2322 && it.message.contains("| \"a\"") })
        }
    }

    @Test
    fun `P3 - DECLARATION reader - a three-arm join drops both narrowed literals`() {
        diagnose(
            prelude +
                """
                export function zzzP3(): void {
                  const x = zzzMk();
                  if (x === "a") { zzzSink(x); } else if (x === 1) { zzzSink(x); }
                  const p: boolean = x;
                }
                """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'string | number' is not assignable") })
            have(none { it.code == 2322 && it.message.contains("| \"a\"") })
            have(none { it.code == 2322 && it.message.contains("| 1") })
        }
    }

    @Test
    fun `P4 - CALL-ARGUMENT reader - a parameter joined after a literal narrow`() {
        diagnose(
            prelude +
                """
                declare function zzzTake(b: boolean): void;
                export function zzzP4(x: string | number): void {
                  if (x === "a") { zzzSink(x); }
                  zzzTake(x);
                }
                """.trimIndent(),
        ) should {
            have(any { it.code == 2345 && it.message.contains("Argument of type 'string | number'") })
            have(none { it.code == 2345 && it.message.contains("| \"a\"") })
        }
    }

    @Test
    fun `P5 - CALL-ARGUMENT reader - an OBJECT subtype introduced by a type-guard call is dropped`() {
        diagnose(
            prelude +
                """
                interface ZzzA { zzzK: number }
                interface ZzzB extends ZzzA { zzzT: string }
                interface ZzzC { zzzOther: string }
                declare function zzzIsB(n: ZzzA | ZzzC): n is ZzzB;
                declare function zzzTakeB(b: boolean): void;
                export function zzzP5(n: ZzzA | ZzzC): void {
                  if (zzzIsB(n)) { zzzSink(n.zzzT); }
                  zzzTakeB(n);
                }
                """.trimIndent(),
        ) should {
            have(any { it.code == 2345 && it.message.contains("Argument of type 'ZzzA | ZzzC'") })
            have(none { it.code == 2345 && it.message.contains("ZzzB |") })
        }
    }

    @Test
    fun `P6 - DECLARATION reader - a narrowed join renders exactly like the un-narrowed one`() {
        // Both functions read the SAME declared type at the SAME reader; the only
        // difference is that one of them passes through a join whose arm was narrowed.
        // On the parent the narrowed one reads `string | number | "a"`.
        // RESIDUE, and NOT this change's: tsc 7.0.2 renders BOTH as the alias `ZzzAl`
        // and we render both structurally — the (INC.27)/(INC.29) alias-display family,
        // measured identical on the un-narrowed control, so it is untouched here.
        val d = diagnose(
            prelude +
                """
                type ZzzAl = string | number;
                declare function zzzMkA(): ZzzAl;
                export function zzzP6a(): void {
                  const x = zzzMkA();
                  if (x === "a") { zzzSink(x); }
                  const p: boolean = x;
                }
                export function zzzP6b(): void {
                  const x = zzzMkA();
                  if (zzzB()) { zzzSink(x); }
                  const p: boolean = x;
                }
                """.trimIndent(),
        )
        val rows = d.filter { it.code == 2322 }.map { it.message }
        assert(rows.size == 2)
        assert(rows[0] == rows[1])
    }

    @Test
    fun `C1 control - a join with no subtype relation keeps every member and its order`() {
        diagnose(
            prelude +
                """
                declare function zzzMk3(): string | number | boolean;
                export function zzzC1(): void {
                  const x = zzzMk3();
                  if (zzzB()) { zzzSink(0); }
                  const p: symbol = x;
                }
                """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'string | number | boolean' is not assignable") })
        }
    }

    @Test
    fun `C2 control - a subtype the DECLARATION itself contains is never dropped`() {
        diagnose(
            prelude +
                """
                declare function zzzMkL(): string | "a";
                export function zzzC2(): void {
                  const x = zzzMkL();
                  if (zzzB()) { zzzSink(0); }
                  const p: boolean = x;
                }
                """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'string | \"a\"' is not assignable") })
        }
    }

    @Test
    fun `P7 - DECLARATION reader - a real drop preserves the ORDER of the members that survive`() {
        diagnose(
            prelude +
                """
                declare function zzzMk3(): string | number | boolean;
                export function zzzC3(): void {
                  const x = zzzMk3();
                  if (x === "a") { zzzSink(x); }
                  const p: symbol = x;
                }
                """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'string | number | boolean' is not assignable") })
        }
    }

}
