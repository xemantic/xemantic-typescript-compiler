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
 * (CHK.64)(ii) AN IF WHOSE THEN-BRANCH DEFINITELY EXITS NARROWS THE REST OF THE BLOCK BY
 * THE NEGATED CONDITION — AND IT IS THE SAME READER GAP AS (i).
 *
 * Measured before the fix: after `if (typeof x !== "number") { return; }` a MEMBER
 * ACCESS, a CALL ARGUMENT and a DECLARATION are all already correct — they consult the
 * flow walk. Only the ASSIGNMENT and RETURN readers were wrong, because round 784's gate
 * sends them to [Checker.currentLocalTypes] for a primitive target and nothing filled
 * that map after an early exit. So the queue's "five mechanisms" is two mechanisms at ONE
 * reader: (i) is a condition SHAPE the filler cannot read, (ii) is a POSITION it never
 * looked at.
 *
 * [Checker.ctaAlwaysExits] is conservative (`return`/`throw`/`continue`/`break`, or a
 * block whose LAST statement is one) and [Checker.negateCondition] is syntactic. The
 * install is REFUSED unless the enclosing frame opened its own `localTypes` scope, which
 * is what bounds the narrowing to the block: a statement-position block SHARES its
 * parent's map and has no pop to revert a write.
 *
 * THREE RESIDUES THIS CLASS RECORDED — a `while`/`do` body, a plain nested `{ … }` block
 * and an `if … else` — ARE CLOSED as of (CHK.70), and none of them by this install. The
 * install is still refused in all three (they share their parent's map, and an else may
 * assign to the very reference the negation is about); what changed is that (CHK.63)
 * opened `canUseTypeEngine`'s nullish-union-versus-primitive gate, so the ASSIGNMENT
 * reader reaches the FLOW walk, which has always handled an early exit. The two pins are
 * kept and INVERTED rather than deleted, because they are now the only thing that would
 * notice that gate closing again.
 */
class EarlyExitNarrowsTheRestOfTheBlockTest {

    private val prelude = """
        let zzzP1: number = 0;
        let zzzQs: string = "";
        let zzzUn: number | boolean = 0;
        interface ZzzNs { zzzKind: 1; zzzNm: string }
        interface ZzzNi { zzzKind: 2; zzzEls: string[] }
        type ZzzNb = ZzzNs | ZzzNi;
        declare function zzzIsNs(zzzX: ZzzNb): zzzX is ZzzNs;
    """.trimIndent() + "\n"

    /** POSITIVE — the ASSIGNMENT reader after an early `return`. */
    @Test
    fun `an early return narrows the assignment reader for the rest of the body`() {
        diagnose(
            prelude +
                "function zzzE1(zzzX: number | string): void {\n" +
                "  if (typeof zzzX !== \"number\") { return; }\n" +
                "  zzzP1 = zzzX;\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /** POSITIVE — the RETURN reader after an early `return`. */
    @Test
    fun `an early return narrows the return reader for the rest of the body`() {
        diagnose(
            prelude +
                "function zzzE2(zzzX: number | string): number {\n" +
                "  if (typeof zzzX !== \"number\") { return 0; }\n" +
                "  return zzzX;\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /** POSITIVE — a `continue` in a `for…of` body, the `core.ts:2191` shape. */
    @Test
    fun `a continue guard narrows the rest of a for-of body`() {
        diagnose(
            prelude +
                "function zzzE3(zzzXs: (number | string)[]): void {\n" +
                "  for (const zzzY of zzzXs) {\n" +
                "    if (typeof zzzY !== \"number\") { continue; }\n" +
                "    zzzP1 = zzzY;\n" +
                "  }\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /** POSITIVE — a bare (un-braced) `continue`, and a `break`. */
    @Test
    fun `a bare continue and a break both count as definite exits`() {
        diagnose(
            prelude +
                "function zzzE4(zzzXs: (number | string)[]): void {\n" +
                "  for (const zzzY of zzzXs) {\n" +
                "    if (typeof zzzY !== \"number\") continue;\n" +
                "    zzzP1 = zzzY;\n" +
                "  }\n" +
                "}\n" +
                "function zzzE5(zzzXs: (number | string)[]): void {\n" +
                "  for (const zzzY of zzzXs) {\n" +
                "    if (typeof zzzY !== \"number\") { break; }\n" +
                "    zzzP1 = zzzY;\n" +
                "  }\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * POSITIVE — a `!`-negated guard, which is what [Checker.negateCondition]'s unary arm
     * is for. The real-world shape is `if (!x) return;` over a `T | undefined`
     * (`path.ts:585`), but that one is UNOBSERVABLE today for a reason of its own:
     * `canUseTypeEngine` refuses a nullish union source against a primitive target
     * altogether ((CHK.63)), so the un-narrowed read is silent too and the pin would pass
     * against a binary with this whole leg deleted. The non-nullish form below asks the
     * same question of the same arm — and it is a CONTROL, not a positive, because the
     * `!` arm is **structurally unobservable today**: truthiness narrowing only ever
     * removes `null`/`undefined`, so its subject is a NULLISH union, and such a source is
     * refused wholesale against a PRIMITIVE target by `canUseTypeEngine` ((CHK.63)) —
     * which is the `currentLocalTypes` reader this leg exists for. Against a UNION target
     * (below) round 784's gate lets the ASSIGNMENT reader consult the FLOW walk instead,
     * and that has always handled an early exit. So this passes on the parent binary too,
     * and it starts discriminating the moment (CHK.63) opens.
     *
     * RESIDUE, both recorded as OUR answer rather than tsc's: a PARENTHESISED operand
     * (`!(x === undefined)`) is not reached — neither this helper nor
     * [Checker.extractNullNarrowing] unwraps a `ParenthesizedExpression` — and a
     * type-guard CALL (`if (!isFoo(x)) return`) is REFUSED outright, see
     * `allowCallPredicate`.
     */
    @Test
    fun `control - a negated truthiness guard is exercised but not yet observable`() {
        diagnose(
            prelude +
                "function zzzE6(zzzX: number | undefined): void {\n" +
                "  if (!zzzX) { return; }\n" +
                "  zzzUn = zzzX;\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * POSITIVE, and the VALUE pin — the type NAMED at the assignment reader after an
     * early exit is the narrowed one. Before the fix this read
     * `Type 'string | number' is not assignable to type 'string'`; tsc 7.0.2 says
     * `Type 'number' is not assignable to type 'string'`.
     */
    @Test
    fun `the type named after an early exit is the narrowed one`() {
        val rows = diagnose(
            prelude +
                "function zzzE7(zzzX: number | string): void {\n" +
                "  if (typeof zzzX !== \"number\") { return; }\n" +
                "  zzzQs = zzzX;\n" +
                "}",
        ).filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'number' is not assignable to type 'string'.")
    }

    /**
     * POSITIVE — an assignment BACK to the narrowed reference is legal, because the
     * DECLARED type is recorded. Writing that record into the frame's SHARED
     * `narrowedDeclared` is what added 21 ours-only rows per profile, so the frame takes
     * a copy of its own first ([Checker.CtaFrame.narrowedDeclaredOwned]); dropping the
     * record altogether left 4. tsc 7.0.2 is silent here.
     */
    @Test
    fun `an assignment back to the narrowed reference checks against the declared type`() {
        diagnose(
            prelude +
                "function zzzE8(zzzX: number | string): void {\n" +
                "  if (typeof zzzX !== \"number\") { return; }\n" +
                "  zzzP1 = zzzX;\n" +
                "  zzzX = \"a string again\";\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * POSITIVE — NESTED narrows on one name record the DECLARED type FIRST-WINS. An
     * unconditional write recorded the OUTER narrow's result as if it were the
     * declaration, so `if (b) { if (isNs(b)) { b = undefined } }` was a false TS2322
     * against `ZzzNb`. That is a SHIPPED defect independent of (ii) — it reproduces with
     * no early exit anywhere (`build/chk64/cb`) — and (ii) is what made it REACHABLE on
     * `services/organizeImports.ts:326`. tsc 7.0.2 is silent.
     */
    @Test
    fun `nested narrows on one name record the declared type first-wins`() {
        diagnose(
            prelude +
                "function zzzE9(zzzB: ZzzNb | undefined): void {\n" +
                "  if (zzzB) {\n" +
                "    if (zzzIsNs(zzzB)) {\n" +
                "      zzzB = undefined;\n" +
                "    }\n" +
                "  }\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * POSITIVE — the declared type recorded for the early exit must NOT escape the
     * FUNCTION. `CtaFrame.narrowedDeclared` is shared down the chain with no undo log, so
     * writing into it directly leaks to every later same-named binding in the file: here
     * `zzzLv` would carry `string | number` into `zzzL2`, whose own `zzzLv` is a
     * `string[]`. Measured on the profiles as 21 ours-only rows; this is the fixture that
     * makes it visible to the suite. tsc 7.0.2 is silent.
     */
    @Test
    fun `the recorded declared type does not escape the function`() {
        diagnose(
            prelude +
                "function zzzL1(zzzLv: number | string): void {\n" +
                "  if (typeof zzzLv !== \"number\") { return; }\n" +
                "  zzzP1 = zzzLv;\n" +
                "}\n" +
                "function zzzL2(zzzLv: string[]): void {\n" +
                "  zzzLv = [\"x\"];\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * DOCUMENTED SHAPE, **NOT a discriminating pin** — say so rather than claim it. The
     * negated TYPE-GUARD CALL is refused because on tsc's own sources it narrowed through
     * a GENERIC predicate whose `T` we do not infer and 20 captured spans in
     * `path.ts`/`utilities.ts` then hovered `any`. That effect lives on the CAPTURE
     * channel, and the ablation arm that drops the refusal leaves this suite GREEN: two
     * fixtures were built for it, a non-generic and the generic one below, and under the
     * embedded lib BOTH narrow precisely, so the diagnostic face never appears. The row
     * below fires on either arm; it is here to record the shape and the refusal.
     */
    @Test
    fun `a negated type-guard call - the refusal is capture-only and this does not discriminate`() {
        val rows = diagnose(
            prelude +
                "declare function zzzSome<T>(zzzA: readonly T[] | undefined): zzzA is readonly T[];\n" +
                "function zzzM1(zzzC: readonly string[] | undefined): void {\n" +
                "  if (!zzzSome(zzzC)) { return; }\n" +
                "  const zzzQ: number = zzzC[0];\n" +
                "}",
        ).filter { it.code == 2322 }
        assert(rows.size == 1)
    }

    /**
     * NEGATIVE CONTROL — a then-branch that does NOT exit must not narrow after the
     * `if`. tsc 7.0.2 reports this row.
     */
    @Test
    fun `negative control - a then-branch that does not exit does not narrow after the if`() {
        val rows = diagnose(
            prelude +
                "function zzzEa(zzzX: number | string): void {\n" +
                "  if (typeof zzzX !== \"number\") { zzzQs = \"no exit\"; }\n" +
                "  zzzP1 = zzzX;\n" +
                "}",
        ).filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'string | number' is not assignable to type 'number'.")
    }

    /**
     * POSITIVE **and** LEAK CONTROL, in one assertion: exactly ONE row. The parent binary
     * gives TWO (it also reports the un-narrowed assignment inside the loop), and a fix
     * whose narrowing escaped the loop body would give ZERO. tsc 7.0.2 reports the one.
     */
    @Test
    fun `the narrowing does not escape a for-of body`() {
        val rows = diagnose(
            prelude +
                "function zzzEb(zzzXs: (number | string)[], zzzX: number | string): void {\n" +
                "  for (const zzzY of zzzXs) {\n" +
                "    if (typeof zzzY !== \"number\") { continue; }\n" +
                "    zzzP1 = zzzY;\n" +
                "  }\n" +
                "  zzzQs = zzzX;\n" +
                "}",
        ).filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'string | number' is not assignable to type 'string'.")
    }

    /**
     * WAS A RESIDUE — an `if … else` is still refused by the install (the ELSE may assign
     * to the very reference the negation is about), and the read is nevertheless correct
     * now, through the flow walk. tsc 7.0.2 is SILENT and so are we.
     */
    @Test
    fun `an if with an else narrows after it too`() {
        // Was a RESIDUE pin recording OUR row where tsc 7.0.2 is silent. (CHK.63)
        // closed it: the ASSIGNMENT reader now consults the flow walk for a
        // PRIMITIVE target, and the flow walk has always handled an early exit —
        // it was `canUseTypeEngine`'s nullish-union gate that kept the
        // `currentLocalTypes` answer in play. Confirmed silent under tsgo 7.0.2.
        val rows = diagnose(
            prelude +
                "function zzzEc(zzzX: number | string): void {\n" +
                "  if (typeof zzzX !== \"number\") { return; } else { zzzQs = \"x\"; }\n" +
                "  zzzP1 = zzzX;\n" +
                "}",
        ).filter { it.code == 2322 }
        assert(rows.isEmpty())
    }

    /**
     * WAS A RESIDUE — a `while` body and a plain nested `{ … }` block SHARE their parent
     * frame's `localTypes` map, so the install is still refused there; the read is
     * nevertheless correct now, through the flow walk. tsc 7.0.2 is SILENT on both.
     */
    @Test
    fun `a while body and a plain nested block are reached too`() {
        // The other half of the same residue, and closed the same way — the
        // `localTypes`-sharing install these two shapes refuse is no longer what
        // decides the read, because the ASSIGNMENT reader reaches the flow walk.
        // tsc 7.0.2 is silent on both.
        val rows = diagnose(
            prelude +
                "function zzzEd(zzzXs: (number | string)[]): void {\n" +
                "  let zzzI = 0;\n" +
                "  while (zzzI < 1) {\n" +
                "    const zzzY = zzzXs[zzzI]; zzzI++;\n" +
                "    if (typeof zzzY !== \"number\") { continue; }\n" +
                "    zzzP1 = zzzY;\n" +
                "  }\n" +
                "}\n" +
                "function zzzEe(zzzY: number | string, zzzB: boolean): void {\n" +
                "  if (zzzB) {\n" +
                "    if (typeof zzzY !== \"number\") { return; }\n" +
                "    zzzP1 = zzzY;\n" +
                "  }\n" +
                "}",
        ).filter { it.code == 2322 }
        assert(rows.isEmpty())
    }
}
