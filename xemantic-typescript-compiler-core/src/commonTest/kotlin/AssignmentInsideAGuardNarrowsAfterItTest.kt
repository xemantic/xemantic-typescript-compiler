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
 * (CHK.63)(a) AN ASSIGNMENT INSIDE A NULLISH GUARD MUST OVERWRITE THE GUARD'S OWN
 * NARROWING — A SHIPPED FALSE POSITIVE ON THE COMMONEST "default it if it is missing"
 * IDIOM IN TypeScript, AND IT IS AT THE **FLOW WALK**, NOT AT A READER.
 *
 * ```ts
 * if (id === undefined) { id = text }   // `text: string`
 * take(id)                              // FALSE TS2345 before this
 * ```
 *
 * [Checker.narrowByAssignmentRhs]'s two resolved-RHS arms (a bare Identifier, round
 * 463; a PropertyAccess, round 464) filter the ANTECEDENT by the assigned type, and
 * [Checker.narrowUnionByRhsAssignment] answers a NON-UNION receiver unchanged — so
 * inside the guard, where the antecedent is the bare `undefined` the condition just
 * produced, the assignment was a NO-OP and the branch join re-minted
 * `string | undefined`. [Checker.assignmentReduceBase] applies round 416's rule there
 * instead: an assignment OVERWRITES, so the post-state is reduced from the DECLARED
 * type (tsc's `getAssignmentReducedType`).
 *
 * WHICH READER EACH PIN EXERCISES is stated per test. All the positives below use the
 * **CALL-ARGUMENT** reader, deliberately: that is the one reader that already reports a
 * nullish union against a primitive today, so these pins are observable on the SHIPPED
 * binary and do not sit under (CHK.63)'s own `canUseTypeEngine` gate. The same defect is
 * what tsc's `parser.ts internIdentifier` is written on, where the reader is a RETURN and
 * the row therefore only appears once that gate opens.
 *
 * Measured on the parent binary with these fixtures in place: the four positives report
 * TS2345 and tsc 7.0.2 reports nothing for them; both controls report on BOTH compilers.
 */
class AssignmentInsideAGuardNarrowsAfterItTest {

    private val prelude = """
        declare function zzzTake(zzzS: string): void;
        interface ZzzHolder { zzzFld: string }
        declare const zzzH: ZzzHolder;
        interface ZzzNs { zzzKind: 1; zzzNm: string }
        interface ZzzNi { zzzKind: 2; zzzEls: string[] }
        type ZzzNb = ZzzNs | ZzzNi;
        declare function zzzIsNs(zzzX: ZzzNb): zzzX is ZzzNs;
        declare function zzzTakeNs(zzzX: ZzzNs): void;
        declare const zzzOtherNs: ZzzNs;
    """.trimIndent() + "\n"

    /**
     * POSITIVE — the CALL-ARGUMENT reader after the guard, with an IDENTIFIER right-hand
     * side. This is `parser.ts internIdentifier`'s flow half.
     */
    @Test
    fun `an identifier assigned inside a nullish guard narrows after the guard`() {
        diagnose(
            prelude +
                "function zzzP1(zzzT: string, zzzId: string | undefined): void {\n" +
                "  if (zzzId === undefined) { zzzId = zzzT; }\n" +
                "  zzzTake(zzzId);\n" +
                "}",
        ) should { have(none { it.code == 2345 }) }
    }

    /** POSITIVE — the same at the CALL-ARGUMENT reader with a PROPERTY-ACCESS right-hand side. */
    @Test
    fun `a property access assigned inside a nullish guard narrows after the guard`() {
        diagnose(
            prelude +
                "function zzzP2(zzzId: string | undefined): void {\n" +
                "  if (zzzId === undefined) { zzzId = zzzH.zzzFld; }\n" +
                "  zzzTake(zzzId);\n" +
                "}",
        ) should { have(none { it.code == 2345 }) }
    }

    /**
     * POSITIVE — the CALL-ARGUMENT reader INSIDE the branch, which is the root of the
     * join failure: before the fix this read `Argument of type 'undefined'`, i.e. the
     * assignment did not move the guard's own narrowing at all.
     */
    @Test
    fun `a read inside the guarded branch sees the assigned type`() {
        diagnose(
            prelude +
                "function zzzP3(zzzT: string, zzzId: string | undefined): void {\n" +
                "  if (zzzId === undefined) { zzzId = zzzT; zzzTake(zzzId); }\n" +
                "}",
        ) should { have(none { it.code == 2345 }) }
    }

    /**
     * POSITIVE — an ASSIGNMENT INSIDE A CALL ARGUMENT, which is the literal shape of
     * tsc's `parser.ts:2642` (`identifiers.set(text, identifier = text)`).
     */
    @Test
    fun `an assignment nested in a call argument narrows after the guard`() {
        diagnose(
            prelude +
                "declare function zzzSet(zzzK: string, zzzV: string): void;\n" +
                "function zzzP4(zzzT: string, zzzId: string | undefined): void {\n" +
                "  if (zzzId === undefined) { zzzSet(zzzT, zzzId = zzzT); }\n" +
                "  zzzTake(zzzId);\n" +
                "}",
        ) should { have(none { it.code == 2345 }) }
    }

    /**
     * CONTROL — an assignment under an UNRELATED guard leaves the reference nullish on
     * the other path, and tsc reports it too. This is the true positive the fix must not
     * delete; it is the pin that fails if the reduction is applied unconditionally.
     */
    @Test
    fun `negative control - an unrelated guard does not make the reference non-nullish`() {
        diagnose(
            prelude +
                "function zzzC1(zzzT: string, zzzF: boolean, zzzId: string | undefined): void {\n" +
                "  if (zzzF) { zzzId = zzzT; }\n" +
                "  zzzTake(zzzId);\n" +
                "}",
        ) should { have(any { it.code == 2345 }) }
    }

    /** CONTROL — with no guard at all the reference is still the declared nullish union. */
    @Test
    fun `negative control - an unguarded nullish reference still reports`() {
        diagnose(
            prelude +
                "function zzzC2(zzzId: string | undefined): void {\n" +
                "  zzzTake(zzzId);\n" +
                "}",
        ) should { have(any { it.code == 2345 }) }
    }

    /**
     * CONTROL — a NON-nullish antecedent must keep the pass-through: reducing from the
     * DECLARED type there would widen a live type-guard narrowing back to the whole union
     * and re-report the very argument the guard made legal. Labelled a control because it
     * is silent on the parent binary too.
     */
    @Test
    fun `negative control - a non-nullish narrow is not widened by a reassignment`() {
        diagnose(
            prelude +
                "function zzzC3(zzzX: ZzzNb): void {\n" +
                "  if (zzzIsNs(zzzX)) { zzzX = zzzOtherNs; zzzTakeNs(zzzX); }\n" +
                "}",
        ) should { have(none { it.code == 2345 }) }
    }

    /**
     * CONTROL — a TRUTHINESS guard (`if (!id)`) around the same assignment was already
     * correct on the parent binary, because the falsy narrowing is a UNION
     * (`"" | undefined`) and the antecedent filter could act on it.
     */
    @Test
    fun `negative control - a truthiness guard already narrowed before the fix`() {
        diagnose(
            prelude +
                "function zzzC4(zzzT: string, zzzId: string | undefined): void {\n" +
                "  if (!zzzId) { zzzId = zzzT; }\n" +
                "  zzzTake(zzzId);\n" +
                "}",
        ) should { have(none { it.code == 2345 }) }
    }
}
