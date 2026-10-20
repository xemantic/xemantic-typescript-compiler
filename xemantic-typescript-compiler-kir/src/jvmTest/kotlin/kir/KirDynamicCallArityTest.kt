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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
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

    private fun compileAndRun(source: String): String =
        compileAndRun(source, Files.createTempDirectory("xtsc-kir-arity"))

    private fun compileAndRun(source: String, output: Path): String {
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

    // ---- (KIR.PERF.3) a MEMBER call on a bag, at every specialized arity ----

    /** The program's own bytecode — `javap`, not a constant-pool scan. */
    private fun disassemble(output: Path): String {
        val javap = Path.of(System.getProperty("java.home"), "bin", "javap").toString()
        val classes = output.toFile().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { it.absolutePath }
            .sorted()
            .toList()
        val dump = File.createTempFile("xtsc-kir-arity-javap", ".txt")
        try {
            ProcessBuilder(listOf(javap, "-p", "-c") + classes)
                .redirectOutput(dump)
                .redirectErrorStream(true)
                .start()
                .waitFor()
            return dump.readText()
        } finally {
            dump.delete()
        }
    }

    @Test
    fun `a bag member call up to three arguments passes them positionally`() {
        // `o.m(a, b)` on a bag was `invokeMember("m", jsArgs(a, b))`: an array at
        // the call site and a second in the base's spread into `jsCall`, on
        // mitt's `emit` path. Only the four-argument call may still build one.
        val output = Files.createTempDirectory("xtsc-kir-arity")
        val stdout = compileAndRun(
            """
            interface Bag {
              zero(): string
              one(a: string): string
              two(a: string, b: string): string
              three(a: string, b: string, c: string): string
              four(a: string, b: string, c: string, d: string): string
            }
            const bag: Bag = {
              zero(): string { return 'z' },
              one(a: string): string { return '1' + a },
              two(a: string, b: string): string { return '2' + a + b },
              three(a: string, b: string, c: string): string { return '3' + a + b + c },
              four(a: string, b: string, c: string, d: string): string { return '4' + a + b + c + d }
            }
            console.log(bag.zero() + bag.one('a') + bag.two('a', 'b') + bag.three('a', 'b', 'c') +
              bag.four('a', 'b', 'c', 'd'))
            """.trimIndent(),
            output,
        )
        assert(stdout == "z1a2ab3abc4abcd\n")
        val code = disassemble(output).lines()
        val specialized = (0..3).map { arity ->
            code.count { it.contains("JsObject.invokeMember$arity:") }
        }
        val general = code.count { it.contains("JsObject.invokeMember:") }
        val arrays = code.count { it.contains("JsRuntimeKt.jsArgs:") }
        assert(specialized == listOf(1, 1, 1, 1))
        assert(general == 1)
        assert(arrays == 1)
    }

    @Test
    fun `a class instance in an interface slot answers every specialized arity`() {
        // The specialized entry points' base bodies read a PROPERTY, which a
        // class's method is not: a generated class forwards them to its own
        // `invokeMember`, virtually, so a subclass's override is the one reached.
        // Expected output from tsgo 7.0.2 + node.
        val stdout = compileAndRun(
            """
            interface Shape {
              zero(): string
              one(a: string): string
              two(a: string, b: string): string
              three(a: string, b: string, c: string): string
            }
            class Base implements Shape {
              zero(): string { return 'z' }
              one(a: string): string { return '1' + a }
              two(a: string, b: string): string { return '2' + a + b }
              three(a: string, b: string, c: string): string { return '3' + a + b + c }
            }
            class Derived extends Base {
              two(a: string, b: string): string { return 'D' + b + a }
            }
            const shapes: Shape[] = [new Base(), new Derived()]
            for (const s of shapes) {
              console.log(s.zero() + s.one('a') + s.two('a', 'b') + s.three('a', 'b', 'c'))
            }
            """.trimIndent()
        )
        assert(stdout == "z1a2ab3abc\nz1aDba3abc\n")
    }

    @Test
    fun `an array callback of any arity sees element, index and array as JavaScript passes them`() {
        // `JsArray`'s callbacks test for a ONE-parameter function first and fall
        // back to `jsCall3`'s adaptation; both halves must answer as node does.
        // Expected output from tsgo 7.0.2 + node.
        val stdout = compileAndRun(
            """
            const xs = [10, 20]
            console.log(xs.map(() => 7).join(','))
            console.log(xs.map((x) => x * 2).join(','))
            console.log(xs.map((x, i) => x + i).join(','))
            console.log(xs.map((x, i, a) => x + i + a.length).join(','))
            console.log(xs.filter((x) => x > 15).join(','))
            """.trimIndent()
        )
        assert(stdout == "7,7\n20,40\n10,21\n12,23\n20\n")
    }

}
