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
import kotlin.test.Test

/**
 * (NARROW.1) round 785 — a type-guard CALL in an if-condition records its narrowing
 * into `currentLocalTypes`, so the whole then-branch sees the narrowed type as the
 * PRIMARY type of the reference.
 *
 * Before this arm, `extractNullNarrowing` had only nullish / `typeof` / truthiness arms,
 * so NO type-predicate call ever reached that map: inside `if (isFoo(x)) { … }` every
 * consumer of `getTypeOfExpression` still saw `x`'s DECLARED type, and only the opt-in
 * `getNarrowedTypeForReference` sites (the var-decl initializer among them) saw the
 * guard at all. The two consumers that had no such opt-in — a RETURN statement and an
 * ASSIGNMENT inside the branch — therefore emitted a TS2322 FALSE POSITIVE on code tsc
 * accepts. That is what the positive pins here are: silence where there used to be a
 * wrong error.
 *
 * The verdict is delegated wholesale to `narrowByCallPredicate`, so this arm cannot
 * disagree with the flow walker about what a guard means; it only decides WHERE the
 * answer is recorded. Unlike a second chance it moves the primary type in BOTH
 * directions, which is why the controls below matter as much as the positives.
 *
 * DISCRIMINATION, measured against an ablated binary (the round-785 HEAD build, i.e.
 * `NARROW1_CALL_PREDICATE` absent; all shapes re-run side by side through the
 * scratch-project CLI): **7 of the 11 pins fail ablated and pass fixed** — the return,
 * the assignment, the sharpened message, the nested pair, the second-parameter subject,
 * the method-call guard, and the no-leak control (whose INSIDE-branch error disappears
 * while its after-branch error stays, so the pin moves in exactly one of its two
 * positions). The remaining four hold on BOTH sides on purpose and are documented as
 * such: they are the "must NOT narrow" direction, and a pin that changed there would be
 * reporting a bug, not a fix.
 */
class GuardCallNarrowsLocalTypesTest {

    private val prelude = """
        declare function isStr(x: string | number): x is string;
        declare function plain(x: string | number): boolean;
        declare function isSecond(a: number, b: string | number): b is string;
        interface Holder { check(v: string | number): v is string; }
        declare const h: Holder;
    """.trimIndent() + "\n"

    /**
     * DISCRIMINATES — fires ablated, silent fixed. A RETURN statement inside the guarded
     * branch is the plainest consumer with no `getNarrowedTypeForReference` opt-in.
     */
    @Test
    fun `a return inside a type-guard branch sees the narrowed type`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(x: string | number): string {
                    if (isStr(x)) { return x; }
                    return "";
                }
                """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /**
     * DISCRIMINATES — fires ablated, silent fixed. The assignment TARGET still checks
     * against the declared type (`narrowedDeclaredTypes`); it is the assigned VALUE that
     * needed the narrow.
     */
    @Test
    fun `an assignment inside a type-guard branch sees the narrowed type`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(x: string | number): string {
                    let s = "";
                    if (isStr(x)) { s = x; }
                    return s;
                }
                """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /**
     * DISCRIMINATES on the MESSAGE — the error must still fire (the narrowed `string` is
     * genuinely not a `number`), but it now names `'string'` where the ablated build
     * named the un-narrowed `'string | number'`. This is the pin that proves the recorded
     * type is really the guard's target and not merely "an error went away".
     */
    @Test
    fun `a narrowed reference still errors against an unrelated target and names the narrowed type`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(x: string | number): number {
                    if (isStr(x)) { return x; }
                    return 0;
                }
                """.trimIndent()
        )
        val d = diagnostics.filter { it.code == 2322 }
        assert(d.size == 1)
        assert(d[0].message == "Type 'string' is not assignable to type 'number'.")
    }

    /**
     * DISCRIMINATES — fires TWICE ablated, silent fixed. Nested guards must each record
     * into their own frame, and the inner frame must not lose the outer one's narrowing.
     */
    @Test
    fun `nested type-guard branches each narrow their own subject`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(x: string | number, y: string | number): string {
                    if (isStr(x)) {
                        if (isStr(y)) { return y; }
                        return x;
                    }
                    return "";
                }
                """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /**
     * DISCRIMINATES — fires ablated, silent fixed. The subject is the guard's SECOND
     * parameter, so the predicate's parameter NAME (not the argument position of the
     * first identifier argument) has to pick the argument out.
     */
    @Test
    fun `a guard whose subject is its second parameter narrows that argument`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(x: string | number): string {
                    if (isSecond(1, x)) { return x; }
                    return "";
                }
                """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /**
     * DISCRIMINATES — fires ablated, silent fixed. A METHOD-call guard resolves through
     * the PropertyAccess callee path rather than a bare declaration lookup.
     */
    @Test
    fun `a method-call type guard narrows its argument`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(x: string | number): string {
                    if (h.check(x)) { return x; }
                    return "";
                }
                """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /**
     * DISCRIMINATES, and it is the containment control — ablated this shape fires TWICE
     * (inside the branch and after it), fixed it fires exactly ONCE. The narrowing frame
     * must be popped at the end of the then-branch: a leak would silence the second
     * return too, which is the failure mode a pure silence pin could not see.
     */
    @Test
    fun `the guard narrowing does not leak past the then-branch`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(x: string | number): string {
                    if (isStr(x)) { return x; }
                    return x;
                }
                """.trimIndent()
        )
        val d = diagnostics.filter { it.code == 2322 }
        assert(d.size == 1)
        assert(d[0].message == "Type 'string | number' is not assignable to type 'string'.")
    }

    /**
     * HOLDS ON BOTH SIDES ON PURPOSE — the "must NOT narrow" direction. A call whose
     * return type is a plain `boolean` carries no predicate, so nothing may be recorded.
     */
    @Test
    fun `negative control - a non-guard call narrows nothing`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(x: string | number): string {
                    if (plain(x)) { return x; }
                    return "";
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2322 })
    }

    /**
     * HOLDS ON BOTH SIDES ON PURPOSE — a guard narrows its own subject and nothing else.
     */
    @Test
    fun `negative control - a guard does not narrow an unrelated reference`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(x: string | number, y: string | number): string {
                    if (isStr(x)) { return y; }
                    return "";
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2322 })
    }

    /**
     * HOLDS ON BOTH SIDES ON PURPOSE — the arm feeds the THEN-branch frame only, so the
     * else branch must not receive the positive narrowing. (That the else branch does not
     * yet receive the SUBTRACTIVE narrowing either is a separate, still-open gap, and is
     * deliberately not pinned here — only that an error must still fire, which is also
     * tsc's verdict.)
     */
    @Test
    fun `negative control - the else branch does not get the positive narrowing`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(x: string | number): string {
                    if (isStr(x)) { return ""; } else { return x; }
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2322 })
    }

    /**
     * HOLDS ON BOTH SIDES ON PURPOSE — the predicate names `b`, so passing a DIFFERENT
     * reference at the guarded position must leave the other one alone.
     */
    @Test
    fun `negative control - a guard on one argument leaves the other argument alone`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(x: string | number, y: string | number): string {
                    if (isStr(y)) { return x; }
                    return "";
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2322 })
    }
}
