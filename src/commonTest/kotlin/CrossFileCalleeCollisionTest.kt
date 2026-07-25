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
 * Round 440 (Blocker #3, self-compile burn-down): a bare-Identifier CALLEE whose name is
 * declared as a top-level function in MULTIPLE files must resolve to the CURRENT file's own
 * declaration, not the merged `globals` symbol. `mergeSymbolTable` pollutes the
 * first-processed file's own symbol with every other file's same-named declarations, so
 * resolving `getBuildInfo` via globals inside tsbuildPublic.ts picked emitter.ts's
 * `getBuildInfo(file: string, ...)` and FP'd `state` against `string`.
 *
 * getCalleeType now consults currentFileLocals/fileLocalTypeMaps before globals — but AFTER
 * the enclosing-namespace lookup, so a call inside `namespace Parser` to `createSourceFile`
 * still picks the namespace-internal one over the file-level exported `createSourceFile`.
 */
class CrossFileCalleeCollisionTest {

    private fun compile(source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "b.ts").diagnostics

    @Test
    fun `callee resolves to the current file's own function - not another file's`() {
        // a.ts has getInfo(file: string, ...); b.ts has its own getInfo(State, ...).
        // The call in b.ts passes a State — must pick b.ts's getInfo.
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            export function getInfo(file: string, text: string): number { return 0; }

            // @Filename: b.ts
            interface State { id: number; }
            function getInfo(state: State, path: string, extra: number): number { return 0; }
            export function build(state: State) {
                return getInfo(state, "p", 1);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `namespace-internal callee wins over a file-level same-named function`() {
        // Single file: file-level make(string) + namespace-internal make(number). A call
        // inside the namespace must pick the namespace-internal one (regression guard for
        // the file-local consult ordering).
        diagnose(
            """
            function make(x: string): void {}
            namespace N {
                function make(x: number): void {}
                export function use() { make(5); }
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `namespace-internal callee still type-checks its args - negative control`() {
        diagnose(
            """
            function make(x: string): void {}
            namespace N {
                function make(x: number): void {}
                export function use() { make("hi"); }
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
