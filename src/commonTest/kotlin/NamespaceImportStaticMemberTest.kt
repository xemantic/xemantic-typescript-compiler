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
 * INV.3(d) round 512 (exportStarFromEmptyModule): `NS.Class.staticMissing`
 * through a namespace import ([Checker.tryEmitNamespaceMemberTs2339]) — the
 * namespace-import alias is the CURRENT file's own top-level local, no longer
 * in the retired merged [Checker.globals]; the consult keys per-file/node.
 * The path-shaped variant additionally pins the dir-relative resolver leg in
 * [Checker.resolveAlias]'s ImportDeclaration branch (the round-511 lesson:
 * flat corpus keys mask a dead leg on real on-disk layouts).
 */
class NamespaceImportStaticMemberTest {

    @Test
    fun `local class shadows star re-export - missing static fires TS2339, present static clean`() {
        diagnose(
            """
            // @module: commonjs
            // @filename: m1.ts
            export class A {
                static r;
            }

            // @filename: m3.ts
            export * from "./m1";

            export class A {
                static q;
            }

            // @filename: m4.ts
            import * as X from "./m3";
            X.A.q;
            X.A.r;
            """.trimIndent(),
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 2339 && it.message == "Property 'r' does not exist on type 'typeof A'." })
            have(none { it.code == 2339 && it.message.contains("'q'") })
        }
    }

    @Test
    fun `path-shaped layout resolves the namespace import through the dir-relative leg`() {
        diagnose(
            """
            // @module: commonjs
            // @filename: /proj/src/m1.ts
            export class A {
                static q;
            }

            // @filename: /proj/src/m4.ts
            import * as X from "./m1";
            X.A.q;
            X.A.r;
            """.trimIndent(),
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 2339 && it.message == "Property 'r' does not exist on type 'typeof A'." })
            have(none { it.code == 2339 && it.message.contains("'q'") })
        }
    }
}
