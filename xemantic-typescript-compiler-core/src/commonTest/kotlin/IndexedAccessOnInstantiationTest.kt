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
 * (REL.2) round 783 — an INDEXED ACCESS `T["p"]` reads the property THROUGH its
 * carrier, so `Token<SK.X>["kind"]` is `SK.X` rather than `any`.
 *
 * An interface member's symbol is SHARED by every type that inherits it, and
 * `symbolTypes[sym.id]` for a type-parameter-typed member is globally `any`
 * (round 761) — so `getTypeOfSymbol` was the wrong reader here. The union arm of
 * `getIndexedAccessType` turns ONE `any` part into an `any` whole, which is how tsc's
 * `Modifier["kind"]` — the predicate target of `isModifierKind` — resolved to `any`
 * and narrowed nothing: `completions.ts:2239`.
 *
 * The pins probe against a PRIMITIVE and read the type out of the MESSAGE. A silence
 * pin cannot work here: the pre-fix answer was `any`, which is assignable to
 * everything, so "no error" is exactly what the broken build produces.
 */
class IndexedAccessOnInstantiationTest {

    private val prelude = """
        enum SK { AbstractKeyword = 126, AccessorKeyword = 129, AsyncKeyword = 134, IfKeyword = 101 }
        interface Node { kind: SK; }
        interface Token<TKind extends SK> extends Node { readonly kind: TKind; }
        type ModifierSK = SK.AbstractKeyword | SK.AccessorKeyword | SK.AsyncKeyword;
        type ModifierToken<TKind extends ModifierSK> = Token<TKind>;
        type Modifier = ModifierToken<SK.AbstractKeyword> | ModifierToken<SK.AccessorKeyword>
            | ModifierToken<SK.AsyncKeyword>;
        type KeywordSK = SK.AbstractKeyword | SK.AccessorKeyword | SK.AsyncKeyword | SK.IfKeyword;
    """.trimIndent() + "\n"

    /**
     * DISCRIMINATES — the ablated build answers `any` and this assignment is SILENT.
     */
    @Test
    fun `an indexed access on a single generic instantiation resolves the type argument`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(x: Token<SK.AsyncKeyword>["kind"]): string {
                    const s: string = x;
                    return s;
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2322 })
    }

    /**
     * DISCRIMINATES BY MESSAGE — `'ModifierSK'` fixed, silent ablated. The union arm
     * is what `Modifier["kind"]` needs, and one `any` constituent poisons the whole.
     *
     * (PARITY.2): a `never` annotation, not a `string` one. Every constituent of
     * `Modifier["kind"]` is an enum MEMBER, so at a `string` target tsc's
     * `reportRelationError` generalization collapses the whole union to `SK` — which a
     * one-constituent resolution would also produce, i.e. the pin would stop testing the
     * union arm. **Two things this pin's expectation is NOT.** It is not tsc's string:
     * tsgo 7.0.2 and pristine `typescript@6.0.3` both print the EXPANSION
     * (`'SK.AbstractKeyword | SK.AccessorKeyword | SK.AsyncKeyword'`) where we print the
     * alias name, because INV.5(a) interns a union by its member-id list and
     * `aliasDisplayMap` is id-keyed — the (INC.27) refusal, which no policy change here
     * can reach. And the fixture must not `return s`: a `never` value is reported as
     * unassignable to `string` by this compiler (an ours-only false positive, recorded
     * by (PARITY.2)), so the return would add a second row.
     */
    @Test
    fun `an indexed access on a union of generic instantiations unions the arguments`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(x: Modifier["kind"]): void {
                    const s: never = x;
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2322 && it.message == "Type 'ModifierSK' is not assignable to type 'never'." })
    }

    /**
     * DISCRIMINATES — `completions.ts:2239`'s shape end to end: a type guard whose
     * PREDICATE is the indexed access. With `any` as the predicate target the narrow
     * kept all 85 keyword members and the return check rejected them.
     */
    @Test
    fun `a type guard whose predicate is an indexed access narrows to the member union`() {
        val diagnostics = diagnose(
            prelude +
                """
                declare function isModifierKind(token: SK): token is Modifier["kind"];
                declare function keywordKind(): KeywordSK | undefined;
                export function f(): ModifierSK | undefined {
                    const k = keywordKind();
                    if (k && isModifierKind(k)) {
                        return k;
                    }
                    return undefined;
                }
                """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /**
     * HOLDS ON BOTH SIDES ON PURPOSE — a NON-generic carrier read the declaring
     * symbol correctly before and must keep doing so. Asserted by MESSAGE so it FIRES.
     */
    @Test
    fun `an indexed access on a plain interface is unchanged`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(x: Node["kind"]): string {
                    const s: string = x;
                    return s;
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2322 })
    }

    /**
     * HOLDS ON BOTH SIDES ON PURPOSE — an ordinary `Array<T>["length"]` still answers
     * `number`, i.e. the carrier read never turns a working answer into a wrong one.
     */
    @Test
    fun `an indexed access on an array instantiation still answers number`() {
        val diagnostics = diagnose(
            """
            export function f(n: Array<string>["length"]): number {
                const m: number = n;
                return m;
            }
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }
}
