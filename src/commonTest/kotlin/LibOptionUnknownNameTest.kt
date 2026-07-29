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
 * (LIB.1)(c): a `lib` entry that names no lib file is REPORTED (TS6046) instead of
 * silently dropped.
 *
 * Until this landed, `RealLibResolver.Resolution.unknownNames` had **zero consumers**:
 * `"lib": ["esnext.arary"]` resolved to nothing, the program was checked against no
 * lib at all, and not one word was said. That is the (LIB.1) defect in its last shape
 * — the same shape as the unshipped DOM in (b), one level up.
 *
 * TWO THINGS THIS FILE PINS BEYOND "the diagnostic fires".
 *
 * **The trap (b) already paid for**: a control that only asks "does the good lib name
 * produce no error?" passes just as happily when the good lib resolved to nothing
 * either — silence is the failure mode, so silence cannot be the control. The control
 * here is therefore a MEMBER probe: the good arm must actually type-CHECK against the
 * lib it named.
 *
 * **The design decision, made explicit**: the diagnostic is raised from the REAL-LIB
 * RESOLUTION, not from a raw `options.lib` x `libMap` check. The corpus runs the
 * embedded lib and never resolves real libs, so no baseline can move — and the last
 * test below is what holds that property in place. Widening the check to the embedded
 * path reaches all 259 `@lib:` corpus cases and needs the full logical-parity
 * judgement; do not do it without one.
 */
class LibOptionUnknownNameTest {

    private val real = "// @strict: true\n// @useRealLibs: true"

    private val program = """
        const n: number = 1
    """.trimIndent()

    @Test
    fun `a lib entry that names no lib file is reported`() {
        val diagnostics = diagnose(program, directives = "$real\n// @lib: es2015,esnext.arary")
        assert(diagnostics.any { it.code == 6046 })
    }

    @Test
    fun `the message enumerates the valid names and does not name the offender`() {
        val message = diagnose(program, directives = "$real\n// @lib: es2015,esnext.arary")
            .first { it.code == 6046 }.message
        assert(message.startsWith("Argument for '--lib' option must be: "))
        assert("'es5'" in message)
        assert("'es2015'" in message)
        assert("'dom'" in message)
        assert(message.endsWith("."))
        // tsc does NOT quote the bad value back — the message is a list of what is
        // allowed. Pinned because naming the offender is the obvious "improvement"
        // and it would diverge from every tsc baseline.
        assert("arary" !in message)
    }

    @Test
    fun `every bad entry is reported once`() {
        val diagnostics = diagnose(program, directives = "$real\n// @lib: es2015,nope.one,nope.two")
        assert(diagnostics.count { it.code == 6046 } == 2)
    }

    @Test
    fun `control - a good lib set is silent AND actually checks against that lib`() {
        // The decisive half is the second assertion. An unknown TYPE degrades to
        // `any` and `any` is silent, so "no TS6046" alone would pass with the whole
        // lib missing; only a MEMBER probe discriminates.
        val good = diagnose(
            """
            function f(s: Screen): void {
                s.definitelyNotAMember
            }
            """.trimIndent(),
            directives = "$real\n// @lib: dom,es2015",
        )
        assert(good.none { it.code == 6046 })
        assert(good.any { it.code == 2339 })
    }

    @Test
    fun `control - the embedded lib path stays silent so no corpus baseline can move`() {
        // Same bogus entry, no `@useRealLibs`: the embedded BUILTIN_LIB_SOURCE path
        // never resolves real libs, so there is nothing to report. This is the
        // property that makes (c) a zero-baseline-risk change, and it is a pin
        // rather than a comment on purpose.
        val embedded = diagnose(program, directives = "// @strict: true\n// @lib: es2015,esnext.arary")
        assert(embedded.none { it.code == 6046 })
    }
}
