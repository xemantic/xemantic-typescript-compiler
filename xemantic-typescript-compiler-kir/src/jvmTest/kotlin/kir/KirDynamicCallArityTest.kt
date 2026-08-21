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

package com.xemantic.typescript.compiler.kir

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.kir.emit.GeneratedProgramClasspath
import com.xemantic.typescript.compiler.kir.emit.runGeneratedProgram
import java.nio.file.Files
import kotlin.test.Test

/**
 * A dynamic call ADAPTS the callee's arity, at every argument count.
 *
 * The pin for the arity-specialized `jsCall0`..`jsCall3` entry points, which
 * exist for cost (no `vararg` array, one type test instead of a chain) and must
 * therefore be indistinguishable from the general `jsCall` in behaviour. What
 * makes that non-obvious is the direction of the mismatch: TypeScript lets a
 * function of FEWER parameters stand for one of more, so the value stored in a
 * property bag routinely has an arity its declared type does not, and the call
 * site can supply either too few arguments or too many.
 *
 * This is not a hypothetical. `mitt` registers a one-parameter wildcard handler
 * against a two-parameter `WildcardHandler` type and `emit` calls it with two
 * arguments — so the DROP case below is the library acceptance test in
 * miniature, and a specialization that assumed the declared arity would compile
 * and then fail on it.
 *
 * A missing argument is `undefined`, which is what a JS engine passes, and a
 * surplus one is discarded.
 */
class KirDynamicCallArityTest {

    private fun compileAndRun(source: String): String {
        val output = Files.createTempDirectory("xtsc-kir-arity")
        val compilation = compileTypeScriptToJvm("t.ts", source, output)
        assert(compilation.successful)
        val run = runGeneratedProgram(
            output,
            compilation.mainClass,
            GeneratedProgramClasspath.minimal()
        )
        assert(run.exitCode == 0)
        return run.stdout
    }

    @Test
    fun `a bag member called with FEWER arguments than it declares pads with undefined`() {
        val stdout = compileAndRun(
            """
            const bag = {
              two(a?: string, b?: string): string {
                return b === undefined ? a + '|absent' : a + '|' + b
              }
            }
            console.log(bag.two('x'))
            console.log(bag.two('x', 'y'))
            """.trimIndent()
        )
        assert(stdout == "x|absent\nx|y\n")
    }

    @Test
    fun `a bag member whose VALUE takes fewer parameters than its type drops the surplus`() {
        // mitt's shape exactly: a `Handler` stored where a `WildcardHandler` is
        // declared, then called with the wildcard's two arguments.
        val stdout = compileAndRun(
            """
            type Wide = (a: string, b: string) => string
            const bag: { h: Wide } = { h: (a: string) => 'got ' + a }
            console.log(bag.h('x', 'y'))
            """.trimIndent()
        )
        assert(stdout == "got x\n")
    }

    @Test
    fun `every specialized arity zero through three behaves as the general form`() {
        val stdout = compileAndRun(
            """
            const bag = {
              zero(): string { return 'z' },
              one(a: string): string { return '1' + a },
              two(a: string, b: string): string { return '2' + a + b },
              three(a: string, b: string, c: string): string { return '3' + a + b + c }
            }
            console.log(bag.zero())
            console.log(bag.one('a'))
            console.log(bag.two('a', 'b'))
            console.log(bag.three('a', 'b', 'c'))
            """.trimIndent()
        )
        assert(stdout == "z\n1a\n2ab\n3abc\n")
    }

    @Test
    fun `above the specialized arities the general vararg form still runs`() {
        // FOUR arguments: past `MAX_SPECIALIZED_CALL_ARITY`, so this exercises
        // the fallback rather than the specialization — and a change to that
        // constant that forgot the fallback would redden here and nowhere else.
        val stdout = compileAndRun(
            """
            const bag = {
              four(a: string, b: string, c: string, d: string): string {
                return a + b + c + d
              }
            }
            console.log(bag.four('a', 'b', 'c', 'd'))
            """.trimIndent()
        )
        assert(stdout == "abcd\n")
    }

}
