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
import kotlin.test.Test

/**
 * (INV.0) step 10c — a HERITAGE base and a QUALIFIED-NAME ROOT resolve a declaration the
 * main binder never bound (B83.5).
 *
 * ## Three more resolvers, and the one that mattered was not the one the item named
 *
 * The queue item pointed at `NameResolver.resolveHeritageBaseSymbol`. That function was
 * given the consult and **nothing moved**: measured over a 25-cell matrix against tsgo
 * 7.0.2 and pristine 6.0.3, `interface J extends I` with a block-scoped `I` resolved `J`
 * perfectly (10a) and inherited NOTHING, because a base type's MEMBERS come from
 * `Checker.getTypeFromBaseTypeExpression` — a FOURTH resolver, the one
 * `resolveBaseTypesLazy` calls. The `implements` VERDICT then turned out to come from a
 * FIFTH, `checkImplementsClauses`' own probe, which is why a class with a block-scoped
 * `implements` target reported the whole-class TS2420 about the OUTER interface where both
 * references report the per-property TS2416 about the inner one.
 *
 * **That is the shape of what is left of B83.5**: not one ladder but a population of
 * per-walker name probes, exactly the ~357 `globals[` readers the step-10 item counted and
 * that 10a/10b/10c were scoped NOT to sweep. Each is added on evidence, one at a time.
 *
 * ## What is NOT closed, and why the fixtures are shaped around it
 *
 * `class D extends B` with a block-scoped `B` is untouched, and the base is not the
 * reason: the DERIVED class is scope-space too, so `new D()` needs the VALUE half of 10b,
 * which is refused until 10b-ii. Every fixture here therefore reads its answer through an
 * `interface` or through a class that is only ever used as an `implements` target.
 */
class ScopeSpaceHeritageResolutionTest {

    /**
     * `interface J extends I` where `I` is block-scoped and UNIQUE: `J` resolved before
     * this step and inherited nothing, so the inherited member read as `any` and the
     * mis-assignment was silent. Both references report it.
     */
    @Test
    fun `an interface extends a block scoped base and inherits its members`() {
        val d = diagnose(
            """
            export function zzzHost(): void {
                interface ZzzI { zzzA: number }
                interface ZzzJ extends ZzzI {}
                let zzzH: ZzzJ = null!
                const zzzProbe: string = zzzH.zzzA
            }
            """,
        )
        assert(d.map { it.message } == listOf("Type 'number' is not assignable to type 'string'."))
    }

    /**
     * The SHADOWING half: with a file-level `ZzzI` present, the base resolved to the OUTER
     * declaration, so `zzzH.zzzInner` read the outer interface's shape. `zzzInner` exists
     * only on the INNER one, so the message names which base answered.
     */
    @Test
    fun `an interface extends the INNER of two same named bases`() {
        val d = diagnose(
            """
            interface ZzzI { zzzOuter: number }
            export function zzzHost(): void {
                interface ZzzI { zzzInner: string }
                interface ZzzJ extends ZzzI {}
                let zzzH: ZzzJ = null!
                const zzzProbe: boolean = zzzH.zzzInner
            }
            """,
        )
        assert(d.map { it.message } == listOf("Type 'string' is not assignable to type 'boolean'."))
    }

    /**
     * `implements` against a block-scoped, SHADOWING interface. The class satisfies the
     * INNER one and not the outer, so a binary that resolves the outer reports a false
     * whole-class TS2420 — which is what shipped until this step.
     *
     * An absence assertion, so it comes with its POSITIVE CONTROL below: the same fixture
     * with a class that does NOT satisfy the inner interface must still report.
     */
    @Test
    fun `a class implements the INNER of two same named interfaces`() {
        val d = diagnose(
            """
            interface ZzzI { zzzOuter: number }
            export function zzzHost(): void {
                interface ZzzI { zzzInner: string }
                class ZzzD implements ZzzI { zzzInner: string = "s" }
            }
            """,
        )
        assert(d.map { it.message } == emptyList<String>())
    }

    /**
     * The positive control for the pin above: the implements CHECK is reached and does
     * fire for a block-scoped target, so the silence there is a verdict and not a skip.
     */
    @Test
    fun `negative control - a class that does not implement its block scoped interface still reports`() {
        val d = diagnose(
            """
            export function zzzHost(): void {
                interface ZzzI { zzzInner: string }
                class ZzzBad implements ZzzI { zzzNope: number = 1 }
            }
            """,
        )
        assert(
            d.map { it.message } ==
                listOf("Class 'ZzzBad' incorrectly implements interface 'ZzzI'."),
        )
    }

    /**
     * The QUALIFIED-NAME ROOT, the fourth type-name path: `ZzzE.ZA` resolved its root
     * through `NameResolver.resolveQualifiedName`'s own ladder, i.e. past both earlier
     * consults, so a block-scoped `enum` answered nothing and the annotation degraded.
     *
     * The root is adopted only when the scope symbol HAS an `exports` table —
     * `declareLexical` publishes an enum's members onto it and does NOT do so for a
     * `namespace`, whose members live in the module's own `LexicalScope`. Adopting a
     * memberless root would turn a wrong answer into no answer at all, which is why the
     * namespace kind is (INV.0) step 10c's stated residue rather than a second arm here.
     */
    @Test
    fun `a qualified reference to a block scoped enum member resolves`() {
        val d = diagnose(
            """
            export function zzzHost(): void {
                enum ZzzE { ZA = 1 }
                let zzzH: ZzzE.ZA = null!
                const zzzProbe: string = zzzH
            }
            """,
        )
        assert(d.map { it.message } == listOf("Type 'ZzzE' is not assignable to type 'string'."))
    }
}
