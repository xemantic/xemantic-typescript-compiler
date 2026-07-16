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
 * INV.4(d) walker 1 (round 530): checkUncalledFunctionsInConditions
 * (TS2774/TS2801) migrated onto the check spine — the recursive
 * walkUncalledChecksInStatement(s)/walkUncalledChecksInExpression walkers and
 * the withUncalledScope/withUncalledBlockScope push/pop pair are DELETED.
 * Reach is reproduced by a memoized boolean ancestor-chain classifier
 * ([spineUncalledEdge] — the deleted walker's exact dispatch arms, including
 * the quirks pinned here: switch subjects / case expressions / for headers /
 * enum initializers / param defaults / dotted-namespace bodies unreached;
 * try/catch/finally block statements walked WITHOUT a block scope;
 * object-literal method and class-EXPRESSION member bodies walked WITHOUT a
 * param scope or this-type). The three scope stacks are REBUILT pull-based at
 * each emission from the ancestor chain (per-owner memoized levels; the
 * this-type chain skips a class whose STATIC method body contains the
 * emission — the legacy temporary pop). All pins verified against the OLD
 * walker (pre-migration checker) — a pure reach-preserving migration.
 */
class Inv4SpineBatch21Test {

    // ── emission shapes ─────────────────────────────────────────────────────

    @Test
    fun `if condition on always-defined function fires TS2774`() {
        diagnose("""
            declare function act(): boolean;
            function isReady(): boolean { return true; }
            if (isReady) { act(); }
        """) should {
            have(any { it.code == 2774 })
        }
    }

    @Test
    fun `while condition fires TS2774`() {
        diagnose("""
            function isReady(): boolean { return true; }
            while (isReady) { break; }
        """) should {
            have(any { it.code == 2774 })
        }
    }

    @Test
    fun `do-while condition fires TS2774`() {
        diagnose("""
            function isReady(): boolean { return true; }
            do { break; } while (isReady);
        """) should {
            have(any { it.code == 2774 })
        }
    }

    @Test
    fun `for condition fires TS2774`() {
        diagnose("""
            function isReady(): boolean { return true; }
            for (; isReady; ) { break; }
        """) should {
            have(any { it.code == 2774 })
        }
    }

    @Test
    fun `expression-statement and-chain LHS fires TS2774`() {
        diagnose("""
            declare function act(): boolean;
            function isReady(): boolean { return true; }
            isReady && act();
        """) should {
            have(any { it.code == 2774 })
        }
    }

    @Test
    fun `negative control - expression-statement and-chain RHS is not a test position`() {
        diagnose("""
            declare function act(): boolean;
            function isReady(): boolean { return true; }
            act() && isReady;
        """) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `expression-statement or-chain LHS fires without sibling suppression`() {
        diagnose("""
            function isReady(): boolean { return true; }
            isReady || isReady();
        """) should {
            have(any { it.code == 2774 })
        }
    }

    @Test
    fun `and-chain sibling call suppresses TS2774`() {
        diagnose("""
            function isReady(): boolean { return true; }
            isReady && isReady();
        """) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `ternary condition fires TS2774`() {
        diagnose("""
            function isReady(): boolean { return true; }
            const x = isReady ? 1 : 2;
        """) should {
            have(any { it.code == 2774 })
        }
    }

    @Test
    fun `ternary branch calling the operand suppresses TS2774`() {
        diagnose("""
            function isReady(): boolean { return true; }
            const x = isReady ? isReady() : false;
        """) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `if body referencing the operand suppresses TS2774`() {
        diagnose("""
            function isReady(): boolean { return true; }
            if (isReady) { isReady(); }
        """) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `ternary inside a call argument fires TS2774`() {
        diagnose("""
            declare function act(x: number): boolean;
            function isReady(): boolean { return true; }
            act(isReady ? 1 : 2);
        """) should {
            have(any { it.code == 2774 })
        }
    }

    @Test
    fun `non-nullable Promise condition fires TS2801`() {
        diagnose("""
            declare function act(): boolean;
            declare const p: Promise<number>;
            if (p) { act(); }
        """) should {
            have(any { it.code == 2801 })
        }
    }

    @Test
    fun `negative control - Promise condition with body reference is suppressed`() {
        diagnose("""
            declare const p: Promise<number>;
            if (p) { p.then(() => {}); }
        """) should {
            have(none { it.code == 2801 })
        }
    }

    // ── scope machinery pins ────────────────────────────────────────────────

    @Test
    fun `negative control - nullable-typed param shadows outer function`() {
        diagnose("""
            declare function act(): boolean;
            function isReady(): boolean { return true; }
            function test(isReady: number | undefined) { if (isReady) { act(); } }
        """) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `negative control - untyped local const shadows outer function`() {
        diagnose("""
            declare function act(): boolean;
            declare function compute(): any;
            function isReady(): boolean { return true; }
            function test() { const isReady = compute(); if (isReady) { act(); } }
        """) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `negative control - block-scoped const shadows outer function`() {
        diagnose("""
            declare function act(): boolean;
            declare function compute(): any;
            function isReady(): boolean { return true; }
            { const isReady = compute(); if (isReady) { act(); } }
        """) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `quirk pin - try-block const does NOT shadow (no block scope for try statements)`() {
        diagnose("""
            declare function act(): boolean;
            declare function compute(): any;
            function isReady(): boolean { return true; }
            try { const isReady = compute(); if (isReady) { act(); } } catch (e) {}
        """) should {
            have(any { it.code == 2774 })
        }
    }

    @Test
    fun `local function typed through a const alias fires TS2774`() {
        diagnose("""
            declare function act(): boolean;
            function outer() {
                function localFn(): void {}
                const f = localFn;
                if (f) { act(); }
            }
        """) should {
            have(any { it.code == 2774 })
        }
    }

    // ── this-type pins ──────────────────────────────────────────────────────

    @Test
    fun `this-method condition in an instance method fires TS2774`() {
        diagnose("""
            declare function act(): boolean;
            class C { m(): void {} test() { if (this.m) { act(); } } }
        """) should {
            have(any { it.code == 2774 })
        }
    }

    @Test
    fun `negative control - this-method condition with body call is suppressed`() {
        diagnose("""
            class C { m(): void {} test() { if (this.m) { this.m(); } } }
        """) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `quirk pin - no this-type inside a STATIC method body`() {
        diagnose("""
            declare function act(): boolean;
            class C { m(): void {} static s() { if (this.m) { act(); } } }
        """) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `quirk pin - no this-type inside a class-EXPRESSION member body`() {
        diagnose("""
            declare function act(): boolean;
            const C = class { m(): void {} test() { if (this.m) { act(); } } };
        """) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `class-expression member body is still reached for outer names`() {
        diagnose("""
            declare function act(): boolean;
            function isReady(): boolean { return true; }
            const C = class { test() { if (isReady) { act(); } } };
        """) should {
            have(any { it.code == 2774 })
        }
    }

    @Test
    fun `quirk pin - object-literal method body has NO param scope`() {
        diagnose("""
            declare function act(): boolean;
            function isReady(): boolean { return true; }
            const o = { m(isReady: number | undefined) { if (isReady) { act(); } } };
        """) should {
            have(any { it.code == 2774 })
        }
    }

    // ── arrow-function pins (the scope-anchor-at-body detail) ───────────────

    @Test
    fun `arrow expression-body truthiness chain fires TS2774`() {
        diagnose("""
            declare function act(): boolean;
            function isReady(): boolean { return true; }
            const g = () => isReady && act();
        """) should {
            have(any { it.code == 2774 })
        }
    }

    @Test
    fun `negative control - arrow expression-body sees its OWN param scope`() {
        diagnose("""
            declare function act(): boolean;
            function isReady(): boolean { return true; }
            const g = (isReady: number | undefined) => isReady && act();
        """) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `negative control - arrow block body sees its param scope`() {
        diagnose("""
            declare function act(): boolean;
            function isReady(): boolean { return true; }
            const g = (isReady: number | undefined) => { if (isReady) { act(); } };
        """) should {
            have(none { it.code == 2774 })
        }
    }

    // ── reach pins (positions the legacy walk never visited) ────────────────

    @Test
    fun `negative reach pin - switch subject is not a walked position`() {
        diagnose("""
            function isReady(): boolean { return true; }
            switch (isReady ? 1 : 2) { case 1: break; }
        """) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `negative reach pin - case clause expression is not walked`() {
        diagnose("""
            function isReady(): boolean { return true; }
            switch (1 as number) { case (isReady ? 1 : 2): break; }
        """) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `switch clause STATEMENTS are walked`() {
        diagnose("""
            declare function act(): boolean;
            function isReady(): boolean { return true; }
            switch (1 as number) { case 1: if (isReady) { act(); } break; }
        """) should {
            have(any { it.code == 2774 })
        }
    }

    @Test
    fun `negative reach pin - for-statement initializer is not walked`() {
        diagnose("""
            function isReady(): boolean { return true; }
            for (let x = isReady ? 1 : 2; false; ) { break; }
        """) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `negative reach pin - parameter defaults are not walked`() {
        diagnose("""
            function isReady(): boolean { return true; }
            function t(x = isReady ? 1 : 2) {}
        """) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `negative reach pin - enum member initializers are not walked`() {
        diagnose("""
            function isReady(): boolean { return true; }
            enum E { A = isReady ? 1 : 2 }
        """) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `namespace body statements are walked`() {
        diagnose("""
            declare function act(): boolean;
            function isReady(): boolean { return true; }
            namespace N { if (isReady) { act(); } }
        """) should {
            have(any { it.code == 2774 })
        }
    }

    @Test
    fun `dotted-namespace bodies are walked (single ModuleDeclaration with dotted name)`() {
        diagnose("""
            declare function act(): boolean;
            function isReady(): boolean { return true; }
            namespace A.B { if (isReady) { act(); } }
        """) should {
            have(any { it.code == 2774 })
        }
    }

    @Test
    fun `labeled statement body is walked`() {
        diagnose("""
            declare function act(): boolean;
            function isReady(): boolean { return true; }
            L: if (isReady) { act(); }
        """) should {
            have(any { it.code == 2774 })
        }
    }
}
