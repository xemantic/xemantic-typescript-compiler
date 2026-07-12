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
 * Round 479: a MODULE file can only reference a const enum it declares or
 * imports, or a SCRIPT-declared ambient global — never another module file's
 * const enum leaked through mergeSymbolTable (fourslashImpl's module-scoped
 * `const enum State` made findAllReferences' namespace-nested `class State` —
 * B83.5-unbound — FP TS2475 at `new State(...)`).
 */
class ModuleFileConstEnumLeakTest {

    @Test
    fun `another module file's const enum does not poison a namespace-nested class`() {
        diagnose(
            """
            // @module: nodenext
            // @filename: four.ts
            const enum State { none, active }
            export function stateName(s: State): string { return s === State.none ? "none" : "active"; }
            // @filename: refs.ts
            export namespace Core {
                export class State {
                    readonly cache = new Map<string, boolean>();
                }
                export function make(): State {
                    return new State();
                }
            }
            """,
            directives = "// @strict: true",
        ) should {
            have(none { it.code == 2475 })
        }
    }

    @Test
    fun `negative control - a module file's own const enum in value position still fires`() {
        diagnose(
            """
            // @module: nodenext
            // @filename: own.ts
            const enum Kind { a, b }
            export const bad = Kind;
            """,
            directives = "// @strict: true",
        ) should {
            have(any { it.code == 2475 })
        }
    }
}
