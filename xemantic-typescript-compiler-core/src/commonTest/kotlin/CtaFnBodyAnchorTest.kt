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
import org.intellij.lang.annotations.Language
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

    private fun countTs2322(@Language("typescript") source: String): Int =
        diagnose(source).count { it.code == 2322 }

    @Test
    fun `top-level fn body var-decl mismatch emits exactly once`() {
        val n = countTs2322("""
            function f() {
                const x: string = 1;
            }
        """)
        assert(n == 1)
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
        assert(n == 1)
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
        assert(n == 1)
    }

    @Test
    fun `async fn return mismatch emits exactly once and Promise unwrap still holds`() {
        val n = countTs2322("""
            async function bad(): Promise<number> {
                return "s";
            }
        """)
        assert(n == 1)
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
        assert(n == 1)
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
        assert(n == 1)
    }

    @Test
    fun `constructor body mismatch emits exactly once`() {
        // (cta-m3g): ctor bodies anchor with the legacy arm's localTypes
        // seeding (this.$prop resolved types + annotated params).
        val n = countTs2322("""
            class C {
                x: number;
                constructor(a: string) {
                    const s: number = a;
                    this.x = 1;
                }
            }
        """)
        assert(n == 1)
    }

    @Test
    fun `setter body param-typed mismatch emits exactly once`() {
        val n = countTs2322("""
            class C {
                set p(v: number) {
                    const s: string = v;
                }
            }
        """)
        assert(n == 1)
    }

    @Test
    fun `getter return checked against paired setter param type exactly once`() {
        // (cta-m3g): the B63.5 bridging — an annotation-less getter's returns
        // check against the paired setter's param annotation.
        val n = countTs2322("""
            class C {
                get p() {
                    return 1;
                }
                set p(v: string) {
                }
            }
        """)
        assert(n == 1)
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
        assert(nStatic == 1)
        val nGeneric = countTs2322("""
            class C<T> {
                m() {
                    const x: string = 1;
                }
            }
        """)
        assert(nGeneric == 1)
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
    fun `switch-clause and bare-position statements emit exactly once`() {
        // (cta-m3h1): nested LIST positions (clauses) and BARE positions
        // (if-then without braces, loop bodies) anchor outside narrowing
        // regions.
        val nClause = countTs2322("""
            declare const k: number;
            function f() {
                switch (k) {
                    case 1:
                        const s: string = 1;
                        break;
                }
            }
        """)
        assert(nClause == 1)
        val nBare = countTs2322("""
            declare const cond: boolean;
            function g(): string {
                if (cond) return 1;
                return "ok";
            }
        """)
        assert(nBare == 1)
        val nLoop = countTs2322("""
            function h() {
                for (let i = 0; i < 3; i++) {
                    const s: string = 1;
                }
            }
        """)
        assert(nLoop == 1)
    }

    @Test
    fun `narrowed then-branch statement emits with the NARROWED display`() {
        // (cta-m3h1/m3i) THE sharp narrowing pin: the then-branch runs under
        // the narrowing state (a = string) — legacy via the wrapper's map
        // copy, the spine via the NARROWING FRAME (localTypes copy + write).
        // A broken frame write would display 'string | undefined'.
        val d = diagnose("""
            function f(a: string | undefined) {
                if (a !== undefined) {
                    const n: number = a;
                }
            }
        """).filter { it.code == 2322 }
        assert(d.size == 1)
        assert(d[0].message.contains("Type 'string' is not assignable"))
    }

    @Test
    fun `nested if inside a narrowed then keeps the narrowing`() {
        // (cta-m3i): the inner If's verdict computes against the NARROWED
        // frame map — the narrowing flows through nested non-narrowing ifs.
        val d = diagnose("""
            declare const cond: boolean;
            function f(a: string | undefined) {
                if (a !== undefined) {
                    if (cond) {
                        const n: number = a;
                    }
                }
            }
        """).filter { it.code == 2322 }
        assert(d.size == 1)
        assert(d[0].message.contains("Type 'string' is not assignable"))
    }

    @Test
    fun `class property initializer mismatch emits exactly once`() {
        // (cta-m3k): the member loop's checkPropertyInitAssignability anchors
        // at the PropertyDeclaration's spine enter with the member-loop
        // ambient (classForThis, class TP decls) installed locally.
        val n = countTs2322("""
            class C {
                p: string = 1;
            }
        """)
        assert(n == 1)
        val nStatic = countTs2322("""
            class D {
                static q: number = "s";
            }
        """)
        assert(nStatic == 1)
    }

    @Test
    fun `narrowing-discarded then-branch recording emits the declaration reader's row once`() {
        // (cta-m3e): the then-branch runs under the legacy narrowing wrapper
        // (recordings discarded — the spine reproduction skips the region), and
        // recordOnly truncates every diagnostic, so the SHAPE of this pin is that
        // the then-branch adds nothing of its own. Its count moved 0 -> 1 when
        // (CHK.63) opened `canUseTypeEngine`'s nullish-union-versus-primitive gate:
        // the `const c: number = a` after the `if` had been a shipped FALSE
        // NEGATIVE, and tsc 7.0.2 reports it at `(3,11)` with exactly this message.
        // The one row is the DECLARATION reader's; the then-branch still adds none.
        val n = countTs2322("""
            function h(a: number | undefined) {
                if (a !== undefined) {
                    const b = a;
                }
                const c: number = a;
            }
        """)
        assert(n == 1)
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
        assert(n == 1)
    }

    private val forInRedeclareBody = """
        for (var x in o) {}
        for (var x = 0; x < 3; x++) {}
    """

    @Test
    fun `for-in numeric-for redeclare walker emits each code exactly once in a fn body`() {
        // (cta-m3l): the B442 body-level walker anchors at the body Block's
        // spine enter; the legacy checkFunctionBody pair skips via the
        // recorded set — each code must appear EXACTLY once (0 = lost to the
        // skip without a spine dispatch; 2 = dispatched without the skip —
        // though B442 also carries its own forInNumForProcessed dedup, so the
        // sharp double-emit signal is the FlatArray pin below).
        val d = diagnose("""
            declare const o: any;
            function f() {
                $forInRedeclareBody
            }
        """)
        assert(d.count { it.code == 2403 } == 1)
        assert(d.count { it.code == 2365 } == 1)
        assert(d.count { it.code == 2356 } == 1)
    }

    @Test
    fun `for-in numeric-for redeclare fires in namespace fn and class method bodies exactly once`() {
        val dNs = diagnose("""
            declare const o: any;
            namespace N {
                export function f() {
                    $forInRedeclareBody
                }
            }
        """)
        assert(dNs.count { it.code == 2403 } == 1)
        val dCls = diagnose("""
            declare const o: any;
            class C {
                m() {
                    $forInRedeclareBody
                }
            }
        """)
        assert(dCls.count { it.code == 2403 } == 1)
    }

    @Test
    fun `negative control - annotated numeric-for decl draws no redeclare diagnostics`() {
        diagnose("""
            declare const o: any;
            function f() {
                for (var x in o) {}
                for (var x: any = 0; x < 3; x++) {}
            }
        """) should {
            have(none { it.code == 2403 || it.code == 2356 })
        }
    }

    @Test
    fun `FlatArray depth-param assignment emits exactly once in fn and method bodies`() {
        // (cta-m3l): the B205 walker has NO internal dedup — a spine dispatch
        // without the legacy skip shows 2 here; a skip without the dispatch
        // shows 0.
        val dFn = diagnose("""
            function f<A, D extends number>(a: FlatArray<A, any>, b: FlatArray<A, D>) {
                b = a;
            }
        """).filter { it.code == 2322 && it.message.contains("FlatArray") }
        assert(dFn.size == 1)
        val dCls = diagnose("""
            class C {
                m<A, D extends number>(a: FlatArray<A, any>, b: FlatArray<A, D>) {
                    b = a;
                }
            }
        """).filter { it.code == 2322 && it.message.contains("FlatArray") }
        assert(dCls.size == 1)
    }

    @Test
    fun `destructuring-from-nullable-union param default emits exactly once`() {
        // (cta-m3m): the checkFunctionBody param-loop emission (16.4ei) anchors
        // in the ctaFnBodyFrame sandwich (before param typing, under the TP
        // scope + flow graph) for eligible bodies; the legacy loop skips via
        // the recorded set. No internal dedup — 2 = anchored without the skip,
        // 0 = skipped without the anchor.
        val dFn = diagnose("""
            declare const r: { a: number } | undefined;
            function f({ a } = r) {
                return a;
            }
        """).filter { it.code == 2339 }
        assert(dFn.size == 1)
        val dCls = diagnose("""
            declare const r: { a: number } | undefined;
            class C {
                m({ a } = r) {
                    return a;
                }
            }
        """).filter { it.code == 2339 }
        assert(dCls.size == 1)
    }

    @Test
    fun `negative control - non-nullable param default draws no destructuring TS2339`() {
        diagnose("""
            declare const r: { a: number };
            function f({ a } = r) {
                return a;
            }
        """) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `arrow param destructuring default inside an anchored statement emits exactly once`() {
        // (cta-m3m) reach control: arrow bodies stay owned by the containing
        // statement's emit-twice — the anchor-time walkFunctionBodiesInExpr run
        // emits, the legacy run truncates at statement granularity. (Must be a
        // BLOCK body — an expression-bodied arrow never routes through
        // checkFunctionBody, so its param defaults draw nothing on any path.)
        val d = diagnose("""
            declare const r: { a: number } | undefined;
            const g = ({ a } = r) => { return a; };
        """).filter { it.code == 2339 }
        assert(d.size == 1)
    }

    @Test
    fun `fn at a bare if-then position stays unreached - legacy parity silence`() {
        // (cta-m3l): the bare InStmt dispatcher has NO FunctionDeclaration arm,
        // so the legacy giant never reaches this body — the ctaM3FnHop landing
        // restriction keeps the spine from anchoring inside it (both the
        // statement anchors and the body-level walkers must stay silent).
        val d = diagnose("""
            declare const cond: boolean;
            declare const o: any;
            if (cond) function f() {
                const x: string = 1;
                $forInRedeclareBody
            }
        """)
        assert(d.none { it.code == 2322 })
        assert(d.none { it.code == 2403 })
    }
}
