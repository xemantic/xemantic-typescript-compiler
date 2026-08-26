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
 * (CHK.54) OVERLOAD SELECTION IGNORED THE WEAK-TYPE RULE, SO AN ALL-OPTIONAL
 * PARAMETER ACCEPTED **ANY** ARGUMENT — `readFileSync(p, 'utf8')` SELECTED THE
 * `Buffer`-RETURNING OVERLOAD. Five of `knip`'s rows were that one call shape.
 *
 * ## What the measurement says, and what the queue item got wrong
 *
 * The queue item read the defect as "an OPTIONAL-parameter overload is selected
 * without checking the argument". Measured over a 14-row overload matrix against
 * tsc 7.0.2, optionality is NOT the axis and the argument IS checked: making the
 * same parameter non-optional reproduces the defect identically, and the plain
 * shape `(x: string, y?: null)` / `(x: string, y: "u")` called with `("a", "u")`
 * already selected the SECOND overload correctly before this round. What actually
 * decides it is that the first overload's parameter is a **weak type** — an object
 * whose every property is optional, with no call/construct/index signature — and
 * our relation says a string literal IS assignable to one.
 *
 * That is true of the relation on purpose: the weak-type rule lives in the B482
 * walkers ([Checker.tryEmitWeakTypeAssignment] & co), which emit TS2559/TS2560 at a
 * handful of named positions, where tsc puts the check inside `checkTypeRelatedTo`
 * so every consumer inherits it. [Checker.signatureAcceptsArgs] is a consumer that
 * did not, and [Checker.weakParamRefusesArg] is the verdict it now asks.
 *
 * ## What is NOT closed, and is deliberately not pinned here
 *
 * The TS2769 *diagnostic* path ([Checker.allArgumentsMatch]) still carries the same
 * hole — `zU(123)` against a weak-parameter overload set is silent where tsc says
 * TS2769 — and the object-literal half of the `readFileSync` family
 * (`readFileSync(p, { encoding: 'utf8' })`) is a different mechanism again. Both are
 * queued as (CHK.55); round 765's law forbids pinning a known-open gap as a control.
 */
class OverloadWeakParamSelectionTest {

    /**
     * The `readFileSync` shape, reduced. Overload 1's parameter
     * `{ zzzEnc?: null; zzzFlag?: string } | null` is weak and shares no property
     * with `"utf8"`, so tsc selects overload 2 and the call is a `string`.
     *
     * Asserted as a VALUE, not as silence: the write probe's target is `ZBuf`, so a
     * correctly-selected overload MUST produce `Type 'string' is not assignable to
     * type 'ZBuf'` — which the pre-fix binary cannot, because it selected `ZBuf` and
     * the assignment was silent. tsc 7.0.2 reports exactly this row.
     */
    @Test
    fun `a weak union parameter does not select its overload for a string-literal argument`() {
        val d = diagnose(OVERLOADS + """
            const zzzOut: ZBuf = zzzRead("f", "utf8")
        """)
        assert(d.map { it.code to it.message } == listOf(
            2322 to "Type 'string' is not assignable to type 'ZBuf'."
        ))
    }

    /** The other end of the same selection: the result really is a `string`. */
    @Test
    fun `the string-returning overload is the one selected`() {
        val d = diagnose(OVERLOADS + """
            const zzzOut: string = zzzRead("f", "utf8")
        """)
        assert(d.isEmpty())
    }

    /**
     * The weak parameter need not be a union constituent — a bare weak object
     * parameter has the same hole, and tsc refuses it the same way.
     */
    @Test
    fun `a bare weak parameter does not select its overload either`() {
        val d = diagnose("""
            type ZzzEnc = "utf8" | "ascii"
            interface ZBuf { zzzB: number }
            declare function zzzW(o: { zzzA?: null; zzzB2?: string }): ZBuf
            declare function zzzW(o: ZzzEnc): string
            const zzzOut: ZBuf = zzzW("utf8")
        """)
        assert(d.map { it.code to it.message } == listOf(
            2322 to "Type 'string' is not assignable to type 'ZBuf'."
        ))
    }

    /**
     * REFUSAL. Sharing ONE property name with the weak parameter is all tsc asks,
     * so the weak overload is still selected. This is the boundary the rule is drawn
     * at; widening the refusal to "the argument must be assignable" takes it.
     *
     * THE WEAK OVERLOAD IS DECLARED **SECOND**, AND THAT IS LOAD-BEARING FOR THE PIN.
     * [Checker.resolveCallOverload] falls back to `arityMatches[0]` when nothing
     * accepts, so refusing a FIRST-declared overload restores exactly the answer the
     * refusal was supposed to remove — the first draft of this pin put the weak
     * overload first and read 0 RED against an ablation that demonstrably changed the
     * selection. Ordered this way, a wrong refusal picks the `string` overload and the
     * probe moves.
     */
    @Test
    fun `an argument sharing a property with the weak parameter still selects it`() {
        val d = diagnose("""
            interface ZBuf { zzzB: number }
            declare function zzzP(o: { zzzQ: 0 }): string
            declare function zzzP(o: { zzzA?: null; zzzFlag?: string }): ZBuf
            const zzzOut: string = zzzP({ zzzFlag: "r" })
        """)
        assert(d.map { it.code to it.message } == listOf(
            2322 to "Type 'ZBuf' is not assignable to type 'string'."
        ))
    }

    /**
     * REFUSAL. An EMPTY object literal is vacuously assignable to an all-optional
     * target and tsc emits nothing — the same guard [Checker.tryEmitWeakTypeAssignment]
     * already carried. Weak overload declared second, for the reason above.
     */
    @Test
    fun `an empty object literal still selects the weak overload`() {
        val d = diagnose("""
            type ZzzEnc = "utf8" | "ascii"
            interface ZBuf { zzzB: number }
            declare function zzzE(o: ZzzEnc): string
            declare function zzzE(o: { zzzA?: null; zzzFlag?: string }): ZBuf
            const zzzOut: string = zzzE({})
        """)
        assert(d.map { it.code to it.message } == listOf(
            2322 to "Type 'ZBuf' is not assignable to type 'string'."
        ))
    }

    /**
     * REFUSAL. A union parameter whose OTHER constituent accepts the argument is not
     * refused, however disjoint its weak constituent is — tsc's `typeRelatedToSomeType`
     * asks each constituent and one acceptance is enough. Weak-carrying overload
     * declared second, for the reason above.
     */
    @Test
    fun `a non-weak constituent that accepts cancels the refusal`() {
        val d = diagnose("""
            interface ZBuf { zzzB: number }
            declare function zzzM(o: number): string
            declare function zzzM(o: { zzzA?: 1 } | string): ZBuf
            const zzzOut: string = zzzM("x")
        """)
        assert(d.map { it.code to it.message } == listOf(
            2322 to "Type 'ZBuf' is not assignable to type 'string'."
        ))
    }

    /**
     * CONTROL — green before and after. Only overload 1 matches by ARITY, and
     * [Checker.resolveCallOverload] returns a single arity match without asking any
     * type question at all, so the weak rule is never consulted.
     */
    @Test
    fun `a call with no options argument still selects the weak overload`() {
        val d = diagnose(OVERLOADS + """
            const zzzOut: ZBuf = zzzRead("f")
        """)
        assert(d.isEmpty())
    }

    /**
     * CONTROL — green before and after. When two overloads both accept, the FIRST
     * declared still wins; the refusal must not become a preference order.
     */
    @Test
    fun `declaration order still decides when both overloads accept`() {
        val d = diagnose("""
            interface ZBuf { zzzB: number }
            declare function zzzO(x: string): ZBuf
            declare function zzzO(x: string): string
            const zzzOut: string = zzzO("a")
        """)
        assert(d.map { it.code to it.message } == listOf(
            2322 to "Type 'ZBuf' is not assignable to type 'string'."
        ))
    }

    private companion object {
        /**
         * `@types/node`'s `readFileSync`, reduced to the three signatures and the
         * two shapes that matter. Overload 1's parameter is the weak one.
         */
        val OVERLOADS = """
            type ZzzEnc = "utf8" | "ascii"
            interface ZBuf { zzzB: number }
            declare function zzzRead(p: string, o?: { zzzEnc?: null; zzzFlag?: string } | null): ZBuf
            declare function zzzRead(p: string, o: { zzzEnc: ZzzEnc } | ZzzEnc): string
            declare function zzzRead(p: string, o?: { zzzEnc?: ZzzEnc | null } | ZzzEnc | null): string | ZBuf
        """.trimIndent() + "\n"
    }
}
