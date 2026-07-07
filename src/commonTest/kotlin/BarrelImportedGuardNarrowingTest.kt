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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the round-409 (M3.4) invariant: a user type-guard / assert imported
 * through an `export *` re-export barrel NARROWS. tsc's own sources import
 * everything via `import { isDefined, Debug, … } from "./_namespaces/ts.js"`
 * where the barrel is `export * from "../core.js"; export * from "../debug.js"`,
 * so the imported guard's declaration is only reachable by following the star
 * chain — `resolveAlias` now does (via [resolveExportedSymbolThroughStars]),
 * and the flow walkers resolve the guard's declaration to narrow.
 *
 * The signal is a downstream TS2345 (an argument that is only assignable once
 * the guard has narrowed the union): its ABSENCE proves narrowing fired, its
 * PRESENCE (the negative controls) proves the change never over-narrows.
 */
class BarrelImportedGuardNarrowingTest {

    private fun build(files: Map<String, String>): ProjectCompiler.Result {
        val vfs = InMemoryVfs(
            files + ("/proj/tsconfig.json" to
                """{ "compilerOptions": { "strict": true, "outDir": "./dist" }, "include": ["src/**/*.ts"] }""")
        )
        return ProjectCompiler(vfs).build("/proj", noEmit = true)
    }

    private fun ts2345(result: ProjectCompiler.Result): List<String> =
        result.diagnostics.filter { it.code == 2345 }.map { it.message }

    // A leaf module of type guards + a barrel re-exporting it wholesale — tsc's shape.
    private val guardsLeaf =
        """
        export function isString(x: string | number): x is string {
            return typeof x === "string";
        }
        export function isDefinedT<T>(x: T | undefined): x is T {
            return x !== undefined;
        }
        export function assertString(x: string | number): asserts x is string {}
        export function plain(x: string | number): boolean { return !!x; }
        """.trimIndent()

    @Test
    fun typeGuardImportedThroughABarrelNarrowsInTheThenBranch() {
        val result = build(
            mapOf(
                "/proj/src/guards.ts" to guardsLeaf,
                "/proj/src/barrel.ts" to """export * from "./guards.js";""",
                "/proj/src/index.ts" to """
                    import { isString } from "./barrel.js";
                    declare function takesString(s: string): void;
                    export function f(x: string | number): void {
                        if (isString(x)) {
                            takesString(x);
                        }
                    }
                """.trimIndent(),
            )
        )
        assertEquals(
            emptyList(),
            ts2345(result),
            "the barrel-imported guard must narrow `x` to string in the then-branch",
        )
    }

    @Test
    fun negativeTypeGuardImportedThroughABarrelNarrowsTheElseBranch() {
        val result = build(
            mapOf(
                "/proj/src/guards.ts" to guardsLeaf,
                "/proj/src/barrel.ts" to """export * from "./guards.js";""",
                "/proj/src/index.ts" to """
                    import { isString } from "./barrel.js";
                    declare function takesNumber(n: number): void;
                    export function f(x: string | number): void {
                        if (!isString(x)) {
                            takesNumber(x);
                        }
                    }
                """.trimIndent(),
            )
        )
        assertEquals(emptyList(), ts2345(result), "the else-branch must narrow `x` to number")
    }

    @Test
    fun genericGuardImportedThroughABarrelNarrowsAwayUndefined() {
        val result = build(
            mapOf(
                "/proj/src/guards.ts" to guardsLeaf,
                "/proj/src/barrel.ts" to """export * from "./guards.js";""",
                "/proj/src/index.ts" to """
                    import { isDefinedT } from "./barrel.js";
                    declare function takesString(s: string): void;
                    export function f(x: string | undefined): void {
                        if (isDefinedT(x)) {
                            takesString(x);
                        }
                    }
                """.trimIndent(),
            )
        )
        assertEquals(
            emptyList(),
            ts2345(result),
            "the generic `x is T` guard must strip undefined through the barrel",
        )
    }

    @Test
    fun assertImportedThroughABarrelNarrowsAfterTheCall() {
        val result = build(
            mapOf(
                "/proj/src/guards.ts" to guardsLeaf,
                "/proj/src/barrel.ts" to """export * from "./guards.js";""",
                "/proj/src/index.ts" to """
                    import { assertString } from "./barrel.js";
                    declare function takesString(s: string): void;
                    export function f(x: string | number): void {
                        assertString(x);
                        takesString(x);
                    }
                """.trimIndent(),
            )
        )
        assertEquals(
            emptyList(),
            ts2345(result),
            "the barrel-imported `asserts x is string` must narrow after the call",
        )
    }

    @Test
    fun multiHopBarrelChainNarrows() {
        val result = build(
            mapOf(
                "/proj/src/guards.ts" to guardsLeaf,
                "/proj/src/mid.ts" to """export * from "./guards.js";""",
                "/proj/src/top.ts" to """export * from "./mid.js";""",
                "/proj/src/index.ts" to """
                    import { isString } from "./top.js";
                    declare function takesString(s: string): void;
                    export function f(x: string | number): void {
                        if (isString(x)) {
                            takesString(x);
                        }
                    }
                """.trimIndent(),
            )
        )
        assertEquals(emptyList(), ts2345(result), "narrowing must follow a multi-hop `export *` chain")
    }

    @Test
    fun reExportSpecifierThroughABarrelNarrows() {
        // `export { isString as isStr } from "./guards.js"` — a renamed re-export.
        val result = build(
            mapOf(
                "/proj/src/guards.ts" to guardsLeaf,
                "/proj/src/barrel.ts" to """export { isString as isStr } from "./guards.js";""",
                "/proj/src/index.ts" to """
                    import { isStr } from "./barrel.js";
                    declare function takesString(s: string): void;
                    export function f(x: string | number): void {
                        if (isStr(x)) {
                            takesString(x);
                        }
                    }
                """.trimIndent(),
            )
        )
        assertEquals(emptyList(), ts2345(result), "a renamed re-export specifier must narrow too")
    }

    // A leaf module wrapping the guards in a namespace + a barrel re-exporting it —
    // tsc's `Debug.assertIsDefined` / `Debug.assert` shape (round 409 follow-up).
    private val nsGuardsLeaf =
        """
        export namespace Guard {
            export function isString(x: string | number): x is string {
                return typeof x === "string";
            }
            export function assertString(x: string | number): asserts x is string {}
        }
        """.trimIndent()

    @Test
    fun namespaceMemberAssertImportedThroughABarrelNarrows() {
        val result = build(
            mapOf(
                "/proj/src/guard.ts" to nsGuardsLeaf,
                "/proj/src/barrel.ts" to """export * from "./guard.js";""",
                "/proj/src/index.ts" to """
                    import { Guard } from "./barrel.js";
                    declare function takesString(s: string): void;
                    export function f(x: string | number): void {
                        Guard.assertString(x);
                        takesString(x);
                    }
                """.trimIndent(),
            )
        )
        assertEquals(
            emptyList(),
            ts2345(result),
            "a namespace-member `asserts` imported through a barrel must narrow",
        )
    }

    @Test
    fun namespaceMemberGuardImportedThroughABarrelNarrows() {
        val result = build(
            mapOf(
                "/proj/src/guard.ts" to nsGuardsLeaf,
                "/proj/src/barrel.ts" to """export * from "./guard.js";""",
                "/proj/src/index.ts" to """
                    import { Guard } from "./barrel.js";
                    declare function takesString(s: string): void;
                    export function f(x: string | number): void {
                        if (Guard.isString(x)) {
                            takesString(x);
                        }
                    }
                """.trimIndent(),
            )
        )
        assertEquals(
            emptyList(),
            ts2345(result),
            "a namespace-member type guard imported through a barrel must narrow",
        )
    }

    @Test
    fun negativeControlNonGuardCallDoesNotNarrow() {
        // `plain` returns a plain boolean, NOT a type predicate — the guard machinery
        // must NOT invent narrowing, so `takesString(x)` in its then-branch stays an
        // error. This proves the fix resolves the declaration but respects its signature.
        val result = build(
            mapOf(
                "/proj/src/guards.ts" to guardsLeaf,
                "/proj/src/barrel.ts" to """export * from "./guards.js";""",
                "/proj/src/index.ts" to """
                    import { plain } from "./barrel.js";
                    declare function takesString(s: string): void;
                    export function f(x: string | number): void {
                        if (plain(x)) {
                            takesString(x);
                        }
                    }
                """.trimIndent(),
            )
        )
        have(ts2345(result).any { it.contains("not assignable to parameter of type 'string'") })
    }

    @Test
    fun negativeControlErrorOutsideTheGuardStillFires() {
        // Sanity: the guard narrows INSIDE the branch only; the same call BEFORE the
        // guard must still error, proving we did not blanket-suppress the family.
        val result = build(
            mapOf(
                "/proj/src/guards.ts" to guardsLeaf,
                "/proj/src/barrel.ts" to """export * from "./guards.js";""",
                "/proj/src/index.ts" to """
                    import { isString } from "./barrel.js";
                    declare function takesString(s: string): void;
                    export function f(x: string | number): void {
                        takesString(x);
                        if (isString(x)) {}
                    }
                """.trimIndent(),
            )
        )
        have(ts2345(result).any { it.contains("not assignable to parameter of type 'string'") })
    }
}
