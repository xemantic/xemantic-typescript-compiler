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
 * Round 479: `export import parse = ts.getPathComponents` (an import-equals
 * alias to a namespace-import member, harness vpathUtil.ts) resolves the
 * CALLEE through its own target — never a same-named merged-globals function
 * (`parse(path)` FP'd TS2345 against a cross-file `parse(sourceFile:
 * SourceFile)`).
 */
class ImportEqualsNamespaceMemberCalleeTest {

    @Test
    fun `import-equals alias callee resolves through its namespace target`() {
        diagnose(
            """
            // @module: nodenext
            // @filename: pathmod.ts
            export function getPathComponents(path: string): string[] { return path.split("/"); }
            // @filename: other.ts
            export interface SourceFile { fileName: string; }
            export function parse(sourceFile: SourceFile): string { return sourceFile.fileName; }
            // @filename: nsbarrel.ts
            export * from "./pathmod.js";
            // @filename: vpath.ts
            import * as ts from "./nsbarrel.js";
            export import parse = ts.getPathComponents;
            export function validate(path: string): string[] {
                return parse(path);
            }
            """,
            directives = "// @strict: true",
        ) should {
            have(none { it.code == 2345 })
        }
    }
}
