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
import kotlin.test.Test

/**
 * Pins for the (EXT.1) generator: exact FULL rendered text, never substrings —
 * a substring pin cannot see an extra member, a lost modifier or a drifted
 * indent, and the rendered text IS the deliverable.
 */
class KotlinExternalsGeneratorTest {

    private fun generate(source: String): KotlinExternals =
        generateKotlinExternals("t.ts", source.trimIndent())

    @Test
    fun `mvp interface renders every mapping exactly`() {
        val result = generate(
            """
            export type Species = string;
            export interface Creature {
                name: string;
                limbCount: number;
                winged: boolean;
                nickname?: string;
                readonly kind: Species;
                tags: string | number;
                describe(prefix: string): string;
                touch(): void;
            }
            """
        )
        val expected = """
            public typealias Species = String

            public external interface Creature {
                public var name: String
                public var limbCount: Double
                public var winged: Boolean
                public var nickname: String?
                public val kind: String
                public var tags: Any? /* xtsc: unmapped string | number */
                public fun describe(prefix: String): String
                public fun touch(): Unit
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `compile check source is the same rendering without the external modifier`() {
        val result = generate(
            """
            export interface Creature {
                name: string;
                describe(prefix: string): string;
            }
            """
        )
        val expected = """
            public interface Creature {
                public var name: String
                public fun describe(prefix: String): String
            }
        """.trimIndent() + "\n"
        val gateVariant = result.compileCheckSource
        assert(gateVariant == expected)
    }

    @Test
    fun `type alias to a primitive renders as the resolved primitive`() {
        // The pin that separates this generator from Dukat and Karakum: the
        // member's ANNOTATION spells `Species`, and the rendered type is what
        // the CHECKER resolved it to.
        val result = generate(
            """
            export type Species = string;
            export interface Creature {
                kind: Species;
            }
            """
        )
        val expected = """
            public typealias Species = String

            public external interface Creature {
                public var kind: String
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `non-exported interface does not appear`() {
        val result = generate(
            """
            export interface Shown { p: string; }
            interface Hidden { q: string; }
            """
        )
        val expected = """
            public external interface Shown {
                public var p: String
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `union member falls back to Any with the checker rendering in a marker`() {
        val result = generate(
            """
            export interface Mixed {
                tags: string | number;
            }
            """
        )
        val expected = """
            public external interface Mixed {
                public var tags: Any? /* xtsc: unmapped string | number */
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `optional member of unmapped type keeps a single question mark`() {
        // The fallback is already nullable — optionality must not produce a
        // double question mark, which would not even compile.
        val result = generate(
            """
            export interface Opt {
                maybe?: string | number;
            }
            """
        )
        val expected = """
            public external interface Opt {
                public var maybe: Any? /* xtsc: unmapped string | number */
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `keyword member names are backticked`() {
        val result = generate(
            """
            export interface Keys {
                object: string;
                val: number;
                in: boolean;
            }
            """
        )
        val expected = """
            public external interface Keys {
                public var `object`: String
                public var `val`: Double
                public var `in`: Boolean
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a generic interface renders its type parameters and members typed by them`() {
        // (EXT.2) flipped this pin from a refusal marker to the rendering. The
        // own-type-parameter rule is SYNTACTIC — measured, the lens at an
        // interface-declaration callback resolves a bare `T` to `any`.
        val result = generate(
            """
            export interface Box<T> { value: T; tag: string; wrap(x: T): T; }
            export interface Plain { p: string; }
            """
        )
        val expected = """
            public external interface Box<T> {
                public var value: T
                public var tag: String
                public fun wrap(x: T): T
            }

            public external interface Plain {
                public var p: String
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a constrained type parameter keeps its name and marks the constraint`() {
        val result = generate(
            """
            export interface Tagged<T extends string> { value: T; }
            """
        )
        val rendered = result.kotlin
        val headerAt = rendered.indexOf("public external interface Tagged<T> {")
        val markerAt = rendered.indexOf("/* xtsc: constraint on T: string not carried */")
        assert(headerAt >= 0)
        assert(markerAt > headerAt)
    }

    @Test
    fun `a member typed by another exported interface renders that name`() {
        val result = generate(
            """
            export interface Creature { name: string; }
            export interface Cage { occupant: Creature; spare?: Creature; }
            """
        )
        val expected = """
            public external interface Creature {
                public var name: String
            }

            public external interface Cage {
                public var occupant: Creature
                public var spare: Creature?
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a generic reference renders with mapped arguments and falls back when one does not map`() {
        val result = generate(
            """
            export interface Box<T> { value: T; }
            export interface Holder { good: Box<string>; bad: Box<string | number>; }
            """
        )
        val rendered = result.kotlin
        val good = "    public var good: Box<String>\n" in rendered
        val bad = rendered.contains("public var bad: Any? /* xtsc: unmapped")
        assert(good)
        assert(bad)
    }

    @Test
    fun `a non-exported interface is not a nameable target`() {
        // Positive evidence only - `Hidden` resolves, the checker knows it,
        // but it is not part of the generated surface, so naming it would emit
        // Kotlin that references a type the module does not declare.
        val result = generate(
            """
            interface Hidden { h: string; }
            export interface Uses { member: Hidden; }
            """
        )
        val rendered = result.kotlin
        val fallback = rendered.contains("public var member: Any? /* xtsc: unmapped")
        val noBareName = "public var member: Hidden" !in rendered
        assert(fallback)
        assert(noBareName)
    }

    @Test
    fun `an exported alias with an unmappable body is a loud skip`() {
        val result = generate(
            """
            export type Mixed = string | number;
            """
        )
        val rendered = result.kotlin
        val skipped = rendered.contains("/* xtsc: skipped type alias Mixed with unmappable body")
        assert(skipped)
        val noTypealias = "typealias Mixed" !in rendered
        assert(noTypealias)
    }

    @Test
    fun `an alias body naming a generated interface renders that name`() {
        val result = generate(
            """
            export interface Creature { name: string; }
            export type Beast = Creature;
            """
        )
        val rendered = result.kotlin
        val alias = "public typealias Beast = Creature\n" in rendered
        assert(alias)
    }

    @Test
    fun `void outside return position falls back`() {
        val result = generate(
            """
            export interface Odd {
                v: void;
            }
            """
        )
        val expected = """
            public external interface Odd {
                public var v: Any? /* xtsc: unmapped void */
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `checker errors are exposed and rendering still happens`() {
        val result = generate(
            """
            export interface Fine { p: string; }
            export const bad: number = "x";
            """
        )
        val expected = """
            public external interface Fine {
                public var p: String
            }

            public external val bad: Double
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(2322 in errorCodes)
    }

    @Test
    fun `two exported interfaces are separated by a blank line`() {
        val result = generate(
            """
            export interface A { a: string; }
            export interface B { b: number; }
            """
        )
        val expected = """
            public external interface A {
                public var a: String
            }

            public external interface B {
                public var b: Double
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `unsupported member shapes are marked rather than dropped`() {
        val result = generate(
            """
            export interface Sub extends Object {
                [k: string]: string;
                own: string;
            }
            """
        )
        val expected = """
            public external interface Sub {
                /* xtsc: skipped heritage clause extends Object */
                /* xtsc: skipped IndexSignature */
                public var own: String
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    // --- (EXT.3) functions and function types --------------------------------

    @Test
    fun `an exported function renders externally and the gate variant grows a nothing body`() {
        val result = generate(
            """
            export function greet(who: string, count: number): string { return who; }
            """
        )
        val expectedExternal = "public external fun greet(who: String, count: Double): String\n"
        val expectedGate = "public fun greet(who: String, count: Double): String = null!!\n"
        val external = result.kotlin
        val gate = result.compileCheckSource
        assert(external == expectedExternal)
        assert(gate == expectedGate)
    }

    @Test
    fun `a generic function renders its own type parameters syntactically`() {
        val result = generate(
            """
            export function id<T>(x: T): T { return x; }
            """
        )
        val rendered = result.kotlin
        assert(rendered == "public external fun <T> id(x: T): T\n")
    }

    @Test
    fun `a constrained function type parameter marks above the fun`() {
        val result = generate(
            """
            export function tag<T extends string>(x: T): T { return x; }
            """
        )
        val rendered = result.kotlin
        val markerAt = rendered.indexOf("/* xtsc: constraint on T: string not carried */")
        val funAt = rendered.indexOf("public external fun <T> tag(x: T): T")
        assert(markerAt >= 0)
        assert(funAt > markerAt)
    }

    @Test
    fun `a function-typed member renders as a kotlin function type`() {
        val result = generate(
            """
            export interface Handlers {
                onName: (name: string) => void;
                pick: (a: string, b: number) => string;
            }
            """
        )
        val expected = """
            public external interface Handlers {
                public var onName: (String) -> Unit
                public var pick: (String, Double) -> String
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `an optional function-typed member parenthesizes its nullability`() {
        val result = generate(
            """
            export interface Handlers { cb?: (n: number) => string; }
            """
        )
        val rendered = result.kotlin
        val line = "    public var cb: ((Double) -> String)?\n" in rendered
        assert(line)
    }

    @Test
    fun `an optional method becomes a nullable function-typed property`() {
        val result = generate(
            """
            export interface Probe { ping?(x: string): void; }
            """
        )
        val rendered = result.kotlin
        val line = "    public var ping: ((String) -> Unit)?\n" in rendered
        assert(line)
    }

    @Test
    fun `a function type with an optional or rest parameter falls back loudly`() {
        // `(x?: s) => v` changes ARITY - a caller may OMIT the argument - which
        // `(String?) -> Unit` does not express, so the whole annotation falls
        // back rather than shipping a signature that invites a wrong call.
        val result = generate(
            """
            export interface Handlers {
                opt: (x?: string) => void;
                rest: (...xs: string[]) => void;
            }
            """
        )
        val rendered = result.kotlin
        val optFallback = "public var opt: Any? /* xtsc: unmapped" in rendered
        val restFallback = "public var rest: Any? /* xtsc: unmapped" in rendered
        assert(optFallback)
        assert(restFallback)
    }

    // --- (EXT.5) generic aliases, generic methods, overloads ----------------

    @Test
    fun `a generic type alias renders its parameters and a syntactic body`() {
        val result = generate(
            """
            export type Handler<T> = (event: T) => void;
            """
        )
        val rendered = result.kotlin
        assert(rendered == "public typealias Handler<T> = (T) -> Unit\n")
    }

    @Test
    fun `a defaulted generic alias parameter keeps its name and marks the default`() {
        val result = generate(
            """
            export type Handler<T = unknown> = (event: T) => void;
            """
        )
        val rendered = result.kotlin
        val markerAt = rendered.indexOf("/* xtsc: default for T: unknown not carried */")
        val aliasAt = rendered.indexOf("public typealias Handler<T> = (T) -> Unit")
        assert(markerAt >= 0)
        assert(aliasAt > markerAt)
    }

    @Test
    fun `a generic alias with an unmappable body is a loud skip`() {
        val result = generate(
            """
            export type Pair<T> = { first: T };
            """
        )
        val rendered = result.kotlin
        val skipped = "/* xtsc: skipped generic type alias Pair with unmappable body */" in rendered
        val noTypealias = "typealias Pair" !in rendered
        assert(skipped)
        assert(noTypealias)
    }

    @Test
    fun `a generic method renders its own type parameters with a constraint marker`() {
        val result = generate(
            """
            export interface Emitter {
                on<Key extends string>(type: Key, count: number): void;
            }
            """
        )
        val rendered = result.kotlin
        val markerAt = rendered.indexOf("    /* xtsc: constraint on Key: string not carried */")
        val funAt = rendered.indexOf("    public fun <Key> on(type: Key, count: Double): Unit")
        assert(markerAt >= 0)
        assert(funAt > markerAt)
    }

    @Test
    fun `interface method overloads render as kotlin overloads`() {
        val result = generate(
            """
            export interface Picker {
                pick(x: string): string;
                pick(x: number, y: number): number;
            }
            """
        )
        val rendered = result.kotlin
        val first = "    public fun pick(x: String): String\n" in rendered
        val second = "    public fun pick(x: Double, y: Double): Double\n" in rendered
        assert(first)
        assert(second)
    }

    @Test
    fun `overloads collapsing to one mapped signature keep only the first`() {
        // The two literal-typed parameters are DIFFERENT types to TypeScript
        // and both widen to `String` here ((EXT.11b); before it, both fell to
        // the same Any? fallback) - Kotlin would refuse the conflicting pair,
        // so the later one becomes a marker.
        val result = generate(
            """
            export interface Chooser {
                choose(mode: "a"): void;
                choose(mode: "b"): void;
            }
            """
        )
        val rendered = result.kotlin
        val marker = "/* xtsc: skipped overload of choose collapsing to a duplicate signature */" in rendered
        val funCount = Regex("public fun choose").findAll(rendered).count()
        assert(marker)
        assert(funCount == 1)
    }

    @Test
    fun `an optional generic method is a loud skip`() {
        val result = generate(
            """
            export interface Probe { ping?<T>(x: T): void; }
            """
        )
        val rendered = result.kotlin
        val skipped = "/* xtsc: skipped optional generic method ping */" in rendered
        assert(skipped)
    }

    // --- (EXT.6) default exports --------------------------------------------

    @Test
    fun `a default-exported function renders with a loud default marker`() {
        val result = generate(
            """
            export default function greet(who: string): string { return who; }
            """
        )
        val rendered = result.kotlin
        val markerAt =
            rendered.indexOf("/* xtsc: default export - consumers bind the module's default */")
        val funAt = rendered.indexOf("public external fun greet(who: String): String")
        assert(markerAt >= 0)
        assert(funAt > markerAt)
    }

    @Test
    fun `a default-exported class renders with the marker and a nameless one skips loudly`() {
        val named = generate(
            """
            export default class Widget { label: string; }
            """
        )
        val namedRendered = named.kotlin
        val marker =
            "/* xtsc: default export - consumers bind the module's default */" in namedRendered
        val header = "public open external class Widget {" in namedRendered
        assert(marker)
        assert(header)
        val nameless = generate(
            """
            export default class { p: string; }
            """
        )
        val namelessRendered = nameless.kotlin
        val skipped = "/* xtsc: skipped class without a name */" in namelessRendered
        assert(skipped)
    }

    // --- (EXT.4) classes and enums ------------------------------------------

    @Test
    fun `an exported class renders constructor members statics and the gate variant grows bodies`() {
        val result = generate(
            """
            export class Animal {
                name: string;
                readonly kind: string;
                constructor(name: string) { this.name = name; this.kind = "beast"; }
                speak(volume: number): string { return this.name; }
                static create(name: string): Animal { return new Animal(name); }
                static tally: number;
            }
            """
        )
        val expected = """
            public open external class Animal(name: String) {
                public var name: String
                public val kind: String
                public fun speak(volume: Double): String
                public companion object {
                    public fun create(name: String): Animal
                    public var tally: Double
                }
            }
        """.trimIndent() + "\n"
        val expectedGate = """
            public abstract class Animal(name: String) {
                public var name: String = null!!
                public val kind: String = null!!
                public fun speak(volume: Double): String = null!!
                public companion object {
                    public fun create(name: String): Animal = null!!
                    public var tally: Double = null!!
                }
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val gate = result.compileCheckSource
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(gate == expectedGate)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `private and protected class members are omitted from the surface`() {
        val result = generate(
            """
            export class Vault {
                openly: string;
                private combo: string;
                protected hinge: number;
                private crack(): void {}
            }
            """
        )
        val expected = """
            public open external class Vault {
                public var openly: String
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `multiple constructors are a loud marker and no primary constructor`() {
        val result = generate(
            """
            export class Multi {
                x: number;
                constructor(x: number);
                constructor(x: string);
                constructor(x: any) { this.x = 0; }
            }
            """
        )
        val rendered = result.kotlin
        val marker = "    /* xtsc: skipped multiple constructors */\n" in rendered
        val headerHasNoParens = "public open external class Multi {\n" in rendered
        assert(marker)
        assert(headerHasNoParens)
    }

    @Test
    fun `a constructor parameter property is loud and the parameter still renders`() {
        val result = generate(
            """
            export class Point {
                constructor(public x: number, y: number) {}
            }
            """
        )
        val rendered = result.kotlin
        val marker = "/* xtsc: skipped parameter property x */" in rendered
        val header = "public open external class Point(x: Double, y: Double) {" in rendered
        assert(marker)
        assert(header)
    }

    @Test
    fun `an abstract class keeps the abstract modifier`() {
        val result = generate(
            """
            export abstract class Shape { area(): number { return 0; } }
            """
        )
        val rendered = result.kotlin
        val header = "public abstract external class Shape {\n" in rendered
        assert(header)
    }

    @Test
    fun `a class instance type and a generic class reference render by name`() {
        val result = generate(
            """
            export class Beast { legs: number; }
            export class Pen<T> { occupant?: T; }
            export interface Farm { star: Beast; pen: Pen<Beast>; }
            """
        )
        val rendered = result.kotlin
        val star = "    public var star: Beast\n" in rendered
        val pen = "    public var pen: Pen<Beast>\n" in rendered
        val ownTypeParam = "    public var occupant: T?\n" in rendered
        assert(star)
        assert(pen)
        assert(ownTypeParam)
    }

    @Test
    fun `a static member typed by the class type parameter falls back loudly`() {
        // A Kotlin companion object cannot see the class's type parameters -
        // and TypeScript refuses `static x: T` too - so the syntactic own-TP
        // answer is refused for statics and the type falls to the marker.
        val result = generate(
            """
            export class Holder<T> { value: T; static last: T; }
            """
        )
        val rendered = result.kotlin
        val instance = "    public var value: T\n" in rendered
        val staticFallback = "        public var last: Any? /* xtsc: unmapped" in rendered
        assert(instance)
        assert(staticFallback)
    }

    @Test
    fun `an exported enum renders as a sealed interface with companion entries`() {
        val result = generate(
            """
            export enum Direction { Up, Down }
            """
        )
        val expected = """
            public sealed external interface Direction {
                public companion object {
                    public val Up: Direction
                    public val Down: Direction
                }
            }
        """.trimIndent() + "\n"
        val expectedGate = """
            public sealed interface Direction {
                public companion object {
                    public val Up: Direction = null!!
                    public val Down: Direction = null!!
                }
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val gate = result.compileCheckSource
        assert(rendered == expected)
        assert(gate == expectedGate)
    }

    @Test
    fun `an enum-typed member renders the enum name and a member literal falls back`() {
        val result = generate(
            """
            export enum Direction { Up, Down }
            export interface Move { dir: Direction; only: Direction.Up; }
            """
        )
        val rendered = result.kotlin
        val named = "    public var dir: Direction\n" in rendered
        val memberLiteralFallback = "public var only: Any? /* xtsc: unmapped" in rendered
        assert(named)
        assert(memberLiteralFallback)
    }

    @Test
    fun `a const enum is a loud skip and is not a nameable target`() {
        val result = generate(
            """
            export const enum Speed { Slow, Fast }
            export interface Racer { speed: Speed; }
            """
        )
        val rendered = result.kotlin
        val skipped = "/* xtsc: skipped const enum Speed - no runtime object */" in rendered
        val noSealedInterface = "sealed external interface Speed" !in rendered
        val memberFallback = "public var speed: Any? /* xtsc: unmapped" in rendered
        assert(skipped)
        assert(noSealedInterface)
        assert(memberFallback)
    }

    @Test
    fun `a string-named enum member is backticked`() {
        val result = generate(
            """
            export enum Terrain { Flat, "up-hill" = 1 }
            """
        )
        val rendered = result.kotlin
        val backticked = "        public val `up-hill`: Terrain\n" in rendered
        assert(backticked)
    }

    @Test
    fun `an overloaded exported function renders its overloads without the implementation signature`() {
        // (EXT.7) supersedes the (EXT.3) loud skip: the overload signatures
        // ARE the surface; the implementation signature (the one with a body)
        // is not callable and produces nothing.
        val result = generate(
            """
            export function pick(x: string): string;
            export function pick(x: number): number;
            export function pick(x: any): any { return x; }
            export function outer(): void { function inner(): void {} }
            """
        )
        val expected = """
            public external fun pick(x: String): String

            public external fun pick(x: Double): Double

            public external fun outer(): Unit
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a lone function with a body still renders - the omission is only for an implementation among overloads`() {
        val result = generate(
            """
            export function one(x: string): string { return x; }
            """
        )
        val expected = "public external fun one(x: String): String\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    // --- (EXT.7) the smol-toml rung: multi-file, overloads, wiring ----------

    private fun generateFiles(vararg files: Pair<String, String>): KotlinExternals =
        generateKotlinExternals(
            files.map { (name, source) ->
                com.xemantic.typescript.compiler.SourceFileEntry(name, source.trimIndent())
            }
        )

    @Test
    fun `a member typed by another file's exported interface renders by name across a js specifier`() {
        // Path-shaped names, a `.js` specifier resolving to its `.d.ts`
        // sibling — the shape every published package has. The Dukat pin
        // still holds across the file boundary: `Kind` is an alias in the
        // OTHER file and renders as what it resolves to.
        val result = generateFiles(
            "/pkg/dist/a.d.ts" to """
                export type Kind = string;
                export interface Creature {
                    name: string;
                }
            """,
            "/pkg/dist/b.d.ts" to """
                import type { Creature, Kind } from './a.js';
                export interface Habitat {
                    resident: Creature;
                    kind: Kind;
                    residents: Creature[];
                }
            """,
        )
        val expected = """
            public typealias Kind = String

            public external interface Creature {
                public var name: String
            }

            public external interface Habitat {
                public var resident: Creature
                public var kind: String
                public var residents: Array<Creature>
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `negative control - a same-named non-exported interface in the importing file still falls back`() {
        // The cross-file naming set is keyed by IDENTITY, not by spelling: b's
        // own non-exported `Creature` is what `resident` resolves to, it is not
        // in the generated surface, and a's exported `Creature` sharing the
        // name must not be borrowed for it. A name-keyed union of the files'
        // exported names would render `Creature` here and be wrong.
        val result = generateFiles(
            "/pkg/a.d.ts" to """
                export interface Creature {
                    name: string;
                }
            """,
            "/pkg/b.d.ts" to """
                interface Creature {
                    local: boolean;
                }
                export interface Habitat {
                    resident: Creature;
                }
            """,
        )
        val rendered = result.kotlin
        val fallback = "    public var resident: Any? /* xtsc: unmapped Creature */\n" in rendered
        assert(fallback)
    }

    @Test
    fun `a type name exported by two files is a loud skip for the second`() {
        val result = generateFiles(
            "/pkg/a.d.ts" to """
                export interface Shape { a: string; }
            """,
            "/pkg/b.d.ts" to """
                export interface Shape { b: number; }
                export class Other {}
            """,
            "/pkg/c.d.ts" to """
                export type Other = string;
            """,
        )
        val expected = """
            public external interface Shape {
                public var a: String
            }

            /* xtsc: skipped Shape declared again by another file - one Kotlin package cannot hold both */

            public open external class Other

            /* xtsc: skipped Other declared again by another file - one Kotlin package cannot hold both */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `top-level overloads collapsing to one mapped signature keep only the first`() {
        // Two literal-typed parameters both widen to `String` ((EXT.11b);
        // before it both fell to the `Any?` fallback): one Kotlin signature,
        // so the second is a marker — the (EXT.5) method rule on the module
        // surface.
        val result = generate(
            """
            export declare function choose(mode: "a"): string;
            export declare function choose(mode: "b"): number;
            export declare function choose(mode: boolean): boolean;
            """
        )
        val expected = """
            public external fun choose(mode: String): String

            /* xtsc: skipped overload of choose collapsing to a duplicate signature */

            public external fun choose(mode: Boolean): Boolean
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a private-name member is omitted like a private one and lib heritage is named`() {
        val result = generate(
            """
            export declare class Stamp extends Date implements Object {
                #secret;
                #hidden(): void;
                private also: string;
                shown: string;
            }
            """
        )
        val expected = """
            public open external class Stamp {
                /* xtsc: skipped heritage clause extends Date */
                /* xtsc: skipped heritage clause implements Object */
                public var shown: String
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `export wiring is loud - default value, export equals, star and named re-exports - and an empty export is silent`() {
        val result = generateFiles(
            "/pkg/a.d.ts" to """
                export interface A { a: string; }
            """,
            "/pkg/index.d.ts" to """
                import { A } from './a.js';
                declare const _default: { a: A };
                export default _default;
                export * from './a.js';
                export { A, A as Alias };
                export {};
            """,
        )
        val expected = """
            public external interface A {
                public var a: String
            }

            /* xtsc: skipped default export of _default - module wiring is a later rung */

            /* xtsc: skipped re-export * from './a.js' - module wiring is a later rung */

            /* xtsc: skipped re-export { A, A as Alias } - module wiring is a later rung */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `export equals is a loud marker`() {
        val result = generate(
            """
            declare const lib: { v: number };
            export = lib;
            """
        )
        val expected = "/* xtsc: skipped export = lib - module wiring is a later rung */\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    // --- (EXT.8) heritage to generated targets --------------------------------

    @Test
    fun `an interface extending generated interfaces renders them as supertypes and redeclarations as override`() {
        val result = generate(
            """
            export interface Named { name: string; readonly id: number; }
            export interface Boxed<T> { value: T; }
            export interface Person extends Named, Boxed<string>, Object {
                name: string;
                readonly id: number;
                age: number;
            }
            """
        )
        val expected = """
            public external interface Named {
                public var name: String
                public val id: Double
            }

            public external interface Boxed<T> {
                public var value: T
            }

            public external interface Person : Named, Boxed<String> {
                /* xtsc: skipped heritage clause extends Object */
                public override var name: String
                public override val id: Double
                public var age: Double
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `a class extending a generated class and implementing a generated interface - override and open`() {
        val result = generate(
            """
            export interface Shape { area(): number; }
            export class Base {
                constructor(tag: string);
                tag: string;
                describe(): string;
                keep(): void;
            }
            export class Circle extends Base implements Shape {
                constructor(tag: string, r: number);
                r: number;
                area(): number;
                describe(): string;
            }
            """
        )
        val expected = """
            public external interface Shape {
                public fun area(): Double
            }

            public open external class Base(tag: String) {
                public var tag: String
                public open fun describe(): String
                public fun keep(): Unit
            }

            public open external class Circle(tag: String, r: Double) : Base, Shape {
                public var r: Double
                public override fun area(): Double
                public override fun describe(): String
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `the gate variant renders classes abstract and calls the generated base - inherited or own constructor`() {
        // TypeScript INHERITS the constructor of a class declaring none, so
        // `Derived` is called as `Derived("x", 1)` and renders the base's
        // parameters, passed through by name in the gate's superclass call;
        // a class with its OWN constructor calls the base with `null!!`s.
        val result = generate(
            """
            export class Base { constructor(a: string, b: number); }
            export class Derived extends Base { x: string; }
            export class Own extends Base { constructor(z: boolean); }
            export class Lone {}
            """
        )
        val expected = """
            public abstract class Base(a: String, b: Double)

            public abstract class Derived(a: String, b: Double) : Base(a, b) {
                public var x: String = null!!
            }

            public abstract class Own(z: Boolean) : Base(null!!, null!!)

            public abstract class Lone
        """.trimIndent() + "\n"
        val gateVariant = result.compileCheckSource
        assert(gateVariant == expected)
        val realDerived = "public open external class Derived(a: String, b: Double) : Base {\n" in result.kotlin
        val realOwn = "public open external class Own(z: Boolean) : Base\n" in result.kotlin
        assert(realDerived)
        assert(realOwn)
    }

    @Test
    fun `an override that differs by parameter types is an overload - and a readonly narrowing is loud`() {
        val result = generate(
            """
            export interface A { m(x: string): string; p: string; }
            export interface B extends A {
                m(x: number): number;
                readonly p: string;
            }
            """
        )
        val expected = """
            public external interface A {
                public fun m(x: String): String
                public var p: String
            }

            public external interface B : A {
                public fun m(x: Double): Double
                /* xtsc: readonly narrows an inherited var - rendered var */
                public override var p: String
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `negative control - a base of the wrong kind or a non-exported base stays a marker`() {
        val result = generate(
            """
            interface Hidden { h: string; }
            export class K { k: string; }
            export interface FromClass extends K { }
            export interface FromHidden extends Hidden { }
            export class Impl implements K { }
            """
        )
        val expected = """
            public open external class K {
                public var k: String
            }

            public external interface FromClass {
                /* xtsc: skipped heritage clause extends K */
            }

            public external interface FromHidden {
                /* xtsc: skipped heritage clause extends Hidden */
            }

            public open external class Impl {
                /* xtsc: skipped heritage clause implements K */
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `heritage across files renders by name under the same identity evidence`() {
        val result = generateFiles(
            "/pkg/a.d.ts" to """
                export interface Root { r: string; }
            """,
            "/pkg/b.d.ts" to """
                import type { Root } from './a.js';
                export interface Leaf extends Root { l: number; }
            """,
        )
        val rendered = result.kotlin
        assert(rendered.contains("public external interface Leaf : Root {\n    public var l: Double\n}\n"))
    }

    // --- (EXT.9) exported values and accessors --------------------------------

    @Test
    fun `exported values render as val or var - annotated resolved, un-annotated by the checker's answer`() {
        val result = generate(
            """
            export type Id = string;
            export declare const VERSION: Id;
            export const RETRIES = 3;
            export let counter: number;
            export var flag: boolean, other: string;
            export const { a, b } = { a: 1, b: 2 };
            declare const hidden: number;
            """
        )
        val expected = """
            public typealias Id = String

            public external val VERSION: String

            public external val RETRIES: Double

            public external var counter: Double

            public external var flag: Boolean

            public external var other: String

            /* xtsc: skipped destructuring export - no single name to declare */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
        val gate = "public val VERSION: String = null!!\n" in result.compileCheckSource
        assert(gate)
    }

    @Test
    fun `accessor pairs collapse to one property - getter alone is val, setter alone is var`() {
        val result = generate(
            """
            export class Meter {
                get value(): number;
                set value(v: number);
                get label(): string;
                set only(v: boolean);
                static get shared(): Meter;
                private get secret(): string;
                #hidden: number;
            }
            export interface Gauge {
                get reading(): number;
                set reading(r: number);
                get max(): number;
            }
            """
        )
        val expected = """
            public open external class Meter {
                public var value: Double
                public val label: String
                public var only: Boolean
                public companion object {
                    public val shared: Meter
                }
            }

            public external interface Gauge {
                public var reading: Double
                public val max: Double
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `an accessor pair is emitted once at the first accessor's position`() {
        val result = generate(
            """
            export class Order {
                set x(v: string);
                y: number;
                get x(): string;
            }
            """
        )
        val expected = """
            public open external class Order {
                public var x: String
                public var y: Double
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    // --- (EXT.10) references to a generated alias render by name -------------

    @Test
    fun `a generic alias instantiation renders by name in members and signatures`() {
        val result = generate(
            """
            export type Handler<T> = (event: T) => void;
            export interface Emitter {
                h: Handler<string>;
                on(type: string, handler: Handler<number>): void;
                last(): Handler<boolean>;
            }
            export declare function wrap(h: Handler<boolean>): Handler<string>;
            """
        )
        val expected = """
            public typealias Handler<T> = (T) -> Unit

            public external interface Emitter {
                public var h: Handler<String>
                public fun on(type: String, handler: Handler<Double>): Unit
                public fun last(): Handler<Boolean>
            }

            public external fun wrap(h: Handler<Boolean>): Handler<String>
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `a function-typed alias is emitted and its uses name it`() {
        val result = generate(
            """
            export type Cb = (done: boolean) => void;
            export interface Task {
                cb: Cb;
                run(cb?: Cb): void;
            }
            """
        )
        val expected = """
            public typealias Cb = (Boolean) -> Unit

            public external interface Task {
                public var cb: Cb
                public fun run(cb: Cb?): Unit
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `the Dukat pin survives - a use of a mapped non-generic alias still renders the resolved type`() {
        val result = generate(
            """
            export type Species = string;
            export interface Creature { kind: Species; }
            """
        )
        val rendered = result.kotlin
        val byResolved = "public var kind: String" in rendered
        val byName = "public var kind: Species" in rendered
        assert(byResolved)
        assert(!byName)
    }

    @Test
    fun `negative control - a use of a skipped alias keeps the fallback`() {
        val result = generate(
            """
            export type Pair<T> = { first: T };
            export interface Holder { p: Pair<string>; }
            """
        )
        val rendered = result.kotlin
        val skipped = "/* xtsc: skipped generic type alias Pair with unmappable body */" in rendered
        val byName = "Pair<String>" in rendered
        val fallback = "public var p: Any? /* xtsc: unmapped" in rendered
        assert(skipped)
        assert(!byName)
        assert(fallback)
    }

    @Test
    fun `negative control - a use omitting a defaulted alias argument keeps the fallback`() {
        val result = generate(
            """
            export type Handler<T = unknown> = (event: T) => void;
            export interface Emitter { h: Handler; }
            """
        )
        val rendered = result.kotlin
        val byName = "public var h: Handler" in rendered
        val fallback = "public var h: Any? /* xtsc: unmapped" in rendered
        assert(!byName)
        assert(fallback)
    }

    @Test
    fun `negative control - a lib generic alias is not named`() {
        val result = generate(
            """
            export interface Bag { entries: Record<string, number>; }
            """
        )
        val rendered = result.kotlin
        val fallback = "public var entries: Any? /* xtsc: unmapped" in rendered
        val byName = "Record<String, Double>" in rendered
        assert(fallback)
        assert(!byName)
    }

    @Test
    fun `an imported generic alias renders by name across files and a same-named local one does not`() {
        val result = generateFiles(
            "/pkg/a.d.ts" to """
                export type Handler<T> = (event: T) => void;
            """,
            "/pkg/b.d.ts" to """
                import type { Handler } from './a.js';
                export interface Emitter { h: Handler<string>; }
            """,
            "/pkg/c.d.ts" to """
                type Handler<T> = (event: T) => void;
                export interface Local { h: Handler<string>; }
            """,
        )
        val expected = """
            public typealias Handler<T> = (T) -> Unit

            public external interface Emitter {
                public var h: Handler<String>
            }

            public external interface Local {
                public var h: Any? /* xtsc: unmapped (event: string) => void */
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    // --- (EXT.11a) the RxJS rung: call signatures, typeof, this parameters --

    @Test
    fun `an interface with exactly one call signature renders as a function typealias`() {
        // RxJS's spine: `interface UnaryFunction<T, R> { (source: T): R }`.
        // The parser spells the signature as a method with an EMPTY name, and
        // the pre-(EXT.11a) output was `public fun ``(source: T): R` — a
        // compile error — inside an interface a consumer could not invoke.
        val result = generate(
            """
            export interface UnaryFunction<T, R> {
                (source: T): R;
            }
            export interface Cb {
                (done: boolean): void;
            }
            """
        )
        val expected = """
            public typealias UnaryFunction<T, R> = (T) -> R

            public typealias Cb = (Boolean) -> Unit
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `an empty interface chain over a callable interface renders as aliases to the base`() {
        // `OperatorFunction`/`MonoTypeOperatorFunction`: an empty body over ONE
        // base is an alias, and Kotlin lets a typealias name a parameterised
        // typealias. Before: `public external interface OperatorFunction<T, R>
        // : UnaryFunction<Box<T>, Box<R>> {` — extending the nameless-method
        // interface, so uncompilable one declaration up.
        val result = generate(
            """
            export interface UnaryFunction<T, R> {
                (source: T): R;
            }
            export interface OperatorFunction<T, R> extends UnaryFunction<Box<T>, Box<R>> {
            }
            export interface MonoTypeOperatorFunction<T> extends OperatorFunction<T, T> {
            }
            export interface Box<T> {
                value: T;
            }
            """
        )
        val expected = """
            public typealias UnaryFunction<T, R> = (T) -> R

            public typealias OperatorFunction<T, R> = UnaryFunction<Box<T>, Box<R>>

            public typealias MonoTypeOperatorFunction<T> = OperatorFunction<T, T>

            public external interface Box<T> {
                public var value: T
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `a call signature beside other members and a construct signature are loud skips`() {
        // Before: `public fun ``(x: String): Double` (a compile error) and a
        // construct signature rendered as a method named `new`. A signature
        // with its OWN type parameters is not the callable shape either.
        val result = generate(
            """
            export interface Mixed {
                (x: string): number;
                name: string;
            }
            export interface Factory<T> {
                new (x: string): T;
                make(): T;
            }
            export interface Poly {
                <U>(x: U): U;
            }
            """
        )
        val expected = """
            public external interface Mixed {
                /* xtsc: skipped call signature */
                public var name: String
            }

            public external interface Factory<T> {
                /* xtsc: skipped construct signature */
                public fun make(): T
            }

            public external interface Poly {
                /* xtsc: skipped call signature */
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `negative control - a callable interface is never a supertype`() {
        // A function type has no subtypes to declare: a class implementing a
        // callable interface and a non-empty interface extending one keep the
        // per-base heritage marker. Before: `class Impl : Cb {` and
        // `interface Extended : Cb {`, against an interface that no longer exists.
        val result = generate(
            """
            export interface Cb {
                (done: boolean): void;
            }
            export declare class Impl implements Cb {
                run(): void;
            }
            export interface Extended extends Cb {
                extra: string;
            }
            """
        )
        val expected = """
            public typealias Cb = (Boolean) -> Unit

            public open external class Impl {
                /* xtsc: skipped heritage clause implements Cb */
                public fun run(): Unit
            }

            public external interface Extended {
                /* xtsc: skipped heritage clause extends Cb */
                public var extra: String
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a member typed by a callable interface renders by name with its arguments`() {
        // The alias is a NAMED type for every use — member, optional member,
        // parameter, return — exactly as an interface reference was; only the
        // declarations changed shape (before: interfaces with a nameless fun).
        val result = generate(
            """
            export interface UnaryFunction<T, R> {
                (source: T): R;
            }
            export interface Cb {
                (done: boolean): void;
            }
            export interface Uses<T> {
                fn: UnaryFunction<T, string>;
                cb: Cb;
                maybe?: Cb;
                apply(f: UnaryFunction<T, number>): Cb;
            }
            """
        )
        val expected = """
            public typealias UnaryFunction<T, R> = (T) -> R

            public typealias Cb = (Boolean) -> Unit

            public external interface Uses<T> {
                public var fn: UnaryFunction<T, String>
                public var cb: Cb
                public var maybe: Cb?
                public fun apply(f: UnaryFunction<T, Double>): Cb
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `a callable interface with an unmappable signature is a loud skip`() {
        val result = generate(
            """
            export interface Unmappable {
                (x: string | number): void;
            }
            """
        )
        val rendered = result.kotlin
        assert(rendered == "/* xtsc: skipped callable interface Unmappable with unmappable signature */\n")
    }

    @Test
    fun `a typeof query refuses with a marker naming the query`() {
        // The lens types a class VALUE as its INSTANCE type (CHK.73), so the
        // resolved rendering would say `Action` — before: `ctor: Action`, the
        // un-instantiated generic, a compile error (`one type argument
        // expected`), and `plain: Plain`, which compiled and was WRONG.
        val result = generate(
            """
            export declare class Action<T> {
                schedule(state: T): void;
            }
            export declare class Plain {
                x: number;
            }
            export declare class Scheduler {
                constructor(ctor: typeof Action, plain: typeof Plain);
                ctor: typeof Action;
            }
            """
        )
        val expected = """
            public open external class Action<T> {
                public fun schedule(state: T): Unit
            }

            public open external class Plain {
                public var x: Double
            }

            public open external class Scheduler(ctor: Any? /* xtsc: unmapped typeof Action */, plain: Any? /* xtsc: unmapped typeof Plain */) {
                public var ctor: Any? /* xtsc: unmapped typeof Action */
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `the arity guard - a value typed by an un-instantiated generic class falls back`() {
        // `export const ctor = Box` is typed by the checker as `Box`'s INSTANCE
        // type (CHK.73), a bare generic `Type.Interface` — the one shape that
        // reaches the renderer's bare-name leg with type parameters declared.
        // Before: `public external val ctor: Box` — one type argument expected.
        val result = generate(
            """
            export declare class Box<T> { value: T; }
            export const ctor = Box;
            """
        )
        val expected = """
            public open external class Box<T> {
                public var value: T
            }

            public external val ctor: Any? /* xtsc: unmapped Box<T> */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a this parameter in a function type renders as a Kotlin receiver`() {
        // Before: `handler: (Box<T>, T) -> Unit`, positionally — it compiled,
        // and a Kotlin lambda written against it would have received the
        // action where JavaScript passes the state. The nullable wrapping
        // still parenthesises, and a function-typed receiver is parenthesised.
        val result = generate(
            """
            export interface Box<T> { value: T; }
            export interface Work<T> {
                handler: (this: Box<T>, state: T) => void;
                maybe?: (this: Box<T>, state: T) => void;
                schedule(work: (this: Box<T>, state: T) => void, delay: number): void;
                nested: (this: () => void, x: string) => void;
            }
            """
        )
        val expected = """
            public external interface Box<T> {
                public var value: T
            }

            public external interface Work<T> {
                public var handler: Box<T>.(T) -> Unit
                public var maybe: (Box<T>.(T) -> Unit)?
                public fun schedule(work: Box<T>.(T) -> Unit, delay: Double): Unit
                public var nested: (() -> Unit).(String) -> Unit
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `a declaration's this parameter is dropped with a loud marker`() {
        // A `this` parameter is not a runtime parameter. Before: `fun run(this:
        // Box<T>, state: T)`, `fun bound(this: Box<String>, x: String)` and
        // `class Ctor(this: Box<String>, x: String)` — a phantom first
        // argument in every one, and `later: ((Box<T>, T) -> Unit)?`.
        val result = generate(
            """
            export interface Box<T> { value: T; }
            export interface Work<T> {
                run(this: Box<T>, state: T): void;
                later?(this: Box<T>, state: T): void;
            }
            export declare function bound(this: Box<string>, x: string): number;
            export declare class Ctor {
                constructor(this: Box<string>, x: string);
            }
            """
        )
        val expected = """
            public external interface Box<T> {
                public var value: T
            }

            public external interface Work<T> {
                /* xtsc: this parameter Box<T> not carried */
                public fun run(state: T): Unit
                /* xtsc: skipped this parameter Box<T> not carried - optional method later */
                public var later: ((T) -> Unit)?
            }

            /* xtsc: this parameter Box<String> not carried */
            public external fun bound(x: String): Double

            public open external class Ctor(x: String) {
                /* xtsc: skipped constructor this parameter Box<String> not carried */
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    // --- (EXT.11b) nullable unions, any/unknown, arrays, literals ----------

    @Test
    fun `nullable unions map to a nullable type on the syntactic and the resolved path`() {
        // Before: every one of these was `Any? /* xtsc: unmapped X | null */`.
        val result = generate(
            """
            export type Maybe = string | null;
            export interface Box { v: number; }
            export interface Nullish<T> {
                p: string | null;
                q: Box | undefined;
                r: number | null | undefined;
                f: ((value: T) => void) | null;
                g: (() => void) | undefined;
                m: Maybe;
                cb(next: ((value: T) => void) | null): Box | null;
            }
            """
        )
        val expected = """
            public typealias Maybe = String?

            public external interface Box {
                public var v: Double
            }

            public external interface Nullish<T> {
                public var p: String?
                public var q: Box?
                public var r: Double?
                public var f: ((T) -> Unit)?
                public var g: (() -> Unit)?
                public var m: String?
                public fun cb(next: ((T) -> Unit)?): Box?
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `double optionality renders one question mark and a nullable return is wrapped`() {
        // `p?: X | undefined` is the `.d.ts` idiom under
        // exactOptionalPropertyTypes: the union already made the text
        // nullable, and optionality must not make it `String??`. The optional
        // method's return is nullable INSIDE the function type, so the
        // property's own nullability parenthesises the whole function.
        val result = generate(
            """
            export interface Box { v: number; }
            export interface Opt {
                p?: string | undefined;
                q?: Box | null;
                f?: ((x: string) => void) | undefined;
                m?(x: string): string | null;
                g(x?: Box | null): void;
            }
            """
        )
        val expected = """
            public external interface Box {
                public var v: Double
            }

            public external interface Opt {
                public var p: String?
                public var q: Box?
                public var f: ((String) -> Unit)?
                public var m: ((String) -> String?)?
                public fun g(x: Box?): Unit
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `negative control - a union of distinct texts stays the marker with or without a nullish member`() {
        val result = generate(
            """
            export interface Box { v: number; }
            export interface Mixed {
                p: string | number;
                q: string | number | null;
                r: Box | string | undefined;
                s: Box | void;
            }
            """
        )
        val expected = """
            public external interface Box {
                public var v: Double
            }

            public external interface Mixed {
                public var p: Any? /* xtsc: unmapped string | number */
                public var q: Any? /* xtsc: unmapped string | number | null */
                public var r: Any? /* xtsc: unmapped string | Box | undefined */
                public var s: Any? /* xtsc: unmapped Box | void */
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `any and unknown map to nullable Any without a marker and unblock the composites carrying them`() {
        // Before: `p: Any? /* xtsc: unmapped any */`, and every composite
        // below refused as a whole because one piece was `any`.
        val result = generate(
            """
            export type Loose = any;
            export type Opaque = unknown;
            export interface Box<T> { v: T; }
            export interface Anything {
                p: any;
                q: unknown;
                t?: any;
                r: (err: any) => void;
                s: any[];
                b: Box<any>;
                l: Loose;
                o: Opaque;
                u: any | undefined;
                fn(cb: (value: unknown) => any): any;
            }
            """
        )
        val expected = """
            public typealias Loose = Any?

            public typealias Opaque = Any?

            public external interface Box<T> {
                public var v: T
            }

            public external interface Anything {
                public var p: Any?
                public var q: Any?
                public var t: Any?
                public var r: (Any?) -> Unit
                public var s: Array<Any?>
                public var b: Box<Any?>
                public var l: Any?
                public var o: Any?
                public var u: Any?
                public fun fn(cb: (Any?) -> Any?): Any?
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `negative control - a degraded resolution stays marked and a Promise is not a built-in`() {
        // `Missing` resolves to nothing and `Record<string, number>` (a lib
        // mapped alias) resolves to the bare `any` intrinsic in this checker
        // (measured): both are the silent direction, so a resolved `any` the
        // source did not SPELL keeps its marker — naming what was written,
        // since `lens.render` prints `any` for both — and the written
        // keyword is the only `any` that maps. A rest parameter inside a
        // function type stays refused, and `Promise<T>` stays a marker: the
        // compile gate has no classpath and `kotlin.js.Promise` is not a
        // built-in.
        val result = generate(
            """
            export interface Degraded {
                u: Missing;
                v: Missing[];
                w: Record<string, number>;
                x: Array<string, number>;
                p: Promise<string>;
                run(): Promise<void>;
                spread: (...args: any[]) => any;
            }
            """
        )
        val expected = """
            public external interface Degraded {
                public var u: Any? /* xtsc: unmapped Missing - resolved to any */
                public var v: Any? /* xtsc: unmapped any[] */
                public var w: Any? /* xtsc: unmapped Record<string, number> - resolved to any */
                public var x: Any? /* xtsc: unmapped Array<string, number> */
                public var p: Any? /* xtsc: unmapped Promise<string> */
                public fun run(): Any? /* xtsc: unmapped Promise<void> */
                public var spread: Any? /* xtsc: unmapped (...args: any[]) => any */
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes == listOf(2304, 2304, 2314))
    }

    @Test
    fun `arrays map to Array on both paths and a declaration's rest parameter is vararg`() {
        // Before: `a: Any? /* xtsc: unmapped string[] */` and `...parts:
        // string[]` rendered `parts: Any? /* xtsc: unmapped string[] */`.
        val result = generate(
            """
            export type Names = string[];
            export interface Box { v: number; }
            export interface Bag<T> {
                a: string[];
                b: Array<T>;
                c: ReadonlyArray<Box>;
                d: readonly number[];
                e: Box[][];
                f: ((x: T) => void)[];
                g: string[] | null;
                names: Names;
                add(...more: Box[]): void;
            }
            export declare function join(sep: string, ...parts: string[]): string;
            export declare function all<T>(...xs: Array<T>): T;
            export declare class Pool {
                constructor(...items: readonly Box[]);
                take(...boxes: ReadonlyArray<Box>): Box;
            }
            """
        )
        val expected = """
            public typealias Names = Array<String>

            public external interface Box {
                public var v: Double
            }

            public external interface Bag<T> {
                public var a: Array<String>
                public var b: Array<T>
                public var c: Array<Box>
                public var d: Array<Double>
                public var e: Array<Array<Box>>
                public var f: Array<(T) -> Unit>
                public var g: Array<String>?
                public var names: Array<String>
                public fun add(vararg more: Box): Unit
            }

            public external fun join(sep: String, vararg parts: String): String

            public external fun <T> all(vararg xs: T): T

            public open external class Pool(vararg items: Box) {
                public fun take(vararg boxes: Box): Box
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `negative control - a program's own Array is not an array and a rest parameter that cannot be vararg is loud`() {
        // The non-exported local `Array` shadows the lib's for `a`: its
        // declaration is in the program, not in a lib file, so it is no
        // evidence of an array — while `b`'s `string[]` SYNTAX is one. The
        // marker spells the WRITTEN reference: the checker resolves a
        // one-argument `Array<X>` to the lib array by name, so its render
        // (`string[]`) would claim the mapping that was refused. A
        // two-argument `Array<A, B>` is not the lib's shape. A rest parameter
        // typed by a tuple or by a bare type parameter has no element to
        // spread, and one whose element does not map keeps the whole
        // annotation's marker.
        val result = generate(
            """
            interface Array<T> { own: T; }
            export interface Uses {
                a: Array<string>;
                b: string[];
                c: Array<string, number>;
            }
            export declare function f(...pair: [string, number]): void;
            export declare function g<T extends unknown[]>(...xs: T): void;
            export declare function h(...xs: (string | number)[]): void;
            """
        )
        val expected = """
            public external interface Uses {
                public var a: Any? /* xtsc: unmapped Array<string> - not the lib Array */
                public var b: Array<String>
                public var c: Any? /* xtsc: unmapped Array<string, number> - not the lib Array */
            }

            public external fun f(pair: Any? /* xtsc: unmapped rest [string, number] */): Unit

            /* xtsc: constraint on T: unknown[] not carried */
            public external fun <T> g(xs: Any? /* xtsc: unmapped rest any */): Unit

            public external fun h(xs: Any? /* xtsc: unmapped (string | number)[] */): Unit
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `literal types widen to their base and a literal union collapses to it`() {
        // Before: `kind: Any? /* xtsc: unmapped "N" */`. A union whose
        // members all widen to ONE text is that text; a union widening to
        // two texts and a bigint literal keep their markers.
        val result = generate(
            """
            export type Kind = "N" | "E" | "C";
            export interface Notif {
                kind: "N";
                code: 1;
                neg: -1;
                on: true;
                mode: "a" | "b" | "c";
                maybe: "a" | "b" | null;
                k: Kind;
                mixed: "a" | 1;
                big: 10n;
                tag(kind: "E" | "C"): "done";
            }
            """
        )
        val expected = """
            public typealias Kind = String

            public external interface Notif {
                public var kind: String
                public var code: Double
                public var neg: Double
                public var on: Boolean
                public var mode: String
                public var maybe: String?
                public var k: String
                public var mixed: Any? /* xtsc: unmapped "a" | 1 */
                public var big: Any? /* xtsc: unmapped 10n */
                public fun tag(kind: String): String
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

}
