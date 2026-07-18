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
 * (cta-m3d) round 570: fn-body-DIRECT statements of eligible
 * FunctionDeclaration chains emit from the spine anchor while the legacy arm
 * truncates its duplicates — the emit-twice-suppress-legacy contract requires
 * every diagnostic to appear EXACTLY ONCE (a gate mismatch shows as 0 — lost
 * to truncation without an anchor — or 2 — anchored without truncation).
 * Non-eligible chains (class methods, if-nesting, arrows) stay legacy-owned
 * and must also emit exactly once.
 */
class CtaFnBodyAnchorTest {

    private fun countTs2322(source: String): Int =
        diagnose(source).count { it.code == 2322 }

    @Test
    fun `top-level fn body var-decl mismatch emits exactly once`() {
        val n = countTs2322("""
            function f() {
                const x: string = 1;
            }
        """)
        assert(n == 1) { "expected exactly 1 TS2322, got $n" }
    }

    @Test
    fun `namespace-nested fn body var-decl mismatch emits exactly once`() {
        val n = countTs2322("""
            namespace N {
                export function f() {
                    const x: string = 1;
                }
            }
        """)
        assert(n == 1) { "expected exactly 1 TS2322, got $n" }
    }

    @Test
    fun `fn-in-fn body var-decl mismatch emits exactly once`() {
        val n = countTs2322("""
            function outer() {
                function inner() {
                    const x: string = 1;
                }
            }
        """)
        assert(n == 1) { "expected exactly 1 TS2322, got $n" }
    }

    @Test
    fun `async fn return mismatch emits exactly once and Promise unwrap still holds`() {
        val n = countTs2322("""
            async function bad(): Promise<number> {
                return "s";
            }
        """)
        assert(n == 1) { "expected exactly 1 TS2322, got $n" }
        diagnose("""
            async function good(): Promise<number> {
                return 1;
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `class method body mismatch emits exactly once`() {
        // (cta-m3f): method bodies of ClassDeclarations are now anchored.
        val n = countTs2322("""
            class C {
                m() {
                    const x: string = 1;
                }
            }
        """)
        assert(n == 1) { "expected exactly 1 TS2322, got $n" }
    }

    @Test
    fun `instance method void-this-call check keeps firing exactly once`() {
        // (cta-m3f): the B101 tryEmitVoidThisMethodToPrimitiveVar consults
        // currentClassForThis — the anchor must thread frame.classForThis or
        // this emission silently dies (reads as 0). The method must be
        // return-ANNOTATION-FREE (the B101 gate bails on `m.type != null`;
        // an explicit `: void` disqualifies — probe-verified round 571b).
        val n = countTs2322("""
            class C {
                v() {}
                m() {
                    var x: number = this.v();
                }
            }
        """)
        assert(n == 1) { "expected exactly 1 TS2322 (B101 void-this), got $n" }
    }

    @Test
    fun `static and generic-class method bodies emit exactly once`() {
        val nStatic = countTs2322("""
            class C {
                static m() {
                    const x: string = 1;
                }
            }
        """)
        assert(nStatic == 1) { "static: expected exactly 1 TS2322, got $nStatic" }
        val nGeneric = countTs2322("""
            class C<T> {
                m() {
                    const x: string = 1;
                }
            }
        """)
        assert(nGeneric == 1) { "generic: expected exactly 1 TS2322, got $nGeneric" }
    }

    @Test
    fun `switch-clause recording feeds a later anchored statement's narrowing`() {
        // (cta-m3e): the clause's `lit` recording must reach the fn-body frame
        // (the legacy leak) so `end`'s assignment-reduction narrowing survives
        // to the anchored final statement — the round-570 barrel shape,
        // single-file. A missing recording reads `end` as number | undefined
        // → FP TS2322.
        diagnose("""
            declare function pick(): { end: number };
            function g(k: number): number {
                let end: number | undefined;
                switch (k) {
                    case 1:
                        const lit = pick();
                        end = lit.end;
                        break;
                    default:
                        end = 0;
                        break;
                }
                return end;
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `narrowing-discarded then-branch recording adds no emissions`() {
        // (cta-m3e) negative control: the then-branch runs under the legacy
        // narrowing wrapper (recordings discarded — the spine reproduction
        // skips the region), and recordOnly truncates every diagnostic — the
        // shape must stay exactly as silent as it is on the legacy path (the
        // param-source nullish var-decl is currently unchecked; the discard
        // rule's behavioral pins are the corpus narrowing shapes).
        val n = countTs2322("""
            function h(a: number | undefined) {
                if (a !== undefined) {
                    const b = a;
                }
                const c: number = a;
            }
        """)
        assert(n == 0) { "expected 0 TS2322 (legacy-parity silence), got $n" }
    }

    @Test
    fun `if-nested statement inside fn body stays legacy-owned and emits exactly once`() {
        val n = countTs2322("""
            declare const cond: boolean;
            function f() {
                if (cond) {
                    const x: string = 1;
                }
            }
        """)
        assert(n == 1) { "expected exactly 1 TS2322, got $n" }
    }
}
