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
 * Round 474 (the tsc editorServices.ts `info.sourceFileLike = {…}` family, TS7006
 * ×4): TWO coupled pieces in the implicit-any walker. (1) A body local initialized
 * from a `this.<method>(…)` call types from the ENCLOSING class's own method
 * return annotation ([initializerCtxTypeForImplicitAny]'s this-call arm —
 * `getTypeOfExpression(this)` is deliberately anyType per B101, so the general
 * receiver typing never resolved it). (2) When the assignment target's member
 * ANNOTATION names a conflated alias-shadowed interface (`sourceFileLike?:
 * SourceFileLike` — a leaked `type SourceFileLike` union shadows the real
 * interface), the context is unknowable-but-EXISTS in tsc — the RHS is treated as
 * contextually typed ([implicitAnyMemberAnnotationConflated] +
 * [implicitAnyCtxUnknowable]) instead of firing on every member arrow param.
 */
class ImplicitAnyThisMethodReceiverTest {

    private val decls = """
        // @strict: true
        // @module: nodenext
        // @filename: types.ts
        export interface LineAndCharacter { line: number; character: number; }
        export interface SourceFileLike {
            readonly text: string;
            getLineAndCharacterOfPosition(pos: number): LineAndCharacter;
            getPositionOfLineAndCharacter?(line: number, character: number, allowEdits?: true): number;
        }
        export interface SourceFile { kind: number; text: string; }
        export interface AmbientModuleDeclaration { body?: string; }
        // @filename: scriptInfo.ts
        import { SourceFileLike } from "./types.js";
        export interface ScriptInfo {
            sourceFileLike?: SourceFileLike;
            positionToLineOffset(pos: number): { line: number; offset: number };
        }
    """.trimIndent()

    private val editorServices = """

        // @filename: editorServices.ts
        import { ScriptInfo } from "./scriptInfo.js";
        export class ProjectService {
            private getOrCreateScriptInfoNotOpenedByClient(fileName: string): ScriptInfo | undefined {
                return fileName ? undefined : undefined;
            }
            getSourceFileLike(fileName: string) {
                const info = this.getOrCreateScriptInfoNotOpenedByClient(fileName);
                if (!info) return undefined;
                if (!info.sourceFileLike) {
                    info.sourceFileLike = {
                        get text() { return ""; },
                        getLineAndCharacterOfPosition: pos => {
                            const lineOffset = info.positionToLineOffset(pos);
                            return { line: lineOffset.line - 1, character: lineOffset.offset - 1 };
                        },
                        getPositionOfLineAndCharacter: (line, character, allowEdits) => line + character,
                    };
                }
                return info.sourceFileLike;
            }
        }
    """.trimIndent()

    @Test
    fun `this-method-call local receiver contextually types the written objlit`() {
        diagnose(decls + editorServices, directives = "") should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `conflated member annotation makes the assignment ctx unknowable - suppressed`() {
        diagnose(
            decls + """

            // @filename: importTracker.ts
            import { SourceFile, AmbientModuleDeclaration } from "./types.js";
            type SourceFileLike = SourceFile | AmbientModuleDeclaration;
            export function useIt(s: SourceFileLike): void { void s; }
            """.trimIndent() + editorServices,
            directives = "",
        ) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - an unannotated param with no context still fires`() {
        diagnose(
            decls + """

            // @filename: editorServices.ts
            export class ProjectService {
                getSourceFileLike(fileName: string) {
                    const untyped = (pos) => pos + fileName.length;
                    return untyped;
                }
            }
            """.trimIndent(),
            directives = "",
        ) should {
            have(any { it.code == 7006 })
        }
    }
}
