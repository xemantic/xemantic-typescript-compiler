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
 * (cpa-m1) round 577: the LEGACY-side audit instrumentation for the g2
 * (checkPropertyAccess) spine migration — under [Checker.cpaAuditEnabled]
 * the statement dispatcher records a per-statement fingerprint of the
 * threaded + ambient context (currentLocalTypes by DISPLAY / param binding
 * names / enum-constrained params / shadowed names / namespace stack /
 * enclosingClassType / contextualType / inStaticClassMethod) keyed by
 * nodeId. The (cpa-m2) spine frame skeleton will record the same and an
 * audit test diffs the two maps. This test validates the recording itself:
 * coverage, per-context fingerprint content, and off-by-default.
 */
class Inv4CpaAuditTest {

    private fun record(source: String): Map<Int, String> {
        val options = CompilerOptions(strict = true)
        val result = Binder(options).bind(Parser(source.trimIndent(), "t.ts").parse())
        Checker.cpaAuditEnabled = true
        try {
            val checker = Checker(options, listOf(result))
            return HashMap(checker.cpaAuditLegacy)
        } finally {
            Checker.cpaAuditEnabled = false
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
        assertTrue(audit.size >= 5, "expected >=5 recorded statements, got ${audit.size}: $audit")
        val paramTyped = audit.values.filter { "x=string" in it }
        assertTrue(paramTyped.isNotEmpty(),
            "expected fn-body statements carrying the annotated param x=string in lt[], got: ${audit.values}")
    }

    @Test
    fun `class member bodies carry enclosingClassType and the static flag`() {
        val audit = record("""
            class K {
                p: number = 1;
                m(): number { const im = 1; return this.p; }
                static s() { const st = 1; }
            }
        """)
        assertTrue(audit.values.any { "ect=K" in it && it.endsWith("f=0") },
            "expected an instance-method body print with ect=K and f=0, got: ${audit.values}")
        assertTrue(audit.values.any { "ect=K" in it && it.endsWith("f=1") },
            "expected a static-method body print with ect=K and f=1, got: ${audit.values}")
    }

    @Test
    fun `namespace bodies carry the enclosing-namespace stack`() {
        val audit = record("""
            namespace Outer {
                export namespace Inner {
                    const deep: number = 1;
                }
                const mid: string = "m";
            }
        """)
        assertTrue(audit.values.any { "ns[Outer]" in it },
            "expected a print with ns[Outer], got: ${audit.values}")
        assertTrue(audit.values.any { "ns[Outer,Inner]" in it },
            "expected a print with ns[Outer,Inner], got: ${audit.values}")
    }

    @Test
    fun `the audit is off by default`() {
        val options = CompilerOptions(strict = true)
        val result = Binder(options).bind(Parser("const a: number = 1;", "t.ts").parse())
        val checker = Checker(options, listOf(result))
        kotlin.test.assertEquals(0, checker.cpaAuditLegacy.size,
            "expected no recordings with the flag off")
    }
}
