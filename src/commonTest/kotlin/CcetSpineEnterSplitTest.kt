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
 * (JIT.1)(d) round 806 — the behavioural gate for the three-way split of
 * `ccetSpineEnter`.
 *
 * The function was **8,686 bytecodes**, above HotSpot's 8,000-byte
 * `HugeMethodLimit`, so it was never JIT-compiled and ran in the interpreter for
 * the whole process — and it runs at EVERY node of every file. Three of its four
 * `when (node.kindId)` arms are now helpers (`ccetEnterBlock`,
 * `ccetEnterClassDeclaration`, `ccetEnterFunctionLike`); `MODULE_DECLARATION` and
 * the two trailing blocks — which run for every node and are the hot path —
 * stayed in the entry.
 *
 * **What `HugeMethodLimitTest` cannot see.** A size check proves the parts are
 * small. It says nothing about whether the arm is still REACHED, whether the ccet
 * FRAME each arm pushes is still visible to the LATER `ccetSpineEnter` and
 * `ccetSpineLeave` calls that read and pop it, or whether the two trailing
 * blocks still run after an arm has dispatched — which is precisely the mistake
 * an extraction makes when an arm is turned into an early `return`.
 *
 * Every pin observes the frame through a CALL ARGUMENT (TS2345), because the ccet
 * pass's own emission is `checkSingleCallExpressionTypes`, run at a call node's
 * leave under `withCcetFrameAmbient(frame)`. A pin that read a property instead
 * would be answered by the cpa pass and could not discriminate.
 */
class CcetSpineEnterSplitTest {

    private val prelude = "declare function takeN(n: number): void;\n"

    // ------------------------------------------------------------------- arms

    @Test
    fun `Block arm - a function declaration's parameter types reach its body`() {
        val d = diagnose(prelude + "function f(s: string): void { takeN(s); }")
        assert(d.any {
            it.code == 2345 &&
                it.message == "Argument of type 'string' is not assignable to parameter of type 'number'."
        })
    }

    @Test
    fun `Block arm - a method body's this is typed from the enclosing class`() {
        val d = diagnose(
            prelude +
                """
                class C {
                  p: string = "";
                  m(): void { takeN(this.p); }
                }
                """.trimIndent(),
        )
        assert(d.any {
            it.code == 2345 &&
                it.message == "Argument of type 'string' is not assignable to parameter of type 'number'."
        })
    }

    @Test
    fun `Block arm - a constructor's parameter types reach its body`() {
        val d = diagnose(prelude + "class C { constructor(s: string) { takeN(s); } }")
        assert(d.any {
            it.code == 2345 &&
                it.message == "Argument of type 'string' is not assignable to parameter of type 'number'."
        })
    }

    @Test
    fun `ClassDeclaration arm - the resolved super base signature checks a super call`() {
        val d = diagnose(
            """
            class B { constructor(n: number) {} }
            class D extends B { constructor() { super("x"); } }
            """.trimIndent(),
        )
        // The base constructor signature is built ONLY in the ClassDeclaration arm,
        // and it is read at the CONSTRUCTOR BODY's enter — a later, separate
        // `ccetSpineEnter` call. So this pin also crosses the helper boundary.
        assert(d.any {
            it.code == 2345 &&
                it.message == "Argument of type 'string' is not assignable to parameter of type 'number'."
        })
    }

    @Test
    fun `ClassDeclaration arm - the class type-parameter scope reaches a member body`() {
        val d = diagnose(prelude + "class G<T> { m(p: T): void { takeN(p); } }")
        // The message NAMES `T`, which is only possible if the class TP scope the
        // arm installed on its frame survived the helper's return.
        assert(d.any {
            it.code == 2345 &&
                it.message == "Argument of type 'T' is not assignable to parameter of type 'number'."
        })
    }

    @Test
    fun `FunctionLike arm - B246 contextual parameter typing for an arrow`() {
        val d = diagnose(prelude + "const a: (s: string) => void = (s) => { takeN(s); };")
        assert(d.any {
            it.code == 2345 &&
                it.message == "Argument of type 'string' is not assignable to parameter of type 'number'."
        })
    }

    @Test
    fun `FunctionLike arm - B246 contextual parameter typing for a function expression`() {
        val d = diagnose(prelude + "const a: (s: string) => void = function (s) { takeN(s); };")
        assert(d.any {
            it.code == 2345 &&
                it.message == "Argument of type 'string' is not assignable to parameter of type 'number'."
        })
    }

    // ------------------------------------------------------------------ seams

    @Test
    fun `seam - the trailing if-arm type-guard override still runs after the Block arm dispatched`() {
        val d = diagnose(
            prelude +
                """
                interface A { a: string }
                interface Z { z: string }
                declare function isA(x: A | Z): x is A;
                function h(x: A | Z): void { if (isA(x)) { takeN(x); } }
                """.trimIndent(),
        )
        // The then-statement is a Block, so the Block arm dispatches FIRST and the
        // If-arm override runs AFTER it, in the same `ccetSpineEnter` call. An arm
        // extracted as an early `return` would skip the override and the message
        // would name the un-narrowed `A | Z`.
        assert(d.any {
            it.code == 2345 &&
                it.message == "Argument of type 'A' is not assignable to parameter of type 'number'."
        })
        assert(d.none { it.code == 2345 && it.message.contains("'A | Z'") })
    }

    @Test
    fun `seam - a nested function's frame is both pushed by the helper and popped after it`() {
        val d = diagnose(
            prelude +
                """
                function outer(s: string): void {
                  function inner(s: number): void { takeN(s); }
                  takeN(s);
                }
                """.trimIndent(),
        )
        // `inner`'s own `s: number` is legal, and the OUTER `s: string` must be
        // visible again afterwards — so exactly one TS2345, and it is the outer
        // one. A frame that is pushed and never popped makes the outer call legal
        // and the count zero.
        assert(d.count { it.code == 2345 } == 1)
        assert(d.any {
            it.code == 2345 &&
                it.message == "Argument of type 'string' is not assignable to parameter of type 'number'."
        })
    }

    @Test
    fun `seam - an arrow's own un-annotated parameter shadows the outer binding`() {
        val d = diagnose(
            prelude +
                """
                function outer(v: string): void {
                  const cb = (v) => { takeN(v); };
                  takeN(v);
                }
                """.trimIndent(),
        )
        // The FunctionLike arm writes `anyType` for every own binding name, so the
        // inner `v` is `any` and only the outer `v: string` is an error. Without
        // the arm the inner `v` would resolve to the outer `string` and there
        // would be TWO.
        assert(d.count { it.code == 2345 } == 1)
    }
}
