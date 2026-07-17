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

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * (cta-m2a) round 561: the LEGACY-side audit instrumentation for the (g1c)
 * checkTypeAssignability spine migration — under [Checker.ctaAuditEnabled]
 * the two legacy dispatchers record a per-statement fingerprint of the
 * threaded context (varTypes / returnType / returnTypeNode / typeParams /
 * body flags) keyed by nodeId. The (cta-m2b) spine frame skeleton will
 * record the same map and an audit diff gates the migration. This test
 * validates the recording itself: coverage (every reached statement),
 * per-context fingerprint content, and that the flag stays off by default.
 */
class Inv4CtaAuditTest {

    private fun record(source: String): Map<Int, String> {
        val options = CompilerOptions(strict = true)
        val result = Binder(options).bind(Parser(source.trimIndent(), "t.ts").parse())
        Checker.ctaAuditEnabled = true
        try {
            val checker = Checker(options, listOf(result))
            return HashMap(checker.ctaAuditLegacy)
        } finally {
            Checker.ctaAuditEnabled = false
        }
    }

    @Test
    fun `statements are recorded with context-bearing fingerprints`() {
        val audit = record("""
            const a: number = 1;
            function f(x: string): number {
                const b = x;
                return 42;
            }
            { const c = 3; }
        """)
        val count = audit.size
        assertTrue(count >= 5, "expected >=5 recorded statements, got $count: $audit")
        val fnBodyPrints = audit.values.filter { "rt=number" in it }
        assertTrue(fnBodyPrints.isNotEmpty(), "expected fn-body statements carrying rt=number, got: ${audit.values}")
        val inFnFlagged = audit.values.filter { it.endsWith("f=1") || it.endsWith("f=3") }
        assertTrue(inFnFlagged.isNotEmpty(), "expected inNonArrowFunctionBody-flagged prints, got: ${audit.values}")
    }

    @Test
    fun `generic function bodies carry their type params in the fingerprint`() {
        val audit = record("""
            function g<T, U>(t: T): T {
                return t;
            }
        """)
        assertTrue(audit.values.any { "tp[T,U]" in it }, "expected tp[T,U] in some print, got: ${audit.values}")
    }

    private fun diffOnLegacyKeys(source: String): List<String> {
        val options = CompilerOptions(strict = true)
        val result = Binder(options).bind(Parser(source.trimIndent(), "t.ts").parse())
        Checker.ctaAuditEnabled = true
        try {
            val checker = Checker(options, listOf(result))
            val mismatches = mutableListOf<String>()
            for ((id, legacyPrint) in checker.ctaAuditLegacy) {
                val spinePrint = checker.ctaAuditSpine[id]
                if (spinePrint != legacyPrint) {
                    mismatches.add("node $id:\n  legacy: $legacyPrint\n  spine:  ${spinePrint ?: "<missing>"}")
                }
            }
            return mismatches
        } finally {
            Checker.ctaAuditEnabled = false
        }
    }

    @Test
    fun `spine frames agree with the legacy context on every legacy-visited statement`() {
        val mismatches = diffOnLegacyKeys("""
            const a: number = 1;
            let b = a;
            { const c: string = "x"; b = 2; }
            function f(x: string, q?: number): number {
                const d = x;
                if (d) { return 1; }
                while (b) { b = 3; break; }
                return 42;
            }
            async function g<T, U>(t: T): Promise<T> {
                return t;
            }
            function* h() { yield 1; }
            class K {
                p: number = 1;
                m(y: string): string { return y; }
                static s() { const e = 1; }
                constructor(z: number) { this.p = z; }
                get gg(): number { return this.p; }
                set ss(v: number) { this.p = v; }
            }
            namespace NS { const n: boolean = true; }
            try { const t1 = 1; } catch (e) { const t2 = 2; } finally { const t3 = 3; }
            switch (b) { case 1: { const s1 = 1; break; } default: { const s2 = 2; } }
            const arrow = (w: number): number => { return w; };
            const fe = function (v: string): string { return v; };
        """)
        kotlin.test.assertTrue(mismatches.isEmpty(),
            "expected spine/legacy agreement, got ${mismatches.size} mismatches:\n" +
                mismatches.take(8).joinToString("\n"))
    }

    @Test
    fun `spine frames agree on the widened fixture shapes`() {
        val mismatches = diffOnLegacyKeys("""
            declare const cond: boolean;
            namespace Outer {
                export namespace Inner {
                    const deep: number = 1;
                    export function nf(p: string): string { return p; }
                }
                const mid: string = "m";
            }
            function withNested<A>(a: A): A {
                function inner<B extends A>(b: B): B {
                    const x: B = b;
                    if (cond) { const y = x; return y; }
                    return b;
                }
                for (let i: number = 0; i < 3; i++) { const li: number = i; }
                for (const k in { a: 1 }) { const ki = k; }
                for (const v of [1, 2]) { const vi = v; }
                do { const di: number = 1; } while (cond);
                return inner(a);
            }
            const ce = class {
                cp: number = 1;
                cm(): number { return this.cp; }
            };
            function overloaded(x: number): number;
            function overloaded(x: string): string;
            function overloaded(x: any): any {
                const inside: number = 1;
                return x;
            }
            label: { const lb: number = 1; }
            with ({} as any) { const wb = 1; }
            if (cond) { const thenB: number = 1; } else if (!cond) { const elifB: number = 2; } else { const elseB: number = 3; }
        """)
        kotlin.test.assertTrue(mismatches.isEmpty(),
            "expected spine/legacy agreement on widened shapes, got ${mismatches.size} mismatches:\n" +
                mismatches.take(8).joinToString("\n"))
    }

    @Test
    fun `the audit is off by default`() {
        val options = CompilerOptions(strict = true)
        val result = Binder(options).bind(Parser("const a: number = 1;", "t.ts").parse())
        val checker = Checker(options, listOf(result))
        val size = checker.ctaAuditLegacy.size
        kotlin.test.assertEquals(0, size, "expected no recordings with the flag off")
    }
}
