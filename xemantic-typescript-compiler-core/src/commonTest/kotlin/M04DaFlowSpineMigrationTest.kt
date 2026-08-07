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
 * (M0.4, round 629): pins for the checkDefiniteAssignmentViaFlowGraph
 * (flow-graph TS2454 + the B187 nullable-union-loop-arg TS2345) spine
 * migration — the FILE-END dispatch variant: the per-file body (positional
 * TS2454 dedup scan + the two whole-file flow walks) runs in checkSpine's
 * per-file loop after spineWalkFile returns, so the dedup scan sees every
 * spine-emitted TS2454 for that file (the set-based walker 5 + the
 * spineUbd co-emissions). Pins the emission shapes (if/try/finally-body
 * reads, top-level statements, namespace/class/arrow recursion), the
 * positional dedup (exactly ONE TS2454 where the set pass overlaps), the
 * suppressions (pre-read assignment, closure assignment, loop-var
 * shadowing, catch-body skip, non-null assert, `| undefined` annotation,
 * explicit non-strict), the B187 TS2345 both directions, and the B223
 * try/catch sibling (stays at its own pass slot). All expectations
 * verified green against the pre-migration legacy pass first.
 */
class M04DaFlowSpineMigrationTest {

    @Test
    fun `read of an uninitialized let inside an if body fires TS2454`() {
        diagnose(
            """
            function f(c: boolean) {
                let x: number
                if (c) { const y = x + 1 }
            }
            """
        ) should {
            have(any { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - assignment before the if suppresses the flow TS2454`() {
        diagnose(
            """
            function f(c: boolean) {
                let x: number
                x = 1
                if (c) { const y = x + 1 }
            }
            """
        ) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `a try-block read overlapping the set pass draws exactly one TS2454`() {
        val count = diagnose(
            """
            function f() {
                let x: number
                try { const y = x + 1 } finally {}
            }
            """
        ).count { it.code == 2454 }
        assert(count == 1)
    }

    @Test
    fun `file-level uninitialized var read inside an if body fires TS2454`() {
        diagnose(
            """
            declare const c: boolean
            let x: number
            if (c) { const y = x + 1 }
            """
        ) should {
            have(any { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - a closure assignment suppresses the flow TS2454`() {
        diagnose(
            """
            function f(c: boolean) {
                let x: number
                function g() { x = 1 }
                if (c) { const y = x + 1 }
                g()
            }
            """
        ) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - a for-of loop variable shadows the outer uninitialized name`() {
        diagnose(
            """
            function f(items: string[]) {
                let v: string
                for (const v of items) { const y = v + "a" }
            }
            """
        ) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - a read inside a catch body draws no flow TS2454`() {
        diagnose(
            """
            function f() {
                let x: number
                try { x = 1 } catch (e) { const y = x + 1 }
            }
            """
        ) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - a non-null-asserted read assumes initialized`() {
        diagnose(
            """
            function f(c: boolean) {
                let x: number
                if (c) { const y = x! + 1 }
            }
            """
        ) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `a read inside a finally block fires TS2454`() {
        diagnose(
            """
            function f() {
                let x: number
                try {} finally { const y = x + 1 }
            }
            """
        ) should {
            have(any { it.code == 2454 })
        }
    }

    @Test
    fun `a read inside a namespace function if body fires TS2454`() {
        diagnose(
            """
            namespace N {
                export function f(c: boolean) {
                    let x: number
                    if (c) { const y = x + 1 }
                }
            }
            """
        ) should {
            have(any { it.code == 2454 })
        }
    }

    @Test
    fun `a read inside a class method if body fires TS2454`() {
        diagnose(
            """
            class C {
                m(c: boolean) {
                    let x: number
                    if (c) { const y = x + 1 }
                }
            }
            """
        ) should {
            have(any { it.code == 2454 })
        }
    }

    @Test
    fun `a read inside an arrow block body fires TS2454`() {
        // Legacy quirk (pinned): the enclosing function's statement walk — the
        // only route into an arrow-initializer body — runs ONLY when the
        // enclosing function itself has >=1 uninit candidate (`outer` here).
        diagnose(
            """
            function f(c: boolean) {
                let outer: number
                const g = () => {
                    let x: number
                    if (c) { const y = x + 1 }
                }
                g()
            }
            """
        ) should {
            have(any { it.code == 2454 && it.message.contains("'x'") })
        }
    }

    @Test
    fun `B187 - a possibly-unassigned nullable-union loop argument fires TS2345`() {
        diagnose(
            """
            declare function use(p: number): void
            function f(c: boolean) {
                let x: number | undefined
                while (c) { use(x) }
                x = 1
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `B187 negative control - an assignment before the loop suppresses the TS2345`() {
        diagnose(
            """
            declare function use(p: number): void
            function f(c: boolean) {
                let x: number | undefined
                x = 1
                while (c) { use(x) }
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a type including undefined needs no definite assignment`() {
        diagnose(
            """
            function f(c: boolean) {
                let x: number | undefined
                if (c) { const y = x }
            }
            """
        ) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - explicit strict false disables the flow TS2454`() {
        diagnose(
            """
            function f(c: boolean) {
                let x: number
                if (c) { const y = x + 1 }
            }
            """,
            directives = "// @strict: false",
        ) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `B223 sibling - a try-only-assigned var read after the try fires TS2454`() {
        diagnose(
            """
            function f() {
                try { var x = 1 } catch (e) {}
                const y = x + 1
            }
            """
        ) should {
            have(any { it.code == 2454 })
        }
    }
}
