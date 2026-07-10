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
 * Pins the round-464 pair that clears tsc's getReferencedFileLocation final
 * return (program.ts:1220):
 *
 *  1. a destructured const's TOP-LEVEL elements type from the source's
 *     declared members ([recordDestructuredConstElementTypes] — B83.5 left
 *     them unbound, so `const { kind, index } = ref` made `index` anyType and
 *     `file.referencedFiles[index]` unresolvable, defeating the
 *     destructuring-overwrite narrowing of `pos`/`end`). FUNCTION-shaped
 *     member types stay unrecorded (the fn-type relation's M3 gaps —
 *     tsbuildPublic.ts's changeCompilerHostLikeToUseCache elements FP'd
 *     when recorded);
 *  2. a MULTI-object-member union return target selects the constituent
 *     whose members cover every objlit property NAME as the contextual type
 *     ([selectUnionMemberByObjLitKeys]).
 */
class DestructuredConstElementTypingTest {

    private val prelude = """
        interface FileReference { pos: number; end: number; }
        interface SourceFile { referencedFiles: readonly FileReference[]; text: string; }
        interface Ref { kind: number; index: number; }
        interface RefLoc { file: SourceFile; pos: number; end: number; }
        interface SynthRefLoc { file: SourceFile; text: string; }
    """.trimIndent() + "\n"

    @Test
    fun `destructured elements type from the source so downstream element access and narrowing complete`() {
        diagnose(prelude + """
            function getLoc(file: SourceFile, ref: Ref): RefLoc | SynthRefLoc {
                const { kind, index } = ref;
                let pos: number | undefined, end: number | undefined;
                switch (kind) {
                    case 1:
                        if (index === -1) return { file, text: "x" };
                        pos = index;
                        end = index + 1;
                        break;
                    default:
                        ({ pos, end } = file.referencedFiles[index]);
                        break;
                }
                return { file, pos, end };
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `fn-shaped destructured members stay unrecorded - no manufactured relation check`() {
        diagnose(prelude + """
            interface Host {
                readFile(fileName: string, encoding?: string): string | undefined;
            }
            interface Cache { originalReadFile: Host["readFile"]; }
            interface St { cache?: Cache; }
            declare function change(host: Host): { originalReadFile: Host["readFile"]; };
            function f(state: St, host: Host): void {
                const { originalReadFile } = change(host);
                state.cache = { originalReadFile };
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a destructured element used against a mismatched target still fires`() {
        diagnose(prelude + """
            function f(ref: Ref): string {
                const { index } = ref;
                const s: string = index;
                return s;
            }
        """.trimIndent()) should {
            have(any { it.code == 2322 })
        }
    }
}
