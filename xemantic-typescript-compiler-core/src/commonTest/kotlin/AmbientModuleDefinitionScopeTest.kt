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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * M4.9: a `declare module "spec"` in a SCRIPT `.d.ts` DECLARES the ambient
 * module — its members are reachable only through an import of that specifier,
 * never as bare global names. The same syntax inside an external MODULE file is
 * an AUGMENTATION, whose new exports do reach globals because that is their only
 * visibility channel.
 *
 * Getting this wrong published every member of `@types/node`'s
 * `declare module "fs" { … }` into globals, where they OUTRANKED a file's own
 * import alias of the same name: the compiler's own `WatchOptions` lost to
 * node's `fs.WatchOptions`, and the resulting excess-property and
 * missing-property errors were the largest cluster on the `"types": ["node"]`
 * profile (30 diagnostics → 13, every survivor a missing node ambient).
 */
class AmbientModuleDefinitionScopeTest {

    private val ambientFs = """
        // @filename: /node_modules/@types/fake/index.d.ts
        declare module "fs" {
            export interface WatchOptions {
                encoding?: string;
                persistent?: boolean;
            }
            export interface OnlyAmbient { z?: number }
        }
    """.trimIndent()

    @Test
    fun `an imported type wins over a same-named ambient module member`() {
        val diagnostics = diagnose(
            """
            $ambientFs
            // @filename: /src/types.ts
            export interface WatchOptions { watchFile?: number }
            // @filename: /src/sys.ts
            import { WatchOptions } from "./types";
            export function make(): WatchOptions { return { watchFile: 1 }; }
            export function read(o: WatchOptions): number | undefined { return o.watchFile; }
            """,
        )
        assert(diagnostics.none { it.code == 2353 })
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `an ambient module member is not a bare global type name`() {
        val diagnostics = diagnose(
            """
            $ambientFs
            // @filename: /src/user.ts
            export function probe(x: OnlyAmbient): void { x; }
            """,
        )
        assert(diagnostics.any { it.code == 2304 })
    }

    @Test
    fun `an ambient module member is reachable through an import of its specifier`() {
        val diagnostics = diagnose(
            """
            $ambientFs
            // @filename: /src/user.ts
            import { OnlyAmbient } from "fs";
            export function probe(x: OnlyAmbient): number | undefined { return x.z; }
            """,
        )
        assert(diagnostics.none { it.code == 2304 })
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `a module file may still augment an existing ambient module`() {
        // The declaring file has a top-level import, so its `declare module` is
        // an AUGMENTATION, not a declaration — the gate must leave that path
        // alone: no TS2664, and the ambient module's own members keep resolving
        // through an import of its specifier.
        val diagnostics = diagnose(
            """
            $ambientFs
            // @filename: /src/base.ts
            export const seed = 1;
            // @filename: /src/aug.ts
            import { seed } from "./base";
            declare module "fs" {
                export interface AugAdded { q?: number }
            }
            export const used = seed;
            // @filename: /src/consumer.ts
            import { OnlyAmbient } from "fs";
            export function probe(x: OnlyAmbient): number | undefined { return x.z; }
            """,
        )
        assert(diagnostics.none { it.code == 2664 })
        assert(diagnostics.none { it.code == 2339 })
    }
}
