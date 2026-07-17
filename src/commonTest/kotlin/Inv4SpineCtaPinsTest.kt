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
 * INV.4(e) cta pin batch (round 559): reach + state pins for the
 * checkTypeAssignability giant before its spine migration — the FIRST giant
 * to migrate per the (g1c) design (checkPropertyAccess consumes its
 * currentLocalTypes residue, so cta must go first). Covers the two-dispatcher
 * reach (checkTypeAssignabilityInStatements handles VariableStatement INLINE;
 * checkTypeAssignabilityInStmt owns if/for/while/try BODIES), the
 * varTypes-per-block copy discipline, returnType nearest-function threading,
 * the B201/M1.9 narrowing frame, currentClassForThis, and B212 TP threading.
 */
class Inv4SpineCtaPinsTest {

    // ── basic TS2322 reach across both dispatchers ──────────────────────────

    @Test
    fun `var-decl mismatches fire in every statement context`() {
        val d = diagnose("""
            declare const c: boolean;
            const a: number = "s1";
            { const b: number = "s2"; }
            if (c) { const e: number = "s3"; } else { const f: number = "s4"; }
            while (c) { const g: number = "s5"; break; }
            do { const h: number = "s6"; break; } while (c);
            for (;;) { const i: number = "s7"; break; }
            function fn() { const j: number = "s8"; }
            class K { m() { const k: number = "s9"; } }
            namespace NS { const l: number = "s10"; }
        """)
        kotlin.test.assertEquals(10, d.count { it.code == 2322 }, "expected 10 TS2322, got: ${d.filter { it.code == 2322 }}")
    }

    @Test
    fun `switch clause bodies and try-catch-finally blocks are reached`() {
        val d = diagnose("""
            declare const n: number;
            switch (n) {
                case 1: { const a: number = "s1"; break; }
                default: { const b: number = "s2"; break; }
            }
            try { const e: number = "s3"; } catch (x) { const f: number = "s4"; } finally { const g: number = "s5"; }
        """)
        kotlin.test.assertEquals(5, d.count { it.code == 2322 }, "expected 5 TS2322, got: ${d.filter { it.code == 2322 }}")
    }

    @Test
    fun `assignment expressions fire at statement level and nested in var-decl initializers`() {
        // B127: `const x = a = b` also validates the inner assignment.
        val d = diagnose("""
            declare let a: number;
            a = "s1";
            const x = a = ("s2" as any as string);
        """)
        kotlin.test.assertEquals(2, d.count { it.code == 2322 }, "expected 2 TS2322, got: ${d.filter { it.code == 2322 }}")
    }

    // ── returnType nearest-function threading ───────────────────────────────

    @Test
    fun `return mismatches check against the NEAREST enclosing annotation`() {
        val d = diagnose("""
            function outer(): number {
                function inner(): string {
                    return 42;
                }
                return inner();
            }
        """)
        // only inner's `return 42` mismatches (string); outer's return is fine.
        kotlin.test.assertEquals(1, d.count { it.code == 2322 }, "expected 1 TS2322, got: ${d.filter { it.code == 2322 }}")
        d should { have(any { it.code == 2322 && "'string'" in it.message }) }
    }

    @Test
    fun `negative control - returns in an unannotated function draw nothing`() {
        diagnose("""
            function f() {
                return 42;
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    // ── the B201 and M1.9 narrowing frame ───────────────────────────────────

    @Test
    fun `null-narrowing then-branch checks assignment targets against the DECLARED type`() {
        // M1.9: inside `if (x !== undefined)`, reads narrow but the WRITE
        // target keeps the declared `string | undefined` — `x = undefined`
        // must not FP.
        diagnose("""
            function f(x: string | undefined) {
                if (x !== undefined) {
                    x = undefined;
                }
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    // ── class this discipline ───────────────────────────────────────────────

    @Test
    fun `method bodies resolve this-property writes through the class member table`() {
        // B85.1b: class property types are pushed into varTypes for method
        // bodies, so a mismatched `this.p = <string>` fires.
        val d = diagnose("""
            class K {
                p: number = 1;
                m() { this.p = "s"; }
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2322 }, "expected 1 TS2322, got: $d")
    }

    // ── B212 type-param threading into nested generics ──────────────────────

    @Test
    fun `nested generic functions keep the enclosing type params in scope`() {
        // B212: the enclosing fn's TP must survive into the nested fn's
        // typeParams set so the bare-TP-vs-different-TP return mismatch
        // fires (`return t: Top` against target `T` — without the threading
        // `Top` is not recognized as a TP and the check bails).
        val d = diagnose("""
            function outer<Top>(t: Top) {
                function inner<T>(m: T): T {
                    return t;
                }
                return inner;
            }
        """)
        d should { have(any { it.code == 2322 }) }
    }

    // ── function-body walk in expression positions ──────────────────────────

    @Test
    fun `fn-expr and arrow bodies inside initializers and call args are walked`() {
        val d = diagnose("""
            const f = function () { const a: number = "s1"; };
            const g = () => { const b: number = "s2"; };
            declare function run(cb: () => void): void;
            run(() => { const c: number = "s3"; });
        """)
        kotlin.test.assertEquals(3, d.count { it.code == 2322 }, "expected 3 TS2322, got: ${d.filter { it.code == 2322 }}")
    }

    @Test
    fun `annotated fn-type var contextually types the initializer params`() {
        // B183: `var x: (...y: string[]) => void = function (...y) { … }` —
        // `y` is string[] inside the body, so a numeric use fires.
        val d = diagnose("""
            var x: (...y: string[]) => void = function (...y) {
                const n: number = y;
            };
        """)
        d should { have(any { it.code == 2322 }) }
    }
}
