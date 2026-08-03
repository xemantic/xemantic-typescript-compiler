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
 * (JIT.1)(h) round 810 — the behavioural gate for the two-way split of
 * `checkReturnAssignabilityCore`.
 *
 * The function was **9,743 bytecodes**, over HotSpot's 8,000-byte
 * `HugeMethodLimit`, so it was never JIT-compiled and ran interpreted for the
 * whole process. Its body is now an entry of 4,052 plus `craGuardWalkers`
 * (3,706) and `craElaborateReturnMismatch` (1,851), each holding one CONTIGUOUS
 * region of the committed [CtaSections] **level-C** partition (round 755's own
 * instrument, already in the source).
 *
 * **Which two regions, and why those.** Level C is a MEASURED partition, so the
 * choice needed no new measurement: `C_ELAB` — the TS2322 elaboration — is
 * **1 reach in a whole compiler self-compile** (the profile has no TS2322), and
 * `C_WALKERS` is the FP-firewall guard cluster the same partition classifies as
 * the dedicated-walker layer. Everything the partition prices as engine work —
 * the SOURCE type (219 ms), flow narrowing (115), `checkConditionalReturnBranches`
 * (46), `canUseTypeEngine` + `checkTypeRelatedTo` (39), the TARGET type (20) —
 * stays inline.
 *
 * **The shape.** `C_WALKERS` is a run of guard blocks each ending in a bare
 * `return`, so it returns `Boolean` (`true` = "a guard fired, the caller must
 * return") and the entry replays it as `if (…) return`; its 20 `return@run`
 * labels never crossed the boundary. `C_ELAB` ended in an UNCONDITIONAL return,
 * so it is `Unit` and its call site returns unconditionally after it.
 *
 * **Cross-boundary values: none** — computed rather than assumed. The only local
 * either region declares that outlives a statement is `effObjTarget`, declared
 * and dead inside `C_WALKERS`; `savedContextual`/`useCtx` (set in `C_CTX`, read
 * in `C_SRCTYPE`) constrain the partition and both rows stay in the entry.
 *
 * **What this class pins that a size check cannot.** `HugeMethodLimitTest` sees
 * the bytecode counts; it cannot see that each helper still RUNS for the shape it
 * owns, nor that a dropped return signal would let the relation emit a SECOND
 * diagnostic at the same return. Each arm pin names the helper it exercises and
 * asserts its distinctive MESSAGE; the seam pins assert a COUNT.
 */
class CraSplitTest {

    // ── craGuardWalkers ───────────────────────────────────────────────────────

    /**
     * The excess-property guard: the walker owns the diagnostic AND the position
     * (the offending KEY, not the `return` keyword).
     *
     * Doubles as the helper's SEAM pin — see the count assertion: with the entry
     * ignoring `craGuardWalkers`' `true`, control reaches the relation, which
     * rejects the same object literal and adds a coarse TS2322 at the `return`.
     */
    @Test
    fun `craGuardWalkers owns the excess-property report for a returned object literal`() {
        val ds = diagnose(
            """
            interface P { a: number }
            function f(): P { return { a: 1, b: 2 }; }
            """
        )
        val ts2353 = ds.filter { it.code == 2353 }
        assert(ts2353.size == 1)
        assert(
            ts2353[0].message ==
                "Object literal may only specify known properties, and 'b' does not exist in type 'P'."
        )
        // the guard SUPPRESSES the coarse whole-object error at the `return`
        assert(ds.none { it.code == 2322 })
    }

    /**
     * B482ext — the per-property TYPE mismatch of a SYNC object-literal return is
     * reported at the KEY with the TS6500 "expected type comes from property"
     * related information, not as a whole-object chain at the `return`.
     */
    @Test
    fun `craGuardWalkers reports a returned object literal's property mismatch at the key`() {
        val ds = diagnose(
            """
            interface P { a: number }
            function f(): P { return { a: "s" }; }
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].message == "Type 'string' is not assignable to type 'number'.")
        assert(ts2322[0].relatedInformation.any { it.code == 6500 })
    }

    /**
     * B491 — the drill into a RETURNED arrow's object-literal body reports the leaf
     * property, not the coarse `() => …` mismatch at the `return`.
     */
    @Test
    fun `craGuardWalkers drills into a returned arrow's object-literal body`() {
        val ds = diagnose(
            """
            interface P { a: number }
            function f(): () => P { return () => ({ a: "s" }); }
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].message == "Type 'string' is not assignable to type 'number'.")
    }

    // ── craElaborateReturnMismatch ────────────────────────────────────────────

    /** The plain engine rejection: message, code and a single emission. */
    @Test
    fun `craElaborateReturnMismatch emits the plain return mismatch`() {
        val ds = diagnose(
            """
            function f(): string { return 1; }
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].message == "Type 'number' is not assignable to type 'string'.")
    }

    /**
     * B69.7 — a LITERAL source is WIDENED for display when the target carries no
     * literal members, so `return true` reports `'boolean'` and not `'true'`. That
     * widening lives inside the moved region.
     */
    @Test
    fun `craElaborateReturnMismatch widens a literal source for display`() {
        val ds = diagnose(
            """
            function f(): string { return true; }
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].message == "Type 'boolean' is not assignable to type 'string'.")
    }

    /**
     * B49.3 — 2+ missing properties are reported as TS2739 rather than the generic
     * TS2322, and that branch is inside the moved region.
     */
    @Test
    fun `craElaborateReturnMismatch emits TS2739 for a source missing two properties`() {
        val ds = diagnose(
            """
            interface P { a: number; b: number }
            declare const src: {};
            function f(): P { return src; }
            """
        )
        assert(ds.count { it.code == 2739 } == 1)
        assert(ds.none { it.code == 2322 })
    }

    /** A UNION source elaborates with its last failing constituent. */
    @Test
    fun `craElaborateReturnMismatch elaborates a union source's failing constituent`() {
        val ds = diagnose(
            """
            declare const u: string | number;
            function f(): string { return u; }
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(
            ts2322[0].messageChain == listOf(
                "  Type 'number' is not assignable to type 'string'."
            )
        )
    }

    /**
     * The TypeParam-TARGET chain (M3.0-gap-3 B1) — the parity line the var-decl and
     * assignment paths carry.
     */
    @Test
    fun `craElaborateReturnMismatch carries the type-parameter target chain`() {
        val ds = diagnose(
            """
            function f<T>(n: number): T { return n; }
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(
            ts2322[0].messageChain == listOf(
                "  'T' could be instantiated with an arbitrary type which could be unrelated to 'number'."
            )
        )
    }

    /**
     * The async early return INSIDE the moved region: a `Promise<T>` annotation
     * accepts `T` directly, so nothing is emitted. A negative control for the
     * helper — it proves the region is entered and that its first block returns.
     */
    @Test
    fun `craElaborateReturnMismatch accepts a value assignable to the awaited form`() {
        val ds = diagnose(
            """
            async function f(): Promise<number> { return 1; }
            """
        )
        assert(ds.none { it.code == 2322 })
    }

    // ── seams and ordering ────────────────────────────────────────────────────

    /**
     * The `C_ELAB` seam: the entry returns unconditionally after the helper, so the
     * LEGACY STRING TAIL (`C_STRTAIL`) never re-checks a return the engine has
     * already rejected. Dropping that `return` makes the string path emit a second
     * TS2322 at the same position.
     */
    @Test
    fun `the elaboration seam emits exactly one diagnostic per rejected return`() {
        val ds = diagnose(
            """
            function f(): string { return 1; }
            function g(): number { return "s"; }
            """
        )
        assert(ds.count { it.code == 2322 } == 2)
        assert(ds.map { it.start }.toSet().size == 2)
    }

    /**
     * `craGuardWalkers` recurses: it drills through NESTED object literals to report
     * the innermost mismatching key. A helper that had been made a leaf by the split
     * would report the coarse whole-object error at the `return` instead.
     */
    @Test
    fun `craGuardWalkers reaches the innermost key of a nested object literal`() {
        val ds = diagnose(
            """
            interface Inner { thing: string }
            interface P { bar: Inner }
            function f(): P { return { bar: { thing: 1 } }; }
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].message == "Type 'number' is not assignable to type 'string'.")
    }

    /**
     * The whole path re-enters itself: the return inside a RETURNED arrow's block
     * body is checked by a nested `checkReturnAssignability`, so the split must not
     * have broken the recursion through either helper.
     */
    @Test
    fun `a return nested inside a returned arrow body is still checked`() {
        val ds = diagnose(
            """
            interface P { a: number }
            function outer(): () => P {
                return () => {
                    return { a: "s" };
                };
            }
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].message == "Type 'string' is not assignable to type 'number'.")
    }

    /**
     * Ordering: the walker cluster runs BEFORE the relation, so when a returned
     * object literal is BOTH excess and mismatched the excess report at the key is
     * what survives — the coarse relation error is never reached.
     */
    @Test
    fun `craGuardWalkers runs before the relation`() {
        val ds = diagnose(
            """
            interface P { a: number }
            function f(): P { return { a: 1, b: "x" }; }
            """
        )
        assert(ds.count { it.code == 2353 } == 1)
        assert(ds.none { it.code == 2322 })
    }
}
