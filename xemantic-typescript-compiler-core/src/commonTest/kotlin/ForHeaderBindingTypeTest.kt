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
 * (P18.119) A `for` STATEMENT HEADER's binding, and a `for…in` binding, have a
 * real type.
 *
 * A `ForStatement`'s initializer is a `VariableDeclarationList` whose parent is
 * the LOOP, not a `VariableStatement` — so every declaration recorder in this
 * checker skipped it, and every `for (let i = 0; …)` index in every program was
 * `any` inside its own loop. Measured against tsgo 7.0.2 over eleven lines, tsgo
 * reported SEVEN rows and this compiler TWO: `for…of` and an ordinary `let` were
 * already right and everything with a `for` header or a `for…in` head was
 * silent, for an ANNOTATED header binding as much as an inferred one.
 *
 * `nums[i]` reading `any` with it is the root cause (KIR.LOWER.3) measured from
 * the other end — a receiver whose type came through an element access is
 * lowered as the dynamic bag.
 *
 * Each positive pin below is graded by a WRONG-TYPED USE ((CHK.46): a receiver
 * that reports nothing is usually typed correctly, so silence grades nothing),
 * and each was proven RED against the pre-change binary.
 */
class ForHeaderBindingTypeTest {

    // ── the `for` header binding ────────────────────────────────────────────

    @Test
    fun `an inferred for header binding has its initializer's widened type`() {
        diagnose(
            """
            const nums: number[] = [1, 2, 3]
            for (let i = 0; i < nums.length; i++) {
                const probe: string = i
                console.log(probe)
            }
            """
        ) should { have(any { it.code == 2322 && "Type 'number' is not assignable to type 'string'" in it.message }) }
    }

    @Test
    fun `an element access through a for header index is the element type`() {
        diagnose(
            """
            const nums: number[] = [1, 2, 3]
            for (let i = 0; i < nums.length; i++) {
                const probe: string = nums[i]
                console.log(probe)
            }
            """
        ) should { have(any { it.code == 2322 && "Type 'number' is not assignable to type 'string'" in it.message }) }
    }

    @Test
    fun `an arithmetic expression over a for header index is the element type`() {
        diagnose(
            """
            const nums: number[] = [1, 2, 3]
            for (let i = 0; i < nums.length; i++) {
                const probe: string = i + 1
                console.log(probe)
            }
            """
        ) should { have(any { it.code == 2322 && "Type 'number' is not assignable to type 'string'" in it.message }) }
    }

    @Test
    fun `a for header binding is not number-specific`() {
        diagnose(
            """
            for (let m = "s"; m.length < 3; m += "x") {
                const probe: number = m
                console.log(probe)
            }
            """
        ) should { have(any { it.code == 2322 && "Type 'string' is not assignable to type 'number'" in it.message }) }
    }

    @Test
    fun `an ANNOTATED for header binding has its annotation`() {
        diagnose(
            """
            for (let j: number = 0; j < 3; j++) {
                const probe: string = j
                console.log(probe)
            }
            """
        ) should { have(any { it.code == 2322 && "Type 'number' is not assignable to type 'string'" in it.message }) }
    }

    @Test
    fun `a var header binding is typed like a let one`() {
        diagnose(
            """
            for (var a = 0; a < 3; a++) {
                const probe: string = a
                console.log(probe)
            }
            """
        ) should { have(any { it.code == 2322 && "Type 'number' is not assignable to type 'string'" in it.message }) }
    }

    @Test
    fun `every declarator of a several-declarator header is typed`() {
        val d = diagnose(
            """
            for (let c1 = 0, c2 = "s"; c1 < 3; c1++) {
                const p1: string = c1
                const p2: number = c2
                console.log(p1, p2)
            }
            """
        )
        d should {
            have(any { it.code == 2322 && "Type 'number' is not assignable to type 'string'" in it.message })
            have(any { it.code == 2322 && "Type 'string' is not assignable to type 'number'" in it.message })
        }
    }

    @Test
    fun `a header binding captured by a closure inside the body is typed`() {
        diagnose(
            """
            for (let d1 = 0; d1 < 3; d1++) {
                const f = () => {
                    const probe: string = d1
                    return probe
                }
                f()
            }
            """
        ) should { have(any { it.code == 2322 && "Type 'number' is not assignable to type 'string'" in it.message }) }
    }

    // ── the `for…in` binding ────────────────────────────────────────────────

    @Test
    fun `a for-in binding over an object literal is string`() {
        diagnose(
            """
            for (const k in { a: 1 }) {
                const probe: number = k
                console.log(probe)
            }
            """
        ) should { have(any { it.code == 2322 && "Type 'string' is not assignable to type 'number'" in it.message }) }
    }

    @Test
    fun `a for-in binding over an ARRAY is string and never string or number`() {
        diagnose(
            """
            const nums: number[] = [1, 2, 3]
            for (const f1 in nums) {
                const probe: number = f1
                console.log(probe)
            }
            """
        ) should { have(any { it.code == 2322 && "Type 'string' is not assignable to type 'number'" in it.message }) }
    }

    @Test
    fun `a for-in binding over a tuple is string`() {
        diagnose(
            """
            const tup: [string, number] = ["a", 1]
            for (const g1 in tup) {
                const probe: number = g1
                console.log(probe)
            }
            """
        ) should { have(any { it.code == 2322 && "Type 'string' is not assignable to type 'number'" in it.message }) }
    }

    @Test
    fun `a for-in binding over a Record is string`() {
        diagnose(
            """
            const rec: Record<string, number> = { a: 1 }
            for (const h1 in rec) {
                const probe: number = h1
                console.log(probe)
            }
            """
        ) should { have(any { it.code == 2322 && "Type 'string' is not assignable to type 'number'" in it.message }) }
    }

    // ── the SCOPE: a header binding may not leak past its loop ──────────────

    @Test
    fun `negative control - a same-named declaration after the loop wins`() {
        // A CONTROL, and recorded as one: it is green against the pre-change binary
        // AND against the unscoped-write arm, because the post-loop declaration
        // overwrites whatever the loop left behind. The pins that DO discriminate a
        // leak are the two shadow ones below, where the outer binding is declared
        // BEFORE the loop and read after it — there the leak has nothing to
        // overwrite it. Kept because the shape is the one a reader reaches for first.
        val d = diagnose(
            """
            const nums: number[] = [1, 2, 3]
            function f(): void {
                for (let i = 0; i < nums.length; i++) {
                    console.log(i)
                }
                const i = "s"
                const after: string = i
                const probe: number = i
                console.log(after, probe)
            }
            f()
            """
        )
        d should {
            have(any { it.code == 2322 && "Type 'string' is not assignable to type 'number'" in it.message })
            have(none { it.code == 2322 && "Type 'number' is not assignable to type 'string'" in it.message })
        }
    }

    @Test
    fun `a for-in binding shadowing an outer binding restores it at the loop's end`() {
        // LOAD-BEARING, and the `for…in` twin of the pin below. `currentLocalTypes`
        // is a FLAT COPY of the enclosing scope, so a binding written without a
        // scope to pop governs every later read of that name in the function. The
        // outer `k` is declared BEFORE the loop, so a leaked `string` has nothing to
        // overwrite it and the post-loop misuse below stops reporting. Measured: RED
        // against an arm that writes the binding into the frame's LIVE map.
        val d = diagnose(
            """
            function f(): void {
                let k = 1
                for (const k in { a: 1 }) {
                    console.log(k)
                }
                const probe: string = k
                console.log(probe)
            }
            f()
            """
        )
        d should {
            have(any { it.code == 2322 && "Type 'number' is not assignable to type 'string'" in it.message })
        }
    }

    @Test
    fun `a header binding shadowing an outer binding restores it at the loop's end`() {
        // LOAD-BEARING — see the `for…in` twin above. Measured: RED against an arm
        // that writes the header binding into the frame's LIVE map instead of
        // registering it for the scoped narrowing frame.
        val d = diagnose(
            """
            function f(): void {
                let n = "s"
                for (let n = 0; n < 3; n++) {
                    console.log(n)
                }
                const probe: number = n
                console.log(probe)
            }
            f()
            """
        )
        d should {
            have(any { it.code == 2322 && "Type 'string' is not assignable to type 'number'" in it.message })
        }
    }

    // ── controls ────────────────────────────────────────────────────────────

    @Test
    fun `negative control - a for-of binding is unchanged`() {
        diagnose(
            """
            const nums: number[] = [1, 2, 3]
            for (const v of nums) {
                const probe: string = v
                console.log(probe)
            }
            """
        ) should { have(any { it.code == 2322 && "Type 'number' is not assignable to type 'string'" in it.message }) }
    }

    @Test
    fun `negative control - an ordinary let is unchanged`() {
        diagnose(
            """
            let j = 0
            const probe: string = j
            console.log(probe)
            """
        ) should { have(any { it.code == 2322 && "Type 'number' is not assignable to type 'string'" in it.message }) }
    }

    @Test
    fun `a BINDING PATTERN header stays any`() {
        // The rule refuses a destructuring head outright rather than guessing.
        // tsgo reports both misuses here; this compiler reports NEITHER, which is
        // a recorded false NEGATIVE, not a licence to widen the rule.
        diagnose(
            """
            for (let [b1, b2] = [1, "s"] as [number, string]; b1 < 3; b1++) {
                const p1: string = b1
                const p2: number = b2
                console.log(p1, p2)
            }
            """
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `a for-in over a TYPE PARAMETER stays any`() {
        // tsc's answer is `Extract<keyof T, string>`, which this checker cannot
        // spell; a refusal keeps the pre-existing `any` rather than reporting the
        // right row carrying the wrong type. A recorded false NEGATIVE.
        diagnose(
            """
            function gen<T extends object>(t: T): void {
                for (const i1 in t) {
                    const probe: number = i1
                    console.log(probe)
                }
            }
            gen({ a: 1 })
            """
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `a header binding with no initializer stays any`() {
        // `for (let k; ;)` — tsgo narrows the evolving `any` to `undefined` here;
        // that is flow analysis over an implicit-any declaration and not this
        // round's mechanism. A recorded false NEGATIVE.
        diagnose(
            """
            for (let k; ; ) {
                const probe: string = k
                console.log(probe)
                break
            }
            """
        ) should { have(none { it.code == 2322 }) }
    }
}
