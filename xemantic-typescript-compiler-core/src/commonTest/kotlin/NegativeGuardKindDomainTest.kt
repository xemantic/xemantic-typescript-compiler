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
 * Round 472: a negative type-guard branch (`!isModifier(node)`) collapses the
 * reference to `never` only when it is GENUINELY a subtype of the guard target —
 * our relation over-accepts a wide AST-node interface against a brand-kinded
 * union (`Node <: Modifier` "held" because `Token<TKind>`'s enum-member `kind`
 * resolves to `any`), washing `node` to never and FP'ing every later use in the
 * false branch (tsc completions.ts isModifierLike → the identifierToKeywordKind
 * TS2345 at :2237). Round 472 fixed it with `kindDomainProvesNotSubtype`, a veto
 * that read the declared `.kind` DOMAINS and kept the subject when its domain
 * exceeded the target's.
 *
 * **(REL.1)(c) step 5c, round 753: that veto is DELETED and these pins now hold on
 * the relation alone** — (REL.1)(a)/(b) gave the relation the enum-member
 * discrimination whose absence was the whole reason the veto existed. Ablated
 * before it was cut, the veto fired 11,667 times on the compiler profile and the
 * output stayed byte-identical, so the deletion is measured rather than assumed.
 * This file is now the sharpest guard on that: if the relation ever loses the
 * ability, the `never` wash comes straight back here.
 */
class NegativeGuardKindDomainTest {

    private val prelude = """
        const enum SyntaxKind2 { Unknown, Identifier2, AbstractKeyword, PublicKeyword, EndOfFile }
        interface Node2 { readonly kind: SyntaxKind2; }
        interface Token2<TKind extends SyntaxKind2> extends Node2 {
            readonly kind: TKind;
        }
        interface ModifierToken2<TKind extends SyntaxKind2> extends Token2<TKind> {
        }
        type AbstractKeyword2 = ModifierToken2<SyntaxKind2.AbstractKeyword>;
        type PublicKeyword2 = ModifierToken2<SyntaxKind2.PublicKeyword>;
        type Modifier2 = AbstractKeyword2 | PublicKeyword2;
        interface Identifier2 extends Node2 {
            readonly kind: SyntaxKind2.Identifier2;
            text: string;
        }
        declare function isModifier2(node: Node2): node is Modifier2;
        declare function isIdentifier2(node: Node2): node is Identifier2;
        declare function identifierToKeywordKind2(node: Identifier2): number | undefined;
    """.trimIndent()

    @Test
    fun `a negative guard keeps a reference whose kind domain exceeds the target's`() {
        diagnose(
            prelude + "\n" + """
            function isModifierLike2(node: Node2): number | undefined {
                if (isModifier2(node)) {
                    return 1;
                }
                if (isIdentifier2(node)) {
                    const k = identifierToKeywordKind2(node);
                    if (k) return k;
                }
                return undefined;
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - an unrelated arg after the guards still fails`() {
        diagnose(
            prelude + "\n" + """
            function bad(node: Node2): number | undefined {
                if (isModifier2(node)) {
                    return 1;
                }
                return identifierToKeywordKind2(node);
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
