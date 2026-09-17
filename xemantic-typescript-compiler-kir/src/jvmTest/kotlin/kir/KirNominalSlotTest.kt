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
 * (LIB.6) — a lowered `class` instance reaching an INTERFACE-typed slot.
 *
 * An interface, a `type` alias and an inline type literal all erase to the
 * runtime's property bag (`docs/kir-design.md` §3.3), and before this a lowered
 * `class` was a bare JVM class, so `i18n: Locale = new en()` could not be
 * stored at all. The fix is the decision the backend had already taken once and
 * written into `JsObject`'s own KDoc — a generated OBJECT LITERAL extends the
 * bag "so that NOTHING about assignability changes" — applied to a class as
 * well: it extends `JsObject` and overrides the bag protocol over its own
 * inheritance chain's fields and methods.
 *
 * ## Why every case here needs a BEHAVIOUR assertion beside its shape one
 *
 * The failure this closes had TWO costumes, measured on the parent binary:
 *
 *  * where the slot's erased type was reached directly — a local, a parameter,
 *    a return, a class FIELD — the coercion was `IMPOSSIBLE` and the backend
 *    REFUSED, loudly;
 *  * where it was reached through `Any?` — an ARRAY element, a `Map` value, an
 *    object-literal property — the coercion was a `CAST`, so the program
 *    COMPILED, read **zero** dynamic member operations, and died at run time
 *    with `ClassCastException: program.En cannot be cast to … JsObject`.
 *
 * So the second family is invisible to the shape instrument by construction,
 * which is `(P18.118)`'s own lesson one mechanism over: a fixture can emit no
 * dynamic operations at all and still not run.
 *
 * ## What the shape assertions are for
 *
 * `dynamicOps` counts `jsGet`/`jsSet`/`jsInvoke` — the three RUNTIME dispatches
 * that look a member up by name on a value whose type the checker declined to
 * give. A virtual `JsObject.get`/`invokeMember` is deliberately NOT one of
 * them: it is the bag protocol, monomorphic at a call site whose name is a
 * constant, and the whole reason the generated override exists rather than a
 * reflective fallback (`docs/perf/kir-backend-levers.md` §2a, and the 33x
 * `(KIR.LOWER.3)` measured for the reflective alternative).
 */
class KirNominalSlotTest {

    private class Lowered(
        val compiled: Boolean,
        val report: String,
        val dynamicOps: Int,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
    ) {

        /**
         * Rendered in full, because power-assert prints the RECEIVER of a
         * failing comparison and the useful half of a generated program's
         * failure is on stderr — a `ClassCastException` in the lowered bytecode
         * leaves stdout empty, which on its own says only "not that string".
         */
        override fun toString(): String =
            "compiled=$compiled exit=$exitCode dynamicOps=$dynamicOps\n" +
                "--- stdout ---\n$stdout--- stderr ---\n$stderr$report"

    }

    private fun lower(source: String): Lowered {
        val output = Files.createTempDirectory("xtsc-kir-nominal")
        try {
            val compilation = compileTypeScriptToJvm(
                fileName = "slot.ts",
                source = source.trimIndent(),
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
        val dump = File.createTempFile("xtsc-kir-nominal-javap", ".txt")
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

    private val locale = """
        interface Locale {
            code: string;
            describe(n: number): string;
        }

        class En implements Locale {
            code: string = "en";
            describe(n: number): string { return this.code + ":" + n; }
        }
    """.trimIndent() + "\n"

    // ---- M1 storage: a class instance reaches a bag-typed slot -------------

    /**
     * `cronstrue`'s own shape, and the one the item was written from.
     *
     * Before this it was `Can not set JsObject field
     * program.ExpressionDescriptor.i18n to program.en` at RUN time; since
     * `(P18.118)` made a `this` member write a direct field store it became a
     * compile-time refusal, `cannot coerce program.En to …JsObject`.
     */
    private val constructorAssignedField = locale + """
        class Describer {
            i18n: Locale;
            constructor() { this.i18n = new En(); }
            run(): string { return this.i18n.describe(7); }
        }
        console.log(new Describer().run());
    """

    @Test
    fun `a class instance assigned to an interface-typed field in a constructor`() {
        val lowered = lower(constructorAssignedField)
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "en:7\n")
    }

    @Test
    fun `that program reaches its members without a runtime dispatch`() {
        assert(lower(constructorAssignedField).dynamicOps == 0)
    }

    @Test
    fun `an interface-typed field INITIALIZER takes a class instance`() {
        val lowered = lower(
            locale + """
            class Describer {
                i18n: Locale = new En();
                run(): string { return this.i18n.describe(1); }
            }
            console.log(new Describer().run());
            """
        )
        assert(lowered.dynamicOps == 0)
        assert(lowered.stdout == "en:1\n")
    }

    @Test
    fun `a local, a parameter and a return all take a class instance`() {
        val lowered = lower(
            locale + """
            function use(l: Locale): string { return l.describe(2); }
            function mk(): Locale { return new En(); }
            const local: Locale = new En();
            console.log(local.describe(1));
            console.log(use(new En()));
            console.log(mk().describe(3));
            """
        )
        assert(lowered.dynamicOps == 0)
        assert(lowered.stdout == "en:1\nen:2\nen:3\n")
    }

    /**
     * The three positions reached through `Any?`, where the parent binary
     * COMPILED, read `dynamicOps == 0`, and threw `ClassCastException`.
     *
     * The behaviour assertion is the whole pin here: the shape one was already
     * satisfied by the broken program.
     */
    @Test
    fun `an array element, a Map value and an object-literal property take one`() {
        val lowered = lower(
            locale + """
            interface Box { l: Locale; }
            const many: Locale[] = [new En()];
            const byName = new Map<string, Locale>();
            byName.set("en", new En());
            const box: Box = { l: new En() };
            const got = byName.get("en");
            console.log(many[0].describe(1));
            console.log(got ? got.describe(2) : "none");
            console.log(box.l.describe(3));
            """
        )
        assert(lowered.compiled)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "en:1\nen:2\nen:3\n")
    }

    @Test
    fun `a type alias and an inline type literal are the same slot`() {
        val lowered = lower(
            """
            type Named = { name(): string };
            class En { name(): string { return "en"; } }
            function inline(v: { name(): string }): string { return v.name(); }
            const alias: Named = new En();
            console.log(alias.name());
            console.log(inline(new En()));
            """
        )
        assert(lowered.dynamicOps == 0)
        assert(lowered.stdout == "en\nen\n")
    }

    // ---- M2 the field half of the protocol ---------------------------------

    @Test
    fun `a property is read and WRITTEN through an interface-typed slot`() {
        val lowered = lower(
            locale + """
            const l: Locale = new En();
            console.log(l.code);
            l.code = "fr";
            console.log(l.code);
            console.log(l.describe(0));
            """
        )
        assert(lowered.dynamicOps == 0)
        // The third line is what says the WRITE reached the class's own field
        // rather than shadowing it in the bag: the method reads `this.code`
        // through the JVM field.
        assert(lowered.stdout == "en\nfr\nfr:0\n")
    }

    @Test
    fun `a numeric field survives the round trip through the bag protocol`() {
        // The slot's JVM type is `Double` while the protocol speaks `Any?`, so
        // this is the only case where the generated `set` has a real unbox to
        // get wrong — and getting it wrong is a `ClassCastException`, not a
        // wrong number.
        val lowered = lower(
            """
            interface Counter { n: number; }
            class Impl { n: number = 1; }
            const c: Counter = new Impl();
            c.n = c.n + 41;
            console.log(c.n);
            """
        )
        assert(lowered.dynamicOps == 0)
        assert(lowered.stdout == "42\n")
    }

    @Test
    fun `hasOwn and Object keys see a class instance's own fields, and SPILL it`() {
        // `Object.hasOwn` rather than the `in` operator and `Object.keys`
        // rather than `delete`: this backend lowers neither of those two
        // ("cannot lower this binary operator", "cannot lower this
        // DeleteExpression"), so a case written with them would have measured
        // a refusal rather than the protocol.
        //
        // The line AFTER the enumeration is the real assertion. `keys()` calls
        // `spillNow()`, which dispatches `spill()` virtually and moves every
        // field of the chain into the bag; from then on `shapeActive()` is
        // false and every read takes the bag path. A `spill` override that
        // moved nothing would leave the enumeration empty, and one that moved
        // the wrong slot would leave the read answering `undefined` — both of
        // them silent.
        val lowered = lower(
            """
            class Base { tag: string = "b"; }
            class Point extends Base { x: number = 1; y: number = 2; }
            const p: any = new Point();
            console.log(Object.hasOwn(p, "x"));
            console.log(Object.hasOwn(p, "zz"));
            console.log(Object.keys(p).join(","));
            console.log(p.x);
            console.log(p.tag);
            """
        )
        assert(lowered.stdout == "true\nfalse\ntag,x,y\n1\nb\n")
    }

    @Test
    fun `a base class's field is reached through an interface-typed slot`() {
        val lowered = lower(
            """
            interface Shape { kind: string; area(): number; }
            class Base { kind: string = "base"; }
            class Square extends Base {
                side: number = 3;
                area(): number { return this.side * this.side; }
            }
            const s: Shape = new Square();
            console.log(s.kind);
            console.log(s.area());
            """
        )
        assert(lowered.dynamicOps == 0)
        assert(lowered.stdout == "base\n9\n")
    }

    /**
     * A name declared in BOTH a base and a derived class.
     *
     * JavaScript has one slot per name, at the position the base inserted it
     * and holding whatever ran last — which the generated `get` reproduces by
     * letting the derived field win while keeping the base's `Object.keys`
     * position.
     */
    @Test
    fun `a redeclared field answers the derived slot at the base's position`() {
        val lowered = lower(
            """
            class Base { tag: string = "base"; extra: number = 1; }
            class Derived extends Base { tag: string = "derived"; }
            const d: any = new Derived();
            console.log(d.tag);
            console.log(Object.keys(d).join(","));
            """
        )
        assert(lowered.stdout == "derived\ntag,extra\n")
    }

    // ---- M3 the method half of the protocol --------------------------------

    @Test
    fun `a method with an OPTIONAL parameter is called through the slot`() {
        // JavaScript pads a short argument list with `undefined`; the generated
        // `invokeMember` reads every slot through `jsArgument`, which answers
        // past the end rather than indexing.
        val lowered = lower(
            """
            interface Greeter { hello(who?: string): string; }
            class En {
                hello(who?: string): string { return who ? "hi " + who : "hi"; }
            }
            const g: Greeter = new En();
            console.log(g.hello());
            console.log(g.hello("you"));
            """
        )
        assert(lowered.dynamicOps == 0)
        assert(lowered.stdout == "hi\nhi you\n")
    }

    @Test
    fun `a void method called through the slot runs and mutates its receiver`() {
        // Its RESULT is deliberately not read. A bag member call has answered
        // `Any?` since long before this — `adaptingCall` types `jsCall` that
        // way too — so `const answer = s.push(5)` casts a `null` to `Unit` and
        // throws, identically on the parent binary. That is an older gap in the
        // erasure of a `void` call, not this one.
        val lowered = lower(
            """
            interface Sink { push(v: number): void; }
            class Impl {
                total: number = 0;
                push(v: number): void { this.total = this.total + v; }
            }
            const impl = new Impl();
            const s: Sink = impl;
            s.push(5);
            s.push(3);
            console.log(impl.total);
            """
        )
        assert(lowered.dynamicOps == 0)
        assert(lowered.stdout == "8\n")
    }

    @Test
    fun `a method inherited from a base is called through the slot`() {
        val lowered = lower(
            """
            interface Named { name(): string; }
            class Base { name(): string { return "base"; } }
            class Derived extends Base {}
            class Other extends Base { name(): string { return "other"; } }
            const a: Named = new Derived();
            const b: Named = new Other();
            console.log(a.name());
            console.log(b.name());
            """
        )
        assert(lowered.dynamicOps == 0)
        assert(lowered.stdout == "base\nother\n")
    }

    // ---- M4 a member whose name is the bag protocol's ----------------------

    @Test
    fun `a class member sharing the bag protocol's JVM signature does not override it`() {
        // Both members here have EXACTLY `JsObject`'s descriptor —
        // `get(String)Object` and `spill()V` — which is what makes the case
        // discriminate: a member whose return type merely differs
        // (`get(k: string): string`) is a distinct JVM method and collides with
        // nothing, so a fixture written that way passes with the mangling
        // removed and measures the mangling not at all. Measured: it did.
        //
        // Without the `$` suffix the accidental override is total and silent.
        // `s.n` is a bag READ, so it calls the object's `get` — the USER's,
        // answering `"user-n"` for a field holding 5. And `Object.keys` calls
        // `spillNow`, which dispatches `spill()` virtually — the USER's, which
        // moves no slot — so the object enumerates nothing.
        val lowered = lower(
            """
            interface Store { get(k: string): any; spill(): void; n: number; }
            class Impl {
                n: number = 5;
                get(k: string): any { return "user-" + k; }
                spill(): void { this.n = 99; }
            }
            const s: Store = new Impl();
            console.log(s.get("a"));
            console.log(s.n);
            const dynamic: any = new Impl();
            console.log(dynamic.get("b"));
            console.log(Object.keys(dynamic).join(","));
            console.log(dynamic.n);
            """
        )
        assert(lowered.stdout == "user-a\n5\nuser-b\nn\n5\n")
    }

    // ---- the runtime's view of such an instance ----------------------------

    @Test
    fun `String of a lowered class instance is object Object, and toString wins`() {
        val lowered = lower(
            """
            class Plain { v: number = 1; }
            class Spoken { toString(): string { return "spoken"; } }
            console.log("" + new Plain());
            console.log("" + new Spoken());
            """
        )
        assert(lowered.stdout == "[object Object]\nspoken\n")
    }

    // ---- negative controls -------------------------------------------------

    @Test
    fun `negative control - an any receiver still dispatches at run time`() {
        // What licenses reaching a member directly is that the receiver's TYPE
        // named a table. A value the checker declined to type has none, and a
        // change that routed everything through the bag protocol would pass
        // every case above while refusing every genuinely dynamic program.
        val lowered = lower(
            """
            class Holder { v: number = 7; get(): number { return this.v; } }
            function readIt(o: any): number { return o.v; }
            function callIt(o: any): number { return o.get(); }
            console.log(readIt(new Holder()));
            console.log(callIt(new Holder()));
            """
        )
        assert(lowered.dynamicOps > 0)
        assert(lowered.stdout == "7\n7\n")
    }

    /**
     * RESIDUE, pinned so that closing it is a visible change.
     *
     * A class extending one of the backend's own runtime classes cannot also
     * extend the bag — the JVM gives a class one superclass — so it still
     * cannot reach an interface-typed slot. It REFUSES, which is the direction
     * that matters: the alternative failure is a `ClassCastException` in the
     * generated program.
     */
    @Test
    fun `residue - a class extending a runtime base still refuses a bag slot`() {
        val lowered = lower(
            """
            interface Stamped { year(): number; }
            class Stamp extends Date {
                year(): number { return this.getFullYear(); }
            }
            const s: Stamped = new Stamp();
            console.log(s.year());
            """
        )
        assert(!lowered.compiled)
        assert(lowered.report.contains("cannot coerce"))
    }

    /**
     * RESIDUE: an ACCESSOR is not part of the generated bag protocol.
     *
     * A `get x()` pair is lowered as `get$x`/`set$x` rather than as a slot, so
     * a read through a bag-typed slot finds no field and no property and
     * answers `undefined`. Silent, which is why it is pinned rather than left
     * to be discovered.
     */
    @Test
    fun `residue - an accessor is invisible through an interface-typed slot`() {
        val lowered = lower(
            """
            interface Sized { size: number; }
            class Impl {
                n: number = 4;
                get size(): number { return this.n; }
            }
            const s: Sized = new Impl();
            console.log(s.size);
            """
        )
        assert(lowered.compiled)
        // `null`, not `undefined`: §3.1 collapses the two onto the JVM's `null`
        // in every erased slot, and `JsObject.get` answers an absent name with
        // it, so `console.log` of one prints "null". That is a second, older
        // divergence from JavaScript and not this one.
        assert(lowered.stdout == "null\n")
    }

    // ---- across files -------------------------------------------------------

    /**
     * The chain crossing a MODULE boundary, which is what decides the pass's
     * position: a base class may be declared in a file lowered after the class
     * extending it, so the protocol is built between every declare pass and any
     * define pass. Built here rather than argued, because the failure is a
     * derived class whose `get` knows only its own fields.
     */
    @Test
    fun `a base class in another file contributes its fields and methods`() {
        val project = Files.createTempDirectory("xtsc-kir-nominal-project")
        val output = Files.createTempDirectory("xtsc-kir-nominal-project-out")
        try {
            project.resolve("tsconfig.json").writeText(
                """{ "compilerOptions": { "strict": true, "target": "es2020" } }"""
            )
            val source = project.resolve("src").also { it.createDirectories() }
            source.resolve("base.ts").writeText(
                """
                export class Base {
                    kind: string = "base";
                    label(): string { return "b:" + this.kind; }
                }
                """.trimIndent()
            )
            source.resolve("main.ts").writeText(
                """
                import { Base } from "./base";
                interface Shape { kind: string; label(): string; }
                class Square extends Base {
                    side: number = 2;
                }
                const s: Shape = new Square();
                console.log(s.kind);
                console.log(s.label());
                """.trimIndent()
            )
            val compilation = compileTypeScriptProjectToJvm(
                project.toString(), "main.ts", output
            )
            assert(compilation.successful)
            val run = runGeneratedProgram(
                output, compilation.mainClass, GeneratedProgramClasspath.minimal()
            )
            assert(run.exitCode == 0)
            assert(run.stdout == "base\nb:base\n")
        } finally {
            project.toFile().deleteRecursively()
            output.toFile().deleteRecursively()
        }
    }

}
