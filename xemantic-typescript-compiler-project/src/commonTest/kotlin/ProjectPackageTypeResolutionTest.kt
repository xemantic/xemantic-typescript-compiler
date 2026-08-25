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

package com.xemantic.typescript.compiler.project

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.ProjectCompiler
import kotlin.test.Test

/**
 * (CHK.30) A type imported from a `node_modules` PACKAGE — a bare specifier —
 * silently resolved to `any`, and this class is the only instrument in the
 * repository that can see it.
 *
 * **WHAT WAS BROKEN.** The crawl resolves `import { V } from 'pkg'` correctly and the
 * package's `.d.ts` really is in the program; the CHECKER then re-derived "which file
 * does this specifier name" by string-matching the specifier against the program's own
 * file NAMES (`Checker.resolveModuleSpecifier` and its relative siblings). That matcher
 * is a corpus-era simplification and CANNOT express a bare specifier — a package's
 * `types` / `main` / `exports` entry is not a string transformation of `pkg` — so every
 * import alias into a package resolved to nothing and every type it named became `any`.
 * The fix carries the crawl's own `(importer, specifier) -> file` answers into the
 * checker (`ParsedSource.moduleResolutions`) as the last leg of each alias ladder.
 *
 * **WHY IT WAS INVISIBLE FOR SO LONG, AND WHY THE PIN IS SHAPED LIKE THIS.** `any` is
 * legal everywhere, so NOTHING moved at the import: no TS2307, no TS2305, no wrong
 * type — the whole defect showed up only as its false-positive SHADOW, a TS7006 on
 * every un-annotated callback parameter whose contextual type lived in the package
 * (89 of knip's 156 residual rows). A pin asserting only that silence would therefore
 * pass against a binary that had merely DISABLED the diagnostic, which is why every
 * test below asserts a diagnostic that must APPEAR: an excess property, a wrong
 * argument, a wrong return. Those can only be reported by a checker that genuinely
 * resolved the package's declarations.
 *
 * **THE `types` FIELD IS LOAD-BEARING IN THE FIXTURE.** `lib/index.d.ts` is reachable
 * ONLY through the crawl's answer: the checker's own `node_modules` walkers try
 * `<pkg>/index.d.ts` and `<pkg>.d.ts`, and the walker that reads a `package.json`
 * cannot see this one (a manifest is not an import target, so it never enters the
 * program's JSON contents). Move the declarations to `index.d.ts` and the test stops
 * discriminating.
 *
 * **EVERY STANDING GATE IS BLIND HERE**, exactly as `ProjectPackageJsonTypeTest`
 * records for its own family: the corpus harness materialises no directory and no
 * `node_modules`, and all eight dashboard profiles are tsc's own sources, which import
 * nothing from a package. A green corpus and a `+0.00%` cost gate are the EXPECTED
 * answers and neither is evidence.
 *
 * Expected values were read out of `tools/tsgo-7.0.2/lib/tsc` over the same fixture on
 * disk, which agrees with us row for row and column for column.
 */
class ProjectPackageTypeResolutionTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext",""" +
            """ "moduleResolution": "bundler", "strict": true, "noEmit": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    /** The package's declarations live at the path its `types` field names. */
    private val packageFiles = mapOf(
        "/proj/node_modules/pkg/package.json" to
            """{ "name": "pkg", "version": "1.0.0", "types": "lib/index.d.ts" }""",
        "/proj/node_modules/pkg/lib/index.d.ts" to """
            export interface N { kind: number }
            export interface V { m?: (n: N) => void }
            export declare function f(x: number): string;
        """.trimIndent() + "\n",
    )

    private fun codesOf(source: String): List<Int> {
        val files = packageFiles + mapOf(
            "/proj/tsconfig.json" to config,
            "/proj/src/a.ts" to source.trimIndent() + "\n",
        )
        return ProjectCompiler(InMemoryVfs(files))
            .build("/proj", noEmit = true)
            .diagnostics.map { it.code }.sorted()
    }

    @Test
    fun `an excess property against a package interface is reported`() {
        assert(
            codesOf(
                """
                import type { V } from "pkg";
                export const bad: V = { zzz: 1 };
                """,
            ) == listOf(2353),
        )
    }

    @Test
    fun `a wrong argument to a package function is reported`() {
        assert(
            codesOf(
                """
                import { f } from "pkg";
                export const s = f("not a number");
                """,
            ) == listOf(2345),
        )
    }

    @Test
    fun `a package function's return type is enforced`() {
        assert(
            codesOf(
                """
                import { f } from "pkg";
                export const n: number = f(1);
                """,
            ) == listOf(2322),
        )
    }

    /**
     * The false-positive shadow itself: an un-annotated object-literal member
     * parameter whose contextual type comes out of the package. Paired with the three
     * tests above, which is what stops this one from being satisfiable by silence.
     */
    @Test
    fun `an object-literal member parameter typed through a package import is silent`() {
        assert(
            codesOf(
                """
                import type { V } from "pkg";
                export const v: V = { m(node) { node; } };
                """,
            ).isEmpty(),
        )
    }

    /**
     * NEGATIVE CONTROL. A specifier that names no package must still resolve to
     * nothing — the fallback added here may only answer for a module the crawl (or a
     * `node_modules` walk) actually found. Both the TS2307 at the import and the
     * TS7006 the un-typed parameter earns are required: an implementation that
     * resolved a missing package to some arbitrary file would lose the first, and one
     * that suppressed TS7006 unconditionally would lose the second.
     */
    @Test
    fun `negative control - a specifier naming no package still resolves to nothing`() {
        assert(
            codesOf(
                """
                import type { V } from "no-such-package";
                export const v: V = { m(node) { node; } };
                """,
            ) == listOf(2307, 7006),
        )
    }
}
