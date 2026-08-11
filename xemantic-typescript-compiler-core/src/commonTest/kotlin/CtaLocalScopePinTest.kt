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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (WARM.18b) round 892 — the cta LOCAL family's scope discipline, pinned
 * THROUGH A COMPILE.
 *
 * `ScopeStackTest` pins the mechanism; these pin that the mechanism is wired to
 * the frames the way the whole-map copy was. The failure mode is the one
 * CLAUDE.md names as the reason round 869 stopped short of this family: a wrong
 * scope does not crash, it silently resolves a name to an OUTER binding (the
 * `applyBodyLocalShadowing` FP class), so what has to be observable is BOTH
 * directions of one shadowing —
 *
 * - inside the function body the INNER binding wins (an inner `number` makes a
 *   `string` annotation an error), and
 * - after the body the OUTER binding is back (the same annotation is fine).
 *
 * The second is the one a leaked scope breaks, and it is asserted as an ABSENCE
 * of TS2322, which is only reachable when the pop actually restored the outer
 * entry. The first is what stops the second from passing vacuously: if the
 * family stopped recording anything at all, the inner error would vanish too.
 */
class CtaLocalScopePinTest {

    @Test
    fun `a function body's local shadows the outer binding INSIDE the body`() {
        val d = diagnose(
            """
            let shared: string = "outer";
            function f(): void {
              let shared: number = 1;
              const inner: string = shared;
            }
            """
        )
        assert(d.any { it.code == 2322 })
    }

    @Test
    fun `the outer binding is BACK after the function body closes`() {
        val d = diagnose(
            """
            let shared: string = "outer";
            function f(): void {
              let shared: number = 1;
            }
            const after: string = shared;
            """
        )
        assert(d.none { it.code == 2322 })
    }

    /** Two sibling bodies: the second must not inherit the first's writes. */
    @Test
    fun `a sibling function body does not inherit the previous body's locals`() {
        val d = diagnose(
            """
            let shared: string = "outer";
            function first(): void {
              let shared: number = 1;
            }
            function second(): void {
              const inner: string = shared;
            }
            """
        )
        assert(d.none { it.code == 2322 })
    }

    // ---- the NARROWING frame. Round 891 refused this family partly because
    // the narrowing frame copied `localTypes` into the `EpochMap` family; round
    // 892 converts it onto the SAME stack, so it is a scope like any other and
    // its write must be undone at the `if`'s end. Without these two the arm
    // that makes the narrowing frame SHARE instead of scope reddens nothing —
    // which is round 813's law (a green pin under its own ablation is as often
    // BLIND as the guard is redundant), caught by running the ablation.

    // The shape is `typeof x === "string"` over `string | number` and NOT a
    // nullish one, because this compiler emits nothing at all for
    // `const s: string = <string | undefined>` — a nullish pin here would have
    // been vacuous in BOTH directions. Verified against the un-narrowed control
    // below before either assertion was written (round 777/784: validate a probe
    // shape against a control whose answer you already know).

    /** The control that stops the other two passing vacuously: with no guard at
     *  all the assignment IS an error, so TS2322 is reachable in this position. */
    @Test
    fun `negative control - an un-narrowed union is not assignable`() {
        val d = diagnose(
            """
            function f(x: string | number): void {
              const s: string = x;
            }
            """
        )
        assert(d.any { it.code == 2322 })
    }

    @Test
    fun `a then-branch narrow is visible INSIDE the branch`() {
        val d = diagnose(
            """
            function f(x: string | number): void {
              if (typeof x === "string") {
                const inside: string = x;
              }
            }
            """
        )
        assert(d.none { it.code == 2322 })
    }

    /** The one the narrowing frame's scope exists for: the write it makes is
     *  undone at the pop, so the DECLARED type is back afterwards. Round 892
     *  converts that frame's `EpochMap` copy into a scope on the shared stack;
     *  if it shared instead, this narrow would leak and the error would vanish. */
    @Test
    fun `a then-branch narrow does NOT leak past the if`() {
        val d = diagnose(
            """
            function f(x: string | number): void {
              if (typeof x === "string") {
              }
              const after: string = x;
            }
            """
        )
        assert(d.any { it.code == 2322 })
    }

    /** Nesting: the inner-most write unwinds one level at a time, so the
     *  MIDDLE body's binding — not the file's — is what the outer body sees
     *  again after the nested one closes. */
    @Test
    fun `a nested body unwinds to the enclosing body's binding - not the file's`() {
        val d = diagnose(
            """
            let shared: string = "outer";
            function outerFn(): void {
              let shared: number = 1;
              function innerFn(): void {
                let shared: boolean = true;
              }
              const back: number = shared;
            }
            """
        )
        assert(d.none { it.code == 2322 })
    }
}
