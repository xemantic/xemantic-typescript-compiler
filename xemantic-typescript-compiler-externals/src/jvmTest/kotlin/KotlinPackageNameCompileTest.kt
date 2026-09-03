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
 * (EXT.21) The MEASUREMENT behind the package-naming scheme: what Kotlin
 * accepts in a `package` declaration, and — the half that decides whether
 * per-module generation can spell a CROSS-module reference at all — whether
 * a name in such a package can be REFERENCED by its qualified spelling.
 *
 * The generator's input is an npm module SPECIFIER (`rxjs`, `node:net`,
 * `fs/promises`, `@types/node`) and its output needs one Kotlin package per
 * declaring module, because two modules may declare the same name ((EXT.20)
 * measured 112 such names in `@types/node`, `Socket` in both `dgram` and
 * `net`). A specifier is not a Kotlin package name, so the mapping has to be
 * derived — and every candidate rule is a guess until a compiler answers,
 * which is what this class is for.
 *
 * ## What it measured (2026-09-03), and what it REFUTED
 *
 * The queued proposal said "any segment that is not a Kotlin identifier
 * backticked". **A backtick rescues a hyphen, a hard keyword and a leading
 * digit, and rescues NOTHING else**: `@` and `:` are refused inside one with
 * `Name contains illegal characters`, the same rule CLAUDE.md records for
 * Kotlin/Native test names, here on a package. So an illegal character has
 * to be REMOVED by the mapping, never escaped, and a specifier carrying one
 * the mapping does not know must be refused loudly rather than emitted.
 *
 * Every accepted package name measured here is also spellable in a QUALIFIED
 * reference, backticked segments included — which is what makes a
 * cross-module reference (`node.net.Socket` named from the `dgram`
 * generation) expressible at all.
 */
class KotlinPackageNameCompileTest {

    /** A package declaration plus one declaration, as the generator would emit it. */
    private fun pkg(name: String): CompileCheck = compileCheck(
        """
        package $name

        public interface Foo {
            public val v: String
        }
        """.trimIndent()
    )

    /** The same, plus a FULLY QUALIFIED self-reference — the cross-module spelling. */
    private fun pkgQualified(name: String): CompileCheck = compileCheck(
        """
        package $name

        public interface Foo {
            public val v: String
        }

        public interface Bar {
            public val foo: $name.Foo
        }
        """.trimIndent()
    )

    @Test
    fun `the measurement - which package names Kotlin accepts`() {
        val cases = listOf(
            "rxjs",
            "typescript",
            "node.net",
            "types.node",
            "fs.promises",
            "rxjs.operators",
            "smol_toml",
            "smol-toml",
            "`smol-toml`",
            "node.`fs-extra`",
            "fun",
            "`fun`",
            "is",
            "`is`",
            "object",
            "`object`",
            "3d",
            "`3d`",
            "`@types`",
            "`node:net`",
        )
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<Pair<String, String>>()
        for (case in cases) {
            val check = pkg(case)
            if (check.successful) accepted += case
            else rejected += case to check.errors.first()
        }
        println("PACKAGE NAMES ACCEPTED (" + accepted.size + "): " + accepted)
        println("PACKAGE NAMES REJECTED (" + rejected.size + "):")
        for ((case, error) in rejected) println("  " + case + " -> " + error)
        assert(rejected.isNotEmpty())
    }

    @Test
    fun `the measurement - which characters survive a backticked package segment`() {
        val characters = listOf(
            '-' to "hyphen", '_' to "underscore", '.' to "dot", '~' to "tilde",
            '@' to "at", ':' to "colon", '/' to "slash", '+' to "plus",
            ' ' to "space",
        )
        val survives = mutableListOf<String>()
        val refused = mutableListOf<Pair<String, String>>()
        for ((character, name) in characters) {
            val check = pkg("`a" + character + "b`")
            if (check.successful) survives += name
            else refused += name to check.errors.first()
        }
        println("SURVIVES A BACKTICK (" + survives.size + "): " + survives)
        println("REFUSED EVEN BACKTICKED (" + refused.size + "):")
        for ((name, error) in refused) println("  " + name + " -> " + error)
        assert(refused.isNotEmpty())
    }

    @Test
    fun `the measurement - which package names can be spelled in a qualified reference`() {
        val cases = listOf(
            "node.net",
            "smol_toml",
            "`smol-toml`",
            "node.`fs-extra`",
            "`fun`",
            "`is`",
            "`3d`",
        )
        val referencable = mutableListOf<String>()
        val notReferencable = mutableListOf<Pair<String, String>>()
        for (case in cases) {
            if (!pkg(case).successful) {
                notReferencable += case to "the package declaration itself is refused"
                continue
            }
            val check = pkgQualified(case)
            if (check.successful) referencable += case
            else notReferencable += case to check.errors.first()
        }
        println("QUALIFIED-REFERENCABLE (" + referencable.size + "): " + referencable)
        println("NOT QUALIFIED-REFERENCABLE (" + notReferencable.size + "):")
        for ((case, error) in notReferencable) println("  " + case + " -> " + error)
    }

    @Test
    fun `the measurement - Kotlin JS agrees with the metadata compiler on package names`() {
        val stdlib = JsStdlib.locate() ?: return
        val cases = listOf("rxjs", "node.net", "`smol-toml`", "`fun`")
        val disagreements = mutableListOf<String>()
        for (case in cases) {
            val js = jsCompileCheck(
                """
                @file:JsModule("m")

                package $case

                public external interface Foo {
                    public val v: String
                }
                """.trimIndent(),
                stdlib,
            )
            val metadata = pkg(case).successful
            if (js.successful != metadata) {
                disagreements += case + ": js=" + js.successful + " metadata=" + metadata + " " + js.errors
            }
        }
        println("JS-vs-METADATA DISAGREEMENTS (" + disagreements.size + "): " + disagreements)
        assert(disagreements.isEmpty())
    }

    @Test
    fun `a plain lowercase specifier is a package name unchanged`() {
        assert(pkg("rxjs").successful)
        assert(pkgQualified("rxjs").successful)
    }

    @Test
    fun `a subpath specifier maps to dotted segments`() {
        assert(pkg("fs.promises").successful)
        assert(pkgQualified("fs.promises").successful)
    }

    @Test
    fun `a hyphenated segment needs a backtick and then is referencable`() {
        assert(!pkg("smol-toml").successful)
        assert(pkg("`smol-toml`").successful)
        assert(pkgQualified("`smol-toml`").successful)
    }

    @Test
    fun `a hard keyword segment needs a backtick and then is referencable`() {
        assert(!pkg("fun").successful)
        assert(pkg("`fun`").successful)
        assert(pkgQualified("`fun`").successful)
    }

    @Test
    fun `a backtick does not rescue an at sign or a colon - they must be mapped away`() {
        assert(!pkg("`@types`").successful)
        assert(!pkg("`node:net`").successful)
    }

}
