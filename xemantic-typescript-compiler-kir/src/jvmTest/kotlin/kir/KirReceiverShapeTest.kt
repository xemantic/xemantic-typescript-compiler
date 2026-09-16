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
 * Which member table a RECEIVER's member access speaks to — the one property of
 * this backend that decides reflection from a field access, and the one nothing
 * else here can see.
 *
 * Every assertion below is a SHAPE assertion over the emitted bytecode, counted
 * as `javap -p -c … | grep -c 'jsGet\|jsSet\|jsInvoke'`, and that instrument is
 * not a convenience: a program whose receivers went through the dynamic bag
 * and one whose receivers reached their fields print the SAME output, run on
 * the same classpath and pass the whole KIR corpus identically. The two
 * measured differences are a 33x wall on one n-body (`(KIR.LOWER.3)`) and, on
 * Kotlin/Native, a `JsTypeError` where a JVM run merely reflects
 * (`(KIR.LOWER.4)`) — neither of which a behaviour test can express.
 *
 * So each mechanism gets three cases and needs all three:
 *
 *  * a SHAPE case — the count is 0 for a program whose every receiver has a
 *    class this backend generated;
 *  * a BEHAVIOUR case — the program still prints what it printed, because a
 *    lowering that reached the wrong field would also read 0;
 *  * a NEGATIVE CONTROL — a receiver the checker genuinely typed `any` STILL
 *    goes through the bag, because a "fix" that routed everything to a field
 *    would pass both of the others and refuse every dynamic program.
 */
class KirReceiverShapeTest {

    /**
     * Compiles [source], counts its dynamic member operations, and runs it.
     *
     * `javap` rather than a class-file parse of the constant pool: the pool
     * records `jsGet` ONCE however many times it is called, so a pool scan
     * cannot tell one reflective read from twenty — which is exactly the
     * distinction `(KIR.LOWER.3)` is about.
     */
    private class Lowered(val dynamicOps: Int, val stdout: String, val exitCode: Int)

    private fun lower(source: String): Lowered {
        val output = Files.createTempDirectory("xtsc-kir-shape")
        try {
            val compilation = compileTypeScriptToJvm(
                fileName = "shape.ts",
                source = source.trimIndent(),
                outputDirectory = output,
            )
            if (!compilation.successful) throw AssertionError("did not compile\n$compilation")
            val run = runGeneratedProgram(
                output,
                compilation.mainClass,
                GeneratedProgramClasspath.minimal()
            )
            return Lowered(dynamicOps(output), run.stdout, run.exitCode)
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    private fun dynamicOps(outputDirectory: Path): Int {
        val javap = Path.of(System.getProperty("java.home"), "bin", "javap").toString()
        val classes = outputDirectory.toFile().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { it.absolutePath }
            .toList()
            .sorted()
        if (classes.isEmpty()) throw AssertionError("the compilation wrote no class files")
        val dump = File.createTempFile("xtsc-kir-javap", ".txt")
        try {
            val process = ProcessBuilder(listOf(javap, "-p", "-c") + classes)
                .redirectOutput(dump)
                .redirectErrorStream(true)
                .start()
            process.waitFor()
            val text = dump.readText()
            // A positive control on the instrument itself: `javap` that printed
            // nothing would read as a program with no dynamic operations.
            if (!text.contains("Code:")) throw AssertionError("javap produced no disassembly")
            // All THREE dynamic member operations, and the third is not
            // decoration: with only the read and the write counted, a fixture
            // whose fields had become direct while its METHOD CALLS still went
            // through `jsInvoke` read 0 — reflection in the same loop the
            // fields had just left, reported as a closed mechanism.
            return text.lines().count {
                it.contains("jsGet") || it.contains("jsSet") || it.contains("jsInvoke")
            }
        } finally {
            dump.delete()
        }
    }

    // ---- (KIR.LOWER.3) an element access's element type --------------------

    /**
     * The measured shape of the n-body loop, minus the arithmetic.
     *
     * `bodies[i]` where `i` is a `for`-HEADER `let`: the checker answers `any`
     * for that access (measured against tsgo 7.0.2, which answers `Particle`),
     * so before this every `bi.x` was a reflective `jsGet` and the whole loop
     * ran 33x slower with byte-identical output.
     */
    private val elementAccessProgram = """
        class Particle {
            x: number = 0;
            y: number = 0;
        }

        function mk(n: number): Particle[] {
            const out: Particle[] = [];
            for (let i = 0; i < n; i = i + 1) {
                const p = new Particle();
                p.x = i;
                p.y = i * 2;
                out.push(p);
            }
            return out;
        }

        function total(bodies: Particle[]): number {
            let s = 0;
            for (let i = 0; i < bodies.length; i = i + 1) {
                const bi = bodies[i];
                s = s + bi.x + bi.y;
            }
            return s;
        }

        console.log(total(mk(4)));
    """

    @Test
    fun `an unannotated local read out of an array reaches its fields`() {
        assert(lower(elementAccessProgram).dynamicOps == 0)
    }

    @Test
    fun `that program still answers what it answered through the bag`() {
        assert(lower(elementAccessProgram).stdout == "18\n")
    }

    @Test
    fun `an element read straight into a member access reaches its field`() {
        // The same recovery without the local, and the case that ALSO fixed a
        // crash: the field access was already chosen here and its receiver kept
        // the runtime array's `Any?`, so the emitted `getfield` named
        // `java.lang.Object` and died with `NoSuchFieldError` at the first run.
        // Verified bytecode, wrong program — and `dynamicOps` was 0 for it too,
        // which is why the behaviour half of every case below is not decoration.
        val lowered = lower(
            """
            class P { x: number = 4; }
            function mk(): P[] { const a: P[] = []; a.push(new P()); return a; }
            const ps = mk();
            console.log(ps[0].x);
            """
        )
        assert(lowered.dynamicOps == 0)
        assert(lowered.exitCode == 0)
        assert(lowered.stdout == "4\n")
    }

    @Test
    fun `a method called on such a local dispatches directly`() {
        // The half the field route does not cover on its own: the receiver's
        // class is known, so the METHOD is named on its chain rather than found
        // reflectively by `jsInvoke`.
        val lowered = lower(
            """
            class Vec {
                x: number = 1;
                scale(k: number): number { return this.x * k; }
            }
            function mk(): Vec[] { const a: Vec[] = []; a.push(new Vec()); return a; }
            const vs = mk();
            for (let i = 0; i < vs.length; i = i + 1) {
                const v = vs[i];
                console.log(v.scale(3));
            }
            """
        )
        assert(lowered.dynamicOps == 0)
        assert(lowered.stdout == "3\n")
    }

    @Test
    fun `a for-of element reaches its fields`() {
        val lowered = lower(
            """
            class Body { m: number = 2; v: number = 3; }
            function mk(n: number): Body[] {
                const out: Body[] = [];
                for (let i = 0; i < n; i = i + 1) { out.push(new Body()); }
                return out;
            }
            function energy(bodies: Body[]): number {
                let e = 0;
                for (const b of bodies) { e = e + b.m * b.v * b.v; }
                return e;
            }
            console.log(energy(mk(3)));
            """
        )
        assert(lowered.dynamicOps == 0)
        assert(lowered.stdout == "54\n")
    }

    @Test
    fun `an index declared outside a for header was never the broken case`() {
        // The control that names the AXIS: the checker types `nums[j]` for a
        // `let j` in an ordinary statement and answers `any` only for a
        // `for`-HEADER one, so a fixture written this way measures nothing.
        val lowered = lower(
            """
            const nums: number[] = [10, 20];
            let j = 1;
            const v = nums[j];
            console.log(v);
            """
        )
        assert(lowered.dynamicOps == 0)
        assert(lowered.stdout == "20\n")
    }

    // ---- (KIR.LOWER.4) `this` --------------------------------------------

    private val thisProgram = """
        class Particle {
            x: number = 0;
            y: number = 0;
            constructor(x: number, y: number) {
                this.x = x;
                this.y = y;
            }
            move(dx: number): void {
                this.x = this.x + dx;
            }
            sum(): number {
                return this.x + this.y;
            }
        }

        const p = new Particle(3, 4);
        p.move(5);
        console.log(p.sum());
    """

    @Test
    fun `this reads and writes reach their fields in a constructor and a method`() {
        // The native-arm blocker: `this.x = x` lowered to `jsSet(this, "x", …)`
        // beside a real `public double x`, which reflects on the JVM and THROWS
        // on Kotlin/Native — so a class with a constructor could not run there
        // at all.
        assert(lower(thisProgram).dynamicOps == 0)
    }

    @Test
    fun `that class still answers what it answered through reflection`() {
        assert(lower(thisProgram).stdout == "12\n")
    }

    @Test
    fun `a field a base class declares is reached from a subclass method`() {
        val lowered = lower(
            """
            class Base { v: number = 1; }
            class Derived extends Base {
                bump(): number {
                    this.v = this.v + 1;
                    return this.v;
                }
            }
            console.log(new Derived().bump());
            """
        )
        assert(lowered.dynamicOps == 0)
        assert(lowered.stdout == "2\n")
    }

    // ---- (KIR.LOWER.4) parameter properties -------------------------------

    private val parameterPropertyProgram = """
        class Point {
            constructor(public x: number, private y: number, readonly label: string) {
            }
            sum(): number {
                return this.x + this.y;
            }
            describe(): string {
                return this.label;
            }
        }

        class Shifted extends Point {
            constructor(x: number) {
                super(x, 10, "shifted");
            }
        }

        const p = new Point(3, 4, "p");
        console.log(p.sum());
        console.log(p.describe());
        console.log(p.x);
        const s = new Shifted(1);
        console.log(s.sum());
        console.log(s.label);
    """

    @Test
    fun `a parameter property compiles to a field and not to a refusal`() {
        assert(lower(parameterPropertyProgram).dynamicOps == 0)
    }

    @Test
    fun `a parameter property carries its value through construction and inheritance`() {
        assert(lower(parameterPropertyProgram).stdout == "7\np\n3\n11\nshifted\n")
    }

    @Test
    fun `a field initializer reads a parameter property the constructor has already stored`() {
        // The ORDER, and it is measured rather than chosen: tsgo 7.0.2 emits
        // `this.x = x` ABOVE `this.a = this.x + 1`, so this prints 6. The other
        // order compiles, runs, and quietly prints 1.
        assert(
            lower(
                """
                class C {
                    a: number = this.x + 1;
                    constructor(public x: number) {
                    }
                }
                console.log(new C(5).a);
                """
            ).stdout == "6\n"
        )
    }

    // ---- negative controls -------------------------------------------------

    @Test
    fun `negative control - a receiver the checker typed any still goes through the bag`() {
        // The whole discipline in one case: what licenses reaching a field is
        // that the receiver's class is NAMED, and a value whose type the checker
        // declined to give has no name to reach. A change that routed this to a
        // field would pass every case above and refuse every dynamic program —
        // `docs/kir-structural-typing.md` §7 measures that as the largest
        // population in real TypeScript.
        val lowered = lower(
            """
            function readIt(o: any): number {
                return o.v;
            }
            class Holder { v: number = 7; }
            console.log(readIt(new Holder()));
            """
        )
        assert(lowered.dynamicOps > 0)
        assert(lowered.stdout == "7\n")
    }

    @Test
    fun `negative control - a method call on an any receiver still goes through the runtime`() {
        val lowered = lower(
            """
            class Holder {
                v: number = 7;
                get(): number { return this.v; }
            }
            function callIt(o: any): number {
                return o.get();
            }
            console.log(callIt(new Holder()));
            """
        )
        assert(lowered.dynamicOps > 0)
        assert(lowered.stdout == "7\n")
    }

    @Test
    fun `negative control - a write through an any receiver still goes through the bag`() {
        val lowered = lower(
            """
            class Holder { v: number = 1; }
            function writeIt(o: any): void {
                o.v = 9;
            }
            const h = new Holder();
            writeIt(h);
            console.log(h.v);
            """
        )
        assert(lowered.dynamicOps > 0)
        assert(lowered.stdout == "9\n")
    }

}
