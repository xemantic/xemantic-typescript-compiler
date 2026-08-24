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
import com.xemantic.typescript.compiler.KeyofCycleCensus
import kotlin.test.Test

/**
 * (INC.25) `keyof` OVER A TYPE WHOSE MEMBER TABLE IS IN FLIGHT MUST ANSWER FROM THE
 * DECLARATIONS — pinned on the LIB shape that ships the defect.
 *
 * `interface Array<T> { readonly [Symbol.unscopables]: { [K in keyof any[]]?: boolean } }`
 * is a cycle: resolving an array reference's member table asks for that member's
 * type, whose `keyof any[]` asks back for the very table being built.
 * `resolveStructuredTypeMembersCore` returned silently leaving `properties` null,
 * `getKeyofType` read that as `string`, the mapped type degraded to `any`, and round
 * 778's write gate froze the `any` into `symbolTypes` — the ambient instantiation
 * context is empty throughout, so nothing refused the write.
 *
 * **THIS IS A SHIPPED-DEFAULT DEFECT AND THE PIN INSTALLS NO MODE.** (INC.23) found
 * it while censusing `FltmDefer.Scope.PARTITION`, but the arm is not what produces
 * it: measured on a three-line project with no partition, an ordinary build rendered
 * `[Symbol.unscopables]: any`. The tsc profiles hide it only because
 * `init:buildFileLocalTypeMaps` happens to resolve that member first, from a place
 * where the table is NOT in flight.
 *
 * **NOTHING ELSE IN THIS REPO SEES IT.** No diagnostic moves, no emitted byte moves
 * and no `cost_gate.py` counter moves — this is (INC.2)'s law, that the capture
 * channel is a different resolver from the diagnostics one, so the ~13k corpus
 * baselines and all eight profiles are green with the defect in place. A completion
 * is the instrument.
 *
 * The counter assertion is the other half: a pin on a rendered string alone could
 * pass over an EMPTY population if some future change made the cycle unreachable,
 * and this fixture would then be pinning nothing (CLAUDE.md, round 794).
 */
class ProjectKeyofCycleTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val mainFile = "/proj/src/a.ts"
    private val otherFile = "/proj/src/b.ts"

    /**
     * An ordinary array receiver. `nums.length` is what makes the program resolve
     * `Array<number>`'s member table at all — with no member access anywhere the
     * cycle is never entered and every assertion below would be vacuous.
     */
    private val main = """
        import { other } from "./b";
        export const nums: number[] = [];
        export const first = nums.length;
        export const keep = other;
    """.trimIndent() + "\n"

    private val other = """
        export const other = 7;
    """.trimIndent() + "\n"

    private fun projectWith(): Project = Project.open(
        "/proj",
        InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to config,
                mainFile to main,
                otherFile to other,
            ),
        ),
    )

    /** The caret immediately after the `.` of the first `<receiver>.` in [access]. */
    private fun afterDotOf(access: String): Int =
        main.indexOf(access) + access.indexOf('.') + 1

    /**
     * The mapped type the lib declares, materialized over the whole key set of
     * `any[]` at `target: es2020`. Asserted WHOLE: the defect this pins renders the
     * single token `any` there, and a containment assertion over one member name
     * would also pass for a partially-enumerated key domain, which round 463 records
     * as a wrong answer rather than a coarse one.
     */
    private val unscopablesType =
        "{ length?: boolean | undefined; toString?: boolean | undefined; toLocaleString?: boolean " +
            "| undefined; pop?: boolean | undefined; push?: boolean | undefined; concat?: boolean " +
            "| undefined; join?: boolean | undefined; reverse?: boolean | undefined; shift?: boolean " +
            "| undefined; slice?: boolean | undefined; sort?: boolean | undefined; splice?: boolean " +
            "| undefined; unshift?: boolean | undefined; indexOf?: boolean | undefined; lastIndexOf?: " +
            "boolean | undefined; every?: boolean | undefined; some?: boolean | undefined; forEach?: " +
            "boolean | undefined; map?: boolean | undefined; filter?: boolean | undefined; reduce?: " +
            "boolean | undefined; reduceRight?: boolean | undefined; find?: boolean | undefined; " +
            "findIndex?: boolean | undefined; fill?: boolean | undefined; copyWithin?: boolean | " +
            "undefined; [Symbol.iterator]?: boolean | undefined; entries?: boolean | undefined; keys?: " +
            "boolean | undefined; values?: boolean | undefined; [Symbol.unscopables]?: boolean | " +
            "undefined; includes?: boolean | undefined; flatMap?: boolean | undefined; flat?: boolean " +
            "| undefined; }"

    @Test
    fun `the lib mapped type over keyof any array does not degrade to any`() {
        KeyofCycleCensus.reset()
        val project = projectWith()
        val items = project.completionsAt(mainFile, afterDotOf("nums.length")).items
        val unscopables = items.filter { it.name == "[Symbol.unscopables]" }
        assert(unscopables.size == 1)
        assert(unscopables[0].typeText == unscopablesType)
        // The population is non-empty: the repaired path was REACHED on this build.
        assert(KeyofCycleCensus.answeredFromDeclarations > 0)
    }
}
