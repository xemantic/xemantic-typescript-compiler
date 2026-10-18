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
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * (KIR.LOWER.7) A CLASS declared inside a function body or a block.
 *
 * Lowered as a Kotlin IR LOCAL class built whole at its own statement (a class
 * is not hoisted: its name is in its temporal dead zone above it), so Kotlin's
 * closure lowering gives a method its captures, and with a VALUE that is a
 * fresh `JsConstructor` per execution of the statement — `node` answers
 * `outer() !== outer()`.
 *
 * Every expected string is `node`'s, over `tools/tsgo-7.0.2/lib/tsc`'s output for
 * the same source (each program type-checks clean under tsgo). Where the two
 * differ the pin says `divergence` and why.
 */
class KirNestedClassTest {

    private class Lowered(val compiled: Boolean, val report: String, val stdout: String, val stderr: String, val exitCode: Int) {
        override fun toString(): String =
            "compiled=$compiled exit=$exitCode\n--- stdout ---\n$stdout--- stderr ---\n$stderr$report"
    }

    private val tsconfig = """
        {
          "compilerOptions": {
            "strict": true,
            "target": "ES2020",
            "module": "ESNext",
            "moduleResolution": "bundler"
          },
          "include": ["src/**/*.ts"]
        }
    """.trimIndent()

    private fun lower(source: String): Lowered {
        val project = Files.createTempDirectory("xtsc-kir-nested-class")
        val output = Files.createTempDirectory("xtsc-kir-nested-class-out")
        try {
            project.resolve("tsconfig.json").writeText(tsconfig)
            val target = project.resolve("src/main.ts")
            target.parent.createDirectories()
            target.writeText(source.trimIndent() + "\n")
            val compilation = compileTypeScriptProjectToJvm(
                projectPath = project.toString(),
                entryFileName = "main.ts",
                outputDirectory = output,
            )
            if (!compilation.successful) return Lowered(false, compilation.toString(), "", "", -1)
            val run = runGeneratedProgram(output, compilation.mainClass, GeneratedProgramClasspath.minimal())
            return Lowered(true, compilation.toString(), run.stdout, run.stderr, run.exitCode)
        } finally {
            project.toFile().deleteRecursively()
            output.toFile().deleteRecursively()
        }
    }

    /** node prints `1` — a field and a method reading it through `this`. */
    @Test
    fun `a nested class with a field and a method`() {
        val lowered = lower(
            """
            function outer(): number { class P { x = 1; get(): number { return this.x } } return new P().get() }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "1\n")
    }

    /** node prints `5` — a parameter property and a method taking an argument. */
    @Test
    fun `a nested class with a parameter property`() {
        val lowered = lower(
            """
            function outer(): number { class P { constructor(public a: number) {} sum(b: number): number { return this.a + b } } return new P(2).sum(3) }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "5\n")
    }

    /** node prints `42` — a method CAPTURES an enclosing parameter. */
    @Test
    fun `a nested class method captures a parameter`() {
        val lowered = lower(
            """
            function outer(n: number): number { class P { get(): number { return n * 2 } } return new P().get() }
            console.log(outer(21))
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "42\n")
    }

    /** node prints `2` — a constructor WRITES an enclosing `let`, shared by both constructions. */
    @Test
    fun `a nested class constructor writes a captured let`() {
        val lowered = lower(
            """
            function outer(): number { let c = 0; class P { constructor() { c++ } } new P(); new P(); return c }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "2\n")
    }

    /** node prints `false true` — the class VALUE is per invocation, and one within an invocation. */
    @Test
    fun `a nested class value is one per invocation`() {
        val lowered = lower(
            """
            function outer() { class P {} return P }
            function same() { class P {} return P === P }
            console.log(outer() === outer(), same())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "false true\n")
    }

    /** node prints `ba` — a nested class EXTENDS a nested class, overrides, and calls `super`. */
    @Test
    fun `a nested class extends a nested class`() {
        val lowered = lower(
            """
            function outer(): string { class A { f(): string { return "a" } } class B extends A { f(): string { return "b" + super.f() } } const x: A = new B(); return x.f() }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "ba\n")
    }

    /** node prints `d>base` — a nested class extends a TOP-LEVEL one. */
    @Test
    fun `a nested class extends a top-level class`() {
        val lowered = lower(
            """
            class Base { hi(): string { return "base" } }
            function outer(): string { class D extends Base { hi(): string { return "d>" + super.hi() } } return new D().hi() }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "d>base\n")
    }

    /**
     * DIVERGENCE — node prints `true false`; ours prints `true true`.
     *
     * `instanceof` is a JVM type test, and every invocation's `P` is one JVM
     * class: an instance made by one invocation IS an instance of another's.
     * The class VALUES differ per invocation (pinned above); their instances'
     * type tests do not. Closing it would mean an invocation token on every
     * instance, checked by every `instanceof` — priced by nothing yet.
     */
    @Test
    fun `divergence - instanceof does not tell two invocations' classes apart`() {
        val lowered = lower(
            """
            function outer() { class P {} return { p: new P(), test: (x: unknown) => x instanceof P } }
            const a = outer(); const b = outer()
            console.log(a.test(a.p), a.test(b.p))
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "true true\n")
    }

    /** node prints `4 3` — an instance ESCAPES its function, and is used there. */
    @Test
    fun `a nested class instance is used outside its function`() {
        val lowered = lower(
            """
            function mk() { class P { v = 3; get(): number { return this.v + 1 } } return new P() }
            const p = mk()
            console.log(p.get(), p.v)
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "4 3\n")
    }

    /**
     * node prints `2 true function` — a TOP-LEVEL class's static side, `typeof C`, erases to the
     * `JsConstructor` its value is. It erased to the INSTANCE type before (KIR.LOWER.7),
     * so a function returning a class (`return C`, inferred `typeof C`) did not lower.
     */
    @Test
    fun `a top-level class value returned as typeof C`() {
        val lowered = lower(
            """
            class C { v = 2 }
            function get() { return C }
            const K: any = get()
            console.log(new K().v, get() === get(), typeof get())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "2 true function\n")
    }

    /** node prints `7` — a class declared in a TOP-LEVEL block. */
    @Test
    fun `a nested class in a top-level block`() {
        val lowered = lower(
            """
            { class P { v = 7 } console.log(new P().v) }
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "7\n")
    }

    /** node prints `5` — the class VALUE handed to a function that constructs it dynamically. */
    @Test
    fun `a nested class value is constructed dynamically`() {
        val lowered = lower(
            """
            function make(C: any): number { return new C().v }
            function outer(): number { class P { v = 5 } return make(P) }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "5\n")
    }

    /** node prints `40` — a getter and a setter on a nested class. */
    @Test
    fun `a nested class with accessors`() {
        val lowered = lower(
            """
            function outer(): number { class P { private _v = 1; get v(): number { return this._v } set v(x: number) { this._v = x * 10 } } const p = new P(); p.v = 4; return p.v }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "40\n")
    }

    /** node prints `16` — a class declared inside a class METHOD. */
    @Test
    fun `a nested class in a method`() {
        val lowered = lower(
            """
            class Host { make(k: number): number { class Inner { constructor(public k: number) {} twice(): number { return this.k * 2 } } return new Inner(k).twice() } }
            console.log(new Host().make(8))
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "16\n")
    }

    /** node prints `3` — instances in an array, read back by element. */
    @Test
    fun `nested class instances in an array`() {
        val lowered = lower(
            """
            function outer(): number { class P { constructor(public v: number) {} } const ps = [new P(1), new P(2)]; return ps[0].v + ps[1].v }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "3\n")
    }

    /**
     * NEGATIVE CONTROL — a `static` member is refused by name (node prints `1`).
     *
     * A JVM static is one per program and this class is one per invocation, so
     * a static field would be shared where `node` gives each invocation its own.
     */
    @Test
    fun `negative control - a static member of a nested class is refused`() {
        val lowered = lower(
            """
            function outer(): number { class P { static n = 1 } return P.n }
            console.log(outer())
            """
        )
        assert(!lowered.compiled)
        assert(lowered.report.contains("a `static` member of a class declared inside a function body or a block"))
    }

    /**
     * NEGATIVE CONTROL — a reference lowered ABOVE the class statement is refused by name.
     *
     * `helper` closes over `P`, so its body is built at its own statement, which
     * precedes the class: there is no class to reach yet. (node prints `9`: the
     * call runs after the class statement.)
     */
    @Test
    fun `negative control - a class referenced above its statement is refused`() {
        val lowered = lower(
            """
            function outer(): number {
              function helper(): number { return new P().v }
              class P { v = 9 }
              return helper()
            }
            console.log(outer())
            """
        )
        assert(!lowered.compiled)
        assert(lowered.report.contains("is used here before its statement is lowered"))
    }

}
