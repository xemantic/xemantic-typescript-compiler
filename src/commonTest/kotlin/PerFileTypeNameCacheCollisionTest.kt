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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * Round 513 (INV.3(d)(v)): the [nodeTypes] cache bypass for per-file-dependent
 * type names ([isPerFileDependentRefNode], re-keying the round-473 conflated-ref
 * bypass). The cache is STRUCTURALLY keyed and node positions COLLIDE across
 * files, so two files' identically-positioned `Info | undefined` annotations
 * would share ONE cached resolution — post-retire each file's `Info` is a
 * DIFFERENT symbol (tsc's private codefix `interface Info` pattern), so the
 * leak makes the second file check against the FIRST file's interface
 * (the fixForgottenThisPropertyAccess excess-'node' FP shape). The fixtures
 * keep both files byte-aligned so the annotation offsets genuinely collide.
 */
class PerFileTypeNameCacheCollisionTest {

    private fun compile(@Language("typescript") source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "a.ts").diagnostics

    // The two files are BYTE-IDENTICAL except for the interface bodies and the
    // returned object literals (padded to equal length), so the return
    // annotations `Info | undefined` sit at IDENTICAL offsets in both files.
    private val files = """
        // @strict: true

        // @Filename: a.ts
        interface Info { readonly aOnly: number; }
        export function getA(): Info | undefined {
            return { aOnly: 1 };
        }

        // @Filename: b.ts
        interface Info { readonly bOnly: string; }
        export function getB(): Info | undefined {
            return { bOnly: "" };
        }
    """

    @Test
    fun `identically-positioned annotations of different per-file interfaces resolve independently`() {
        compile(files) should {
            have(none { it.code == 2322 || it.code == 2353 })
        }
    }

    @Test
    fun `negative control - a genuinely wrong objlit against the own-file interface still fires`() {
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            interface Info { readonly aOnly: number; }
            export function getA(): Info | undefined {
                return { wrong: 1 };
            }

            // @Filename: b.ts
            interface Info { readonly bOnly: string; }
            export function getB(): Info | undefined {
                return { bOnly: "" };
            }
            """
        ) should {
            have(any { it.code == 2322 || it.code == 2353 })
        }
    }
}
