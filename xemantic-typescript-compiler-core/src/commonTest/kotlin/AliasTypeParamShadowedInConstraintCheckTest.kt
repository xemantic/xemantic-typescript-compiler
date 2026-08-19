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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.16) round 943 — A DECLARATION'S OWN TYPE PARAMETER WAS INVISIBLE TO THE TS2344
 * CONSTRAINT WALKER, SO A SAME-NAMED FILE-LEVEL TYPE WON.
 *
 * `checkConstraintsInStatements` pushed a declaration's own type parameters into scope for
 * a `FunctionDeclaration` (round 82, and its comment names this very defect) and for a
 * type alias ONLY when the alias body was an `ImportType` (B98a's narrow gate) — never for
 * a class or an interface. Everywhere else the walker resolved each type ARGUMENT with no
 * type-parameter scope at all, so a parameter that happens to share a name with a
 * file-level interface / class / alias resolved to THAT type and was then judged against
 * the callee's constraint. `withDeclTypeParamScope` is now the one site, used by the alias,
 * class and interface branches alike (heritage clauses included).
 *
 * Both directions are wrong and both are pinned here:
 *
 *  * a FALSE POSITIVE when the shadowing type does not satisfy the constraint and the
 *    parameter does — pristine `conditionalTypes1`'s `interface A` (line 309) against its
 *    `type And<A extends boolean, …> = If<A, B, false>` (line 171), two ours-only TS2344;
 *  * a FALSE NEGATIVE the other way round, which is why the fix ADDS diagnostics: an
 *    alias parameter that genuinely violates the callee's constraint — including an
 *    UNCONSTRAINED one — was silent whenever any resolvable type shared its name, and
 *    silent anyway when nothing did.
 *
 * The type RESOLUTION path was never affected: `getTypeFromTypeReference` answers
 * `Wrap<"x">` correctly with the same interface in scope, which is what bounds this to
 * the walker and is why no emitted type or narrowing can move.
 */
class AliasTypeParamShadowedInConstraintCheckTest {

    /** A callee whose parameter demands `string`, and the two-line conditional the
     *  pristine fixture uses. */
    private val callees = """
        interface Box<S extends string> { s: S }
        type If<C extends boolean, T, F> = C extends true ? T : F;
    """.trimIndent() + "\n"

    @Test
    fun `an alias type parameter shadowed by a same-named interface is not judged as that interface`() {
        diagnose(
            callees + """
                interface A { a: 'a' }
                type And<A extends boolean, B extends boolean> = If<A, B, false>;
            """
        ) should {
            have(none { it.code == 2344 })
        }
    }

    @Test
    fun `the shadowing declaration may follow the alias - pristine puts it 138 lines later`() {
        diagnose(
            callees + """
                type And<A extends boolean, B extends boolean> = If<A, B, false>;
                interface A { a: 'a' }
            """
        ) should {
            have(none { it.code == 2344 })
        }
    }

    @Test
    fun `a shadowed parameter of a plain reference alias body is not judged as the shadow`() {
        diagnose(
            callees + """
                interface S { s: 1 }
                type UseBox<S extends string> = Box<S>;
            """
        ) should {
            have(none { it.code == 2344 })
        }
    }

    @Test
    fun `negative control - the same alias with no shadowing declaration is silent`() {
        diagnose(
            callees + """
                type UseBox<Q extends string> = Box<Q>;
            """
        ) should {
            have(none { it.code == 2344 })
        }
    }

    @Test
    fun `the walker judges the PARAMETER - a violating parameter errors although its shadow would satisfy`() {
        diagnose(
            callees + """
                type Q = string;
                type Bad<Q extends number> = Box<Q>;
            """
        ) should {
            have(any {
                it.code == 2344 && "'Q'" in it.message && "'string'" in it.message
            })
        }
    }

    @Test
    fun `a violating alias parameter errors with no shadow in play`() {
        diagnose(
            callees + """
                type Bad<Q extends number> = Box<Q>;
            """
        ) should {
            have(any {
                it.code == 2344 && "'Q'" in it.message && "'string'" in it.message
            })
        }
    }

    @Test
    fun `an UNCONSTRAINED alias parameter does not satisfy a constrained callee`() {
        diagnose(
            callees + """
                type Loose<Q> = Box<Q>;
            """
        ) should {
            have(any {
                it.code == 2344 && "'Q'" in it.message && "'string'" in it.message
            })
        }
    }

    @Test
    fun `regression guard - a concrete violating type argument in an alias body still errors`() {
        diagnose(
            callees + """
                type Wrong = Box<number>;
            """
        ) should {
            have(any { it.code == 2344 && "'number'" in it.message })
        }
    }

    @Test
    fun `regression guard - a function declaration's own type parameter was never affected`() {
        diagnose(
            callees + """
                interface P { p: 2 }
                declare function g<P extends string>(x: Box<P>): void;
            """
        ) should {
            have(none { it.code == 2344 })
        }
    }

    @Test
    fun `an INTERFACE's own type parameter shadowed by a same-named interface`() {
        diagnose(
            callees + """
                interface R { r: 3 }
                interface Holder<R extends string> { b: Box<R> }
            """
        ) should {
            have(none { it.code == 2344 })
        }
    }

    @Test
    fun `a CLASS's own type parameter shadowed by a same-named interface`() {
        diagnose(
            callees + """
                interface R { r: 3 }
                declare class Holder<R extends string> { b: Box<R> }
            """
        ) should {
            have(none { it.code == 2344 })
        }
    }

    @Test
    fun `a class HERITAGE clause sees the class's own type parameter`() {
        diagnose(
            callees + """
                interface R { r: 3 }
                declare class Base<S extends string> { s: S }
                declare class Sub<R extends string> extends Base<R> {}
            """
        ) should {
            have(none { it.code == 2344 })
        }
    }

    @Test
    fun `the walker judges an INTERFACE's parameter - a violating one still errors`() {
        diagnose(
            callees + """
                type R = string;
                interface Holder<R extends number> { b: Box<R> }
            """
        ) should {
            have(any {
                it.code == 2344 && "'R'" in it.message && "'string'" in it.message
            })
        }
    }
}
