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
 * TS2345 at :2237). `kindDomainProvesNotSubtype` reads the declared `.kind`
 * DOMAINS — including a bare-enum annotation (`kind: SyntaxKind` = all members)
 * and a generic token reference whose `kind` is inherited via `extends` levels
 * with type-arg NODES threaded through TP positions — and keeps [t] when its
 * domain exceeds the target's.
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
