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
 * (KIR.LOWER.5) `new e(…)` where the checker resolved no constructor.
 *
 * Before this, `lowerNew` resolved a CLASS DECLARATION or refused, so
 * `new (x as any)()` refused for every dynamic callee — which is what
 * `cronstrue`'s all-locales loader is written as, and the last thing between
 * that shape and a running program after (P18.124) gave a namespace import its
 * object and (P18.125) made a barrel enumerate.
 *
 * ## Why a carrier rather than the forwarding lambda
 *
 * A dynamic `new` has nothing but the VALUE to decide on. A class's value used
 * to be a `FunctionN` that constructed when invoked — and so is an ordinary
 * function export's, which does not. Routing `new` at that value would have
 * CALLED a function export and answered its return value where JavaScript makes
 * an object: a silent wrong answer in the one shape whose whole point is that
 * the callee is not statically known. `JsConstructor` is what separates them,
 * so every non-constructible callee throws the `TypeError` node throws
 * (measured against `tools/node/bin/node`, which is where every expectation
 * below comes from).
 *
 * ## Why every shape assertion has a behaviour assertion beside it
 *
 * `(P18.118)`'s lesson, thrice confirmed: the op counters read 0 for a program
 * that never compiled, so `compiled` is asserted FIRST in every shape pin and
 * the behaviour pin beside it is what says the program also ran.
 *
 * ## The one stated divergence from node
 *
 * JavaScript constructs from a plain `function` declaration too, giving an
 * object with that function's prototype. This backend has no prototypes, so
 * there is nothing to construct and answering the function's RETURN VALUE would
 * be a wrong answer rather than a missing feature — it refuses loudly instead,
 * which `negative control - new on a function export refuses` pins.
 */
class KirDynamicNewTest {

    private class Lowered(
        val compiled: Boolean,
        val report: String,
        val jsNew: Int,
        val jsGet: Int,
        val disassembly: String,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
    ) {

        /** In full — see `KirNominalSlotTest` for why the stderr half matters. */
        override fun toString(): String =
            "compiled=$compiled exit=$exitCode jsNew=$jsNew jsGet=$jsGet\n" +
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

    /** The imported module the namespace cases use — one class, one function. */
    private val module = """
        export const alpha: string = "A";
        export function bump(): number { return 7; }
        export class Cls {
            tag: string = "cls";
            describe(): string { return "cls:" + this.tag; }
        }
    """.trimIndent() + "\n"

    private fun lower(vararg files: Pair<String, String>): Lowered {
        val project = Files.createTempDirectory("xtsc-kir-dynamic-new")
        val output = Files.createTempDirectory("xtsc-kir-dynamic-new-out")
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
                return Lowered(false, compilation.toString(), 0, 0, "", "", "", -1)
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
                disassembly.lines().count { it.contains("jsNew") },
                disassembly.lines().count { it.contains("jsGet") },
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

    /**
     * `javap`, not a constant-pool scan — see `KirReceiverShapeTest`'s own note.
     *
     * The counters this feeds are `jsNew` and `jsGet`; `jsIndexGet` is
     * deliberately NOT counted by the `jsGet` one, because its name does not
     * contain it — which CLAUDE.md records as the reason an index-signature
     * fixture is a useless negative control for a shape claim.
     */
    private fun disassemble(outputDirectory: Path): String {
        val javap = Path.of(System.getProperty("java.home"), "bin", "javap").toString()
        val classes = outputDirectory.toFile().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { it.absolutePath }
            .toList()
            .sorted()
        if (classes.isEmpty()) throw AssertionError("the compilation wrote no class files")
        val dump = File.createTempFile("xtsc-kir-dynamic-new-javap", ".txt")
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

    private val namespaceLoader = arrayOf(
        "m.ts" to module,
        "main.ts" to """
            import * as ns from './m'
            const found: any = ns;
            const key: string = "Cls";
            const made: any = new found[key]();
            console.log(made.describe())
        """,
    )

    // ---- the construction itself -------------------------------------------

    @Test
    fun `a dynamic new through a namespace constructs the class`() {
        val lowered = lower(*namespaceLoader)
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "cls:cls\n")
    }

    /**
     * The SHAPE half: ONE `jsNew`, and the construction behind it is a direct
     * JVM `new` of the generated class rather than anything reflective.
     *
     * `compiled` first, deliberately: [lower] reports ZERO operations for a
     * program that never compiled, so a shape pin written as the count alone is
     * satisfied by a REFUSAL — which is exactly what this round removed.
     */
    @Test
    fun `a dynamic new is one jsNew over a direct constructor call`() {
        val lowered = lower(*namespaceLoader)
        assert(lowered.compiled)
        assert(lowered.jsNew == 1)
        // The carrier's `impl` — `new program/Cls` — is what makes the claim
        // that nothing here reaches reflection a fact about the bytecode.
        val constructs = lowered.disassembly.lines()
            .count { it.contains("new") && it.contains("// class program/Cls") }
        assert(constructs == 1)
    }

    @Test
    fun `arguments reach a dynamic construction`() {
        val lowered = lower(
            "m.ts" to """
                export class P {
                    v: string;
                    constructor(x: string, y: number) { this.v = x + y; }
                }
            """,
            "main.ts" to """
                import * as ns from './m'
                const found: any = ns;
                console.log((new found["P"]("a", 2)).v)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "a2\n")
    }

    /**
     * A SURPLUS argument is dropped, which is what JavaScript does.
     *
     * Measured against node: `class B { constructor(x) {…} }; new B("p","q")`
     * answers `p`. The carrier takes every actual argument as one array for
     * exactly this reason — `JsVarargFunction`'s rule, one operation over.
     */
    @Test
    fun `a surplus argument to a dynamic construction is dropped`() {
        val lowered = lower(
            "m.ts" to """
                export class P { v: string; constructor(x: string) { this.v = x; } }
            """,
            "main.ts" to """
                import * as ns from './m'
                const found: any = ns;
                console.log((new found["P"]("p", "q")).v)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "p\n")
    }

    /**
     * The LIBRARY shape, mechanically and end to end — `allLocalesLoader.ts`.
     *
     * `cronstrue`'s loader is `for (const property in allLocales) {
     * locales[property] = new (allLocales as any)[property]() }` over a
     * namespace import of a PURE `export * from` barrel, and every hop of it is
     * here: the barrel, the namespace import, the `for…in`, the dynamic
     * construction, and a METHOD CALL on what was constructed — which is the
     * half that says the value is a real instance rather than a bag.
     *
     * `cronstrue` itself is NOT on this box, so this is a claim about the SHAPE
     * and not about the library, exactly as (P18.124)'s and (P18.125)'s were.
     */
    @Test
    fun `the cronstrue loader shape constructs and calls its locale classes`() {
        val lowered = lower(
            "locales.ts" to """
                export class En { name(): string { return "en"; } }
                export class Fr { name(): string { return "fr"; } }
            """,
            "allLocales.ts" to "export * from './locales'",
            "main.ts" to """
                import * as allLocales from './allLocales'
                const found: any = allLocales;
                const locales: any = {};
                for (const property in allLocales) {
                    locales[property] = new found[property]();
                }
                console.log(locales["En"].name() + "," + locales["Fr"].name())
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "en,fr\n")
    }

    /** The loader's SHAPE half: one `jsNew` for the whole enumeration. */
    @Test
    fun `the loader shape constructs through exactly one jsNew`() {
        val lowered = lower(
            "locales.ts" to """
                export class En { name(): string { return "en"; } }
                export class Fr { name(): string { return "fr"; } }
            """,
            "allLocales.ts" to "export * from './locales'",
            "main.ts" to """
                import * as allLocales from './allLocales'
                const found: any = allLocales;
                const locales: any = {};
                for (const property in allLocales) {
                    locales[property] = new found[property]();
                }
                console.log(locales["En"].name())
            """,
        )
        assert(lowered.compiled)
        assert(lowered.jsNew == 1)
    }

    // ---- what the carrier is, as a value -----------------------------------

    /**
     * A class's VALUE is still a `function` to `typeof`, and still the SAME
     * value every time it is read.
     *
     * Both are JavaScript's answers (`typeof C` is `"function"`, `o.C === o.C`
     * is `true`), and both are the regression this round's carrier could have
     * caused: the value it replaced was a `FunctionN`, which `jsTypeOf` already
     * answered for, and a non-capturing Kotlin lambda which the JVM happens to
     * make a singleton. Neither property survives by accident now — the
     * `jsTypeOf` arm and the lazily-cached static field are what hold them.
     */
    @Test
    fun `a class value is a function to typeof and is read back identical`() {
        val lowered = lower(
            "m.ts" to module,
            "main.ts" to """
                import * as ns from './m'
                const found: any = ns;
                console.log(typeof found["Cls"])
                console.log(found["Cls"] === found["Cls"])
                console.log(String(found["Cls"]))
                console.log(Object.keys(ns).join(","))
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "function\ntrue\nclass Cls\nalpha,bump,Cls\n")
    }

    // ---- the negative controls: failing the way JavaScript fails -----------

    /**
     * `new` on a number, a string, a bag and an arrow — every one a `TypeError`.
     *
     * Node's own answers, measured: `TypeError: c is not a constructor` for all
     * four. It names the EXPRESSION where this runtime names the VALUE, which
     * is this backend's standing convention for the same family of message
     * (`jsCall`'s *is not a function*).
     */
    @Test
    fun `negative control - new on a number throws a TypeError`() {
        val lowered = lower(
            "main.ts" to """
                const c: any = 1;
                console.log(new c())
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 1)
        assert(lowered.stderr.contains("JsTypeError: 1 is not a constructor"))
    }

    @Test
    fun `negative control - new on a string throws a TypeError`() {
        val lowered = lower(
            "main.ts" to """
                const c: any = "s";
                console.log(new c())
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 1)
        assert(lowered.stderr.contains("JsTypeError: s is not a constructor"))
    }

    @Test
    fun `negative control - new on an object literal throws a TypeError`() {
        val lowered = lower(
            "main.ts" to """
                const c: any = { a: 1 };
                console.log(new c())
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 1)
        assert(lowered.stderr.contains("JsTypeError: [object Object] is not a constructor"))
    }

    @Test
    fun `negative control - new on an arrow throws a TypeError`() {
        val lowered = lower(
            "main.ts" to """
                const g = (): number => 3;
                const c: any = g;
                console.log(new c())
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 1)
        assert(lowered.stderr.contains("JsTypeError: function is not a constructor"))
    }

    /**
     * `null` and `undefined` are ONE value in this runtime (design §3.1), so
     * both spell node's `TypeError` the same way.
     */
    @Test
    fun `negative control - new on null throws a TypeError`() {
        val lowered = lower(
            "main.ts" to """
                const c: any = null;
                console.log(new c())
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 1)
        assert(lowered.stderr.contains("JsTypeError: undefined is not a constructor"))
    }

    @Test
    fun `negative control - new on undefined throws a TypeError`() {
        val lowered = lower(
            "main.ts" to """
                const c: any = undefined;
                console.log(new c())
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 1)
        assert(lowered.stderr.contains("JsTypeError: undefined is not a constructor"))
    }

    /**
     * THE pin the carrier exists for — a `new` on a FUNCTION export refuses.
     *
     * Without `JsConstructor` this callee is the same `FunctionN` a class's
     * value was, so `jsNew` would have called it and answered `7`. Node
     * constructs an object here (a `function` declaration IS a constructor
     * there), so the loud refusal is a STATED divergence: this backend has no
     * prototypes, and answering `7` would be a wrong answer rather than a
     * missing feature.
     */
    @Test
    fun `negative control - new on a function export refuses`() {
        val lowered = lower(
            "m.ts" to module,
            "main.ts" to """
                import * as ns from './m'
                const found: any = ns;
                console.log(new found["bump"]())
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 1)
        assert(lowered.stderr.contains("JsTypeError: function is not a constructor"))
        // …and specifically NOT the function's return value.
        assert(lowered.stdout == "")
    }

    /**
     * Calling a class value WITHOUT `new` throws, as node does.
     *
     * Node: `TypeError: Class constructor Cls cannot be invoked without 'new'`.
     * Before the carrier this CONSTRUCTED silently, because the value was a
     * lambda whose body was the construction — so this is a defect the round
     * closed rather than a property it preserved.
     */
    @Test
    fun `negative control - calling a class value without new throws`() {
        val lowered = lower(
            "m.ts" to module,
            "main.ts" to """
                import * as ns from './m'
                const found: any = ns;
                const g: any = found["Cls"];
                console.log(g())
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 1)
        assert(lowered.stderr.contains("JsTypeError: class Cls is not a function"))
    }

    /**
     * A MISSING argument for a parameter whose erased type is not nullable is
     * refused BY NAME rather than crashing.
     *
     * Node answers `undefined/undefined` here; this backend erases `x: string`
     * to a non-null `String`, so the coercion is a `checkcast` and `undefined`
     * reaches it as a JVM `NullPointerException: null cannot be cast to
     * non-null type kotlin.String` — measured, before the guard. The guard
     * refuses EXACTLY that set, so it converts an internal message into one
     * naming the class and the counts and diverges from node nowhere the
     * program would have run.
     */
    @Test
    fun `a missing argument for a non-nullable parameter is refused by name`() {
        val lowered = lower(
            "m.ts" to """
                export class P {
                    v: string;
                    constructor(x: string, y: string) { this.v = x + "/" + y; }
                }
            """,
            "main.ts" to """
                import * as ns from './m'
                const found: any = ns;
                console.log((new found["P"]()).v)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 1)
        assert(
            lowered.stderr.contains(
                "class P cannot be constructed with 0 argument(s): this backend needs 2"
            )
        )
    }

    /**
     * …and an OPTIONAL parameter may be left `undefined`, as JavaScript leaves
     * it: the guard counts the parameters this backend cannot leave unwritten,
     * never the declared ones.
     */
    @Test
    fun `an optional constructor parameter may be omitted by a dynamic new`() {
        val lowered = lower(
            "m.ts" to """
                export class P {
                    v: string;
                    constructor(x?: string) { this.v = x === undefined ? "none" : x; }
                }
            """,
            "main.ts" to """
                import * as ns from './m'
                const found: any = ns;
                console.log((new found["P"]()).v)
                console.log((new found["P"]("given")).v)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "none\ngiven\n")
    }

    /**
     * negative control — a construction this backend does not model is STILL a
     * compile refusal rather than a late `TypeError`.
     *
     * A CONSTRUCT SIGNATURE on an interface resolves a constructor that is no
     * program class, and the dynamic arm must not turn that into a run-time
     * error: a loud compile refusal traded for a late one is the direction that
     * costs a user a diagnosis. (A library constructor — `new Object()` — is
     * the shape a reader expects here and is the WRONG fixture: it refuses one
     * layer earlier still, at *cannot map the type*, so it never reaches
     * `lowerNew` at all.)
     *
     * It is the MESSAGE that discriminates the `signature == null` GATE, not
     * the refusal: with the gate removed this shape still refuses — at the
     * CALLEE, because a library constructor's name is not a reference this
     * backend can lower either — so a pin asserting only `!compiled` reads
     * green on both arms and credits the gate with coverage it has not been
     * tested for (round 807's law). Measured, the ablated arm says *cannot
     * lower the reference* where this says the construction is not a class.
     */
    @Test
    fun `negative control - an unmodelled construction is still a compile refusal`() {
        val lowered = lower(
            "main.ts" to """
                interface Factory { new (): { v: number } }
                function make(f: Factory): any { return new f(); }
                console.log(typeof make)
            """,
        )
        assert(!lowered.compiled)
        assert(lowered.report.contains("cannot lower `new` on a non-class"))
    }

    /**
     * negative control — a `new` whose CALLEE cannot be lowered still refuses
     * at the callee, so the dynamic arm cannot turn a missing reference into a
     * run-time error.
     *
     * RE-POINTED by (KIR.LOWER.6), against the measured answer rather than
     * edited to whatever the new code prints. This test used to hold a
     * TOP-LEVEL `class Cls {}; const c: any = Cls; new c().describe()` and
     * assert *cannot lower the reference 'Cls'* — a countdown on the gap
     * (P18.126) had just found and left out of its own scope. That gap is now
     * closed: the same fixture compiles and prints `d`, which
     * `KirDeclaredValueTest` pins positively.
     *
     * What still cannot be lowered, and so is the honest subject here, is a
     * class declared INSIDE a function body: the declare pass walks a file's
     * TOP-LEVEL statements only, so such a declaration reaches neither
     * `tables.classes` nor `tables.functions` and a name for it refuses. The
     * point the original made survives intact — the dynamic `new` does not
     * paper over a callee the lowering could not build.
     */
    @Test
    fun `negative control - a new whose callee cannot be lowered still refuses`() {
        val lowered = lower(
            "main.ts" to """
                function outer(): string {
                    class Local { describe(): string { return "d"; } }
                    const c: any = Local;
                    return new c().describe();
                }
                console.log(outer())
            """,
        )
        assert(!lowered.compiled)
        // RE-POINTED by (P18.129): the refusal is unchanged and its MESSAGE is
        // not. That round landed the nested-FUNCTION half of this family and
        // gave the class half a refusal naming the capability, where this used
        // to read the generic *cannot lower the reference 'Local'*.
        assert(
            lowered.report.contains(
                "a `class` declared inside a function body or a block is out of the spike subset"
            )
        )
        assert(lowered.report.contains("'Local'"))
    }

}
