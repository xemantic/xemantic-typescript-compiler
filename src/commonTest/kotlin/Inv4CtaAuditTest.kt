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

    @Test
    fun `the audit is off by default`() {
        val options = CompilerOptions(strict = true)
        val result = Binder(options).bind(Parser("const a: number = 1;", "t.ts").parse())
        val checker = Checker(options, listOf(result))
        val size = checker.ctaAuditLegacy.size
        kotlin.test.assertEquals(0, size, "expected no recordings with the flag off")
    }
}
