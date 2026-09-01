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
                /* xtsc: skipped heritage clause */
                /* xtsc: skipped IndexSignature */
                public var own: String
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

}
