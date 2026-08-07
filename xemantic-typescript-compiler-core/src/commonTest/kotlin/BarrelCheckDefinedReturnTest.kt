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
 * Pins the round-464 pair that types tsc's getReferencedFileLocation
 * (program.ts:1220 family):
 *
 *  1. a BARREL-imported namespace-member call in the checkDefined SHAPE
 *     (`Debug.checkDefined(program.getSourceFileByPath(x))` where the callee
 *     is `<T>(value: T | undefined | null, …): T`) resolves its RETURN as
 *     the argument's type with nullish stripped
 *     ([tryBarrelCheckDefinedReturn]) — previously the unresolvable `Debug`
 *     receiver made the whole local `any`, so nothing downstream narrowed;
 *  2. a plain `=` with a PROPERTY-ACCESS RHS (`end = importLiteral.end`)
 *     filters the antecedent union by the member's resolved NON-UNION type,
 *     mirroring the round-463 Identifier arm.
 */
class BarrelCheckDefinedReturnTest {

    private fun build(@Language("typescript") programSource: String): ProjectCompiler.Result {
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to
                    """{ "compilerOptions": { "strict": true, "module": "nodenext", "target": "es2020" }, "include": ["src/**/*"] }""",
                "/proj/src/debug.ts" to """
                    export namespace Debug {
                        export function checkDefined<T>(value: T | undefined | null, message?: string): T {
                            if (value === undefined || value === null) throw new Error(message);
                            return value;
                        }
                    }
                """.trimIndent(),
                "/proj/src/types.ts" to """
                    export interface FileReference { pos: number; end: number; }
                    export interface SourceFile { referencedFiles: readonly FileReference[]; text: string; }
                    export interface Program { getSourceFileByPath(p: string): SourceFile | undefined; }
                    export interface RefLoc { file: SourceFile; pos: number; end: number; }
                    export interface SynthRefLoc { file: SourceFile; text: string; }
                """.trimIndent(),
                "/proj/src/barrel.ts" to """
                    export * from "./debug.js";
                    export * from "./types.js";
                """.trimIndent(),
                "/proj/src/program.ts" to programSource.trimIndent(),
            )
        )
        return ProjectCompiler(vfs).build("/proj", noEmit = true)
    }

    @Test
    fun `a barrel checkDefined call types as the argument minus nullish - and downstream narrowing completes`() {
        val result = build(
            """
            import { Debug, Program, RefLoc, SynthRefLoc, SourceFile } from "./barrel.js";

            interface StringLit { pos: number; end: number; text: string; }
            interface TemplateLit { pos: number; end: number; text: string; }
            type StringLiteralLike = StringLit | TemplateLit;

            export function litAt(file: SourceFile, index: number): StringLiteralLike {
                return { pos: index, end: index, text: file.text };
            }
            export function skipT(text: string, pos: number): number {
                return pos + text.length;
            }

            export function getLoc(program: Program, refFile: string, kind: number, index: number): RefLoc | SynthRefLoc {
                const file = Debug.checkDefined(program.getSourceFileByPath(refFile));
                let pos: number | undefined, end: number | undefined;
                switch (kind) {
                    case 1:
                        const importLiteral = litAt(file, index);
                        if (importLiteral.pos === -1) return { file, text: importLiteral.text };
                        pos = skipT(file.text, importLiteral.pos);
                        end = importLiteral.end;
                        break;
                    default:
                        ({ pos, end } = file.referencedFiles[index]);
                        break;
                }
                return { file, pos, end };
            }
            """
        )
        result.diagnostics should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a checkDefined call with an unresolvable argument stays unchecked but a plain nullable member still fires`() {
        val result = build(
            """
            import { Debug, Program, RefLoc, SourceFile } from "./barrel.js";
            export function getLoc(program: Program, refFile: string): RefLoc {
                const file = Debug.checkDefined(program.getSourceFileByPath(refFile));
                let pos: number | undefined;
                return { file, pos, end: 0 };
            }
            """
        )
        result.diagnostics should {
            have(any { it.code == 2322 })
        }
    }
}
