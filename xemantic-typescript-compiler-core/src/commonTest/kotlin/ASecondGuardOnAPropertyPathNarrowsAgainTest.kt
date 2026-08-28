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
 * (CHK.65) A SECOND `!== undefined` GUARD ON THE SAME PROPERTY PATH MUST NARROW AGAIN —
 * A DOMAIN OF EXACTLY ONE LITERAL, MINUS THAT LITERAL, IS **EMPTY**.
 *
 * ```ts
 * if (s.p !== undefined) { return s.p; }   // fine
 * if (s.p !== undefined) { return s.p; }   // SHIPPED FALSE POSITIVE before this
 * ```
 *
 * The first guard's ELSE branch narrows the path to exactly `undefined`;
 * [Checker.narrowUnionByLiteral]'s NON-union `keep = false` arm then answered its input
 * UNCHANGED, on the reasoning (correct, but not applicable here) that subtracting a
 * literal from an INFINITE primitive domain is a no-op. `undefined` minus `undefined` is
 * `never`, which is what tsc's own `filterType` answers — there the union and non-union
 * cases are ONE function, and here they are two branches of which only the union one
 * subtracted.
 *
 * WHY IT SURVIVED: an IDENTIFIER subject is answered by the M1.9 if-arm machinery and
 * was always correct, and every hand-written narrowing fixture in this repo uses a
 * local. It reproduces only for a PROPERTY PATH, and only with a PRECEDING guard that
 * leaves the else state behind (a `return`, a `throw`, or an `&&` chain).
 *
 * TWO READERS, both pinned, because closing the first one alone leaves the second: the
 * ASSIGNMENT/RETURN reader (TS2322 `Type 'undefined' is not assignable …`) reads the
 * flow walk, and the ARITHMETIC/RELATIONAL OPERAND reader (TS18048 `'s.p' is possibly
 * 'undefined'`) reads [Checker.arithOperandType], whose flow consult is gated on a UNION
 * base AND refuses a `never` answer — so it never saw the re-narrowing.
 * [Checker.operandFlowNarrowsToNever] is the suppression there, and it must answer TRUE
 * (caller emits nothing) rather than merely decline, or the TS18048 becomes a TS2365
 * about `number | undefined` one line down.
 *
 * Measured on the parent binary `3c6a8e33` with these fixtures in place: every positive
 * below reports and tsc 7.0.2 reports nothing for any of them; every negative control
 * reports on BOTH compilers.
 */
class ASecondGuardOnAPropertyPathNarrowsAgainTest {

    private val prelude = """
        interface ZzzS { zzzP: number | undefined }
        interface ZzzD { zzzQ: ZzzS }
        declare function zzzSide(): boolean;
    """.trimIndent() + "\n"

    /** POSITIVE — the RETURN reader: two guards, the first one returns. */
    @Test
    fun `a second guard on a property path narrows again at the return reader`() {
        diagnose(
            prelude +
                "function zzzP1(zzzs: ZzzS): number {\n" +
                "  if (zzzs.zzzP !== undefined) { return zzzs.zzzP; }\n" +
                "  if (zzzs.zzzP !== undefined) { return zzzs.zzzP; }\n" +
                "  return 0;\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /** POSITIVE — the RETURN reader over a DEEPER path (`d.q.p`). */
    @Test
    fun `a second guard on a two-hop property path narrows again`() {
        diagnose(
            prelude +
                "function zzzP2(zzzd: ZzzD): number {\n" +
                "  if (zzzd.zzzQ.zzzP !== undefined) { return zzzd.zzzQ.zzzP; }\n" +
                "  if (zzzd.zzzQ.zzzP !== undefined) { return zzzd.zzzQ.zzzP; }\n" +
                "  return 0;\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /** POSITIVE — the first guard THROWS rather than returning; same else state. */
    @Test
    fun `a second guard narrows again when the first branch throws`() {
        diagnose(
            prelude +
                "function zzzP3(zzzs: ZzzS): number {\n" +
                "  if (zzzs.zzzP !== undefined) { throw new Error(); }\n" +
                "  if (zzzs.zzzP !== undefined) { return zzzs.zzzP; }\n" +
                "  return 0;\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /** POSITIVE — the receiver is a body-local `const`, not a parameter. */
    @Test
    fun `a second guard narrows again on a local const receiver`() {
        diagnose(
            prelude +
                "function zzzP4(): number {\n" +
                "  const zzzs: ZzzS = { zzzP: 1 };\n" +
                "  if (zzzs.zzzP !== undefined) { return zzzs.zzzP; }\n" +
                "  if (zzzs.zzzP !== undefined) { return zzzs.zzzP; }\n" +
                "  return 0;\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * POSITIVE — the RELATIONAL OPERAND reader (TS18048). This is the second reader, and
     * it is the one tsc's own `checker.ts isSymbolAssignedDefinitely` is written on.
     */
    @Test
    fun `a second guard narrows again at the relational operand reader`() {
        diagnose(
            prelude +
                "function zzzP5(zzzs: ZzzS): boolean {\n" +
                "  if (zzzs.zzzP !== undefined) { return zzzs.zzzP < 0; }\n" +
                "  if (zzzs.zzzP !== undefined) { return zzzs.zzzP < 0; }\n" +
                "  return false;\n" +
                "}",
        ) should {
            have(none { it.code == 18048 })
            have(none { it.code == 2365 })
        }
    }

    /** POSITIVE — the same at the ARITHMETIC operand reader. */
    @Test
    fun `a second guard narrows again at the arithmetic operand reader`() {
        diagnose(
            prelude +
                "function zzzP6(zzzs: ZzzS): number {\n" +
                "  if (zzzs.zzzP !== undefined) { return zzzs.zzzP + 1; }\n" +
                "  if (zzzs.zzzP !== undefined) { return zzzs.zzzP + 1; }\n" +
                "  return 0;\n" +
                "}",
        ) should {
            have(none { it.code == 18048 })
            have(none { it.code == 2365 })
        }
    }

    /**
     * POSITIVE — the guard that follows is a LATER CONJUNCT of an `&&`, which is the
     * exact shape of tsc's `checker.ts:30269`
     * (`isSymbolAssigned(s) && s.lastAssignmentPos !== undefined && s.lastAssignmentPos < 0`).
     */
    @Test
    fun `a guard in a later conjunct narrows again after an early return`() {
        diagnose(
            prelude +
                "function zzzP7(zzzs: ZzzS): boolean {\n" +
                "  if (zzzs.zzzP !== undefined) { return zzzs.zzzP < 0; }\n" +
                "  return zzzSide() && zzzs.zzzP !== undefined && zzzs.zzzP < 0;\n" +
                "}",
        ) should {
            have(none { it.code == 18048 })
            have(none { it.code == 2365 })
        }
    }

    /**
     * CONTROL — a SINGLE guard was always correct; this is the shape that made the
     * defect invisible, because it is what every fixture writes.
     */
    @Test
    fun `negative control - a single guard on a property path still narrows`() {
        diagnose(
            prelude +
                "function zzzC1(zzzs: ZzzS): number {\n" +
                "  if (zzzs.zzzP !== undefined) { return zzzs.zzzP; }\n" +
                "  return 0;\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * CONTROL — an IDENTIFIER subject goes through the M1.9 if-arm machinery and never
     * had the defect. It is here so the pin says which reader it is NOT about.
     */
    @Test
    fun `negative control - an identifier subject was already correct`() {
        diagnose(
            prelude +
                "function zzzC2(zzzv: number | undefined): number {\n" +
                "  if (zzzv !== undefined) { return zzzv; }\n" +
                "  if (zzzv !== undefined) { return zzzv; }\n" +
                "  return 0;\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * CONTROL — A GENUINELY POSSIBLY-UNDEFINED RELATIONAL OPERAND MUST STILL REPORT
     * TS18048. This is the pin that fails if [Checker.operandFlowNarrowsToNever] is
     * widened past `never`, and it is the reason that suppression is expressed as a
     * `never` test rather than as "the flow narrowed it".
     */
    @Test
    fun `negative control - an unguarded relational operand still reports TS18048`() {
        diagnose(
            prelude +
                "function zzzC3(zzzs: ZzzS): boolean {\n" +
                "  return zzzs.zzzP < 0;\n" +
                "}",
        ) should { have(any { it.code == 18048 }) }
    }

    /**
     * CONTROL — an UNRELATED second guard leaves the path nullish, so the read after it
     * is a TRUE positive that both compilers report.
     */
    @Test
    fun `negative control - an unrelated guard leaves the path possibly undefined`() {
        diagnose(
            prelude +
                "function zzzC4(zzzs: ZzzS): boolean {\n" +
                "  if (zzzSide()) { return false; }\n" +
                "  return zzzs.zzzP < 0;\n" +
                "}",
        ) should { have(any { it.code == 18048 }) }
    }

    /**
     * CONTROL — AN OPERAND THAT IS NARROWED BUT STILL NULLISH MUST STILL REPORT. This is
     * the pin that separates "the flow proves it `never`" from "the flow narrowed it at
     * all": with the suppression widened to any narrowing, this row disappears while
     * every other pin here stays green. tsc 7.0.2 reports it too.
     */
    @Test
    fun `negative control - a narrowed but still nullish operand still reports TS18048`() {
        diagnose(
            """
            interface ZzzS3 { zzzP: number | null | undefined }
            function zzzC6(zzzs: ZzzS3): boolean {
              if (zzzs.zzzP === null) { return false; }
              return zzzs.zzzP < 0;
            }
            """.trimIndent(),
        ) should { have(any { it.code == 18048 }) }
    }

    /**
     * CONTROL — the ENUM arms of [Checker.narrowUnionByLiteral]'s non-union branch are
     * ABOVE the new one and are untouched: a chain of `===` guards peeled down to the
     * LAST member must still reach `never` for the `assertNever` idiom ((REL.4) round
     * 768).
     */
    @Test
    fun `negative control - the enum last-member subtraction still reaches never`() {
        diagnose(
            """
            enum ZzzK { ZzzA, ZzzB }
            declare function zzzAssertNever(zzzX: never): void;
            function zzzC5(zzzk: ZzzK): void {
              if (zzzk === ZzzK.ZzzA) { return; }
              if (zzzk === ZzzK.ZzzB) { return; }
              zzzAssertNever(zzzk);
            }
            """.trimIndent(),
        ) should { have(none { it.code == 2345 }) }
    }
}
