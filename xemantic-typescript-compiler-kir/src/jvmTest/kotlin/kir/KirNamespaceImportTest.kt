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
 * (LIB.7) — `import * as ns from "./m"`, which before this refused outright.
 *
 * An import contributes NOTHING at run time in this backend: the checker turns
 * each imported name into the declaration it names and the declare pass has
 * already generated for that declaration, so a named import is an ordinary
 * cross-file reference. A NAMESPACE import has no single declaration to resolve
 * to — measured, its `Symbol.declarations` is EMPTY — so every use of the alias
 * fell off the end of `lowerIdentifier` as *cannot lower the reference 'ns'*.
 *
 * Two mechanisms close it, and they are deliberately separate because only one
 * of them costs anything at run time:
 *
 *  * a QUALIFIED reference (`ns.c`, `ns.f()`, `new ns.C()`) reaches the
 *    declaration through the module's export table and pays NOTHING — it is the
 *    named-import design with one more hop;
 *  * the alias in a VALUE position (`for…in`, `Object.keys(ns)`, `ns[key]`)
 *    needs an object, which is a generated `JsObject` subclass whose `get` is a
 *    `when` over the exports calling the declaring file's own accessors.
 *
 * ## Why the object holds no values
 *
 * An ES module's exports are LIVE BINDINGS. A bag filled at module-init time —
 * the obvious shape — answers `ns.counter` with the value the export had when
 * the bag was built, so `bump(); ns.counter` reads the OLD number. That is a
 * wrong answer, and `a module whose exports change reads them through the
 * namespace` below is the pin that separates the two designs: it is green only
 * for an object that delegates.
 *
 * ## Why every shape assertion has a behaviour assertion beside it
 *
 * `(P18.118)`'s lesson, twice confirmed: a fixture can emit zero dynamic
 * operations and still not run. `dynamicOps` counts `jsGet`/`jsSet`/`jsInvoke`
 * — a virtual `JsObject.get` is deliberately not one of them — so a program
 * that reached its members through the generated protocol and then threw would
 * read 0 exactly as a correct one does.
 */
class KirNamespaceImportTest {

    private class Lowered(
        val compiled: Boolean,
        val report: String,
        val dynamicOps: Int,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
    ) {

        /** In full — see `KirNominalSlotTest` for why the stderr half matters. */
        override fun toString(): String =
            "compiled=$compiled exit=$exitCode dynamicOps=$dynamicOps\n" +
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

    /**
     * The imported module every case but the export-clause ones uses: one
     * `const`, one mutable `let` with a function that changes it, and a class.
     */
    private val module = """
        export const alpha: string = "A";
        export let counter: number = 0;
        export function bump(): number { counter += 1; return counter; }
        export class Cls {
            tag: string = "cls";
            describe(): string { return "cls:" + this.tag; }
        }
    """.trimIndent() + "\n"

    private fun lower(vararg files: Pair<String, String>): Lowered {
        val project = Files.createTempDirectory("xtsc-kir-namespace")
        val output = Files.createTempDirectory("xtsc-kir-namespace-out")
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
                return Lowered(false, compilation.toString(), 0, "", "", -1)
            }
            val run = runGeneratedProgram(
                output,
                compilation.mainClass,
                GeneratedProgramClasspath.minimal()
            )
            return Lowered(
                true, compilation.toString(), dynamicOps(output),
                run.stdout, run.stderr, run.exitCode
            )
        } finally {
            project.toFile().deleteRecursively()
            output.toFile().deleteRecursively()
        }
    }

    /** `javap`, not a constant-pool scan — see `KirReceiverShapeTest`'s own note. */
    private fun dynamicOps(outputDirectory: Path): Int {
        val javap = Path.of(System.getProperty("java.home"), "bin", "javap").toString()
        val classes = outputDirectory.toFile().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { it.absolutePath }
            .toList()
            .sorted()
        if (classes.isEmpty()) throw AssertionError("the compilation wrote no class files")
        val dump = File.createTempFile("xtsc-kir-namespace-javap", ".txt")
        try {
            ProcessBuilder(listOf(javap, "-p", "-c") + classes)
                .redirectOutput(dump)
                .redirectErrorStream(true)
                .start()
                .waitFor()
            val text = dump.readText()
            if (!text.contains("Code:")) throw AssertionError("javap produced no disassembly")
            return text.lines().count {
                it.contains("jsGet") || it.contains("jsSet") || it.contains("jsInvoke")
            }
        } finally {
            dump.delete()
        }
    }

    // ---- M1: a QUALIFIED reference reaches the declaration -----------------

    private val qualified = arrayOf(
        "m.ts" to module,
        "main.ts" to """
            import * as ns from './m'
            console.log(ns.alpha)
            console.log(ns.bump())
            console.log(new ns.Cls().describe())
        """,
    )

    @Test
    fun `a namespace import's constant, function and class all resolve`() {
        val lowered = lower(*qualified)
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "A\n1\ncls:cls\n")
    }

    /**
     * The SHAPE half: none of the three reaches a runtime dispatch.
     *
     * This is the pin that separates M1 from "the namespace object answers
     * everything": with the qualified arms removed the program still PRINTS the
     * right three lines, through `jsGet` on the generated object — so only the
     * count says the reference was resolved rather than looked up.
     */
    @Test
    fun `a qualified namespace reference costs no runtime dispatch`() {
        // `compiled` first, deliberately: [lower] reports ZERO dynamic
        // operations for a program that never compiled, so a shape pin written
        // as the count alone is satisfied by a REFUSAL.
        val lowered = lower(*qualified)
        assert(lowered.compiled)
        assert(lowered.dynamicOps == 0)
    }

    /**
     * The SHAPE pin for a qualified READ, with no `new` in it.
     *
     * Deliberately separate from the one above, which is satisfied by the
     * `new ns.Cls()` refusal: with the qualified arms removed but the namespace
     * OBJECT present this fixture still COMPILES and still prints the right two
     * lines — through `jsGet` on the object — so the only thing that says the
     * reference was resolved rather than looked up is the count. That is
     * `(KIR.LOWER.3)`'s 33x measured one mechanism over, and the reason a
     * qualified reference is a mechanism of its own rather than a convenience.
     */
    @Test
    fun `a qualified namespace READ costs no runtime dispatch`() {
        val lowered = lower(
            "m.ts" to module,
            "main.ts" to """
                import * as ns from './m'
                console.log(ns.alpha)
                console.log(ns.bump())
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "A\n1\n")
        assert(lowered.dynamicOps == 0)
    }

    /**
     * The LIVE-BINDING pin, and the one that decides the design.
     *
     * `bump()` changes the module's own `let`; reading it back through the
     * namespace must answer the NEW value. A bag filled at module-init time —
     * the shape this round refused — answers `0`.
     */
    @Test
    fun `a module whose exports change reads them through the namespace`() {
        val lowered = lower(
            "m.ts" to module,
            "main.ts" to """
                import * as ns from './m'
                ns.bump()
                ns.bump()
                console.log(ns.counter)
                const dynamic: any = ns;
                console.log(dynamic["counter"])
            """,
        )
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "2\n2\n")
    }

    /**
     * NEGATIVE CONTROL: a LOCAL of the alias's name still shadows it.
     *
     * `moduleSymbolOf` asks the lexical scopes first for exactly this; without
     * that the inner `ns` would resolve to the module and the function would
     * return the namespace object rather than its own string.
     */
    @Test
    fun `negative control - a local shadows the namespace alias`() {
        val lowered = lower(
            "m.ts" to module,
            "main.ts" to """
                import * as ns from './m'
                function inner(): string { const ns: string = "local"; return ns; }
                console.log(inner())
                console.log(ns.alpha)
            """,
        )
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "local\nA\n")
    }

    // ---- M2: the namespace OBJECT ------------------------------------------

    private val enumerated = arrayOf(
        "m.ts" to module,
        "main.ts" to """
            import * as ns from './m'
            for (const key in ns) { console.log(key) }
            console.log(Object.keys(ns).join(","))
            const dynamic: any = ns;
            console.log(dynamic["alpha"])
            console.log(typeof dynamic)
        """,
    )

    @Test
    fun `a namespace import enumerates and indexes as an object`() {
        val lowered = lower(*enumerated)
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(
            lowered.stdout ==
                "alpha\ncounter\nbump\nCls\nalpha,counter,bump,Cls\nA\nobject\n"
        )
    }

    /**
     * The SHAPE half: the object answers through the generated protocol.
     *
     * `Object.keys` and `for…in` go through `JsObject.keys`, and `dynamic[…]`
     * through the bag's element access — none of which is a `jsGet`/`jsSet`/
     * `jsInvoke`. A namespace built as a property BAG would read the same 0, so
     * this pin is about the export values reaching it without reflection, which
     * `a module whose exports change …` above is what really separates.
     */
    @Test
    fun `the namespace object reaches its exports without reflection`() {
        val lowered = lower(*enumerated)
        assert(lowered.compiled)
        assert(lowered.dynamicOps == 0)
    }

    /** A namespace object is one object per module: `ns === ns`. */
    @Test
    fun `the namespace object has a stable identity`() {
        val lowered = lower(
            "m.ts" to module,
            "main.ts" to """
                import * as ns from './m'
                const first: any = ns;
                const second: any = ns;
                console.log(first === second)
            """,
        )
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "true\n")
    }

    /**
     * NEGATIVE CONTROL: a TYPE-only export is not a runtime property.
     *
     * An `interface` and a `type` alias contribute nothing in JavaScript
     * either, so they must be absent from `Object.keys`. An `enum` is absent
     * too, which is a DIVERGENCE and a stated one: an enum has no runtime
     * object in this backend at all, its members being replaced by constants.
     */
    @Test
    fun `negative control - type-only exports are not namespace properties`() {
        val lowered = lower(
            "m.ts" to """
                export interface Shape { w: number }
                export type Alias = string;
                export enum Flavour { A, B }
                export const only: string = "only";
            """,
            "main.ts" to """
                import * as ns from './m'
                console.log(Object.keys(ns).join(","))
            """,
        )
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "only\n")
    }

    /**
     * A class export is a FUNCTION value, which is what `typeof C` answers in
     * JavaScript — and what makes the `new (ns as any)[k]()` shape a question
     * about a dynamic `new` rather than about namespaces.
     */
    @Test
    fun `a class export answers a function value through the object`() {
        val lowered = lower(
            "m.ts" to module,
            "main.ts" to """
                import * as ns from './m'
                const dynamic: any = ns;
                console.log(typeof dynamic["Cls"])
                console.log(typeof dynamic["bump"])
                console.log(typeof dynamic["nothingHere"])
            """,
        )
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "function\nfunction\nundefined\n")
    }

    /**
     * CROSS-FILE: the importer is not the entry file.
     *
     * The namespace class and its lazy instance are generated into the
     * IMPORTING file, so this is the case that would break if they were built
     * into the imported one — `(P18.122)`'s own lesson, where every single-file
     * case was green and only the cross-file pin saw the IR verifier refuse a
     * declaration reached from another file.
     */
    @Test
    fun `a namespace object works in a file that is not the entry point`() {
        val lowered = lower(
            "m.ts" to module,
            "reader.ts" to """
                import * as ns from './m'
                export function read(): string {
                    const dynamic: any = ns;
                    return Object.keys(ns).join("|") + "/" + dynamic["alpha"];
                }
            """,
            "main.ts" to """
                import { read } from './reader'
                console.log(read())
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.dynamicOps == 0)
        assert(lowered.stdout == "alpha|counter|bump|Cls/A\n")
    }

    /**
     * TWO modules namespace-imported into one file.
     *
     * Each gets its own generated class, and the generated names carry the FILE
     * and an index for the reason a shape class's do: a JVM class clash
     * surfaces as a mangled program rather than as a diagnostic.
     */
    @Test
    fun `two namespace imports in one file keep their own objects`() {
        val lowered = lower(
            "first.ts" to "export const one: string = \"1\";",
            "second.ts" to "export const two: string = \"2\";",
            "main.ts" to """
                import * as a from './first'
                import * as b from './second'
                console.log(Object.keys(a).join(",") + ";" + Object.keys(b).join(","))
                console.log(a.one + b.two)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "one;two\n12\n")
    }

    /**
     * The LIBRARY shape, mechanically — `cronstrue`'s `allLocalesLoader.ts`.
     *
     * That loader is `for (const property in allLocales) { locales[property] =
     * new (allLocales as any)[property]() }` over a namespace import of a PURE
     * `export * from` BARREL — which is why the import here goes through one,
     * (LIB.8) being what made that shape lowerable at all. Half of the loader
     * runs: the enumeration, and reading each export back as the function value
     * JavaScript says a class is. The other half — constructing through a
     * dynamic callee — is refused by `lowerNew` for ANY dynamic callee and is
     * pinned as a residue below.
     *
     * `cronstrue` itself is NOT on this box, so this is a claim about the
     * SHAPE and not about the library.
     */
    @Test
    fun `the cronstrue loader shape enumerates and reads its locale classes`() {
        val lowered = lower(
            "locales.ts" to """
                export class En { name(): string { return "en"; } }
                export class Fr { name(): string { return "fr"; } }
            """,
            "allLocales.ts" to "export * from './locales'",
            "main.ts" to """
                import * as allLocales from './allLocales'
                const found: any = allLocales;
                for (const property in allLocales) {
                    console.log(property + "=" + typeof found[property])
                }
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.dynamicOps == 0)
        assert(lowered.stdout == "En=function\nFr=function\n")
    }

    // ---- M3: a RENAMED export answers by the name an IMPORTER sees --------

    /**
     * `export { inner as outer }` — `ns.outer` reads, and `Object.keys` reports
     * `outer`.
     *
     * (P18.124) REFUSED this module: the checker's export table is the file's
     * `locals`, keyed by the DECLARED name, so `ns.outer` found nothing and
     * `Object.keys(ns)` reported `inner` — (INC.51)'s two-spellings-one-symbol
     * trap one table over, `ExportSpecifier.propertyName` being the local and
     * `.name` what the importer sees. (LIB.8) re-keys it at the source, in
     * `Checker.exportedSymbolsThroughStars`, and the expectation here is tsgo
     * 7.0.2's own, measured by running the same program under `node`.
     */
    @Test
    fun `a renamed export reads and enumerates by its exported name`() {
        val lowered = lower(
            "m.ts" to """const inner: string = "R"; export { inner as outer };""",
            "main.ts" to """
                import * as ns from './m'
                console.log(ns.outer)
                console.log(Object.keys(ns).join(","))
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "R\nouter\n")
    }

    /** The SHAPE half: a renamed export is still a RESOLVED reference. */
    @Test
    fun `a renamed export costs no runtime dispatch`() {
        val lowered = lower(
            "m.ts" to """const inner: string = "R"; export { inner as outer };""",
            "main.ts" to """
                import * as ns from './m'
                console.log(ns.outer)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "R\n")
        assert(lowered.dynamicOps == 0)
    }

    /**
     * The DECLARED name is no longer visible — the other half of the re-keying.
     *
     * `ns.inner` is TS2339 in both references, and before this it was the ONLY
     * name that worked; a pin on `outer` alone cannot see that `inner` stopped
     * being an export, which is what makes the enumeration the assertion.
     */
    @Test
    fun `negative control - a renamed export hides its declared name`() {
        val lowered = lower(
            "m.ts" to """const inner: string = "R"; export { inner as outer };""",
            "main.ts" to """
                import * as ns from './m'
                const dynamic: any = ns;
                console.log(typeof dynamic["inner"])
                console.log(typeof dynamic["outer"])
            """,
        )
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "undefined\nstring\n")
    }

    @Test
    fun `negative control - a non-renaming export clause still works`() {
        val lowered = lower(
            "m.ts" to """const inner: string = "R"; export { inner };""",
            "main.ts" to """
                import * as ns from './m'
                console.log(ns.inner)
                console.log(Object.keys(ns).join(","))
            """,
        )
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "R\ninner\n")
    }

    /**
     * A module-PRIVATE local is not a namespace property.
     *
     * The third way the `locals` table was wrong for an enumeration, and the
     * one no renaming fixture shows: `secret` is in that table and is exported
     * by nothing, so before this it was a key of `Object.keys(ns)`.
     */
    @Test
    fun `negative control - a module-private local is not a namespace property`() {
        val lowered = lower(
            "m.ts" to """
                const secret: string = "S";
                export const shown: string = "V" + secret;
            """,
            "main.ts" to """
                import * as ns from './m'
                console.log(Object.keys(ns).join(","))
                const dynamic: any = ns;
                console.log(typeof dynamic["secret"])
            """,
        )
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "shown\nundefined\n")
    }

    // ---- M1: a `export * from` BARREL enumerates and resolves ---------------

    /**
     * The shape `cronstrue`'s `allLocales.ts` is: a PURE re-export barrel.
     *
     * (P18.124) refused it with *cannot lower the reference 'ns'*, because the
     * checker resolves a star re-export at LOOKUP time
     * (`resolveExportedSymbolThroughStars`) and never populates the barrel
     * module's own `exports` — which IS its file's `locals`, and a pure barrel
     * declares none. `Checker.exportedSymbolsThroughStars` is the ENUMERATION
     * companion that answers instead.
     *
     * Expectations measured under tsgo 7.0.2 + `node`.
     */
    @Test
    fun `a namespace import of a re-export barrel resolves its members`() {
        val lowered = lower(
            "a.ts" to module,
            "b.ts" to "export * from './a'",
            "main.ts" to """
                import * as ns from './b'
                console.log(ns.alpha)
                console.log(ns.bump())
                console.log(new ns.Cls().describe())
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "A\n1\ncls:cls\n")
    }

    /**
     * The SHAPE half: a barrel's members are RESOLVED references, not lookups.
     *
     * `(P18.118)`'s law — `compiled` first, because [lower] reports ZERO dynamic
     * operations for a program that never compiled, so the count alone is
     * satisfied by the very refusal this pin exists to have closed.
     */
    @Test
    fun `a barrel's qualified members cost no runtime dispatch`() {
        val lowered = lower(
            "a.ts" to module,
            "b.ts" to "export * from './a'",
            "main.ts" to """
                import * as ns from './b'
                console.log(ns.alpha)
                console.log(ns.bump())
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "A\n1\n")
        assert(lowered.dynamicOps == 0)
    }

    /** A barrel ENUMERATES, which is the half `cronstrue`'s loader needs. */
    @Test
    fun `a namespace import of a re-export barrel enumerates its members`() {
        val lowered = lower(
            "a.ts" to module,
            "b.ts" to "export * from './a'",
            "main.ts" to """
                import * as ns from './b'
                console.log(Object.keys(ns).join(","))
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        // tsgo answers `Cls,alpha,bump,counter`: an ES module namespace object
        // SORTS its keys. This backend reports declaration order, which is
        // (P18.124)'s stated divergence and not this round's.
        assert(lowered.stdout == "alpha,counter,bump,Cls\n")
    }

    /**
     * A barrel that ALSO declares its own exports — the shape that was a SILENT
     * WRONG ANSWER rather than a refusal.
     *
     * Its `locals` table is non-empty, so `moduleSymbolOf`'s "empty means
     * refuse" guard passed and the starred names came back `null` at run time:
     * measured before this round, `ns.alpha + ns.ownV` printed `nullOWN`. That
     * is the failure direction every gate in this repo is blind to.
     */
    @Test
    fun `a barrel with its own exports resolves both halves`() {
        val lowered = lower(
            "m.ts" to module,
            "barrel.ts" to """
                export * from './m'
                export const ownV: string = "OWN";
            """,
            "main.ts" to """
                import * as ns from './barrel'
                ns.bump()
                console.log(ns.counter)
                console.log(ns.alpha + ns.ownV)
                console.log(Object.keys(ns).join(","))
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.dynamicOps == 0)
        assert(lowered.stdout == "1\nAOWN\nalpha,counter,bump,Cls,ownV\n")
    }

    /** A barrel's exports are LIVE BINDINGS too — the design pin, one hop out. */
    @Test
    fun `a barrel whose exports change reads them through the namespace`() {
        val lowered = lower(
            "a.ts" to module,
            "b.ts" to "export * from './a'",
            "main.ts" to """
                import * as ns from './b'
                ns.bump()
                ns.bump()
                console.log(ns.counter)
                const dynamic: any = ns;
                console.log(dynamic["counter"])
            """,
        )
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "2\n2\n")
    }

    /** A barrel over a BARREL — the chain, not one hop. */
    @Test
    fun `a nested barrel resolves through both hops`() {
        val lowered = lower(
            "a.ts" to "export const alpha: string = \"A\";",
            "inner.ts" to """
                export * from './a'
                export const midv: string = "M";
            """,
            "barrel.ts" to "export * from './inner'",
            "main.ts" to """
                import * as ns from './barrel'
                console.log(ns.alpha + ns.midv)
                console.log(Object.keys(ns).join(","))
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.dynamicOps == 0)
        assert(lowered.stdout == "AM\nalpha,midv\n")
    }

    /** A star-export CYCLE terminates, and both files' names are reachable. */
    @Test
    fun `a star-export cycle resolves both files`() {
        val lowered = lower(
            "a.ts" to """
                export * from './b'
                export const av: string = "A";
            """,
            "b.ts" to """
                export * from './a'
                export const bv: string = "B";
            """,
            "main.ts" to """
                import * as ns from './a'
                console.log(ns.av + ns.bv)
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "AB\n")
    }

    /**
     * `export { x as default }` — the name an importer sees is `default`.
     *
     * (P18.124) refused this through the same renamed-export guard. tsgo
     * enumerates `default,plain` and answers `X` for `ns.default`.
     */
    @Test
    fun `an as-default export answers under the name default`() {
        val lowered = lower(
            "m.ts" to """
                const x: string = "X";
                export { x as default };
                export const plain: string = "P";
            """,
            "main.ts" to """
                import * as ns from './m'
                console.log(Object.keys(ns).join(","))
                const dynamic: any = ns;
                console.log(dynamic["default"] + dynamic["plain"])
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "default,plain\nXP\n")
    }

    /**
     * A star re-export does NOT carry `default` — ES semantics.
     *
     * The negative control for the arm above: without it a barrel of a module
     * with a default export would grow a `default` key that tsgo does not have.
     */
    @Test
    fun `negative control - a star re-export does not carry default`() {
        val lowered = lower(
            "a.ts" to """
                const x: string = "X";
                export { x as default };
                export const plain: string = "P";
            """,
            "barrel.ts" to "export * from './a'",
            "main.ts" to """
                import * as ns from './barrel'
                console.log(Object.keys(ns).join(","))
            """,
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "plain\n")
    }

    /**
     * An UNKNOWABLE export set is REFUSED, not answered from the locals table.
     *
     * The only honest answer a backend can give there is a loud one: a short
     * namespace object is the silent wrong answer this round exists to remove,
     * so the refusal is the mechanism and not a shortfall.
     *
     * The fixture is a barrel chain PAST the walk's depth bound, because that
     * is the one unknowable case this harness can reach: a bare specifier and a
     * missing target are both TS2307 and an `export =` target is TS2498, so the
     * checker stops all three before any backend sees them. A bare specifier
     * that RESOLVES (a package with types) is the case a real library hits, and
     * it needs a `node_modules`, which this harness has none of.
     */
    @Test
    fun `a barrel chain past the depth bound is refused, loudly`() {
        val hops = 70
        val files = mutableListOf<Pair<String, String>>()
        files += "leaf.ts" to "export const deep: string = \"D\";"
        // hop0 stars the leaf, hopN stars hop(N-1) — so `main` is 71 hops out.
        files += "hop0.ts" to "export * from './leaf'"
        for (i in 1 until hops) files += "hop$i.ts" to "export * from './hop${i - 1}'"
        files += "main.ts" to """
            import * as ns from './hop${hops - 1}'
            console.log(ns.deep)
        """
        val lowered = lower(*files.toTypedArray())
        assert(!lowered.compiled)
        assert(lowered.report.contains("export set is not knowable"))
    }

    // ---- what is still refused, recorded rather than claimed ---------------

    /**
     * residue — `new (x as any)()` is refused for ANY dynamic callee.
     *
     * `cronstrue`'s loader spells its construction that way, and this backend
     * has no dynamic `new` at all: `lowerNew` resolves a class declaration or
     * refuses. The namespace object already answers the class export as a
     * function value, so what is missing is one arm in `lowerNew`, not anything
     * about modules.
     */
    @Test
    fun `residue - a dynamic new is still refused`() {
        val lowered = lower(
            "m.ts" to module,
            "main.ts" to """
                import * as ns from './m'
                const key: string = "Cls";
                const made: any = new (ns as any)[key]();
                console.log(made)
            """,
        )
        assert(!lowered.compiled)
        assert(lowered.report.contains("`new`"))
    }

}
