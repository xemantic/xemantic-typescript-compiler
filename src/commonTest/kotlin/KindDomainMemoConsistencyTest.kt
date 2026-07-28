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
 * Round 482 wrote this against a memo: `kindDomainKeysOfType` cached each member's
 * `.kind` domain by Type.id, and the risk was cross-site contamination — the cache is
 * keyed on the MEMBER type while the exceed check compares it against a PER-SITE target
 * domain, so two negative guards on the SAME union with DIFFERENT targets had to narrow
 * independently.
 *
 * **(REL.1)(c) step 5c, round 753: the memo and the whole veto family are DELETED, so
 * there is no longer a cache here to contaminate — and the pin is kept anyway, because
 * the OBSERVABLE invariant it states never depended on the implementation.** Two negative
 * guards over the same union with different targets must still each narrow on their own,
 * and that is now a statement about the structural relation, which (REL.1)(a)/(b) taught
 * to discriminate enum members directly.
 *
 * The prelude mirrors the tsc token family: a brand-kinded `Modifier` union whose members'
 * `kind` domains exceed the property-poorer guard targets — the shape that used to collapse
 * to `never` when nothing could tell the siblings apart.
 */
class KindDomainMemoConsistencyTest {

    private val prelude = """
        const enum SK { Unknown, Ident, AbstractKw, PublicKw, StaticKw, StringLit, NumberLit }
        interface Node3 { readonly kind: SK; }
        interface Token3<TKind extends SK> extends Node3 { readonly kind: TKind; }
        interface ModifierToken3<TKind extends SK> extends Token3<TKind> {}
        type AbstractKw3 = ModifierToken3<SK.AbstractKw>;
        type PublicKw3 = ModifierToken3<SK.PublicKw>;
        type StaticKw3 = ModifierToken3<SK.StaticKw>;
        type Modifier3 = AbstractKw3 | PublicKw3 | StaticKw3;
        interface Ident3 extends Node3 { readonly kind: SK.Ident; text: string; }
        interface StringLit3 extends Node3 { readonly kind: SK.StringLit; value: string; }
        interface NumberLit3 extends Node3 { readonly kind: SK.NumberLit; value: number; }
        type Literal3 = StringLit3 | NumberLit3;
        declare function isModifier3(n: Node3): n is Modifier3;
        declare function isIdent3(n: Node3): n is Ident3;
        declare function isLiteral3(n: Node3): n is Literal3;
        declare function useIdent3(n: Ident3): number | undefined;
        declare function useLiteral3(n: Literal3): number | undefined;
    """.trimIndent()

    @Test
    fun `two negative guards on the same union with different targets each narrow independently`() {
        diagnose(
            prelude + "\n" + """
            // Site A: after !isModifier3, node's kind domain still exceeds Ident3's
            // (and Literal3's) — the reference must NOT wash to never, so the later
            // isIdent3 / isLiteral3 positive guards resolve their arg cleanly.
            function siteA(node: Node3): number | undefined {
                if (isModifier3(node)) return 1;
                if (isIdent3(node)) return useIdent3(node);
                if (isLiteral3(node)) return useLiteral3(node);
                return undefined;
            }
            // Site B: same union type, DIFFERENT negative guard target (Ident3).
            // The Type.id memo for each member's kind domain is shared with site A,
            // but the exceed check compares against B's own hoisted target domain.
            function siteB(node: Node3): number | undefined {
                if (isIdent3(node)) return 1;
                if (isLiteral3(node)) return useLiteral3(node);
                return undefined;
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a member whose kind is a genuine subtype still collapses`() {
        // After the exhaustive positive guards there is nothing left that a
        // Literal3-only function may accept — the arg is a genuine subtype/never.
        diagnose(
            prelude + "\n" + """
            function bad(node: Node3): number | undefined {
                if (isModifier3(node)) return 1;
                if (isIdent3(node)) return 1;
                return useLiteral3(node);
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
