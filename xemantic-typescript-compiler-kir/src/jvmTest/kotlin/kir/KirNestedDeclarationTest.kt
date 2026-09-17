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
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * (P18.129) A FUNCTION or CLASS declared inside a function body or a block.
 *
 * The declare pass walks a file's TOP-LEVEL statements only, so a nested
 * `function helper() {}` reached neither declaration table and every reference
 * to one — its call, its value, `new` on a nested class — refused at *cannot
 * lower the reference*. Censused for this round: **not one of the 59 KIR corpus
 * fixtures declares a `function` inside a body**, which is why the gap
 * survived; `18-var-scoping.ts` reaches for `const innerFn = function () {}`,
 * the EXPRESSION form, which has worked since the first closures landed. A
 * nested `function helper() {}` is ordinary TypeScript this backend could not
 * compile at all.
 *
 * ## Every expectation below is `node`'s
 *
 * Measured against `tools/tsgo-7.0.2/lib/tsc` + `tools/node/bin/node` over the
 * same source, which is where each expected string comes from. 41 shapes were
 * characterised that way before anything was built; where the two diverge it is
 * named as a divergence and its reason is stated.
 *
 * ## What landed, and what the CHECKER made impossible
 *
 * The FUNCTION half. The obvious design — a real local IR function, so a direct
 * call costs no dynamic operation, mirroring what (KIR.LOWER.6) does for a
 * top-level one — was BUILT and does not work, for a reason that is a fact
 * about the checker rather than about the IR: a nested declaration is never
 * BOUND (CLAUDE.md's B83.5), so `CheckedFacts.signatureOf` answers **null** for
 * it and there is no declared return type and no erased parameter type to build
 * a typed function from. The refusal simply moved from *cannot lower the
 * reference* to *the checker gave no signature for this declaration*.
 *
 * The EXPRESSION form needs no oracle at all — every slot is `Any?` and every
 * name comes from the syntax — so a nested declaration reuses it verbatim, into
 * a hoisted local slot. The measured price is one `jsCall` per call where a
 * top-level function costs none.
 *
 * ## The CLASS half is refused, and the measurement that decides it
 *
 * `function outer() { class P {} ; return P }` answers **`outer() !== outer()`**
 * in `node`, and an instance made by one invocation is **not** `instanceof`
 * another invocation's `P`. So a nested class's identity is per INVOCATION, and
 * the lazy STATIC carrier (KIR.LOWER.5)/(KIR.LOWER.6) give a top-level class
 * value is the wrong shape for it — which is the question this family raises
 * that no previous round had to answer. It refuses by a message that says so.
 *
 * ## Why every shape assertion has a behaviour assertion beside it
 *
 * (P18.118)'s lesson: the op counters read 0 for a program that never compiled,
 * so `compiled` is asserted FIRST in every shape pin and a behaviour pin beside
 * it is what says the program also ran. And (P18.126)'s sharper form: a
 * `!compiled` negative control credits a refusal gate with untested coverage,
 * so every refusal below asserts the MESSAGE.
 */
class KirNestedDeclarationTest {

    private class Lowered(
        val compiled: Boolean,
        val report: String,
        val jsGet: Int,
        val jsCall: Int,
        val jsNew: Int,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
    ) {

        override fun toString(): String =
            "compiled=$compiled exit=$exitCode jsGet=$jsGet jsCall=$jsCall jsNew=$jsNew\n" +
                "--- stdout ---\n$stdout--- stderr ---\n$stderr$report"

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
        val project = Files.createTempDirectory("xtsc-kir-nested-declaration")
        val output = Files.createTempDirectory("xtsc-kir-nested-declaration-out")
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
            if (!compilation.successful) {
                return Lowered(false, compilation.toString(), 0, 0, 0, "", "", -1)
            }
            val disassembly = disassemble(output)
            val run = runGeneratedProgram(
                output,
                compilation.mainClass,
                GeneratedProgramClasspath.minimal()
            )
            return Lowered(
                true,
                compilation.toString(),
                disassembly.lines().count { it.contains("jsGet") },
                disassembly.lines().count { it.contains("jsCall") },
                disassembly.lines().count { it.contains("jsNew") },
                run.stdout,
                run.stderr,
                run.exitCode,
            )
        } finally {
            project.toFile().deleteRecursively()
            output.toFile().deleteRecursively()
        }
    }

    /** `javap`, not a constant-pool scan — see `KirReceiverShapeTest`'s own note. */
    private fun disassemble(outputDirectory: Path): String {
        val javap = Path.of(System.getProperty("java.home"), "bin", "javap").toString()
        val classes = outputDirectory.toFile().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { it.absolutePath }
            .toList()
            .sorted()
        if (classes.isEmpty()) throw AssertionError("the compilation wrote no class files")
        val dump = File.createTempFile("xtsc-kir-nested-declaration-javap", ".txt")
        try {
            ProcessBuilder(listOf(javap, "-p", "-c") + classes)
                .redirectOutput(dump)
                .redirectErrorStream(true)
                .start()
                .waitFor()
            val text = dump.readText()
            if (!text.contains("Code:")) throw AssertionError("javap produced no disassembly")
            return text
        } finally {
            dump.delete()
        }
    }

    // ---- M1a: the declaration, its call and its shape ----------------------

    /**
     * SHAPE — a nested function's call is one `jsCall` and constructs nothing.
     *
     * The measured PRICE of reusing the expression form: a top-level function's
     * call is direct and costs no dynamic operation, and this one cannot be,
     * because the checker gives a nested declaration no signature to build a
     * typed IR function from. Asserted rather than left implicit, so the day
     * the binder changes this pin is what says the price moved.
     */
    @Test
    fun `shape - a nested function call is one jsCall and no jsNew`() {
        val lowered = lower(
            """
            function outer(): number {
              function helper(x: number): number { return x + 1 }
              return helper(1)
            }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.jsCall == 1)
        assert(lowered.jsNew == 0)
        assert(lowered.stdout == "2\n")
    }

    /** node prints `2` — a helper declared above its use. */
    @Test
    fun `a nested function is callable below its declaration`() {
        val lowered = lower(
            """
            function outer(): number {
              function helper(x: number): number { return x + 1 }
              return helper(1)
            }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "2\n")
    }

    /**
     * node prints `2` — HOISTING, the half a `const` holding a function
     * expression cannot give.
     *
     * The one way the declaration and the expression forms are not
     * interchangeable however alike their lowering is: the name is usable above
     * its own textual position, which is how "helpers at the bottom" is written.
     */
    @Test
    fun `a nested function is callable ABOVE its declaration`() {
        val lowered = lower(
            """
            function outer(): number {
              const a = helper(1)
              function helper(x: number): number { return x + 1 }
              return a
            }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "2\n")
    }

    /** node prints `2,4` — hoisting reaches a VALUE position, not only a call. */
    @Test
    fun `a nested function is a value ABOVE its declaration`() {
        val lowered = lower(
            """
            function outer(): string {
              const r = [1, 2].map(dbl).join(",")
              function dbl(n: number): number { return n * 2 }
              return r
            }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "2,4\n")
    }

    /**
     * node prints `n3/L` — a body reading only an OUTER name still hoists.
     *
     * The discriminating case for the conservative test that decides where the
     * lambda is built: this body reads the enclosing parameter `n`, which is in
     * scope at the top of the list, while the list itself declares `first` and
     * `label`, which it does not read. A test that looked at the whole list, or
     * at the declaration NODE (whose own name is in the list), would defer this
     * one and `tag()` above it would read `undefined`.
     */
    @Test
    fun `a hoisted body may read an enclosing parameter`() {
        val lowered = lower(
            """
            function outer(n: number): string {
              const first = tag()
              const label = "L"
              function tag(): string { return "n" + n }
              return first + "/" + label
            }
            console.log(outer(3))
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "n3/L\n")
    }

    // ---- M1b: capture ------------------------------------------------------

    /** node prints `40` — a nested function closes over a PARAMETER. */
    @Test
    fun `a nested function captures a parameter`() {
        val lowered = lower(
            """
            function outer(n: number): number {
              function helper(): number { return n * 10 }
              return helper()
            }
            console.log(outer(4))
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "40\n")
    }

    /** node prints `11` — it closes over a `let` and WRITES through it. */
    @Test
    fun `a nested function captures and writes a let`() {
        val lowered = lower(
            """
            function outer(): number {
              let acc = 1
              function bump(): void { acc = acc + 5 }
              bump(); bump()
              return acc
            }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "11\n")
    }

    /** node prints `6` — the same for a function-scoped `var`. */
    @Test
    fun `a nested function captures a var`() {
        val lowered = lower(
            """
            function outer(): number {
              var acc = 1
              function bump(): void { acc = acc + 5 }
              bump()
              return acc
            }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "6\n")
    }

    /**
     * node prints `1,2,1` — the closure ESCAPES and two invocations do not
     * share its captured variable.
     *
     * The brief's own standing requirement, and a pin rather than an
     * assumption: `a` and `b` come from two calls of `makeCounter`, so `b()`
     * answering `1` after `a()` has answered `1,2` is what says each invocation
     * closed over its OWN `n`.
     */
    @Test
    fun `two invocations do not share a captured variable`() {
        val lowered = lower(
            """
            function makeCounter(): () => number {
              let n = 0
              function tick(): number { n = n + 1; return n }
              return tick
            }
            const a = makeCounter()
            const b = makeCounter()
            console.log(a() + "," + a() + "," + b())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "1,2,1\n")
    }

    /** node prints `11,12,101` — the same with a SEEDED capture, so the two differ. */
    @Test
    fun `two invocations keep their own seeded capture`() {
        val lowered = lower(
            """
            function outer(seed: number): () => number {
              let n = seed
              function tick(): number { n = n + 1; return n }
              return tick
            }
            const p = outer(10)
            const q = outer(100)
            console.log(p() + "," + p() + "," + q())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "11,12,101\n")
    }

    /** node prints `2` — two SIBLING nested functions share one captured `n`. */
    @Test
    fun `two nested functions share one captured variable`() {
        val lowered = lower(
            """
            function makePair(): any {
              let n = 0
              function inc(): number { n = n + 1; return n }
              function get(): number { return n }
              return { inc, get }
            }
            const p = makePair()
            p.inc(); p.inc()
            console.log(p.get())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "2\n")
    }

    // ---- M1c: recursion, mutual recursion, shadowing, nesting --------------

    /** node prints `120` — a nested function calls ITSELF. */
    @Test
    fun `a nested function recurses`() {
        val lowered = lower(
            """
            function outer(n: number): number {
              function fact(x: number): number { return x <= 1 ? 1 : x * fact(x - 1) }
              return fact(n)
            }
            console.log(outer(5))
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "120\n")
    }

    /**
     * node prints `even,odd` — MUTUAL recursion, which is why every slot is
     * created before any body is built.
     */
    @Test
    fun `two nested functions call each other`() {
        val lowered = lower(
            """
            function outer(n: number): string {
              function isEven(x: number): boolean { return x === 0 ? true : isOdd(x - 1) }
              function isOdd(x: number): boolean { return x === 0 ? false : isEven(x - 1) }
              return isEven(n) ? "even" : "odd"
            }
            console.log(outer(4) + "," + outer(7))
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "even,odd\n")
    }

    /** node prints `inner,top` — an inner declaration SHADOWS a top-level one. */
    @Test
    fun `a nested function shadows a top-level one of the same name`() {
        val lowered = lower(
            """
            function helper(): string { return "top" }
            function outer(): string {
              function helper(): string { return "inner" }
              return helper()
            }
            console.log(outer() + "," + helper())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "inner,top\n")
    }

    /** node prints `9` — a nested function inside a nested function. */
    @Test
    fun `a nested function may itself declare one`() {
        val lowered = lower(
            """
            function outer(): number {
              function mid(): number {
                function inner(): number { return 9 }
                return inner()
              }
              return mid()
            }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "9\n")
    }

    // ---- M1d: every statement list that can hold one -----------------------

    /** node prints `3` — inside a plain BLOCK, not a function body. */
    @Test
    fun `a function declared in a block`() {
        val lowered = lower(
            """
            function outer(): number {
              if (true) {
                function helper(): number { return 3 }
                return helper()
              }
              return 0
            }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "3\n")
    }

    /** node prints `42` — a block at the TOP LEVEL of the module. */
    @Test
    fun `a function declared in a top-level block`() {
        val lowered = lower(
            """
            {
              function helper(): number { return 42 }
              console.log(helper())
            }
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "42\n")
    }

    /**
     * node prints `t,d` — a `switch`, whose clauses share ONE block scope.
     *
     * The discriminating shape: `case 1` calls a helper the `default` clause
     * declares, so a per-CLAUSE hoist would leave the first read `undefined`.
     */
    @Test
    fun `a function declared in one switch clause is callable from another`() {
        val lowered = lower(
            """
            function outer(k: number): string {
              switch (k) {
                case 1:
                  return tag()
                default:
                  function tag(): string { return "t" }
                  return "d"
              }
            }
            console.log(outer(1) + "," + outer(2))
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "t,d\n")
    }

    /** node prints `caught` — inside a `catch` block. */
    @Test
    fun `a function declared in a catch block`() {
        val lowered = lower(
            """
            function outer(): string {
              try { throw new Error("x") } catch (e) {
                function tag(): string { return "caught" }
                return tag()
              }
            }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "caught\n")
    }

    /** node prints `i0,i1` — inside a loop body, closing over the loop variable. */
    @Test
    fun `a function declared in a loop block captures the loop variable`() {
        val lowered = lower(
            """
            function outer(): string {
              const parts: string[] = []
              for (let i = 0; i < 2; i = i + 1) {
                function tag(): string { return "i" + i }
                parts.push(tag())
              }
              return parts.join(",")
            }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "i0,i1\n")
    }

    /** node prints `8` — inside a class METHOD, beside a `this` read. */
    @Test
    fun `a function declared in a class method`() {
        val lowered = lower(
            """
            class Holder {
              n: number = 7
              run(): number {
                function helper(): number { return 1 }
                return helper() + this.n
              }
            }
            console.log(new Holder().run())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "8\n")
    }

    /** node prints `12` — inside an ARROW's body. */
    @Test
    fun `a function declared in an arrow body`() {
        val lowered = lower(
            """
            const outer = (): number => {
              function helper(): number { return 12 }
              return helper()
            }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "12\n")
    }

    // ---- M1e: the value position, and IDENTITY -----------------------------

    /**
     * SHAPE and behaviour — as a callback the value costs NO `jsCall`.
     *
     * `map` is a runtime member, so the slot is handed over directly; node
     * prints `2,4`.
     */
    @Test
    fun `a nested function is a callback at no jsCall`() {
        val lowered = lower(
            """
            function outer(): string {
              function dbl(n: number): number { return n * 2 }
              return [1, 2].map(dbl).join(",")
            }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.jsCall == 0)
        assert(lowered.stdout == "2,4\n")
    }

    /**
     * node prints `true` — two reads WITHIN one invocation are the same object,
     * and the function CAPTURES, which is what makes the pin discriminating.
     *
     * A non-capturing one would read `true` on any implementation, because a
     * non-capturing Kotlin lambda is a JVM singleton — (P18.126)'s observation.
     * Capturing forces a real allocation, so `a === b` is `true` only if both
     * reads went through one slot.
     */
    @Test
    fun `two reads of a capturing nested function in one invocation are one object`() {
        val lowered = lower(
            """
            function outer(n: number): boolean {
              function g(): number { return n }
              const a: any = g
              const b: any = g
              return a === b
            }
            console.log(outer(1))
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "true\n")
    }

    /**
     * node prints `false` — two INVOCATIONS mint two function objects.
     *
     * This is the measurement that decides the carrier: (KIR.LOWER.6)'s lazy
     * STATIC is a per-file singleton and would answer `true` here. A nested
     * declaration's identity is per invocation because its closure is, so the
     * carrier is a local.
     */
    @Test
    fun `two invocations mint two capturing function objects`() {
        val lowered = lower(
            """
            function outer(n: number): any {
              function g(): number { return n }
              return g
            }
            console.log(outer(1) === outer(1))
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "false\n")
    }

    /**
     * DIVERGENCE, inherited and not introduced — a NON-capturing nested
     * function reads `true` across invocations where node reads `false`.
     *
     * A non-capturing Kotlin lambda compiles to a singleton instance, so both
     * invocations hand back the same object. The control below is what says
     * this is the EXPRESSION form's standing divergence rather than anything
     * this round added: `const g = function () { return 1 }` answers `true`
     * here too, and has since the first closures landed.
     */
    @Test
    fun `divergence - a non-capturing nested function is one object across invocations`() {
        val lowered = lower(
            """
            function outer(): any {
              function g(): number { return 1 }
              return g
            }
            console.log(outer() === outer())
            """
        )
        assert(lowered.compiled)
        // node: `false`.
        assert(lowered.stdout == "true\n")
    }

    /** The control for the divergence above: the EXPRESSION form answers the same. */
    @Test
    fun `control - the expression form diverges identically`() {
        val lowered = lower(
            """
            function outer(): any {
              const g = function (): number { return 1 }
              return g
            }
            console.log(outer() === outer())
            """
        )
        assert(lowered.compiled)
        // node: `false`.
        assert(lowered.stdout == "true\n")
    }

    // ---- M1f: the parameter shapes the expression form already had ---------

    /** node prints `1:2` — a REST parameter, which decides the carrier's shape. */
    @Test
    fun `a nested function with a rest parameter`() {
        val lowered = lower(
            """
            function outer(): string {
              function g(a: number, ...rest: number[]): string { return a + ":" + rest.length }
              return g(1, 2, 3)
            }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "1:2\n")
    }

    /** node prints `10` — a DEFAULTED parameter, applied in the body's prologue. */
    @Test
    fun `a nested function with a default parameter`() {
        val lowered = lower(
            """
            function outer(): number {
              function g(a: number = 4): number { return a * 2 }
              return g() + g(1)
            }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "10\n")
    }

    /**
     * node prints `v:1/v:x` — OVERLOAD signatures above the implementation.
     *
     * Only the declaration with a BODY takes a slot; the signature-only ones
     * declare nothing at run time, exactly as at file level.
     */
    @Test
    fun `a nested function with overload signatures`() {
        val lowered = lower(
            """
            function outer(): string {
              function pick(a: number): string
              function pick(a: string): string
              function pick(a: any): string { return "v:" + a }
              return pick(1) + "/" + pick("x")
            }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "v:1/v:x\n")
    }

    /** node prints `3` — the value may be read into a local and then replaced. */
    @Test
    fun `a nested function read into a local that is then reassigned`() {
        val lowered = lower(
            """
            function outer(): number {
              function g(): number { return 1 }
              let h: any = g
              h = function () { return 2 }
              return g() + h()
            }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "3\n")
    }

    /** node prints `7` — `this` reaches a nested function the way JavaScript does it. */
    @Test
    fun `a nested function reads this through an alias`() {
        val lowered = lower(
            """
            class Holder {
              n: number = 7
              run(): number {
                const self = this
                function helper(): number { return self.n }
                return helper()
              }
            }
            console.log(new Holder().run())
            """
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "7\n")
    }

    // ---- negative controls, each asserting the MESSAGE ---------------------

    /**
     * negative control — a CLASS declared in a body refuses, and the message
     * names the capability rather than the name.
     *
     * The measurement behind the refusal is in this class's own KDoc: a nested
     * class's identity is per INVOCATION, so the lazy static carrier a
     * top-level class value uses is the wrong shape for it.
     */
    @Test
    fun `negative control - a class declared in a body is refused by name`() {
        val lowered = lower(
            """
            function outer(): string {
              class P { tag: string = "p"; describe(): string { return "P:" + this.tag } }
              return new P().describe()
            }
            console.log(outer())
            """
        )
        assert(!lowered.compiled)
        assert(
            lowered.report.contains(
                "a `class` declared inside a function body or a block is out of the spike subset"
            )
        )
        assert(lowered.report.contains("per INVOCATION rather than per file"))
    }

    /** negative control — the same for a class whose value is merely READ. */
    @Test
    fun `negative control - a class declared in a body is refused in a value position`() {
        val lowered = lower(
            """
            function outer(): string {
              class P { v: number = 1 }
              const C: any = P
              return String(new C().v)
            }
            console.log(outer())
            """
        )
        assert(!lowered.compiled)
        assert(
            lowered.report.contains(
                "a `class` declared inside a function body or a block is out of the spike subset"
            )
        )
        assert(lowered.report.contains("'P'"))
    }

    /**
     * negative control — a nested GENERATOR refuses by message.
     *
     * `node` runs it; the refusal is the spike subset's standing one for a
     * generator anywhere, and it is asserted here so the nested position
     * inherits it rather than silently emitting a broken function.
     */
    @Test
    fun `negative control - a nested generator is refused by message`() {
        val lowered = lower(
            """
            function outer(): number {
              function* gen(): Generator<number> { yield 1 }
              return 1
            }
            console.log(outer())
            """
        )
        assert(!lowered.compiled)
        assert(lowered.report.contains("generators and `async` are out of the spike subset"))
    }

    /**
     * DIVERGENCE — a deferred body CALLED above its own declaration fails, and
     * `node` fails too.
     *
     * A body that reads something its own statement list declares cannot be
     * built at the top of that list, so its slot is still `null` above its
     * declaration. `node` does not run this program either: the name it closes
     * over is in its temporal dead zone, so it throws a `ReferenceError`. Both
     * exit non-zero; only the message differs, and asserting ours is what says
     * the failure is diagnosed rather than silent.
     */
    @Test
    fun `divergence - a capturing nested function called above its declaration throws`() {
        val lowered = lower(
            """
            function outer(): number {
              const r = helper()
              let n = 5
              function helper(): number { return typeof n === "undefined" ? -1 : n }
              return r
            }
            console.log(outer())
            """
        )
        assert(lowered.compiled)
        assert(lowered.exitCode != 0)
        // node: `ReferenceError: Cannot access 'n' before initialization`.
        assert(lowered.stderr.contains("undefined is not a function"))
    }

}
