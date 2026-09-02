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
    fun `a lib base stays marked and an index signature renders as an operator pair`() {
        // Until (EXT.15) the index signature was the "unsupported member
        // shape" this pin carried; it now renders, and the lib base stays
        // the loud marker it always was.
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
                public operator fun get(k: String): String?
                public operator fun set(k: String, value: String): Unit
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
        val marker = "/* xtsc: skipped overload of choose collapsing to a duplicate signature - kept choose(mode: String) */" in rendered
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
    fun `a constructor parameter property declares a member and the parameter still renders`() {
        // (EXT.15) The four modifiers in one constructor: `public` and
        // `readonly` declare consumable members (`var` and `val`),
        // `private` and `protected` are omitted silently like every other
        // private member, and the constructor keeps all four parameters —
        // the gate variant included, where the members grow bodies.
        val result = generate(
            """
            export class Point<T> {
                constructor(public x: number, private y: string, readonly z: boolean, protected w: T) {}
            }
            """
        )
        val expected = """
            public open external class Point<T>(x: Double, y: String, z: Boolean, w: T) {
                public var x: Double
                public val z: Boolean
            }
        """.trimIndent() + "\n"
        val expectedGate = """
            public abstract class Point<T>(x: Double, y: String, z: Boolean, w: T) {
                public var x: Double = null!!
                public val z: Boolean = null!!
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val gateVariant = result.compileCheckSource
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(gateVariant == expectedGate)
        assert(errorCodes.isEmpty())
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

            /* xtsc: skipped overload of choose collapsing to a duplicate signature - kept choose(mode: String) */

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
    fun `a generic class value is refused on both paths - the initializer rule first-hand and the arity guard second-hand`() {
        // `export const ctor = Box` is typed by the checker as `Box`'s INSTANCE
        // type (CHK.73), a bare generic `Type.Interface`. (CHK.73b) refuses it
        // by what the initializer NAMES, before the checker is asked — before
        // that: `public external val ctor: Any? /* xtsc: unmapped Box<T> */`,
        // the arity guard, and before (EXT.11a) `val ctor: Box`, a compile
        // error. The SECOND-hand value `again = ctor` names no class, so it
        // still reaches the renderer's bare-name leg with type parameters
        // declared: the arity guard is the negative control that both paths
        // agree on refusing.
        val result = generate(
            """
            export declare class Box<T> { value: T; }
            export const ctor = Box;
            export const again = ctor;
            """
        )
        val expected = """
            public open external class Box<T> {
                public var value: T
            }

            /* xtsc: skipped value ctor initialized by the class Box - a constructor value has no externals shape yet */

            public external val again: Any? /* xtsc: unmapped Box<T> */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    // --- (CHK.73b) a class value is not its instance ---------------------------

    @Test
    fun `a value initialized by a class is a loud skip and a new expression keeps the instance type`() {
        // Before: `public external val plain: Plain` — the instance type,
        // which compiles and is WRONG (a consumer reads `plain.x` where the
        // runtime value is the constructor). `new Plain()` IS an instance.
        val result = generate(
            """
            export declare class Plain { x: number; }
            export const plain = Plain;
            export const p = new Plain();
            """
        )
        val expected = """
            public open external class Plain {
                public var x: Double
            }

            /* xtsc: skipped value plain initialized by the class Plain - a constructor value has no externals shape yet */

            public external val p: Plain
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `an imported class value is refused through the import alias across files`() {
        // Before: `public external val p: Plain`. The lexical `resolveName`
        // answers the import SPECIFIER here and `aliasTarget` on it is null
        // (measured), so the rule resolves the initializer as a heritage
        // base does — the class in the other file. The instance-typed `q`
        // still renders by name across the file boundary.
        val result = generateFiles(
            "/pkg/a.ts" to """
                export declare class Plain { x: number; }
            """,
            "/pkg/b.ts" to """
                import { Plain } from './a.js';
                export const p = Plain;
                export const q = new Plain();
            """,
        )
        val expected = """
            public open external class Plain {
                public var x: Double
            }

            /* xtsc: skipped value p initialized by the class Plain - a constructor value has no externals shape yet */

            public external val q: Plain
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `an enum object and a namespace object are refused - a namespace member and a merged class decide by kind`() {
        // Before: `public external val K: Kind` (the entries' sealed
        // interface — a consumer would read `K` as an ENTRY), `public
        // external val N: Any?` (marker-less, the shape reserved for a
        // written `any`), `public external val I: Any? /* xtsc: unmapped
        // Inner */` (a misleading marker for a nested class's constructor)
        // and `public external val C: Any?` for a class expression. A
        // namespace MEMBER `NS.x` resolves to a value and keeps the
        // checker's answer; a class merged with a namespace is the class.
        // (EXT.13) The two ambient namespaces now FLATTEN — `NS`'s `x` and
        // `Inner` and `M`'s `z` join the surface under their headers — which
        // moved this pin's text; the value refusals are unchanged.
        val result = generate(
            """
            export enum Kind { A, B }
            export const K = Kind;
            export declare namespace NS { const x: number; class Inner { y: number; } }
            export const N = NS;
            export const I = NS.Inner;
            export const v = NS.x;
            export const C = class { y: number; };
            export declare class M { y: number; }
            export declare namespace M { const z: number; }
            export const m = M;
            """
        )
        val expected = """
            public sealed external interface Kind {
                public companion object {
                    public val A: Kind
                    public val B: Kind
                }
            }

            /* xtsc: skipped value K initialized by the enum Kind - an enum object value has no externals shape yet */

            /* xtsc: namespace NS - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external val x: Double

            public open external class Inner {
                public var y: Double
            }

            /* xtsc: skipped value N initialized by the namespace NS - a namespace object value has no externals shape yet */

            /* xtsc: skipped value I initialized by the class NS.Inner - a constructor value has no externals shape yet */

            public external val v: Double

            /* xtsc: skipped value C initialized by a class expression - a constructor value has no externals shape yet */

            public open external class M {
                public var y: Double
            }

            /* xtsc: namespace M - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external val z: Double

            /* xtsc: skipped value m initialized by the class M - a constructor value has no externals shape yet */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `negative control - a lib constructor value keeps its marker and a typeof annotation stays the typeof marker`() {
        // `lib.*.d.ts` spells `Error` as `interface Error` plus `declare var
        // Error: ErrorConstructor` — no class declaration — so the checker
        // types `E` CORRECTLY as the constructor interface and the (EXT.11b)
        // marker carries it; the (CHK.73b) rule does not fire and must not.
        // An ANNOTATED value renders its annotation whatever its initializer
        // names: `typeof Plain` is the (EXT.11a) marker on both.
        val result = generate(
            """
            export declare class Plain { x: number; }
            export const E = Error;
            export declare const ctor: typeof Plain;
            export const both: typeof Plain = Plain;
            """
        )
        val expected = """
            public open external class Plain {
                public var x: Double
            }

            public external val E: Any? /* xtsc: unmapped ErrorConstructor */

            public external val ctor: Any? /* xtsc: unmapped typeof Plain */

            public external val both: Any? /* xtsc: unmapped typeof Plain */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `a this parameter in a function type is dropped with a loud top-level marker`() {
        // Before (EXT.11a): `handler: (Box<T>, T) -> Unit`, positionally — it
        // compiled, and a Kotlin lambda written against it would have
        // received the action where JavaScript passes the state. (EXT.11a)
        // rendered the receiver `Box<T>.(T) -> Unit`; (EXT.17) measured that
        // the Kotlin/JS compiler refuses it in every external declaration
        // and that a receiver lambda is called with the receiver as its
        // FIRST ARGUMENT — the same silent direction. Receiver-less, with the
        // marker after the WHOLE type (after the nullable wrap, never inside
        // a parenthesis), and the marker names the receiver's Kotlin type.
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
                public var handler: (T) -> Unit /* xtsc: this parameter Box<T> not carried */
                public var maybe: ((T) -> Unit)? /* xtsc: this parameter Box<T> not carried */
                public fun schedule(work: (T) -> Unit /* xtsc: this parameter Box<T> not carried */, delay: Double): Unit
                public var nested: (String) -> Unit /* xtsc: this parameter () -> Unit not carried */
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `a this parameter in an alias body or a callable interface is a marker on the alias`() {
        // (EXT.17) The receiver of an alias body cannot trail the body (a
        // use of the alias would carry it), so it is one of the alias's own
        // markers, above the declaration — for both alias producers.
        val result = generate(
            """
            export interface Box { value: number; }
            export type Work = (this: Box, state: number) => void;
            export interface Callable { (this: Box, x: string): boolean; }
            export declare function run(w: Work, c: Callable): void;
            """
        )
        val expected = """
            public external interface Box {
                public var value: Double
            }

            /* xtsc: this parameter Box not carried */
            public typealias Work = (Double) -> Unit

            /* xtsc: this parameter Box not carried */
            public typealias Callable = (String) -> Boolean

            public external fun run(w: Work, c: Callable): Unit
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `a non-abstract class renders every interface member no class in its chain declares by key as a loud override`() {
        // (EXT.17) Measured by the Kotlin/JS gate on rxjs: `Observer<T>.next`
        // is a function-typed PROPERTY and `Subscriber<T>.next(value)` a
        // METHOD; TypeScript reads them as one member, Kotlin as two, and the
        // class then owed the property (`Class 'Subscriber' is not abstract
        // and does not implement abstract members`). The owed member renders
        // as an override of the INHERITED shape after the class's own
        // members, loudly (a Kotlin class may declare a property and a
        // function of one name); both directions; the next class down the
        // chain sees the owed member as declared and owes nothing.
        val result = generate(
            """
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
            """
        )
        val expected = """
            public external interface Observer<T> {
                public var next: (T) -> Unit
                public var error: ((Any?) -> Unit)?
            }

            public external interface Clock {
                public fun now(): Double
            }

            public open external class Subscriber<T> : Observer<T>, Clock {
                public open fun next(value: T): Unit
                public fun next(value: T, again: Boolean): Unit
                public fun error(err: Any?): Unit
                public var now: () -> Double
                /* xtsc: property next inherited from Observer - implemented by the class's own next under another signature in TypeScript */
                public override var next: (T) -> Unit
                /* xtsc: property error inherited from Observer - implemented by the class's own error under another signature in TypeScript */
                public override var error: ((Any?) -> Unit)?
                /* xtsc: method now inherited from Clock - implemented by the class's own now under another signature in TypeScript */
                public override fun now(): Double
            }

            public open external class Safe<T> : Subscriber<T> {
                public override fun next(value: T): Unit
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `an abstract class owes nothing and its first concrete subclass owes what it never declared`() {
        // (EXT.17) Measured on `typescript.d.ts`: `server.Project` is abstract
        // and never declares `LanguageServiceHost`'s OPTIONAL members, so
        // `server.InferredProject` owed them (`var getTypeRootsVersion: (() ->
        // Double)?` …). The optional member's inherited type is already
        // nullable, so the override is true to the JavaScript; the abstract
        // class renders no override (Kotlin's rule too), and a concrete
        // subclass of a CONCRETE class owes nothing its base rendered.
        val result = generate(
            """
            export interface Host {
                getVersion(): string;
                getRoots?: () => number;
                readFile(path: string, encoding?: string): string | undefined;
            }
            export declare abstract class Base implements Host {
                getVersion(): string;
                readFile(path: string): string | undefined;
            }
            export declare class Inferred extends Base {}
            export declare class Configured extends Inferred {}
            """
        )
        val expected = """
            public external interface Host {
                public fun getVersion(): String
                public var getRoots: (() -> Double)?
                public fun readFile(path: String, encoding: String?): String?
            }

            public abstract external class Base : Host {
                public override fun getVersion(): String
                public fun readFile(path: String): String?
            }

            public open external class Inferred : Base {
                /* xtsc: property getRoots inherited from Host - not declared by the class in TypeScript */
                public override var getRoots: (() -> Double)?
                /* xtsc: method readFile inherited from Host - implemented by the class's own readFile under another signature in TypeScript */
                public override fun readFile(path: String, encoding: String?): String?
            }

            public open external class Configured : Inferred
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

    // --- (EXT.11c) Kotlin's overload equivalence, name collisions, narrowed vars ---

    @Test
    fun `overloads differing only in nullability or in type-parameter names collapse`() {
        // Before ((EXT.5)'s textual key): all five rendered, and Kotlin
        // refused `first(predicate: Boolean, defaultValue: D)` beside
        // `defaultValue: D?` and beside `<T, S> … defaultValue: S` as
        // `Conflicting overloads` — the rxjs `first`/`last` shape. Measured:
        // a FREE own type parameter (every occurrence covariant) reads as its
        // bound `Any?`, `?` or not; names never count.
        val result = generate(
            """
            export interface Src<T> { v: T; }
            export declare function first<T, D>(predicate: boolean, defaultValue: D): Src<D>;
            export declare function first<T, D>(predicate: boolean, defaultValue?: D): Src<D>;
            export declare function first<T, S>(predicate: boolean, defaultValue: S): Src<S>;
            export declare function first<T, S>(pick: (value: T) => boolean, defaultValue?: S): Src<S>;
            export declare function first<T, D>(pick: (value: T) => boolean, defaultValue: D): Src<D>;
            """
        )
        val expected = """
            public external interface Src<T> {
                public var v: T
            }

            public external fun <T, D> first(predicate: Boolean, defaultValue: D): Src<D>

            /* xtsc: skipped overload of first collapsing to a duplicate signature - kept <T, D> first(predicate: Boolean, defaultValue: D) */

            /* xtsc: skipped overload of first collapsing to a duplicate signature - kept <T, D> first(predicate: Boolean, defaultValue: D) */

            public external fun <T, S> first(pick: (T) -> Boolean, defaultValue: S?): Src<S>

            /* xtsc: skipped overload of first collapsing to a duplicate signature - kept <T, S> first(pick: (T) -> Boolean, defaultValue: S?) */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `a bare type parameter is one overload with any at equal arity and type-parameter order does not count`() {
        // Before: `<T> of(value: T)` beside `<A> of(anything: Any?)` was a
        // compile error (the rxjs `of` shape), and so were the two
        // `pair`s that only reorder or reuse their type parameters. The
        // negative control is the COUNT: `of(single: Any?)` declares no type
        // parameter and is a distinct overload, and so is the one-parameter
        // `pair`.
        val result = generate(
            """
            export interface Box<T> { v: T; }
            export declare function of<T>(value: T): Box<T>;
            export declare function of<A>(anything: any): Box<A>;
            export declare function of(single: any): Box<any>;
            export declare function pair<T, U>(a: T, b: U): void;
            export declare function pair<U, T>(a: T, b: U): void;
            export declare function pair<A, B>(a: A, b: A): void;
            export declare function pair<A>(a: A, b: A): void;
            """
        )
        val expected = """
            public external interface Box<T> {
                public var v: T
            }

            public external fun <T> of(value: T): Box<T>

            /* xtsc: skipped overload of of collapsing to a duplicate signature - kept <T> of(value: T) */

            public external fun of(single: Any?): Box<Any?>

            public external fun <T, U> pair(a: T, b: U): Unit

            /* xtsc: skipped overload of pair collapsing to a duplicate signature - kept <T, U> pair(a: T, b: U) */

            /* xtsc: skipped overload of pair collapsing to a duplicate signature - kept <T, U> pair(a: T, b: U) */

            public external fun <A> pair(a: A, b: A): Unit
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `negative control - a type parameter in a generic argument or a callback parameter keeps the overload`() {
        // Measured: an own type parameter PINNED by an invariant (`Box<T>`)
        // or contravariant (`(T) -> Unit`) occurrence is distinct from
        // `Any?` and from its nullable self, and compared with another
        // pinned one up to renaming — `Box<T>` and `Box<U>` collapse,
        // `(Box<T>, Box<U>)` and `(Box<A>, Box<A>)` do not, `(Box<B>,
        // Box<A>)` does. A callback's RETURN is covariant, so `() => T`
        // erases like a bare parameter. Before this round `wrap<U>(x: Box<U>)`
        // and `two<A, B>(a: Box<B>, b: Box<A>)` were compile errors.
        val result = generate(
            """
            export interface Box<T> { v: T; }
            export declare function wrap<T>(x: Box<T>): void;
            export declare function wrap<U>(x: Box<any>): void;
            export declare function wrap<U>(x: Box<U>): void;
            export declare function wrap<U>(x: Box<U | null>): void;
            export declare function on<T>(cb: (x: T) => void): void;
            export declare function on<U>(cb: (x: any) => void): void;
            export declare function make<T>(cb: () => T): void;
            export declare function make<U>(cb: () => any): void;
            export declare function two<T, U>(a: Box<T>, b: Box<U>): void;
            export declare function two<A, B>(a: Box<A>, b: Box<A>): void;
            export declare function two<A, B>(a: Box<B>, b: Box<A>): void;
            """
        )
        val expected = """
            public external interface Box<T> {
                public var v: T
            }

            public external fun <T> wrap(x: Box<T>): Unit

            public external fun <U> wrap(x: Box<Any?>): Unit

            /* xtsc: skipped overload of wrap collapsing to a duplicate signature - kept <T> wrap(x: Box<T>) */

            public external fun <U> wrap(x: Box<U?>): Unit

            public external fun <T> on(cb: (T) -> Unit): Unit

            public external fun <U> on(cb: (Any?) -> Unit): Unit

            public external fun <T> make(cb: () -> T): Unit

            /* xtsc: skipped overload of make collapsing to a duplicate signature - kept <T> make(cb: () -> T) */

            public external fun <T, U> two(a: Box<T>, b: Box<U>): Unit

            public external fun <A, B> two(a: Box<A>, b: Box<A>): Unit

            /* xtsc: skipped overload of two collapsing to a duplicate signature - kept <T, U> two(a: Box<T>, b: Box<U>) */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `method overloads use the same equivalence and an enclosing type parameter is an ordinary type`() {
        // `emit<V>(x: V)` is one overload with `emit<U>(x: any)`; `on<V>(x:
        // V)` is not one with `on<U>(x: T)`, because the INTERFACE's `T` is
        // a named type to Kotlin, not a free parameter — and `once(x: T)`
        // beside `once(x: any)` is legal for the same reason.
        val result = generate(
            """
            export interface Emitter<T> {
                on<U>(x: T): void;
                on<V>(x: V): void;
                emit<U>(x: any): void;
                emit<V>(x: V): void;
                once(x: T): void;
                once(x: any): void;
            }
            """
        )
        val expected = """
            public external interface Emitter<T> {
                public fun <U> on(x: T): Unit
                public fun <V> on(x: V): Unit
                public fun <U> emit(x: Any?): Unit
                /* xtsc: skipped overload of emit collapsing to a duplicate signature - kept <U> emit(x: Any?) */
                public fun once(x: T): Unit
                public fun once(x: Any?): Unit
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `a value sharing a type's name is a loud skip whatever the order and a function beside an interface stays`() {
        // Before: `public external val AjaxError: AjaxErrorCtor` beside
        // `public external interface AjaxError` — Kotlin's `Conflicting
        // declarations` (the rxjs companion-value idiom, nine times over the
        // package). The type wins in both walk orders; a function and an
        // interface of one name are legal Kotlin (measured) and both stay.
        val result = generate(
            """
            export interface AjaxError { status: number; }
            export interface AjaxErrorCtor { new (status: number): AjaxError; }
            export declare const AjaxError: AjaxErrorCtor;
            export declare const Later: number;
            export interface Later { id: string; }
            export declare function Shape(): number;
            export interface Shape { s: string; }
            """
        )
        val expected = """
            public external interface AjaxError {
                public var status: Double
            }

            public external interface AjaxErrorCtor {
                /* xtsc: skipped construct signature */
            }

            /* xtsc: skipped value AjaxError shares its name with the type AjaxError - module wiring is a later rung */

            /* xtsc: skipped value Later shares its name with the type Later - module wiring is a later rung */

            public external interface Later {
                public var id: String
            }

            public external fun Shape(): Double

            public external interface Shape {
                public var s: String
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `a function spelling a class's constructor is a loud skip and a second same-named value too`() {
        // Measured against the metadata compiler: `fun Box(v: String)`
        // beside `class Box(v: String)` is `Conflicting overloads` with the
        // constructor, `fun Box(v: Double)` is a legal overload of it, a
        // class without a constructor exposes the implicit `Empty()`, and a
        // `typealias Name = String` exposes `String()` — so only the
        // zero-argument `Name()` collides. Two values of one name are
        // `Conflicting declarations` like two types.
        val result = generateFiles(
            "/pkg/a.d.ts" to """
                export declare class Box { constructor(v: string); }
                export declare class Empty { }
                export type Name = string;
            """,
            "/pkg/b.d.ts" to """
                export declare function Box(v: string): number;
                export declare function Box(v: number): number;
                export declare function Empty(): number;
                export declare function Name(): string;
                export declare function Name(x: string): string;
                export declare const twice: number;
            """,
            "/pkg/c.d.ts" to """
                export declare const twice: string;
            """,
        )
        val expected = """
            public open external class Box(v: String)

            public open external class Empty

            public typealias Name = String

            /* xtsc: skipped function Box shares its signature with the constructor of Box - module wiring is a later rung */

            public external fun Box(v: Double): Double

            /* xtsc: skipped function Empty shares its signature with the constructor of Empty - module wiring is a later rung */

            /* xtsc: skipped function Name shares its signature with the constructor of Name - module wiring is a later rung */

            public external fun Name(x: String): String

            public external val twice: Double

            /* xtsc: skipped twice declared again by another file - one Kotlin package cannot hold both */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a narrowed var override renders the inherited type loudly and a covariant val override keeps its own`() {
        // (EXT.13) `tag` MOVED: `override val tag: Obs<U>` over `val tag:
        // Obs<Any?>` was pinned here as the covariant case and is REFUSED by
        // the metadata compiler (`T` is invariant; measured 2026-09-02) — no
        // gate had compiled this fixture. The covariant `val` rule survives
        // for a Kotlin SUBTYPE ([Inheritance.isSubtypeText]); a generic
        // instantiation is not one, and renders the inherited type loudly.
        // Before: `public override var source: Obs<U>` over the inherited
        // `var source: Obs<Any?>?` — Kotlin's "type of 'var' doesn't match
        // the overridden" (the rxjs `ConnectableObservable` shape) — and
        // `handle(x: U)` carried no `override` at all, because the base's
        // `T` was compared with the derived's `U` by text. The base is now
        // read through the supertype's arguments; a readonly member over a
        // readonly base is a covariant `val` override, legal as it is; the
        // readonly-narrows-var and the narrowed-type markers compose; two
        // unmapped types are one `Any?` and no narrowing.
        val result = generate(
            """
            interface Hidden1 { a: string; }
            interface Hidden2 extends Hidden1 { b: string; }
            export interface Obs<T> { v: T; }
            export declare class Base<T> {
                source: Obs<any> | undefined;
                readonly tag: Obs<any>;
                both: Obs<any>;
                other: Obs<T>;
                raw: Hidden1;
                handle(x: T): void;
            }
            export declare class Derived<U> extends Base<U> {
                source: Obs<U>;
                readonly tag: Obs<U>;
                readonly both: Obs<U>;
                other: Obs<U>;
                raw: Hidden2;
                handle(x: U): void;
            }
            """
        )
        val expected = """
            public external interface Obs<T> {
                public var v: T
            }

            public open external class Base<T> {
                public open var source: Obs<Any?>?
                public open val tag: Obs<Any?>
                public open var both: Obs<Any?>
                public open var other: Obs<T>
                public open var raw: Any? /* xtsc: unmapped Hidden1 */
                public open fun handle(x: T): Unit
            }

            public open external class Derived<U> : Base<U> {
                /* xtsc: narrowed to Obs<U> in TypeScript - rendered as the inherited Obs<Any?>? */
                public override var source: Obs<Any?>?
                /* xtsc: narrowed to Obs<U> in TypeScript - rendered as the inherited Obs<Any?> */
                public override val tag: Obs<Any?>
                /* xtsc: readonly narrows an inherited var - rendered var */
                /* xtsc: narrowed to Obs<U> in TypeScript - rendered as the inherited Obs<Any?> */
                public override var both: Obs<Any?>
                public override var other: Obs<U>
                public override var raw: Any? /* xtsc: unmapped Hidden2 */
                public override fun handle(x: U): Unit
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `an inherited constructor and an override are read through a renamed generic chain`() {
        // Before: `class Leaf<V>(x: T)` — the base's own parameter name,
        // unbound in the leaf — and `use(v: V)` without `override` two links
        // down. The substitution composes: `Leaf<V> : Mid<V>`, `Mid<U> :
        // Root<Obs<U>>`, so `Root<T>`'s `T` is `Obs<V>` seen from the leaf.
        val result = generate(
            """
            export interface Obs<T> { v: T; }
            export declare class Root<T> {
                constructor(x: T);
                use(v: T): void;
            }
            export declare class Mid<U> extends Root<Obs<U>> { }
            export declare class Leaf<V> extends Mid<V> {
                use(v: Obs<V>): void;
            }
            """
        )
        val expected = """
            public external interface Obs<T> {
                public var v: T
            }

            public open external class Root<T>(x: T) {
                public open fun use(v: T): Unit
            }

            public open external class Mid<U>(x: Obs<U>) : Root<Obs<U>>

            public open external class Leaf<V>(x: Obs<V>) : Mid<V> {
                public override fun use(v: Obs<V>): Unit
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    // --- (EXT.12) the collapse's survivor: fewest markers, ties to the first ---

    @Test
    fun `the of shape keeps the clean overload over the marked one declared before it`() {
        // rxjs's `of`: the `[...A, SchedulerLike]` rest overload comes FIRST
        // and falls to a marked `Any?`, and `<T> of(value: T)` four lines
        // below is the same Kotlin overload (a free type parameter erases
        // to `Any?`). Before: first-wins kept the marked one and the clean
        // signature was the marker.
        val result = generate(
            """
            export interface SchedulerLike { now(): number; }
            export interface Observable<T> { value: T; }
            export declare function of<A extends readonly unknown[]>(...valuesAndScheduler: [...A, SchedulerLike]): Observable<A[number]>;
            export declare function of<T>(value: T): Observable<T>;
            """
        )
        val expected = """
            public external interface SchedulerLike {
                public fun now(): Double
            }

            public external interface Observable<T> {
                public var value: T
            }

            /* xtsc: skipped overload of of collapsing to a duplicate signature - kept <T> of(value: T) */

            public external fun <T> of(value: T): Observable<T>
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `the least-marked overload wins and a parameter marker a return marker and a constraint marker each count`() {
        // Three ingredients of the rank, one per name: `pick` differs in a
        // PARAMETER marker, `wrap` in the RETURN marker, `gen` in the
        // declaration's own marker list (a constraint not carried) — in
        // each the clean twin is declared SECOND and is kept. `tie` is the
        // control: two equally marked twins keep the first, the standing
        // policy, so every earlier collapse pin renders as it did.
        val result = generate(
            """
            export declare function pick(x: string | number): string;
            export declare function pick(x: any): string;
            export declare function wrap(x: any): string | number;
            export declare function wrap(x: any): string;
            export declare function gen<A extends string>(x: A): void;
            export declare function gen<T>(x: T): void;
            export declare function tie(x: string | number): void;
            export declare function tie(x: boolean | string): void;
            """
        )
        val expected = """
            /* xtsc: skipped overload of pick collapsing to a duplicate signature - kept pick(x: Any?) */

            public external fun pick(x: Any?): String

            /* xtsc: skipped overload of wrap collapsing to a duplicate signature - kept wrap(x: Any?) */

            public external fun wrap(x: Any?): String

            /* xtsc: skipped overload of gen collapsing to a duplicate signature - kept <T> gen(x: T) */

            public external fun <T> gen(x: T): Unit

            public external fun tie(x: Any? /* xtsc: unmapped string | number */): Unit

            /* xtsc: skipped overload of tie collapsing to a duplicate signature - kept tie(x: Any?) */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `a three-member class keeps its least-marked middle member at its own position across an interleaved twin`() {
        // The class is `pick(x: Any?)` three times over, split by a
        // two-parameter `pick` that is a different overload: collected as a
        // WHOLE before deciding (a running seen-set would have kept the
        // first), the middle member wins, and every slot stays where it was
        // declared — marker, the interleaved twin, the survivor, marker.
        val result = generate(
            """
            export declare function pick(x: string | number): string | number;
            export declare function pick(x: string, y: string): string;
            export declare function pick(x: any): string;
            export declare function pick(x: boolean | string): string;
            """
        )
        val expected = """
            /* xtsc: skipped overload of pick collapsing to a duplicate signature - kept pick(x: Any?) */

            public external fun pick(x: String, y: String): String

            public external fun pick(x: Any?): String

            /* xtsc: skipped overload of pick collapsing to a duplicate signature - kept pick(x: Any?) */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `a method and a static member collapse by the same rank as a top-level function`() {
        // The one helper decides all three sites: an instance method, a
        // companion (static) member and an interface method each keep the
        // clean second twin over the marked first.
        val result = generate(
            """
            export declare class Bus {
                send(x: string | number): void;
                send(x: any): void;
                static open(mode: string | number): Bus;
                static open(mode: any): Bus;
            }
            export interface Port {
                read(x: string | number): string;
                read(x: any): string;
            }
            """
        )
        val expected = """
            public open external class Bus {
                /* xtsc: skipped overload of send collapsing to a duplicate signature - kept send(x: Any?) */
                public fun send(x: Any?): Unit
                public companion object {
                    /* xtsc: skipped overload of open collapsing to a duplicate signature - kept open(mode: Any?) */
                    public fun open(mode: Any?): Bus
                }
            }

            public external interface Port {
                /* xtsc: skipped overload of read collapsing to a duplicate signature - kept read(x: Any?) */
                public fun read(x: Any?): String
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    // ---- (EXT.13) the namespace rung ---------------------------------------
    //
    // Every positive pin below was RED against the pre-(EXT.13) collector by
    // construction — a namespace rendered nothing then (its members were not
    // in any membership set), so no ablation arm was needed to prove
    // discrimination; each expected text was read from the generator's own
    // output and every fixture's gate variant compiled.

    private fun generateDts(source: String): KotlinExternals =
        generateKotlinExternals("t.d.ts", source.trimIndent())

    @Test
    fun `a root ambient namespace with export equals flattens every declaration kind under one header`() {
        // The `typescript.d.ts` shape: `declare namespace ts { … } export = ts`.
        // Interfaces, a class with a static, an enum, an alias, function
        // overloads and values render exactly as top-level declarations do,
        // under the loud header; the `export =` keeps its wiring marker.
        val result = generateDts(
            """
            declare namespace ts {
                interface Node { kind: number; parent: Node; }
                class Program {
                    getRoot(): Node;
                    static create(n: Node): Program;
                }
                enum Kind { A, B }
                type Path = string;
                function transform(n: Node): Node;
                function transform(n: Node, deep: boolean): Node;
                const version: string;
                let sys: Node;
            }
            export = ts;
            """
        )
        val expected = """
            /* xtsc: namespace ts - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external interface Node {
                public var kind: Double
                public var parent: Node
            }

            public open external class Program {
                public fun getRoot(): Node
                public companion object {
                    public fun create(n: Node): Program
                }
            }

            public sealed external interface Kind {
                public companion object {
                    public val A: Kind
                    public val B: Kind
                }
            }

            public typealias Path = String

            public external fun transform(n: Node): Node

            public external fun transform(n: Node, deep: Boolean): Node

            public external val version: String

            public external var sys: Node

            /* xtsc: skipped export = ts - module wiring is a later rung */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `an ambient namespace exports every member implicitly and an explicit export elsewhere does not switch it off`() {
        // tsgo 7.0.2, measured: `ns.A`, `ns.B` and `ns.v` all resolve from
        // another file — tsc's `setExportContextFlag` is switched off by an
        // export DECLARATION only, which a namespace body cannot hold.
        val result = generateDts(
            """
            declare namespace ns {
                export interface A { p: string; }
                interface B { q: A; }
                const v: number;
            }
            """
        )
        val expected = """
            /* xtsc: namespace ns - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external interface A {
                public var p: String
            }

            public external interface B {
                public var q: A
            }

            public external val v: Double
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `an ambient module with an export declaration exports only the export-modified members`() {
        // The one switch-off (tsgo: `B` is TS2459, "declares 'B' locally,
        // but it is not exported"); the `export { … }` itself is wiring.
        val result = generateDts(
            """
            declare module "widgets" {
                export interface Widget { id: number; }
                interface Hidden { id: number; }
                export { Widget as Gadget };
            }
            """
        )
        val expected = """
            /* xtsc: module "widgets" - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external interface Widget {
                public var id: Double
            }

            /* xtsc: skipped re-export { Widget as Gadget } - module wiring is a later rung */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a nested namespace is an object holding an interface a value a function and a further object`() {
        val result = generateDts(
            """
            declare namespace ts {
                namespace server {
                    interface Project { name: string; }
                    const defaultName: string;
                    function open(name: string): Project;
                    namespace protocol {
                        interface Request { seq: number; }
                    }
                }
            }
            export = ts;
            """
        )
        val expected = """
            /* xtsc: namespace ts - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external object server {
                public interface Project {
                    public var name: String
                }

                public val defaultName: String

                public fun open(name: String): Project

                public object protocol {
                    public interface Request {
                        public var seq: Double
                    }
                }
            }

            /* xtsc: skipped export = ts - module wiring is a later rung */
        """.trimIndent() + "\n"
        val gateExpected = """
            /* xtsc: namespace ts - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public object server {
                public interface Project {
                    public var name: String
                }

                public val defaultName: String = null!!

                public fun open(name: String): Project = null!!

                public object protocol {
                    public interface Request {
                        public var seq: Double
                    }
                }
            }

            /* xtsc: skipped export = ts - module wiring is a later rung */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val gate = result.compileCheckSource
        assert(rendered == expected)
        assert(gate == gateExpected)
    }

    @Test
    fun `a nested object holds an enum a class with a static an abstract class a callable interface overloads and an empty object`() {
        // Every declaration kind one indent in, the `external` keyword on
        // the object alone; a callable interface is an interface here (a
        // `typealias` cannot nest), its call signature the usual loud skip.
        val result = generateDts(
            """
            declare namespace ts {
                namespace server {
                    enum Mode { Fast, Slow }
                    class Session { mode: Mode; static open(): Session; }
                    abstract class Base { abstract go(): void; }
                    interface Callable { (x: number): string; }
                    function overloaded(x: string): string;
                    function overloaded(x: any): string;
                    namespace empty { }
                }
            }
            export = ts;
            """
        )
        val expected = """
            /* xtsc: namespace ts - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external object server {
                public sealed interface Mode {
                    public companion object {
                        public val Fast: Mode
                        public val Slow: Mode
                    }
                }

                public open class Session {
                    public var mode: Mode
                    public companion object {
                        public fun open(): Session
                    }
                }

                public abstract class Base {
                    public fun go(): Unit
                }

                public interface Callable {
                    /* xtsc: skipped call signature */
                }

                public fun overloaded(x: String): String

                public fun overloaded(x: Any?): String

                public object empty
            }

            /* xtsc: skipped export = ts - module wiring is a later rung */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a type alias inside a nested namespace is a loud skip and a use of it renders its body`() {
        val result = generateDts(
            """
            declare namespace ts {
                namespace server {
                    type NormalizedPath = string;
                    interface Project { path: NormalizedPath; }
                }
            }
            export = ts;
            """
        )
        val expected = """
            /* xtsc: namespace ts - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external object server {
                /* xtsc: skipped type alias NormalizedPath inside namespace server - Kotlin aliases are top-level only */

                public interface Project {
                    public var path: String
                }
            }

            /* xtsc: skipped export = ts - module wiring is a later rung */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `an export import alias inside a namespace is a loud marker naming its target whatever the target is`() {
        val result = generateDts(
            """
            declare namespace ts {
                interface Info { name: string; }
                namespace server {
                    namespace protocol {
                        export import Info = ts.Info;
                        export import Other = ts.Missing;
                    }
                }
            }
            export = ts;
            """
        )
        val expected = """
            /* xtsc: namespace ts - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external interface Info {
                public var name: String
            }

            public external object server {
                public object protocol {
                    /* xtsc: alias Info = ts.Info - re-exported name, wiring is a later rung */

                    /* xtsc: alias Other = ts.Missing - re-exported name, wiring is a later rung */
                }
            }

            /* xtsc: skipped export = ts - module wiring is a later rung */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `cross-scope references render by the shortest path in all three directions`() {
        // root -> nested (`server.Project`, `server.protocol.Request`),
        // nested -> root (bare `Node`), nested -> enclosing's nested sibling
        // (`protocol.Request` from `server` and from `server.typingsInstaller`),
        // and a bare name inside the same nested scope.
        val result = generateDts(
            """
            declare namespace ts {
                interface Node { kind: number; }
                interface Host { project: server.Project; request: server.protocol.Request; }
                namespace server {
                    interface Project { root: Node; request: protocol.Request; }
                    namespace protocol {
                        interface Request { node: Node; project: Project; }
                    }
                    namespace typingsInstaller {
                        interface Log { request: protocol.Request; }
                    }
                }
            }
            export = ts;
            """
        )
        val expected = """
            /* xtsc: namespace ts - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external interface Node {
                public var kind: Double
            }

            public external interface Host {
                public var project: server.Project
                public var request: server.protocol.Request
            }

            public external object server {
                public interface Project {
                    public var root: Node
                    public var request: protocol.Request
                }

                public object protocol {
                    public interface Request {
                        public var node: Node
                        public var project: Project
                    }
                }

                public object typingsInstaller {
                    public interface Log {
                        public var request: protocol.Request
                    }
                }
            }

            /* xtsc: skipped export = ts - module wiring is a later rung */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `the same type name in two namespaces survives in both and each reference picks its own`() {
        val result = generateDts(
            """
            declare namespace ts {
                interface Node { kind: number; }
                namespace a {
                    interface Node { left: Node; }
                    interface Holder { n: Node; }
                }
                namespace b {
                    interface Node { right: Node; }
                }
                interface Both { a: a.Node; b: b.Node; root: Node; }
            }
            export = ts;
            """
        )
        val expected = """
            /* xtsc: namespace ts - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external interface Node {
                public var kind: Double
            }

            public external object a {
                public interface Node {
                    public var left: Node
                }

                public interface Holder {
                    public var n: Node
                }
            }

            public external object b {
                public interface Node {
                    public var right: Node
                }
            }

            public external interface Both {
                public var a: a.Node
                public var b: b.Node
                public var root: Node
            }

            /* xtsc: skipped export = ts - module wiring is a later rung */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a root type shadowed inside a namespace declaring the same name has no spelling and is a loud marker`() {
        // `ts.Node` written inside `a`, which declares its own `Node`: Kotlin
        // resolves the bare name innermost-first and the generated file has
        // no package to qualify by. The bare `Node` beside it is `a.Node`.
        val result = generateDts(
            """
            declare namespace ts {
                interface Node { kind: number; }
                namespace a {
                    interface Node { left: Node; }
                    interface Holder { root: ts.Node; }
                }
            }
            export = ts;
            """
        )
        val expected = """
            /* xtsc: namespace ts - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external interface Node {
                public var kind: Double
            }

            public external object a {
                public interface Node {
                    public var left: Node
                }

                public interface Holder {
                    public var root: Any? /* xtsc: unmapped ts.Node - shadowed inside a, no Kotlin spelling reaches it */
                }
            }

            /* xtsc: skipped export = ts - module wiring is a later rung */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `heritage to a nested type renders the supertype by its path`() {
        val result = generateDts(
            """
            declare namespace ts {
                namespace server {
                    interface Base { id: number; }
                    class ProjectService { host: string; }
                }
                interface Derived extends server.Base { name: string; }
                class Service extends server.ProjectService { open(): void; }
            }
            export = ts;
            """
        )
        val expected = """
            /* xtsc: namespace ts - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external object server {
                public interface Base {
                    public var id: Double
                }

                public open class ProjectService {
                    public var host: String
                }
            }

            public external interface Derived : server.Base {
                public var name: String
            }

            public open external class Service : server.ProjectService {
                public fun open(): Unit
            }

            /* xtsc: skipped export = ts - module wiring is a later rung */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a dotted root namespace flattens its first segment and nests the rest`() {
        val result = generateDts(
            """
            declare namespace A.B {
                interface X { p: string; }
            }
            declare namespace A.C.D {
                interface Y { x: B.X; }
            }
            """
        )
        val expected = """
            /* xtsc: namespace A - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external object B {
                public interface X {
                    public var p: String
                }
            }

            /* xtsc: namespace A - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external object C {
                public object D {
                    public interface Y {
                        public var x: B.X
                    }
                }
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a non-ambient namespace is a loud skip and an ambient one beside ordinary exports flattens`() {
        val result = generate(
            """
            export namespace Runtime {
                export interface A { p: string; }
                export const x: number = 1;
            }
            export declare namespace Ambient {
                interface B { q: string; }
            }
            export interface Top { a: Runtime.A; b: Ambient.B; }
            """
        )
        val expected = """
            /* xtsc: skipped namespace Runtime has a runtime body - only an ambient namespace is generated */

            /* xtsc: namespace Ambient - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external interface B {
                public var q: String
            }

            public external interface Top {
                public var a: Any? /* xtsc: unmapped A */
                public var b: B
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a declaration merged in one scope is a loud skip naming the merge and a namespace merged onto an enum too`() {
        val result = generateDts(
            """
            declare namespace ts {
                interface Node { kind: number; }
                interface Node { parent: Node; }
                enum Kind { A }
                namespace Kind { function parse(s: string): Kind; }
            }
            export = ts;
            """
        )
        val expected = """
            /* xtsc: namespace ts - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external interface Node {
                public var kind: Double
            }

            /* xtsc: skipped Node declared again in the same scope - TypeScript merges the declarations, one Kotlin scope cannot hold both */

            public sealed external interface Kind {
                public companion object {
                    public val A: Kind
                }
            }

            /* xtsc: skipped namespace Kind declared again in the same scope - one Kotlin scope cannot hold both */

            /* xtsc: skipped export = ts - module wiring is a later rung */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a var narrowed down a chain renders the type its base actually renders`() {
        // `typescript.d.ts`'s request chain: the second link compared its
        // redeclaration with the first link's DECLARED `FileRequestArgs`,
        // which the first link does not render (it renders the inherited
        // `Any?`) — 29 compile errors before the effective inherited type.
        val result = generateDts(
            """
            declare namespace ts {
                interface Request { arguments?: any; }
                interface FileRequest extends Request { arguments: FileRequestArgs; }
                interface FileLocationRequest extends FileRequest { arguments: FileLocationRequestArgs; }
                interface FileRequestArgs { file: string; }
                interface FileLocationRequestArgs extends FileRequestArgs { line: number; }
            }
            export = ts;
            """
        )
        val expected = """
            /* xtsc: namespace ts - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external interface Request {
                public var arguments: Any?
            }

            public external interface FileRequest : Request {
                /* xtsc: narrowed to FileRequestArgs in TypeScript - rendered as the inherited Any? */
                public override var arguments: Any?
            }

            public external interface FileLocationRequest : FileRequest {
                /* xtsc: narrowed to FileLocationRequestArgs in TypeScript - rendered as the inherited Any? */
                public override var arguments: Any?
            }

            public external interface FileRequestArgs {
                public var file: String
            }

            public external interface FileLocationRequestArgs : FileRequestArgs {
                public var line: Double
            }

            /* xtsc: skipped export = ts - module wiring is a later rung */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a base whose member clashes with one already inherited is dropped with a loud heritage marker`() {
        // Two bases, one property key, two Kotlin types — no override
        // reconciles them (a `var` override must repeat the type exactly),
        // so the later base is dropped whole, on a class and on an interface.
        val result = generateDts(
            """
            declare namespace ts {
                interface ModuleResolutionHost { useCaseSensitiveFileNames?: boolean | (() => boolean); readFile(f: string): string; }
                interface LanguageServiceHost { useCaseSensitiveFileNames?(): boolean; getScriptVersion(f: string): string; }
                class Project implements LanguageServiceHost, ModuleResolutionHost {
                    useCaseSensitiveFileNames(): boolean;
                    getScriptVersion(f: string): string;
                    readFile(f: string): string;
                }
                interface Both extends LanguageServiceHost, ModuleResolutionHost { }
            }
            export = ts;
            """
        )
        val expected = """
            /* xtsc: namespace ts - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external interface ModuleResolutionHost {
                public var useCaseSensitiveFileNames: Any? /* xtsc: unmapped boolean | (() => boolean) */
                public fun readFile(f: String): String
            }

            public external interface LanguageServiceHost {
                public var useCaseSensitiveFileNames: (() -> Boolean)?
                public fun getScriptVersion(f: String): String
            }

            public open external class Project : LanguageServiceHost {
                public fun useCaseSensitiveFileNames(): Boolean
                public override fun getScriptVersion(f: String): String
                public fun readFile(f: String): String
                /* xtsc: skipped heritage clause implements ModuleResolutionHost - its useCaseSensitiveFileNames clashes with the one inherited from LanguageServiceHost */
                /* xtsc: property useCaseSensitiveFileNames inherited from LanguageServiceHost - implemented by the class's own useCaseSensitiveFileNames under another signature in TypeScript */
                public override var useCaseSensitiveFileNames: (() -> Boolean)?
            }

            public external interface Both : LanguageServiceHost {
                /* xtsc: skipped heritage clause extends ModuleResolutionHost - its useCaseSensitiveFileNames clashes with the one inherited from LanguageServiceHost */
            }

            /* xtsc: skipped export = ts - module wiring is a later rung */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a readonly redeclaration keeps its type only where Kotlin sees a subtype`() {
        // `Leaf.parent: Identifier` over `Node.parent: Node` is a subtype
        // (Identifier extends Node) and keeps its type; an enum-member
        // literal (marked `Any?`), a type parameter and a nullable widening
        // are not, and render the inherited type loudly; a `var` over a `val`
        // of the same type is a legal override.
        val result = generateDts(
            """
            declare namespace ts {
                enum SyntaxKind { Identifier, Token }
                interface Node { readonly kind: SyntaxKind; readonly parent: Node; }
                interface Identifier extends Node { readonly kind: SyntaxKind.Identifier; }
                interface Token<TKind extends SyntaxKind> extends Node { readonly kind: TKind; }
                interface Declaration extends Node { kind: SyntaxKind; }
                interface Leaf extends Node { readonly parent: Identifier; }
                interface Wide extends Node { readonly parent?: Node; }
            }
            export = ts;
            """
        )
        val expected = """
            /* xtsc: namespace ts - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public sealed external interface SyntaxKind {
                public companion object {
                    public val Identifier: SyntaxKind
                    public val Token: SyntaxKind
                }
            }

            public external interface Node {
                public val kind: SyntaxKind
                public val parent: Node
            }

            public external interface Identifier : Node {
                /* xtsc: narrowed to Any? in TypeScript - rendered as the inherited SyntaxKind */
                public override val kind: SyntaxKind
            }

            public external interface Token<TKind> : Node {
                /* xtsc: constraint on TKind: SyntaxKind not carried */
                /* xtsc: narrowed to TKind in TypeScript - rendered as the inherited SyntaxKind */
                public override val kind: SyntaxKind
            }

            public external interface Declaration : Node {
                public override var kind: SyntaxKind
            }

            public external interface Leaf : Node {
                public override val parent: Identifier
            }

            public external interface Wide : Node {
                /* xtsc: narrowed to Node? in TypeScript - rendered as the inherited Node */
                public override val parent: Node
            }

            /* xtsc: skipped export = ts - module wiring is a later rung */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    // ---- (EXT.14) the lens is the resolver inside a namespace body --------
    //
    // (CHK.76) made the checker's name resolution position-derived, and the
    // (EXT.13) namespace ladder — a syntactic resolver consulted BEFORE the
    // lens for a qualified name or any name inside a namespace body — is
    // retired as the resolver: every (EXT.13) pin above now resolves through
    // the lens alone (measured: `typescript.d.ts` byte-identical). What
    // survives is a written-name FALLBACK consulted after every lens leg,
    // for the two shapes the lens still cannot answer; the pins below are
    // those shapes, each read from the generator's own output, and the
    // decision pin — a shape the per-file ladder could not answer at all.

    @Test
    fun `a qualified cross-file reference into a merged namespace resolves through the lens where the per-file ladder could not`() {
        // `ts.server.A` written in b.d.ts names a.d.ts's declaration — the
        // annotation is the LENS's answer (`resolveQualifiedName` reaches
        // the merged root through the per-file consult); the heritage base
        // is the FALLBACK's, program-wide (the lens's heritage resolver
        // still stops at a nested ambient namespace's implicit export — the
        // checker's half of (EXT.14)'s residue). The (EXT.13) ladder was
        // per-file and answered null for both.
        val result = generateKotlinExternals(
            files = listOf(
                SourceFileEntry(
                    "/p/a.d.ts",
                    """
                    declare namespace ts {
                        namespace server {
                            interface A { a: number; }
                        }
                        interface R { r: number; }
                    }
                    """.trimIndent(),
                ),
                SourceFileEntry(
                    "/p/b.d.ts",
                    """
                    declare namespace other {
                        interface Use { a: ts.server.A; r: ts.R; }
                        interface Der extends ts.server.A { }
                    }
                    """.trimIndent(),
                ),
            ),
        )
        val expected = """
            /* xtsc: namespace ts - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external object server {
                public interface A {
                    public var a: Double
                }
            }

            public external interface R {
                public var r: Double
            }

            /* xtsc: namespace other - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external interface Use {
                public var a: server.A
                public var r: R
            }

            public external interface Der : server.A {
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `inside a declare module body a bare name resolves through the written-name fallback`() {
        // The checker's position-derived resolver skips a string-named module
        // on purpose (its block may be an augmentation), so the lens types
        // `Widget` beside its own declaration as `any` and answers no heritage
        // base there — the residue (EXT.14) keeps the fallback for. `Cb`
        // resolves through the lens's walk-scoped chain even here.
        val result = generateDts(
            """
            declare module "widgets" {
                export interface Widget { id: number; }
                export interface Panel { w: Widget; ws: Widget[]; cb: Cb; }
                export type Cb = () => void;
                export class Base { x: number; }
                export class Derived extends Base implements Widget { id: number; }
            }
            """
        )
        val expected = """
            /* xtsc: module "widgets" - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external interface Widget {
                public var id: Double
            }

            public external interface Panel {
                public var w: Widget
                public var ws: Array<Widget>
                public var cb: Cb
            }

            public typealias Cb = () -> Unit

            public open external class Base {
                public var x: Double
            }

            public open external class Derived : Base, Widget {
                public override var id: Double
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `a qualified heritage base whose head is a type-only namespace in an enclosing scope resolves through the fallback`() {
        // `typescript.d.ts:2679` — `interface InstallTypingHost extends
        // JsTyping.TypingResolutionHost` inside `ts.server.typingsInstaller`:
        // the one hunk the lens alone lost. `JsTyping` declares only
        // interfaces (a type-only namespace), and the lens's heritage resolver
        // asks `Type | Value` of a dotted base's head. The annotation beside
        // it is the lens's own answer.
        val result = generateDts(
            """
            declare namespace ts {
                namespace JsTyping {
                    interface Host { r(): string; }
                }
                namespace server {
                    namespace typingsInstaller {
                        interface InstallTypingHost extends JsTyping.Host { q: number; }
                        interface Other { h: JsTyping.Host; }
                    }
                }
            }
            export = ts;
            """
        )
        val expected = """
            /* xtsc: namespace ts - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public external object JsTyping {
                public interface Host {
                    public fun r(): String
                }
            }

            public external object server {
                public object typingsInstaller {
                    public interface InstallTypingHost : JsTyping.Host {
                        public var q: Double
                    }

                    public interface Other {
                        public var h: JsTyping.Host
                    }
                }
            }

            /* xtsc: skipped export = ts - module wiring is a later rung */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `a qualified reference to an alias whose resolved body has no Kotlin spelling resolves through the fallback`() {
        // `typeReferenceSymbol` refuses a qualified name by contract, so the
        // (EXT.10) name rule and the nested-alias inlining cannot be reached
        // through the lens for `ts.Cb` / `server.Gen<number>`; the fallback
        // reaches both. The inlined body's `Node` is resolved at the BODY's
        // position (`server.Node`) and spelled from the use's scope: bare
        // inside `server`, `server.Node` from the root. `server.NP` maps
        // through the lens (its resolved body is `string`).
        val result = generateDts(
            """
            declare namespace ts {
                type Cb = () => void;
                interface Node { k: number; }
                namespace server {
                    type Gen<T> = (e: T, n: Node) => void;
                    type NP = string;
                    interface Node { nested: string; }
                    interface Holder { cb: ts.Cb; g: Gen<string>; }
                }
                interface Root { g: server.Gen<number>; np: server.NP; }
            }
            export = ts;
            """
        )
        val expected = """
            /* xtsc: namespace ts - members rendered at top level; @JsModule/@JsQualifier wiring is a later rung */

            public typealias Cb = () -> Unit

            public external interface Node {
                public var k: Double
            }

            public external object server {
                /* xtsc: skipped type alias Gen inside namespace server - Kotlin aliases are top-level only */

                /* xtsc: skipped type alias NP inside namespace server - Kotlin aliases are top-level only */

                public interface Node {
                    public var nested: String
                }

                public interface Holder {
                    public var cb: Cb
                    public var g: (String, Node) -> Unit
                }
            }

            public external interface Root {
                public var g: (Double, server.Node) -> Unit
                public var np: String
            }

            /* xtsc: skipped export = ts - module wiring is a later rung */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    // --- (EXT.15) index signatures and parameter properties -------------------

    @Test
    fun `string and number index signatures are two operator pairs and readonly drops the set`() {
        val result = generate(
            """
            export interface Table<T> {
                [key: string]: T;
                [index: number]: T;
            }
            export interface Frozen {
                readonly [key: string]: number;
            }
            export interface Loose {
                [key: string]: string | number;
            }
            """
        )
        val expected = """
            public external interface Table<T> {
                public operator fun get(key: String): T?
                public operator fun set(key: String, value: T): Unit
                public operator fun get(index: Double): T?
                public operator fun set(index: Double, value: T): Unit
            }

            public external interface Frozen {
                public operator fun get(key: String): Double?
            }

            public external interface Loose {
                public operator fun get(key: String): Any? /* xtsc: unmapped string | number */
                public operator fun set(key: String, value: Any? /* xtsc: unmapped string | number */): Unit
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `an index signature keyed by anything but string or number is a loud skip naming the key`() {
        val result = generate(
            """
            export interface Odd {
                [s: symbol]: string;
                own: string;
            }
            """
        )
        val expected = """
            public external interface Odd {
                /* xtsc: skipped index signature keyed by symbol - only a string or number key has a Kotlin get/set pair */
                public var own: String
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a class index signature grows gate bodies and a static one lands in the companion`() {
        val result = generate(
            """
            export class Registry {
                [key: string]: any;
                static [key: string]: string;
                name: string;
            }
            """
        )
        val expected = """
            public open external class Registry {
                public operator fun get(key: String): Any?
                public operator fun set(key: String, value: Any?): Unit
                public var name: String
                public companion object {
                    public operator fun get(key: String): String?
                    public operator fun set(key: String, value: String): Unit
                }
            }
        """.trimIndent() + "\n"
        val expectedGate = """
            public abstract class Registry {
                public operator fun get(key: String): Any? = null!!
                public operator fun set(key: String, value: Any?): Unit = null!!
                public var name: String = null!!
                public companion object {
                    public operator fun get(key: String): String? = null!!
                    public operator fun set(key: String, value: String): Unit = null!!
                }
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val gateVariant = result.compileCheckSource
        assert(rendered == expected)
        assert(gateVariant == expectedGate)
    }

    @Test
    fun `a redeclared index signature renders override and the class base renders open`() {
        val result = generate(
            """
            export interface Base { [key: string]: string; }
            export interface Sub extends Base {
                [key: string]: string;
                extra: string;
            }
            export class B { [key: string]: string; }
            export class D extends B { [key: string]: string; }
            """
        )
        val expected = """
            public external interface Base {
                public operator fun get(key: String): String?
                public operator fun set(key: String, value: String): Unit
            }

            public external interface Sub : Base {
                public override operator fun get(key: String): String?
                public override operator fun set(key: String, value: String): Unit
                public var extra: String
            }

            public open external class B {
                public open operator fun get(key: String): String?
                public open operator fun set(key: String, value: String): Unit
            }

            public open external class D : B {
                public override operator fun get(key: String): String?
                public override operator fun set(key: String, value: String): Unit
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `readonly parameter properties are vals and an unmappable one carries its marker`() {
        val result = generate(
            """
            export class Config {
                constructor(readonly name: string, public readonly tags: string[], public raw: string | number, public level?: number) {}
            }
            """
        )
        val expected = """
            public open external class Config(name: String, tags: Array<String>, raw: Any? /* xtsc: unmapped string | number */, level: Double?) {
                public val name: String
                public val tags: Array<String>
                public var raw: Any? /* xtsc: unmapped string | number */
                public var level: Double?
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `a parameter property is inherited by a constructor-less subclass and overridable by a redeclaring one`() {
        // The (EXT.8) inherited-constructor rule still passes the parameters
        // through by name; the parameter property itself is an ordinary
        // member to the override machinery, so `Flat`'s redeclaration is
        // `override` and `Point`'s member `open`. Implementations, not
        // `declare` classes: a parameter property is only allowed in a
        // constructor IMPLEMENTATION (TS2369, tsgo agrees), which is why no
        // `.d.ts` in the fixture ladder carries one.
        val result = generate(
            """
            export class Point {
                constructor(public x: number, public y: number) {}
            }
            export class Named extends Point { label: string = ""; }
            export class Flat extends Point {
                constructor(x: number) { super(x, 0); }
                x: number = 0;
            }
            """
        )
        val expected = """
            public open external class Point(x: Double, y: Double) {
                public open var x: Double
                public var y: Double
            }

            public open external class Named(x: Double, y: Double) : Point {
                public var label: String
            }

            public open external class Flat(x: Double) : Point {
                public override var x: Double
            }
        """.trimIndent() + "\n"
        val expectedGate = """
            public abstract class Point(x: Double, y: Double) {
                public open var x: Double = null!!
                public var y: Double = null!!
            }

            public abstract class Named(x: Double, y: Double) : Point(x, y) {
                public var label: String = null!!
            }

            public abstract class Flat(x: Double) : Point(null!!, null!!) {
                public override var x: Double = null!!
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val gateVariant = result.compileCheckSource
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(gateVariant == expectedGate)
        assert(errorCodes.isEmpty())
    }

}
