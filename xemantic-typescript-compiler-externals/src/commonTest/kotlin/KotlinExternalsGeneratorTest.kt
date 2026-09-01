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
    fun `generic interface is refused with a marker`() {
        val result = generate(
            """
            export interface Box<T> { value: T; }
            export interface Plain { p: string; }
            """
        )
        val expected = """
            /* xtsc: skipped generic interface Box */

            public external interface Plain {
                public var p: String
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
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
