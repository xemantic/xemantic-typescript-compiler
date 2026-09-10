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
 * (INV.0) step 10b — a VALUE read stops resolving the WRONG declaration when the main
 * binder never bound the right one (B83.5): a `function`, `class`, `enum` or `namespace`
 * inside a function body or a block.
 *
 * ## The two failure modes, and why this step closes exactly one of them
 *
 * A UNIQUE scope-space name resolved to nothing, so the read degraded to `any` and every
 * check under it went quiet. A SHADOWING one resolved to the OUTER declaration — a wrong
 * answer with nothing to detect. Measured over the step-10 129-cell matrix against tsgo
 * 7.0.2 and pristine 6.0.3, the VALUE half of the B83.5 sites was 19 ours-only and 46
 * lost rows after 10a landed.
 *
 * **The unique half was BUILT, MEASURED and REFUSED, and its blocker is named.** A
 * consult that answers a unique scope-space name is correct and takes the matrix to 9
 * cells FIXED plus 9 IMPROVED — and adds **19-20 ours-only rows to EVERY ONE of the
 * eight dashboard profiles**. tsc's own sources are full of
 * `return { <shorthand nested functions> }` against a declared interface, and of reads
 * like `return links.isVisible` where `links` comes from a nested `getNodeLinks`; every
 * new row is a pre-existing inference or narrowing gap that `any` had been masking
 * ((CHK.50)'s law at scale), not a defect of the consult. So this step is
 * OVERRIDE-ONLY — see [Checker.getTypeOfIdentifierCore]: it replaces a conventional
 * answer, never silence.
 *
 * ## What makes the override sound
 *
 * [LexicalScopeResolver.symbolAt]'s `stopFlags`: the ascent ENDS at the innermost
 * scope-space binding of the name in VALUE space and answers only when that binding is
 * one of the four DECLARATION kinds. An inner `const` shadowing an outer nested
 * `function` therefore refuses here and falls through to the conventional ladder, which
 * carries it. Without that the ascent would walk PAST the `const` and answer the
 * function — again a wrong answer rather than a miss.
 *
 * ## Why the assertions are on MESSAGE TEXT
 *
 * Both failure modes are silent, so "no error here" passes on a broken binary and "an
 * error here" cannot say WHICH declaration answered. Every pin below reads the resolved
 * type — or a member only one of the two candidates has — out of the diagnostic text.
 */
class ScopeSpaceValueResolutionTest {

    /**
     * The FUNCTION kind, shadowing: the file-level `zzzFn` is what every conventional
     * rung answers, and the block-nested one is what TypeScript means. `zzzInner` exists
     * only on the INNER declaration's return type, so the message names which one
     * answered — before this step it read `{ zzzOuter: number; }`.
     *
     * **BLOCK-nested deliberately, and the fnTop spelling of the same shape is NOT
     * closed**: measured, the conventional ladder answers `any` for a function-body-TOP
     * reference to a same-named file-level `function`, so there is nothing for an
     * override to replace. That cell is the unique half in disguise and is (P18.63)'s
     * recorded residue.
     */
    @Test
    fun `an inner function in a block wins over a same named file level one`() {
        val d = diagnose(
            """
            function zzzFn(): { zzzOuter: number } { return { zzzOuter: 1 } }
            export function zzzHost(): void {
                {
                    function zzzFn(): { zzzInner: string } { return { zzzInner: "s" } }
                    const zzzProbe: boolean = zzzFn().zzzInner
                }
            }
            """,
        )
        assert(d.map { it.message } == listOf("Type 'string' is not assignable to type 'boolean'."))
    }

    /**
     * The CLASS kind, shadowing. A `class` is in BOTH projections — it declares a name in
     * the type space AND the value space — so the two consults ask the same scope symbol
     * with different flag masks; this pin is the value one, through a `new` expression.
     */
    @Test
    fun `an inner class wins over a same named file level one`() {
        val d = diagnose(
            """
            class ZzzCls { zzzOuter: number = 1 }
            export function zzzHost(): void {
                class ZzzCls { zzzInner: string = "s" }
                const zzzProbe: boolean = new ZzzCls().zzzInner
            }
            """,
        )
        assert(d.map { it.message } == listOf("Type 'string' is not assignable to type 'boolean'."))
    }

    /**
     * The ENUM kind, shadowing — round 748 closed the enum half in TYPE position only,
     * so a VALUE read of a block-scoped enum still answered the OUTER declaration.
     *
     * `ZInner` is a member of the INNER enum only, so the surviving TS2339 (an ours-only
     * row from the member-access family, which resolves the receiver by NAME through a
     * table of its own) is what says the two resolvers still disagree — recorded as
     * (P18.63)'s residue rather than asserted away.
     */
    @Test
    fun `an inner enum wins over a same named file level one`() {
        val d = diagnose(
            """
            enum ZzzE { ZOuter = 1 }
            export function zzzHost(): void {
                enum ZzzE { ZInner = 2 }
                const zzzProbe: boolean = ZzzE.ZInner
            }
            """,
        )
        assert(d.any { it.message == "Type 'ZzzE' is not assignable to type 'boolean'." })
    }

    /**
     * The `stopFlags` axis, as a VALUE pin rather than an argument: an inner `const`
     * shadowing an OUTER scope-space `function` must win, and it can only do so because
     * the ascent STOPS at the first value-space binding instead of filtering past it.
     *
     * Both candidate answers produce a diagnostic here, which is the point — the pin
     * discriminates on WHICH type is named, not on whether anything is reported. Drop
     * `stopFlags` and the message becomes `Type '() => number'`.
     */
    @Test
    fun `an inner const shadows an outer scope space function`() {
        val d = diagnose(
            """
            export function zzzHost(): void {
                function zzzShadowed(): number { return 1 }
                {
                    const zzzShadowed = "s"
                    const zzzProbe: number = zzzShadowed
                }
            }
            """,
        )
        assert(d.map { it.message } == listOf("Type 'string' is not assignable to type 'number'."))
    }

    /**
     * The CONTAINMENT control, and the reason this change cannot move an existing
     * answer: `declareLexical` refuses any name the main binder already bound in that
     * container, so a conventionally-bound name is absent from `scope.symbols`
     * everywhere and keeps resolving exactly as before.
     *
     * The function body here declares NOTHING named `zzzBoundFn`, so the file-level
     * declaration must still answer — and it must answer with its OWN return type,
     * which the mis-assignment prints.
     */
    @Test
    fun `a conventionally bound name is untouched by the scope space value consult`() {
        val d = diagnose(
            """
            export function zzzBoundFn(): { zzzOuter: number } { return { zzzOuter: 1 } }
            export function zzzHost(): void {
                const zzzProbe: boolean = zzzBoundFn().zzzOuter
            }
            """,
        )
        assert(d.map { it.message } == listOf("Type 'number' is not assignable to type 'boolean'."))
    }

    /**
     * The (INC.16) COST property, pinned as a STRUCTURAL fact because it has no
     * diagnostic — and pinned in the REGIME where it can pay, which is not the obvious
     * one.
     *
     * The program-wide gate is a UNION over files, so a name declared scope-space in
     * `a.ts` hits for an identifier in `b.ts` — and
     * [LexicalScopeResolver.scopesOfOwningFile] BUILDS the tables it reads. The consult
     * therefore tests the OWNING FILE's own projection first.
     *
     * **Measured while writing this pin: on a FULL build the property is unobservable,
     * because `checkSpine` forces every CHECKED file's tables anyway** (`LexDefer.census`
     * reads `forcedBy={checkSpine=2}` for a two-file program, with or without the gate).
     * So (INC.16)'s prize — and this gate's — lives entirely in the PARTITIONED regime:
     * with `assignedFileNames = {a.ts}` the spine forces one file and the other must stay
     * unbuilt however many program-wide walkers reach into it. That is the incremental
     * floor, which is the whole reason `lexicalScopes` is lazy.
     *
     * Asserted on the [BinderResult]s directly rather than through `LexDefer`'s
     * process-global counters: a pin whose environment is another test is (INC.67)'s trap.
     */
    @Test
    fun `a partitioned build leaves an unassigned file's scope tables unbuilt`() {
        val binder = Binder(CompilerOptions())
        val a = binder.bind(
            Parser(
                """
                export function zzzHostA(): void {
                    function zzzHelper(): number { return 1 }
                    const zzzProbeA: string = zzzHelper()
                }
                """.trimIndent(),
                "/p/a.ts",
            ).parse(),
        )
        val b = binder.bind(
            Parser(
                """
                declare function zzzHelper(): string
                export function zzzHostB(): void {
                    const zzzProbeB: number = zzzHelper()
                }
                """.trimIndent(),
                "/p/b.ts",
            ).parse(),
        )
        assert(a.scopeValueNames == setOf("zzzHelper"))
        assert(b.scopeValueNames.isEmpty())
        Checker(CompilerOptions(), listOf(a, b), assignedFileNames = setOf("/p/a.ts")).getDiagnostics()
        assert(a.lexicalScopesBuilt)
        assert(!b.lexicalScopesBuilt)
    }
}