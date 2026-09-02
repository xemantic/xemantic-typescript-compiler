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

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.75) tsc's `checkAmbientInitializer` (grammarchecks.go), mirrored exactly.
 *
 * The rule, as read from `typescript-go-repo/internal/checker/grammarchecks.go`
 * (tag typescript/v7.0.2): for a variable declaration or a property declaration
 * carrying `NodeFlagsAmbient` and an initializer —
 *
 *  - if the declaration is const-like (`const`/`using`/`await using`) or carries a
 *    `readonly` modifier AND has NO type annotation, the initializer must be a
 *    string/numeric literal, a no-substitution template, `-<numeric>`, `true`/`false`,
 *    a bigint or `-<bigint>`, or a simple enum-member reference (`E.A`, `E["A"]` with
 *    an entity-name receiver and a string/numeric literal argument, judged by the
 *    expression's TYPE being enum-like) — else **TS1254**, the CONST wording, even
 *    for a readonly PROPERTY;
 *  - every other initializer (annotated const/readonly, `let`, `var`, a non-readonly
 *    property) is **TS1039**.
 *
 * Before this round the property arm had no exemption at all, so tsc 6.0.3's own
 * `typescript.d.ts:2610` (`protected readonly latestDistTag = "latest";` inside an
 * abstract class three namespaces deep) reported TS1039; and the variable arm's
 * literal set lacked `true`/`false`, the template and the enum reference. Every row
 * here was read against `tools/tsgo-7.0.2/lib/tsc --noEmit` on a scratch project;
 * a `.ts` `declare class` and a `.d.ts` class behave identically in both compilers.
 *
 * Positive rows assert the WHOLE list is empty (tsgo is silent there); negative rows
 * assert the code and the initializer's `start`.
 */
class AmbientReadonlyInitializerTest {

    private val prelude = """
        declare enum E { A = 0, B = "b" }
        declare const enum CE { X = 1 }
        declare function foo(): string;
    """.trimIndent() + "\n"

    /**
     * The `start` [diagnose] reports for [needle]'s LAST occurrence in the compiled
     * text — positions are relative to the source proper (the harness strips its
     * `// @strict` header), and the prelude itself spells `foo()` once.
     */
    private fun startOf(body: String, needle: String): Int =
        (prelude + body.trimIndent()).lastIndexOf(needle)

    private fun member(init: String, modifiers: String = "readonly", annotation: String = ""): String =
        """
        declare class C {
            $modifiers p$annotation = $init;
        }
        """

    private fun silent(body: String) {
        val d = diagnose(prelude + body.trimIndent())
        assert(d.isEmpty())
    }

    private fun refused(body: String, code: Int, needle: String) {
        val d = diagnose(prelude + body.trimIndent())
        val start = startOf(body, needle)
        assert(d.size == 1)
        assert(d.single().code == code)
        assert(d.single().start == start)
    }

    // ---- readonly property, no annotation: the legal literal set ----

    @Test
    fun `ambient readonly property with a string literal initializer is silent`() = silent(member("\"s\""))

    @Test
    fun `ambient readonly property with a numeric literal initializer is silent`() = silent(member("1"))

    @Test
    fun `ambient readonly property with a negated numeric literal is silent`() = silent(member("-1"))

    @Test
    fun `ambient readonly property with true is silent`() = silent(member("true"))

    @Test
    fun `ambient readonly property with false is silent`() = silent(member("false"))

    @Test
    fun `ambient readonly property with a no-substitution template is silent`() = silent(member("`tpl`"))

    @Test
    fun `ambient readonly property with a numeric enum member reference is silent`() = silent(member("E.A"))

    @Test
    fun `ambient readonly property with a string enum member reference is silent`() = silent(member("E.B"))

    @Test
    fun `ambient readonly property with a const enum member reference is silent`() = silent(member("CE.X"))

    @Test
    fun `ambient readonly property with an enum element access by string is silent`() = silent(member("E[\"A\"]"))

    @Test
    fun `ambient readonly property with a bigint literal is silent`() = silent(member("1n"))

    @Test
    fun `ambient readonly property with a negated bigint literal is silent`() = silent(member("-1n"))

    @Test
    fun `ambient static readonly property with a literal is silent`() = silent(member("\"s\"", modifiers = "static readonly"))

    @Test
    fun `ambient protected readonly property with a literal is silent`() = silent(member("\"latest\"", modifiers = "protected readonly"))

    @Test
    fun `readonly literal inside a declare namespace class is silent`() = silent(
        """
        declare namespace NS {
            class C2 {
                readonly s = "s";
                readonly e = E.A;
            }
        }
        """
    )

    @Test
    fun `the typescript d ts shape - protected readonly literal in an abstract class three namespaces deep`() {
        val d = diagnose(
            """
            declare namespace ts {
                namespace server {
                    namespace typingsInstaller {
                        abstract class TypingsInstaller {
                            protected readonly latestDistTag = "latest";
                        }
                    }
                }
            }
            """.trimIndent(),
            fileName = "typescript.d.ts",
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a bare enum member reference inside the declare namespace declaring the enum is silent`() = silent(
        """
        declare namespace NS2 {
            enum LE { Q = 0, R = "r" }
            class C3 {
                readonly e = LE.Q;
                readonly q = NS2.LE.R;
            }
            const lc = LE.Q;
        }
        declare const c13 = NS2.LE.Q;
        """
    )

    // ---- readonly property, no annotation: refused with the CONST wording TS1254 ----

    @Test
    fun `ambient readonly property with a call initializer is TS1254`() =
        refused(member("foo()"), 1254, "foo()")

    @Test
    fun `ambient readonly property with null is TS1254`() =
        refused(member("null"), 1254, "null;")

    @Test
    fun `ambient readonly property with a substituting template is TS1254`() =
        refused(member("`a\${1}b`"), 1254, "`a\${1}b`")

    @Test
    fun `ambient readonly property with a parenthesized literal is TS1254`() =
        refused(member("(1)"), 1254, "(1)")

    @Test
    fun `ambient readonly property with a unary plus literal is TS1254`() =
        refused(member("+1"), 1254, "+1")

    @Test
    fun `ambient readonly property with an identifier initializer is TS1254`() =
        refused(member("foo"), 1254, "foo;")

    @Test
    fun `ambient readonly property with a numeric enum element access is TS1254 - a reverse mapping is a string`() =
        refused(member("E[0]"), 1254, "E[0]")

    @Test
    fun `ambient readonly property with a negated string literal is TS1254`() =
        refused(member("-\"s\""), 1254, "-\"s\"")

    // ---- TS1039: annotated readonly, non-readonly, declare member of a plain class ----

    @Test
    fun `ambient readonly property with a type annotation and a literal is TS1039`() =
        refused(member("\"s\"", annotation = ": string"), 1039, "\"s\"")

    @Test
    fun `ambient non-readonly property with a literal is TS1039`() =
        refused(member("\"s\"", modifiers = "public"), 1039, "\"s\"")

    @Test
    fun `annotated readonly literal in a declare namespace abstract class is TS1039`() =
        refused(
            """
            declare namespace NS {
                namespace inner {
                    abstract class TI {
                        readonly x: number = 1;
                    }
                }
            }
            """,
            1039, "1;",
        )

    @Test
    fun `a declare-modified member of a plain class with a literal is TS1039`() =
        refused(
            """
            class Cat {
                declare p = "uh";
            }
            """,
            1039, "\"uh\"",
        )

    @Test
    fun `negative control - a plain class readonly call initializer is not ambient and is silent`() = silent(
        """
        class Plain {
            readonly s = "s";
            p = foo();
        }
        """
    )

    // ---- declare const / let / var ----

    @Test
    fun `declare const with a numeric literal is silent`() = silent("declare const c = 1;")

    @Test
    fun `declare const with a string literal is silent`() = silent("declare const c = \"s\";")

    @Test
    fun `declare const with a negated numeric literal is silent`() = silent("declare const c = -1;")

    @Test
    fun `declare const with true is silent`() = silent("declare const c = true;")

    @Test
    fun `declare const with a no-substitution template is silent`() = silent("declare const c = `t`;")

    @Test
    fun `declare const with an enum member reference is silent`() = silent("declare const c = E.A;")

    @Test
    fun `declare const with an enum element access by string is silent`() = silent("declare const c = E[\"A\"];")

    @Test
    fun `declare const with a bigint literal is silent`() = silent("declare const c = 1n;")

    @Test
    fun `declare const inside a declare namespace with a literal is silent`() = silent(
        """
        declare namespace M {
            const c6 = 0;
        }
        """
    )

    @Test
    fun `declare const with a call initializer is TS1254`() =
        refused("declare const c = foo();", 1254, "foo()")

    @Test
    fun `declare const with null is TS1254`() =
        refused("declare const c = null;", 1254, "null")

    @Test
    fun `declare const with a substituting template is TS1254`() =
        refused("declare const c = `a\${1}`;", 1254, "`a\${1}`")

    @Test
    fun `declare const with the enum object itself is TS1254`() =
        refused("declare const c = E;", 1254, "E;")

    @Test
    fun `declare const with a type annotation and a literal is TS1039`() =
        refused("declare const c: number = 1;", 1039, "1;")

    @Test
    fun `declare let with a literal is TS1039`() =
        refused("declare let l = 1;", 1039, "1;")

    @Test
    fun `declare var with a literal is TS1039`() =
        refused("declare var v = 1;", 1039, "1;")

    @Test
    fun `declare const inside a declare namespace with a type annotation is TS1039`() =
        refused(
            """
            declare namespace M {
                const c7: number = 7;
            }
            """,
            1039, "7;",
        )

    @Test
    fun `the TS1254 message is the const wording for a readonly property too`() {
        val d = diagnose(prelude + member("foo()").trimIndent())
        d should {
            have(any { it.code == 1254 && it.message.startsWith("A 'const' initializer in an ambient context") })
        }
    }
}
