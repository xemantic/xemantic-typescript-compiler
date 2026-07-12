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
 * Round 479: a BARE specifier under nodenext never resolves RELATIVE — node
 * module kinds use node_modules lookup only, so `import pathModule from
 * "path"` (the node builtin) must not "resolve" to a sibling `path.ts` and
 * FP TS1192 (harnessIO.ts).
 */
class BareSpecifierNodenextNoRelativeTest {

    @Test
    fun `bare specifier does not resolve to a sibling file under nodenext`() {
        diagnose(
            """
            // @module: nodenext
            // @filename: path.ts
            export function normalize(p: string): string { return p; }
            // @filename: main.ts
            import pathModule from "path";
            export function f(): unknown { return pathModule; }
            """,
            directives = "// @strict: true",
        ) should {
            have(none { it.code == 1192 })
        }
    }

    @Test
    fun `negative control - a relative default import of a no-default module still fires`() {
        diagnose(
            """
            // @module: nodenext
            // @filename: mod.ts
            export function normalize(p: string): string { return p; }
            // @filename: main.ts
            import mod from "./mod.js";
            export function f(): unknown { return mod; }
            """,
            directives = "// @strict: true",
        ) should {
            have(any { it.code == 1192 })
        }
    }
}
