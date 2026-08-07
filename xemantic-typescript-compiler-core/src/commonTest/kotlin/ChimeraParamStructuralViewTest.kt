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
 * Round 481 (Blocker #3): the STRUCTURAL sibling of the round-480 derived-vs-
 * chimera bail — a source with NO heritage link to the conflated name that
 * nevertheless satisfies SOME declaring file's per-file view of the chimera
 * target relates (tsc resolves the annotation to exactly one module's interface
 * and compares structurally). editorServices' `CachedDirectoryStructureHost`
 * passed to a `ParseConfigHost` param whose fakesHosts `class ParseConfigHost`
 * merge demanded a required `getCurrentDirectory` (optional on the compiler
 * interface tsc sees).
 */
class ChimeraParamStructuralViewTest {

    @Test
    fun `an arg satisfying the interface-declaring file's view of a chimera param is accepted`() {
        diagnose(
            """
            // @filename: a.ts
            export interface Host {
                useCase: boolean;
                fileExists(path: string): boolean;
                getCurrentDirectory?(): string;
            }
            export function use(host: Host): void {}
            // @filename: b.ts
            export class Host {
                extra: number = 1;
                useCase = false;
                fileExists(p: string): boolean { return true; }
                getCurrentDirectory(): string { return ""; }
            }
            // @filename: main.ts
            import { use } from "./a";
            export interface Cached {
                useCase: boolean;
                fileExists(path: string): boolean;
            }
            export function run(c: Cached): void {
                use(c);
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - an arg missing a required member of every declaring file's view still fires`() {
        diagnose(
            """
            // @filename: a.ts
            export interface Host {
                useCase: boolean;
                fileExists(path: string): boolean;
                getCurrentDirectory?(): string;
            }
            export function use(host: Host): void {}
            // @filename: b.ts
            export class Host {
                extra: number = 1;
                useCase = false;
                fileExists(p: string): boolean { return true; }
                getCurrentDirectory(): string { return ""; }
            }
            // @filename: main.ts
            import { use } from "./a";
            export interface Cached {
                useCase: boolean;
            }
            export function run(c: Cached): void {
                use(c);
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
