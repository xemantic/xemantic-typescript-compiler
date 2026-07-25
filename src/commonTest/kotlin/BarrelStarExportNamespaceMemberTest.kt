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

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * EP.1a (round 668): a namespace import of a BARREL — `import * as B from
 * "./barrel"` where the barrel is `export * from "./enums"` — must resolve
 * `B.Member` in TYPE position.
 *
 * The bug: [Checker.checkQualifiedNameExports] looks the final segment up in
 * `symbol.exports`, which an `export * from` never populates (a star re-export
 * adds nothing to the barrel's own export table), so valid TypeScript drew
 * TS2694 *"Namespace '"x".B' has no exported member 'Kind'"*. That shape is
 * exactly tsc's own `_namespaces/ts.js` layout, and it was found while
 * triaging EP.1's stale premise (round 667).
 *
 * The fix is SUPPRESSION-ONLY — it consults
 * `getModuleExportsFollowingStars` and can only withhold an emission, never
 * produce one, and it resolves no types. That distinction is load-bearing:
 * star-following inside the general `resolveAlias` is a documented dead-end
 * (it flooded TS2315 ×466 on the self-compile by resolving barrel-imported
 * TYPES and arity-checking them). So the negative controls below matter as
 * much as the positive ones — a genuinely absent member must still report.
 */
class BarrelStarExportNamespaceMemberTest {

    private val enums = """
        // @filename: enums.ts
        export const enum Kind { A = 0, B = 1 }
        export interface Shape { kind: Kind }
    """.trimIndent() + "\n"

    private val barrel = """
        // @filename: barrel.ts
        export * from "./enums";
    """.trimIndent() + "\n"

    private fun ts2694(ds: List<Diagnostic>) = ds.count { it.code == 2694 }

    @Test
    fun `a const enum reached through a star-export barrel resolves in type position`() {
        val ds = diagnose(
            enums + barrel + """
            // @filename: main.ts
            import * as B from "./barrel";
            export function q(k: B.Kind): number { return k === B.Kind.A ? 1 : 0; }
            """.trimIndent(),
            "// @module: commonjs",
        )
        assertEquals(0, ts2694(ds))
    }

    @Test
    fun `an interface reached through a star-export barrel resolves in type position`() {
        val ds = diagnose(
            enums + barrel + """
            // @filename: main.ts
            import * as B from "./barrel";
            export function r(s: B.Shape): number { return s.kind; }
            """.trimIndent(),
            "// @module: commonjs",
        )
        assertEquals(0, ts2694(ds))
    }

    @Test
    fun `a member reached through TWO chained barrels still resolves`() {
        val ds = diagnose(
            enums + barrel + """
            // @filename: outer.ts
            export * from "./barrel";
            // @filename: main.ts
            import * as O from "./outer";
            export function s(k: O.Kind): number { return k; }
            """.trimIndent(),
            "// @module: commonjs",
        )
        assertEquals(0, ts2694(ds))
    }

    // ── Negative controls: the suppression must not silence real absences ──

    @Test
    fun `negative control - a member absent from the whole barrel chain still reports TS2694`() {
        val ds = diagnose(
            enums + barrel + """
            // @filename: main.ts
            import * as B from "./barrel";
            export function t(x: B.NotThere): number { return 0; }
            """.trimIndent(),
            "// @module: commonjs",
        )
        assertEquals(1, ts2694(ds))
    }

    @Test
    fun `negative control - a member absent from a direct (non-barrel) namespace import still reports`() {
        val ds = diagnose(
            enums + """
            // @filename: main.ts
            import * as E from "./enums";
            export function u(x: E.Missing): number { return 0; }
            """.trimIndent(),
            "// @module: commonjs",
        )
        assertEquals(1, ts2694(ds))
    }

    @Test
    fun `a direct namespace import of a real member is unaffected`() {
        val ds = diagnose(
            enums + """
            // @filename: main.ts
            import * as E from "./enums";
            export function v(k: E.Kind): number { return k; }
            """.trimIndent(),
            "// @module: commonjs",
        )
        assertEquals(0, ts2694(ds))
    }
}
