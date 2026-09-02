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

package com.xemantic.typescript.compiler.externals

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.SourceFileEntry
import kotlin.io.path.readText
import kotlin.test.Test

/**
 * (EXT.17) THE KOTLIN/JS COMPILE GATE over the REAL externals output — the
 * `external` declarations and the `@file:JsModule` / `@file:JsNonModule` /
 * `@JsName` wiring that the metadata gate ([KotlinExternalsCompileGateTest])
 * cannot see, compiled by `K2JSCompiler` against the Kotlin/JS stdlib klib
 * ([jsCompileCheck]). Until this gate no compiler had seen the real output.
 *
 * ## LOCAL-only, loudly
 *
 * The stdlib klib is not on any classpath this build declares ([JsStdlib]):
 * every test here takes the `SKIPPED: …` branch when it is absent, naming the
 * paths looked at, and runs when `XTSC_KOTLIN_STDLIB_JS` names a
 * `kotlin-stdlib-js-<compiler version>.klib` (Maven Central has it:
 * `org/jetbrains/kotlin/kotlin-stdlib-js/<v>/`). Making it a CI gate is one
 * build-file line and an owner decision; the hermetic RECEIPT for this rung
 * is the session note plus the generator pins the findings produced.
 *
 * ## What the first run found (2026-09-02, Kotlin 2.4.10), and what became of it
 *
 * Nine of twelve arms were green on the first run — nested `external
 * object`s, the enum as a `sealed external interface` under `@JsName`,
 * `external var`/`val` under `@file:JsModule`, `operator fun get`/`set`,
 * `vararg`, callable typealiases, `@JsName("default")`, `@file:JsNonModule`,
 * mitt and smol-toml. Three mechanisms failed, each now a rule or a pin:
 *
 *  1. `Function types with receivers are prohibited in external declarations`
 *     — (EXT.11a) rendered a `this` parameter as a Kotlin receiver
 *     (`SchedulerAction<T>.(T) -> Unit`). Refused directly and through a
 *     typealias; lifted only by `-Xextension-functions-in-externals`, a flag
 *     no consumer passes. And it was the silent direction in disguise: a
 *     Kotlin/JS receiver lambda is a JavaScript function taking the receiver
 *     as its FIRST argument. FIXED in the generator: receiver-less function
 *     types with a top-level marker (`the compiler refuses a receiver …`
 *     below keeps the decision honest).
 *  2. `Class 'Subscriber' is not abstract and does not implement abstract
 *     members: var next: (T) -> Unit` — a class METHOD implementing an
 *     interface's function-typed PROPERTY; then `Scheduler.now: () =>
 *     number` implementing `TimestampProvider.now(): number` (the other
 *     direction) and `Scheduler`'s one `schedule(work, delay?, state?)`
 *     implementing three interface OVERLOADS; then, on `typescript.d.ts`,
 *     `server.InferredProject` owing the OPTIONAL host members its abstract
 *     base never declared and `readFile(path)` implementing
 *     `readFile(path, encoding?)`. One member in TypeScript, two in Kotlin,
 *     every time. FIXED in the renderer by one rule: a non-abstract class
 *     renders every interface member no class in its chain declares by KEY
 *     as an `override` of the inherited shape, loudly
 *     ([Inheritance.owedMembers]).
 *  3. `When accessing module declarations from UMD, they must be marked with
 *     both @JsModule and @JsNonModule` — at every subclass or constructor
 *     call of a generated class. Not a generator defect: `K2JSCompiler`'s
 *     default module kind is UMD, and Kotlin/JS refuses to CALL a
 *     `@JsModule`-only declaration from a UMD compilation, which is the
 *     consumer's `moduleKind` choice (`commonjs`/`es` accept; a UMD consumer
 *     of a non-UMD npm package cannot subclass anything, whatever we render).
 *     The gate compiles as `commonjs`, the shape of an npm consumer, and
 *     `a UMD consumer …` below pins the constraint so it is documented by a
 *     measurement rather than a sentence.
 *
 * Warnings are printed, never gated on.
 */
class KotlinExternalsJsGateTest {

    private fun wired(module: String, entry: String, vararg files: Pair<String, String>): KotlinExternals =
        generateKotlinExternals(
            files.map { (name, content) -> SourceFileEntry(name, content) },
            module = ModuleWiring(module, entry),
        )

    /** Compiles the REAL output, printing every finding; null when the stdlib is absent (already printed). */
    private fun gate(name: String, result: KotlinExternals): JsCompileCheck? {
        val stdlib = JsStdlib.locate() ?: return null
        val check = jsCompileCheck(result.kotlin, stdlib)
        println(
            "=== $name: ${result.kotlin.lines().size} generated lines, ${result.errors.size} checker errors, " +
                "${check.errors.size} Kotlin/JS errors, ${check.warnings.size} warnings"
        )
        check.errors.forEach { println("  ERROR $it") }
        check.warnings.forEach { println("  warning $it") }
        if (check.errors.isNotEmpty()) {
            println(result.kotlin.lines().mapIndexed { i, l -> "${i + 1}: $l" }.joinToString("\n"))
        }
        return check
    }

    // ---- the harness ---------------------------------------------------------

    @Test
    fun `hello world - an external fun under a file-level JsModule compiles as Kotlin JS`() {
        val stdlib = JsStdlib.locate() ?: return
        val check = jsCompileCheck(
            """
            @file:JsModule("m")

            public external fun f(): Int
            """.trimIndent(),
            stdlib,
        )
        assert(check.errors.isEmpty())
        assert(check.successful)
    }

    @Test
    fun `positive control - the collector sees a Kotlin JS error`() {
        val stdlib = JsStdlib.locate() ?: return
        val check = jsCompileCheck(
            """
            @file:JsModule("m")

            public external fun f(): Int = 1
            """.trimIndent(),
            stdlib,
        )
        val bodyRefused = check.errors.any { "Wrong body of external declaration" in it }
        assert(bodyRefused)
        assert(!check.successful)
    }

    @Test
    fun `the compiler refuses a receiver function type in an external declaration - directly and through an alias`() {
        // The measurement behind the generator's receiver-less rendering
        // ((EXT.17), `functionTypeText`). The day a Kotlin release accepts
        // this without the flag, this pin reddens and the rule may be
        // revisited — not before.
        val stdlib = JsStdlib.locate() ?: return
        val direct = """
            @file:JsModule("m")

            public external interface Recv { public var x: Int }
            public external interface S { public fun schedule(work: Recv.(Int) -> Unit): Unit }
        """.trimIndent()
        val aliased = """
            @file:JsModule("m")

            public external interface Recv { public var x: Int }
            public typealias W = Recv.(Int) -> Unit
            public external interface S { public fun schedule(work: W): Unit }
        """.trimIndent()
        val message = "Function types with receivers are prohibited in external declarations"
        val directRefused = jsCompileCheck(direct, stdlib).errors.any { message in it }
        val aliasedRefused = jsCompileCheck(aliased, stdlib).errors.any { message in it }
        val liftedByFlag = jsCompileCheck(direct, stdlib, extensionFunctionsInExternals = true).errors.isEmpty()
        assert(directRefused)
        assert(aliasedRefused)
        assert(liftedByFlag)
    }

    @Test
    fun `a UMD consumer cannot extend a JsModule-only class - commonjs and es can`() {
        val stdlib = JsStdlib.locate() ?: return
        val source = """
            @file:JsModule("m")

            public open external class Base
            public open external class Sub : Base
        """.trimIndent()
        val umdRefused = jsCompileCheck(source, stdlib, moduleKind = "umd").errors
            .any { "must be marked with both @JsModule and @JsNonModule" in it }
        val commonjsAccepts = jsCompileCheck(source, stdlib, moduleKind = "commonjs").errors.isEmpty()
        val esAccepts = jsCompileCheck(source, stdlib, moduleKind = "es").errors.isEmpty()
        assert(umdRefused)
        assert(commonjsAccepts)
        assert(esAccepts)
    }

    // ---- one fixture per rule family -----------------------------------------

    @Test
    fun `nested external objects - a namespace with members and a nested namespace`() {
        val result = wired(
            "pkg", "/pkg/index.d.ts",
            "/pkg/index.d.ts" to """
                export declare namespace ns {
                    interface Item { p: number }
                    function f(i: Item): void;
                    const v: number;
                    let w: string;
                    class K { x: number; static make(): K }
                    enum E { A, B }
                    namespace inner {
                        function g(): Item;
                        const q: boolean;
                    }
                }
                export { ns as other };
            """.trimIndent(),
        )
        val check = gate("nested objects", result) ?: return
        assert(check.errors.isEmpty())
    }

    @Test
    fun `an enum rendered as a sealed external interface with a JsName`() {
        val result = wired(
            "pkg", "/pkg/index.d.ts",
            "/pkg/index.d.ts" to """
                declare enum Direction { Up, Down }
                export { Direction as Dir };
                export declare enum Speed { Slow = "slow", Fast = "fast" }
                export declare function move(d: Direction, s: Speed): Direction;
            """.trimIndent(),
        )
        val check = gate("enum", result) ?: return
        assert(check.errors.isEmpty())
    }

    @Test
    fun `external var and val under a file-level JsModule`() {
        val result = wired(
            "pkg", "/pkg/index.d.ts",
            "/pkg/index.d.ts" to """
                export declare const VERSION: string;
                export declare let counter: number;
                export declare var flag: boolean;
                declare const hidden: string;
                export { hidden as shown };
            """.trimIndent(),
        )
        val check = gate("vars", result) ?: return
        assert(check.errors.isEmpty())
    }

    @Test
    fun `an index signature renders operator get and set on an external interface`() {
        val result = wired(
            "pkg", "/pkg/index.d.ts",
            "/pkg/index.d.ts" to """
                export interface Dict { [key: string]: number }
                export interface Frozen { readonly [key: string]: string }
                export interface Numbered { [i: number]: boolean; size: number }
                export declare function dict(): Dict;
            """.trimIndent(),
        )
        val check = gate("index signatures", result) ?: return
        assert(check.errors.isEmpty())
    }

    @Test
    fun `vararg in a function a method and a constructor`() {
        val result = wired(
            "pkg", "/pkg/index.d.ts",
            "/pkg/index.d.ts" to """
                export interface Box { v: number }
                export interface Adder { add(...more: Box[]): void }
                export declare function join(sep: string, ...parts: string[]): string;
                export declare function all<T>(...xs: T[]): T;
                export declare class Pool {
                    constructor(...items: Box[]);
                    take(...boxes: Box[]): Box;
                }
            """.trimIndent(),
        )
        val check = gate("vararg", result) ?: return
        assert(check.errors.isEmpty())
    }

    @Test
    fun `a callable interface and a function alias render as typealiases`() {
        val result = wired(
            "pkg", "/pkg/index.d.ts",
            "/pkg/index.d.ts" to """
                export interface Cb { (b: boolean): void }
                export type Handler<T> = (event: T) => void;
                export type UnaryFunction<T, R> = (source: T) => R;
                export declare function on(cb: Cb, h: Handler<string>): void;
                export declare function pipe<T, R>(f: UnaryFunction<T, R>): R;
            """.trimIndent(),
        )
        val check = gate("callable aliases", result) ?: return
        assert(check.errors.isEmpty())
    }

    @Test
    fun `a class with a companion an abstract class and a default-exported class`() {
        val result = wired(
            "pkg", "/pkg/index.d.ts",
            "/pkg/index.d.ts" to """
                export declare class Pool {
                    x: number;
                    readonly y: string;
                    constructor(n: number);
                    take(): Pool;
                    static create(n: number): Pool;
                    static readonly LIMIT: number;
                }
                export declare abstract class Base<T> {
                    value: T;
                    abstract run(): T;
                }
                export declare class Sub extends Base<number> {
                    run(): number;
                }
                declare class Main { go(): void }
                export default Main;
            """.trimIndent(),
        )
        val check = gate("classes", result) ?: return
        assert(check.errors.isEmpty())
    }

    @Test
    fun `a default-exported function and a UMD entry`() {
        val result = wired(
            "pkg", "/pkg/index.d.ts",
            "/pkg/index.d.ts" to """
                export as namespace Pkg;
                export interface Options { deep?: boolean }
                export default function make(o?: Options): number;
                export declare function ${'$'}(): void;
            """.trimIndent(),
        )
        val check = gate("default + umd", result) ?: return
        assert(check.errors.isEmpty())
    }

    @Test
    fun `a this parameter in a function type compiles receiver-less - finding 1`() {
        val result = wired(
            "pkg", "/pkg/index.d.ts",
            "/pkg/index.d.ts" to """
                export interface Action<T> { state: T }
                export interface Scheduler {
                    schedule<T>(work: (this: Action<T>, state: T) => void, delay: number): void;
                    handler: (this: Action<string>, s: string) => void;
                    maybe?: (this: Action<string>, s: string) => void;
                }
                export type Work<T> = (this: Action<T>, state: T) => void;
                export interface Callable { (this: Action<number>, x: string): boolean; }
                export declare function run<T>(w: Work<T>, c: Callable): void;
            """.trimIndent(),
        )
        val check = gate("this parameters", result) ?: return
        val receiverLess = "schedule(work: (T) -> Unit /* xtsc: this parameter Action<T> not carried */, delay: Double): Unit" in result.kotlin
        assert(receiverLess)
        assert(check.errors.isEmpty())
    }

    @Test
    fun `a method implementing a function-typed property compiles as an override of the property - finding 2`() {
        val result = wired(
            "pkg", "/pkg/index.d.ts",
            "/pkg/index.d.ts" to """
                export interface Observer<T> {
                    next: (value: T) => void;
                    error?: (err: any) => void;
                }
                export interface Clock { now(): number; }
                export declare class Subscriber<T> implements Observer<T>, Clock {
                    next(value: T): void;
                    next(value: T, again: boolean): void;
                    error(err: any): void;
                    now: () => number;
                }
                export declare class Safe<T> extends Subscriber<T> {
                    next(value: T): void;
                }
            """.trimIndent(),
        )
        val check = gate("owed members", result) ?: return
        val overridesProperty = "    public override var next: (T) -> Unit\n" in result.kotlin
        val overridesMethod = "    public override fun now(): Double\n" in result.kotlin
        assert(overridesProperty)
        assert(overridesMethod)
        assert(check.errors.isEmpty())
    }

    // ---- the libraries' real output --------------------------------------------

    @Test
    fun `mitt's real output compiles as Kotlin JS`() {
        val check = gate("mitt", KotlinExternalsMittGateTest().generateMitt()) ?: return
        assert(check.errors.isEmpty())
    }

    @Test
    fun `smol-toml's real output compiles as Kotlin JS`() {
        val check = gate("smol-toml", KotlinExternalsSmolTomlGateTest().generateSmolToml()) ?: return
        assert(check.errors.isEmpty())
    }

    @Test
    fun `rxjs core's real output compiles as Kotlin JS`() {
        val check = gate("rxjs core", KotlinExternalsRxjsGateTest().generateRxjsCore()) ?: return
        assert(check.errors.isEmpty())
    }

    @Test
    fun `rxjs extras' real output compiles as Kotlin JS`() {
        val check = gate("rxjs extras", KotlinExternalsRxjsExtrasGateTest().generateRxjsExtras()) ?: return
        assert(check.errors.isEmpty())
    }

    @Test
    fun `typescript dts real output compiles as Kotlin JS`() {
        // Both local artifacts must be present: the file (the typescript
        // gate's own locator, its skip line) and the stdlib klib.
        val path = KotlinExternalsTypescriptGateTest().typescriptDts() ?: return
        val result = generateKotlinExternals("/typescript/lib/typescript.d.ts", path.readText())
        val check = gate("typescript.d.ts", result) ?: return
        assert(check.errors.isEmpty())
    }

    // ---- (EXT.20) declaration merging: what a Kotlin/JS external may NEST ----

    /** Whether Kotlin/JS ACCEPTS [source] under a module header; a refusal is printed. */
    private fun accepts(stdlib: java.nio.file.Path, source: String): Boolean {
        val check = jsCompileCheck("@file:JsModule(\"m\")\n\n" + source.trimIndent() + "\n", stdlib)
        if (check.errors.isNotEmpty()) println("  refused: ${check.errors.joinToString(" | ")}")
        return check.errors.isEmpty()
    }

    /**
     * (EXT.20) The measurements behind `mergedDeclaration`'s shapes — every
     * sentence of its KDoc is a row here. The day a Kotlin release moves a
     * row, the pin reddens and the rule may be revisited, not before.
     */
    @Test
    fun `measured - an external class nests an interface a class and an object and holds a companion`() {
        val stdlib = JsStdlib.locate() ?: return
        val classNests = accepts(
            stdlib,
            """
            public open external class EE {
                public fun emit(name: String): Boolean
                public interface Abortable { public var signal: Double? }
                public open class Inner { public fun f(): Int }
                public object Opts { public val v: Int }
                public companion object {
                    public fun once(e: EE, name: String): Any?
                    public var defaultMaxListeners: Double
                }
            }
            public open external class Sub : EE
            public external fun useIt(a: EE.Abortable, i: EE.Inner, o: Any?): EE.Opts
            """,
        )
        val nestedExternalModifier = accepts(
            stdlib,
            """
            public open external class EE {
                public external interface Abortable { public var signal: Double? }
            }
            """,
        )
        assert(classNests)
        assert(!nestedExternalModifier)
    }

    @Test
    fun `measured - an external interface nests an interface and holds a companion but nests no class or object`() {
        val stdlib = JsStdlib.locate() ?: return
        val interfaceNestsInterface = accepts(
            stdlib,
            """
            public external interface I {
                public var x: Int
                public interface Nested { public var n: Int }
                public companion object { public fun f(): Int; public var v: Double }
            }
            public external fun useIt(a: I.Nested): Unit
            """,
        )
        val interfaceNestsClass = accepts(
            stdlib,
            """
            public external interface I { public open class InnerC { public fun f(): Int } }
            """,
        )
        val interfaceNestsObject = accepts(
            stdlib,
            """
            public external interface I { public object O { public val v: Int } }
            """,
        )
        val sealedNests = accepts(
            stdlib,
            """
            public sealed external interface K {
                public interface Nested { public var n: Int }
                public companion object {
                    public val A: K
                    public fun f(): Int
                    public var v: Double
                }
            }
            """,
        )
        assert(interfaceNestsInterface)
        assert(!interfaceNestsClass)
        assert(!interfaceNestsObject)
        assert(sealedNests)
    }

    @Test
    fun `measured - a function beside an object or a class of one name compiles`() {
        val stdlib = JsStdlib.locate() ?: return
        val funBesideObject = accepts(
            stdlib,
            """
            public external fun assert(value: Any?): Unit
            public external object assert { public fun ok(value: Any?): Unit }
            """,
        )
        val funBesideClassCompanion = accepts(
            stdlib,
            """
            public external fun test(name: String): Unit
            public open external class test { public companion object { public fun skip(): Unit } }
            """,
        )
        assert(funBesideObject)
        assert(funBesideClassCompanion)
    }

    @Test
    fun `the merged export equals class of an events-shaped module compiles as Kotlin JS`() {
        // (EXT.20) The real output of the `events.d.ts` shape — the class
        // merged with its interface and namespace, companion and nested
        // declarations, a subclass in another module block — through the
        // Kotlin/JS compiler.
        val result = generateKotlinExternals(
            "/events.d.ts",
            """
            declare module "events" {
                interface Hidden { h: number; }
                interface EventEmitter { fromInterface(): void; }
                class EventEmitter {
                    constructor(options?: Hidden);
                    emit(name: string): boolean;
                    static defaultMaxListeners: number;
                }
                import internal = require("node:events");
                namespace EventEmitter {
                    export { internal as EventEmitter };
                    export interface Abortable { signal?: number | undefined; }
                    export function once(e: EventEmitter, name: string): string;
                    export const captureRejections: boolean;
                    export class Resource extends EventEmitter { readonly x: number; }
                }
                export interface Uses { e: EventEmitter; a: EventEmitter.Abortable; r: EventEmitter.Resource; }
                export = EventEmitter;
            }
            declare module "node:events" {
                import events = require("events");
                export = events;
            }
            declare module "stream" {
                import { EventEmitter } from "events";
                class Stream extends EventEmitter { pipe(): void; }
                export = Stream;
            }
            """.trimIndent(),
        )
        val check = gate("merged events shape", result) ?: return
        val mergedClassRendered = "public open external class EventEmitter(" in result.kotlin
        assert(mergedClassRendered)
        assert(check.errors.isEmpty())
    }

}
