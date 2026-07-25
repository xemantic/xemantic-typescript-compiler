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
 * M2.2 (round 391): under `useRealLibs` the default library is SPLIT across many files
 * whose positions overlap, so the position-based `resolveDeclarationSourceFile` cannot
 * attribute a lib declaration to its file and can false-match a large user file — the
 * TS2728 "declared here" related info then points at the wrong file with a bogus offset
 * (the round-390 A/B failures externModule / errorMessageOnObjectLiteralType / libMembers).
 * The fix attributes lib declarations NODE-first via [realLibDeclFile]. These pins prove:
 * a NON-es5 lib member resolves to its actual lib file (not es5, not the user file); an es5
 * lib member resolves to `lib.es5.d.ts`; and — the control — a USER member is untouched
 * (still the user file with a real position, because the node→file map is lib-only).
 */
class RealLibsTs2728FileTest {

    private fun related(@Language("typescript") source: String): List<Diagnostic> =
        TypeScriptCompiler().compile(source, "t.ts").diagnostics
            .flatMap { it.relatedInformation }
            .filter { it.code == 2728 }

    @Test
    fun `TS2728 for a non-es5 lib member points at its real lib file - masked`() {
        // `includes` lives in a post-es5 lib layer (es2016.array.include). Before the fix
        // the position fell through to the FIRST lib file (es5) or a user-file false-match.
        related(
            """
            // @useRealLibs: true
            // @lib: es2016
            // @target: es2016
            const b = [1, 2, 3].includess(2);
            """.trimIndent(),
        ) should {
            have(isNotEmpty())
            have(
                any {
                    val f = it.fileName ?: ""
                    f.startsWith("lib.") && f.endsWith(".d.ts") &&
                        f != "lib.es5.d.ts" && it.line == null && it.character == null
                },
            )
        }
    }

    @Test
    fun `TS2728 for an es5 lib member points at lib_es5_d_ts - not the user file`() {
        related(
            """
            // @useRealLibs: true
            Object.getOwnPropertyNamess(null);
            """.trimIndent(),
        ) should {
            have(any { it.fileName == "lib.es5.d.ts" && it.line == null }, "an es5 lib member must render lib.es5.d.ts:--:--")
            have(none { it.fileName == "t.ts" })
        }
    }

    @Test
    fun `TS2728 for a user member still points at the user file with a real position`() {
        // Control: the node->file map is lib-only, so a USER member is unaffected.
        related(
            """
            // @useRealLibs: true
            interface Foo { bar: number; }
            declare const f: Foo;
            f.barr;
            """.trimIndent(),
        ) should {
            have(any { it.fileName == "t.ts" && it.line != null })
        }
    }

    @Test
    fun `TS2728 from the CJS string-import spelling suggestion attributes to the lib - not the user file`() {
        // Round 394: the B553 emitCjsStringImportMethodAccess walker built its TS2728 with
        // the position-based resolveDeclarationSourceFile, so under real libs `fixed` (on the
        // real String interface, a DEPRECATED HTML helper) false-matched the large /index.ts
        // (/index.ts:8:18528). This is the jsExportMemberMergedWithModuleAugmentation2 shape:
        // a checkJs CJS `module.exports = { a: "ok" }` (typed string) + a `declare module`
        // augmentation, then `a.toFixed()` → TS2551 "did you mean 'fixed'?" + TS2728.
        val diags = TypeScriptCompiler().compile(
            """
            // @useRealLibs: true
            // @target: es2015
            // @allowJs: true
            // @checkJs: true
            // @noEmit: true

            // @Filename: /test.js
            module.exports = {
              a: "ok"
            };

            // @Filename: /index.ts
            import { a } from "./test";

            declare module "./test" {
              export const a: number;
            }

            a.toFixed();
            """.trimIndent(),
            "index.ts",
        ).diagnostics
        diags.firstOrNull { it.code == 2551 && it.message.contains("toFixed") } should {
            val rel = relatedInformation.filter { it.code == 2728 }
            have(
                rel.any { it.fileName == "lib.es2015.core.d.ts" && it.line == null && it.character == null },
                "the 'fixed' TS2728 must be masked to lib.es2015.core.d.ts:--:--",
            )
            have(rel.none { (it.fileName ?: "").endsWith("index.ts") })
        }
    }
}
