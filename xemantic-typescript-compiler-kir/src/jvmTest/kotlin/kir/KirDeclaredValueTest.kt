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
 * (KIR.LOWER.6) A generated FUNCTION or CLASS named in a VALUE position.
 *
 * `lowerIdentifier` had an arm for a local, a module field and the intrinsic
 * names, and none for a generated declaration — so `[1, 2].map(f)` for a
 * top-level named `f` refused at *cannot lower the reference 'f'*, and so did
 * `const c: any = Cls`, `typeof Cls`, `make(Cls)`, `{ Cls }` and `Cls.name`.
 * Passing a named function as a callback is everywhere in real TypeScript,
 * which is what makes this table stakes rather than exotic.
 *
 * ## Every expectation below is node's
 *
 * Measured against `tools/tsgo-7.0.2/lib/tsc` + `tools/node/bin/node` over the
 * same source, which is where each expected string comes from. Where the two
 * diverge it is named as a divergence and its reason is stated.
 *
 * ## Why the two carriers are different, and why both are LAZY STATICS
 *
 * A function's value is a `FunctionN` and a class's is a `JsConstructor`,
 * because (KIR.LOWER.5) measured what one carrier for both costs: a dynamic
 * `new` has nothing but the value to go on, so a shared carrier makes
 * `new found["bump"]()` answer a function's RETURN VALUE. Both are allocated
 * once into a static field, which is what makes `f === f`, `Cls === Cls`,
 * `Cls === ns.Cls` and `C.m === C.m` all `true` as they are in JavaScript —
 * measured, `C.m === C.m` was **false** before this round, because two
 * `IrFunctionExpression`s compile to two anonymous classes with two singleton
 * instances.
 *
 * ## Why every shape assertion has a behaviour assertion beside it
 *
 * (P18.118)'s lesson: the op counters read 0 for a program that never
 * compiled, so `compiled` is asserted FIRST in every shape pin and a behaviour
 * pin beside it is what says the program also ran. And (P18.126)'s sharper
 * form: a `!compiled` negative control credits a refusal gate with untested
 * coverage, so every refusal below asserts the MESSAGE.
 */
class KirDeclaredValueTest {

    private class Lowered(
        val compiled: Boolean,
        val report: String,
        val jsGet: Int,
        val jsCall: Int,
        val jsNew: Int,
        val disassembly: String,
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

    /** The imported module the cross-file cases use — one class, one function. */
    private val module = """
        export function bump(): number { return 7; }
        export class Cls {
            tag: string = "cls";
            describe(): string { return "cls:" + this.tag; }
        }
    """.trimIndent() + "\n"

    private fun lower(vararg files: Pair<String, String>): Lowered {
        val project = Files.createTempDirectory("xtsc-kir-declared-value")
        val output = Files.createTempDirectory("xtsc-kir-declared-value-out")
        try {
            project.resolve("tsconfig.json").writeText(tsconfig)
            files.forEach { (relative, text) ->
                val target = project.resolve("src/$relative")
                target.parent.createDirectories()
                target.writeText(text.trimIndent() + "\n")
            }
            val compilation = compileTypeScriptProjectToJvm(
                projectPath = project.toString(),
                entryFileName = "main.ts",
                outputDirectory = output,
            )
            if (!compilation.successful) {
                return Lowered(false, compilation.toString(), 0, 0, 0, "", "", "", -1)
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
                disassembly,
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
        val dump = File.createTempFile("xtsc-kir-declared-value-javap", ".txt")
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

    // ---- M1: a FUNCTION name in a value position ---------------------------

    /** node prints `2,4` — the shape that makes this item table stakes. */
    @Test
    fun `a named function is a callback`() {
        val lowered = lower(
            "main.ts" to """
                function f(n: number): number { return n * 2 }
                console.log([1, 2].map(f).join(","))
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "2,4\n")
    }

    /**
     * The SHAPE half: the callback reaches `map` as a direct accessor read, so
     * nothing about it is reflective.
     *
     * `compiled` FIRST, deliberately — [lower] reports ZERO operations for a
     * program that never compiled, which is exactly what this round removed,
     * so the count alone would be satisfied by the refusal.
     */
    @Test
    fun `a named function callback reaches map with no dynamic operation`() {
        val lowered = lower(
            "main.ts" to """
                function f(n: number): number { return n * 2 }
                console.log([1, 2].map(f).join(","))
            """,
        )
        assert(lowered.compiled)
        assert(lowered.jsGet == 0)
        assert(lowered.jsNew == 0)
        // The value is read through its lazy accessor, whose name carries the
        // file prefix — one function value in this program, so exactly one.
        val accessors = lowered.disassembly.lines()
            .count { it.contains("fnValue\$main\$0\$get") && it.contains("invokestatic") }
        assert(accessors == 1)
    }

    /** A named function handed to a USER-defined higher-order function; node: `6`. */
    @Test
    fun `a named function reaches a user-defined higher-order function`() {
        val lowered = lower(
            "main.ts" to """
                function f(n: number): number { return n * 2 }
                function apply(g: (n: number) => number, n: number): number { return g(n) }
                console.log(apply(f, 3))
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "6\n")
    }

    /**
     * A function DECLARED BELOW its use — JavaScript hoists it; node: `3,6`.
     *
     * Free here rather than implemented: the declare pass walks every top-level
     * statement before any body is lowered, so the table is complete by the
     * time `main` is built. Pinned because nothing else says so.
     */
    @Test
    fun `a function used before its declaration is hoisted`() {
        val lowered = lower(
            "main.ts" to """
                console.log([1, 2].map(f).join(","))
                function f(n: number): number { return n * 3 }
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "3,6\n")
    }

    /** `typeof f` is `"function"` in node, and the carrier is a `FunctionN`. */
    @Test
    fun `typeof a function name is function`() {
        val lowered = lower(
            "main.ts" to """
                function f(n: number): number { return n }
                console.log(typeof f)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "function\n")
    }

    /** An IMPORTED function is the same question one file over; node: `7`. */
    @Test
    fun `an imported function is a callback`() {
        val lowered = lower(
            "m.ts" to module,
            "main.ts" to """
                import { bump } from './m'
                function apply(g: () => number): number { return g() }
                console.log(apply(bump))
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "7\n")
    }

    /** A function value CALLED through an `any` — `jsCall`'s own path; node: `10`. */
    @Test
    fun `a function value is callable through an any`() {
        val lowered = lower(
            "main.ts" to """
                function f(n: number): number { return n * 2 }
                const g: any = f;
                console.log(g(5))
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "10\n")
    }

    // ---- M2: a CLASS name in a value position -------------------------------

    /**
     * `typeof Cls` is `"function"` in JavaScript — a class's value is a
     * function — and the carrier is the `JsConstructor` (KIR.LOWER.5) built.
     */
    @Test
    fun `typeof a class name is function`() {
        val lowered = lower(
            "main.ts" to """
                class Cls { tag: string = "t" }
                console.log(typeof Cls)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.stdout == "function\n")
    }

    /**
     * A class held in an `any` and CONSTRUCTED through it; node: `t`.
     *
     * The shape (P18.126)'s own negative control used to pin as a refusal —
     * `const c: any = Cls; new c()` — and the one that needed the checker's
     * class-value-as-instance-type quirk ((CHK.73)) worked around in
     * `variableType`: without that, the initializer's checked type is `Cls` and
     * storing the `JsConstructor` in a `program.Cls` slot is refused.
     */
    @Test
    fun `a class held in an any constructs`() {
        val lowered = lower(
            "main.ts" to """
                class Cls { tag: string = "t" }
                const c: any = Cls;
                console.log((new c()).tag)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "t\n")
    }

    /** A class passed as an ARGUMENT and constructed by the callee; node: `t`. */
    @Test
    fun `a class reaches a factory as an argument`() {
        val lowered = lower(
            "main.ts" to """
                class Cls { tag: string = "t" }
                function make(ctor: any): any { return new ctor() }
                console.log(make(Cls).tag)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "t\n")
    }

    /**
     * Classes in an ARRAY LITERAL, each constructed by index; node: `ab`.
     *
     * The registry shape — a table of constructors chosen at run time — which
     * is what `cronstrue`'s loader is and what (KIR.LOWER.5) built the carrier
     * for. Two `jsNew`, one per element access.
     */
    @Test
    fun `a table of classes constructs by index`() {
        val lowered = lower(
            "main.ts" to """
                class A { tag: string = "a" }
                class B { tag: string = "b" }
                const all: any[] = [A, B];
                console.log((new all[0]()).tag + (new all[1]()).tag)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "ab\n")
        assert(lowered.jsNew == 2)
    }

    /** An object literal's SHORTHAND member; node: `function|function|true`. */
    @Test
    fun `a class and a function reach an object literal as shorthand members`() {
        val lowered = lower(
            "main.ts" to """
                class Cls { tag: string = "t" }
                function f(n: number): number { return n }
                const o: any = { Cls, f };
                console.log(typeof o.Cls + "|" + typeof o.f + "|" + (o.Cls === Cls))
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "function|function|true\n")
    }

    /** An IMPORTED class in a value position, constructed and called; node: `cls:cls`. */
    @Test
    fun `an imported class in a value position constructs`() {
        val lowered = lower(
            "m.ts" to module,
            "main.ts" to """
                import { Cls } from './m'
                const c: any = Cls;
                console.log((new c()).describe())
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "cls:cls\n")
    }

    /**
     * The SHAPE half of the class value: one `jsNew` over a DIRECT constructor
     * call, so nothing about the construction is reflective.
     */
    @Test
    fun `a class value constructs through a direct constructor call`() {
        val lowered = lower(
            "main.ts" to """
                class Cls { tag: string = "t" }
                const c: any = Cls;
                console.log((new c()).tag)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.jsNew == 1)
        val constructs = lowered.disassembly.lines()
            .count { it.contains("new") && it.contains("// class program/Cls") }
        assert(constructs == 1)
    }

    // ---- M3: identity, which is what makes both carriers LAZY STATICS -------

    /**
     * `f === f` and `Cls === Cls`; node prints `true` for both.
     *
     * Before this round a function value was a fresh `IrFunctionExpression` per
     * READ, and two of those compile to two anonymous JVM classes with two
     * singleton instances — so this would have been `false` for the function
     * half however correct everything else was.
     */
    @Test
    fun `a function value and a class value are each one object`() {
        val lowered = lower(
            "main.ts" to """
                class Cls { tag: string = "t" }
                function f(n: number): number { return n }
                console.log((f === f) + "|" + (Cls === Cls))
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "true|true\n")
    }

    /**
     * `Cls === ns.Cls` and `bump === ns.bump`; node prints `true` for both.
     *
     * The identity question the brief named as a pin rather than an assumption:
     * a bare name and the NAMESPACE OBJECT's `get` must hand out the same
     * object. They do because both go through the same per-file accessor —
     * `declaredValueOf` and `namespaceExportValue` call [functionValue] and
     * `constructorValue`, which memoize per target.
     *
     * The scope of the claim is ONE FILE, which is what `constructorValues`'
     * and `functionValues`' KDoc already state: the static field a carrier is
     * cached in belongs to the file that declares it, and a cross-file field
     * read is the one thing the IR verifier refuses. Two files that both take a
     * class's value mint two carriers — a stated divergence from an ES module,
     * inherited from (P18.124)'s namespace object.
     */
    @Test
    fun `a bare name and the namespace object hand out the same object`() {
        val lowered = lower(
            "m.ts" to module,
            "main.ts" to """
                import * as ns from './m'
                import { Cls, bump } from './m'
                const a: any = ns;
                console.log((Cls === a.Cls) + "|" + (bump === a.bump))
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "true|true\n")
    }

    /**
     * A STATIC METHOD read as a value twice is one object; node: `true`.
     *
     * The same carrier one read site over, and the measurement that made the
     * laziness a mechanism rather than a tidiness: this printed **false**
     * before this round, on a binary in which everything else about `C.m` as a
     * value was correct.
     */
    @Test
    fun `a static method read twice as a value is one object`() {
        val lowered = lower(
            "main.ts" to """
                class C { static m(): number { return 1 } }
                const a: any = C.m; const b: any = C.m;
                console.log(a === b)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "true\n")
    }

    // ---- M4: a REST parameter decides the CARRIER ---------------------------

    /**
     * A function with a rest parameter, handed to `map`; node: `7:2,8:2`.
     *
     * A fixed-arity forwarder hands the rest slot whatever the caller passed
     * positionally, and that coercion is a `checkcast`: before this the program
     * COMPILED and died with `ClassCastException: Double cannot be cast to
     * JsArray` — the (P18.118) class, zero dynamic operations and a program
     * that does not run. `JsVarargFunction` is the carrier whose arity is
     * decided by the CALL.
     *
     * The FIXED-then-rest form is the one pinned here because the pure-rest
     * form is a TYPE ERROR in tsgo (TS2345: `number[]` is not assignable to
     * `array`), so it could not be a claim about node at all — while
     * `(a: number, ...xs: any[])` in `map` is valid TypeScript and prints
     * `7:2,8:2` on both sides.
     */
    @Test
    fun `a rest-parameter function is a callback`() {
        val lowered = lower(
            "main.ts" to """
                function f(a: number, ...xs: any[]): string { return a + ":" + xs.length }
                console.log([7, 8].map(f).join(","))
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "7:2,8:2\n")
    }

    /**
     * The SHAPE half: the carrier IS a `JsVarargFunction`, which is what makes
     * the pin above a statement about the mechanism and not only about `map`.
     */
    @Test
    fun `a rest-parameter function value is a JsVarargFunction`() {
        val lowered = lower(
            "main.ts" to """
                function f(a: number, ...xs: any[]): string { return a + ":" + xs.length }
                console.log([7, 8].map(f).join(","))
            """,
        )
        assert(lowered.compiled)
        val carriers = lowered.disassembly.lines().count {
            it.contains("jsVarargFunction") && it.contains("invokestatic")
        }
        assert(carriers == 1)
    }

    /** The same value CALLED through an `any` with four arguments; node: `4`. */
    @Test
    fun `a rest-parameter function value packs every actual argument`() {
        val lowered = lower(
            "main.ts" to """
                function f(...xs: number[]): number { return xs.length }
                const g: any = f;
                console.log(g(1, 2, 3, 4))
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "4\n")
    }

    /** A NON-rest function value is still a plain `FunctionN`, not a vararg one. */
    @Test
    fun `negative control - a fixed-arity function value is not a JsVarargFunction`() {
        val lowered = lower(
            "main.ts" to """
                function f(n: number): number { return n * 2 }
                console.log([1, 2].map(f).join(","))
            """,
        )
        assert(lowered.compiled)
        val carriers = lowered.disassembly.lines().count {
            it.contains("jsVarargFunction") && it.contains("invokestatic")
        }
        assert(carriers == 0)
    }

    // ---- M5: `.name`, answered on the carrier rather than reflected at ------

    /** `Cls.name` is the class's declared name; node: `Cls`. */
    @Test
    fun `the name of a class is its declared name`() {
        val lowered = lower(
            "main.ts" to """
                class Cls { tag: string = "t" }
                console.log(Cls.name)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "Cls\n")
    }

    /** `f.name` is the function's declared name; node: `f`. */
    @Test
    fun `the name of a function is its declared name`() {
        val lowered = lower(
            "main.ts" to """
                function f(n: number): number { return n }
                console.log(f.name)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "f\n")
    }

    /**
     * A QUALIFIED `.name` is a CONSTANT, so it reaches neither the carrier nor
     * reflection — the half the brief asked to be measured rather than argued.
     */
    @Test
    fun `a qualified name read is a constant and reaches no dynamic operation`() {
        val lowered = lower(
            "main.ts" to """
                class Cls { tag: string = "t" }
                function f(n: number): number { return n }
                console.log(Cls.name + f.name)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.jsGet == 0)
        assert(lowered.stdout == "Clsf\n")
    }

    /**
     * `.name` through an `any` is answered by the CARRIER; node: `Cls`.
     *
     * The read is a `jsGet`, so this is the arm that keeps it out of
     * `reflectiveGet` — where a Kotlin `public val` is a PRIVATE field behind a
     * `getName` accessor and is found under neither spelling, so the answer was
     * a `JsTypeError` naming `JsConstructor` (measured).
     */
    @Test
    fun `the name of a class value is answered through an any`() {
        val lowered = lower(
            "main.ts" to """
                class Cls { tag: string = "t" }
                const c: any = Cls;
                console.log(c.name)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "Cls\n")
        assert(lowered.jsGet == 1)
    }

    /**
     * negative control — ANY OTHER member of a class value refuses BY NAME.
     *
     * A STATED DIVERGENCE, and the message is asserted rather than the
     * refusal: node answers `undefined` for an absent member and `"K"` for a
     * real static (measured: `undefined|K|Cls`). The carrier holds no statics,
     * so answering `undefined` would be a SILENT WRONG ANSWER for exactly the
     * reads that matter — `docs/kir-lowering.md` §8's refusal discipline, and
     * the reason the arm cannot simply mimic a bag.
     */
    @Test
    fun `negative control - any other member of a class value refuses by name`() {
        val lowered = lower(
            "main.ts" to """
                class Cls { static kind: string = "K" }
                const c: any = Cls;
                console.log(c.kind)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 1)
        assert(
            lowered.stderr.contains(
                "'kind' is not a member of the class Cls; this backend gives a class value " +
                    "only 'name'"
            )
        )
    }

    /**
     * negative control — a member of a FUNCTION value refuses BY NAME.
     *
     * A second stated divergence: node answers `"f"` for `g.name` and
     * `undefined` for an absent one. A `FunctionN` carries neither the
     * declaration's name nor its arity, so both are refused rather than
     * reflected at — where `reflectiveGet` would name a JVM lambda
     * (`MainKt$$Lambda/0x…`), which is an implementation detail and not the
     * program (measured before this arm).
     */
    @Test
    fun `negative control - a member of a function value refuses by name`() {
        val lowered = lower(
            "main.ts" to """
                function f(n: number): number { return n }
                const g: any = f;
                console.log(g.name)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 1)
        assert(
            lowered.stderr.contains(
                "'name' is not a member of a function value; this backend gives one no properties"
            )
        )
    }

    /**
     * A class DECLARING its own `static name` keeps it — a MEASURED REDUNDANT
     * GUARD, recorded rather than claimed.
     *
     * The `name` answer is placed LAST in the static-member block, after the
     * static FIELD and static METHOD searches, which is what JavaScript would
     * do. It can never be reached by a valid program: tsgo refuses
     * `static name` outright (TS2699, *Static property 'name' conflicts with
     * built-in property 'Function.name'*), and our checker accepting it is a
     * separate gap. The ordering is free and kept; this says what it is.
     */
    @Test
    fun `a class declaring its own static name keeps it - tsgo refuses the program`() {
        val lowered = lower(
            "main.ts" to """
                class Cls { static name: string = "own" }
                console.log(Cls.name)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "own\n")
    }

    // ---- what is still refused, and loudly ---------------------------------

    /**
     * negative control — a function declared INSIDE a function body refuses.
     *
     * The declare pass walks a file's TOP-LEVEL statements only, so such a
     * declaration reaches neither `tables.functions` nor `tables.classes` and
     * `declaredValueOf` answers null. Lowering one is a closure question this
     * backend has not answered, and it refuses its CALL too — so this is a
     * standing gap the value arm deliberately does not paper over, not a
     * regression of it. node prints `2,3`.
     *
     * The MESSAGE is asserted, never merely the refusal: a `!compiled` control
     * is satisfied by any refusal at all, including one from a later round's
     * unrelated gate.
     */
    @Test
    fun `negative control - a function declared in a body is refused`() {
        val lowered = lower(
            "main.ts" to """
                function outer(): string {
                  function g(n: number): number { return n + 1 }
                  return [1, 2].map(g).join(",")
                }
                console.log(outer())
            """,
        )
        assert(!lowered.compiled)
        assert(lowered.report.contains("cannot lower the reference 'g'"))
    }

    /** negative control — the same for a CLASS declared in a body; node: `function`. */
    @Test
    fun `negative control - a class declared in a body is refused`() {
        val lowered = lower(
            "main.ts" to """
                function outer(): string {
                  class Local { tag: string = "L" }
                  const c: any = Local;
                  return typeof c
                }
                console.log(outer())
            """,
        )
        assert(!lowered.compiled)
        assert(lowered.report.contains("cannot lower the reference 'Local'"))
    }

    /**
     * negative control — `f.length` is still refused, and loudly.
     *
     * node answers `2`. It is not answered here because the honest source is
     * the declaration's parameter list, and the qualified `.name` arm's
     * precedent would extend to it — but `length` also has a DYNAMIC half
     * (`(f as any).length`) that a `FunctionN` cannot carry, so answering only
     * the qualified one would make the two spellings disagree. Recorded as
     * residue rather than half-answered.
     */
    @Test
    fun `negative control - the length of a function is refused`() {
        val lowered = lower(
            "main.ts" to """
                function f(a: number, b: number): number { return a + b }
                console.log(f.length)
            """,
        )
        assert(!lowered.compiled)
        assert(
            lowered.report.contains(
                "property access on '(a: number, b: number) => number' is out of the spike subset"
            )
        )
    }

    /**
     * negative control — CALLING a class value is a `TypeError`, as in node.
     *
     * (KIR.LOWER.5) closed this for a namespace member; the bare-name arm is a
     * new way to reach the same carrier, so this says the two agree. node:
     * `TypeError: Class constructor Cls cannot be invoked without 'new'`.
     */
    @Test
    fun `negative control - calling a class value is a TypeError`() {
        val lowered = lower(
            "main.ts" to """
                class Cls { tag: string = "t" }
                const c: any = Cls;
                console.log(c())
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 1)
        assert(lowered.stderr.contains("class Cls is not a function"))
    }

    /**
     * negative control — a GENERIC function in a value position still refuses.
     *
     * The erasure has no answer for a bare type parameter, which is a standing
     * gap of its own and not the value arm's: the refusal names the TYPE, not
     * the reference, which is what says the arm resolved the name and the
     * erasure declined.
     */
    @Test
    fun `negative control - a generic function value refuses at its type parameter`() {
        val lowered = lower(
            "main.ts" to """
                function id<T>(x: T): T { return x }
                console.log([1, 2].map(id).join(","))
            """,
        )
        assert(!lowered.compiled)
        assert(lowered.report.contains("cannot map the type 'T'"))
    }
}
