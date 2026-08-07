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
 * Round 513 (INV.3(d)(v) deletion #2): `objectLiteralMatchesFileLocalAliasUnion`
 * — the re-keyed survivor of the round-447 conflation bail, now an M3-relation
 * residue bridge: a returned object literal against a FILE-LOCAL type-alias
 * UNION whose matched constituent's REFERENCE-valued members carry guard
 * narrow-DOWNs invisible to objlit member typing (the round-438
 * nullish-strip-only gate). tsc's fixAddMissingMember.ts:410 shape:
 * `return { kind: InfoKind.Enum, token, parentDeclaration: enumDeclaration }`
 * vs `Info | undefined` — the shorthand `token` types as the declared wide
 * union, failing `EnumInfo.token: Identifier`. The helper accepts by AST-side
 * name-coverage of some constituent, WITH the explicit-value type firewall
 * (a genuinely wrong non-reference value still fires).
 */
class FileLocalAliasUnionReturnTest {

    private val decls = """
        // @strict: true
        interface NodeBase { kind: number; }
        interface Identifier extends NodeBase { text: string; }
        interface PrivateIdentifier extends NodeBase { escaped: string; }
        interface EnumDecl extends NodeBase { members: string[]; }
        declare function isPrivateIdentifier(n: NodeBase): n is PrivateIdentifier;
        enum InfoKind { Enum, TypeLike }
        interface EnumInfo {
            readonly kind: InfoKind.Enum;
            readonly token: Identifier;
            readonly parentDeclaration: EnumDecl;
        }
        interface TypeLikeInfo {
            readonly kind: InfoKind.TypeLike;
            readonly declSourceFile: string;
        }
        type Info = TypeLikeInfo | EnumInfo;
    """

    @Test
    fun `guard-narrowed shorthand member accepts via constituent name-coverage`() {
        diagnose(
            decls + """

            declare function findEnum(t: NodeBase): EnumDecl | undefined;
            export function getInfo(token: Identifier | PrivateIdentifier): Info | undefined {
                const enumDeclaration = findEnum(token);
                if (enumDeclaration && !isPrivateIdentifier(token)) {
                    return { kind: InfoKind.Enum, token, parentDeclaration: enumDeclaration };
                }
                return undefined;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a wrong explicit member value still fires`() {
        diagnose(
            decls + """

            export function bad(token: Identifier): Info | undefined {
                return { kind: InfoKind.TypeLike, declSourceFile: 42 };
            }
            """,
        ) should {
            have(any { it.code == 2322 || it.code == 2353 })
        }
    }

    @Test
    fun `negative control - an excess member still fires`() {
        diagnose(
            decls + """

            export function bad2(token: Identifier): Info | undefined {
                return { kind: InfoKind.TypeLike, declSourceFile: "a.ts", bogus: 1 };
            }
            """,
        ) should {
            have(any { it.code == 2322 || it.code == 2353 })
        }
    }
}
