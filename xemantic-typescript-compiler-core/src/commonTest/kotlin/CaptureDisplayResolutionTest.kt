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
 * (INC.5) A CAPTURED TYPE'S DISPLAY STRING MUST BE A FUNCTION OF THE TYPE — NOT OF
 * WHICH FILE THE CHECKER HAPPENED TO WALK FIRST.
 *
 * ## What was wrong
 *
 * `typeToString`'s anonymous-object branch renders a property by reading
 * `symbolTypes[p.id]` RAW and printing `any` when that entry is absent. The two
 * utility-type materializers never populate it: `Pick`/`Omit` hand back the SOURCE
 * interface's own member symbols and `Readonly` hands back copies carrying the
 * source declarations, so in both cases the member's type is perfectly resolvable
 * from its declaration and has simply not been asked for yet. Whichever file's check
 * asks `getTypeOfSymbol` about that member first decides what every later hover
 * prints.
 *
 * A whole-program build usually asks, because it checks the declaring file. A build
 * narrowed by `recheckOnly` — what an incremental language-service query is — does
 * not. Measured on tsc's own 78 sources by `scripts/capture-equivalence.sh`: of
 * 381,666 captured spans, 45 in 11 files rendered differently between the two arms,
 * every one of them a member of a `Pick`/`Readonly`/`Required` shape collapsing to
 * `any`.
 *
 * ## What this pin holds, and why it is not a partition test
 *
 * The narrowed arm is the one that exposes it, but the claim is not about partitions:
 * it is that **both arms print the same string, and that string names the type**. So
 * the fixture is a three-file program of exactly the divergent shape — an interface
 * in one file, a `Pick`/`Readonly` of it in the signature of a function in a second,
 * and the capture in a third that has never seen the interface — and every assertion
 * is made against BOTH `recheckOnly = { caller }` and the whole-program control.
 *
 * On the un-fixed compiler the narrowed arm renders
 * `(file: { fileName: any; }, r: { fileName: any; count?: any | undefined; }) => void`
 * and every one of these assertions fails.
 *
 * The `probe` file is what makes the CONTROL arm meaningful: it reads `info.fileName`,
 * which is the ordinary check that warms `symbolTypes` for the interface's members.
 * Without it the whole-program arm collapses to `any` too — the two arms agree, and a
 * pin that only compared them would pass vacuously on a broken compiler.
 */
class CaptureDisplayResolutionTest {

    private val types = "export interface Info { fileName: string; count?: number }\n"

    /**
     * Sorts BEFORE `api.ts`, so on the whole-program arm this file's check has already
     * asked for `Info`'s member types by the time `api.ts`'s signature is built.
     */
    private val probe =
        "import { Info } from \"./types.js\";\n" +
            "export function read(info: Info): string { return info.fileName; }\n"

    private val api =
        "import { Info } from \"./types.js\";\n" +
            "export function make(file: Pick<Info, \"fileName\">, r: Readonly<Info>): void {}\n"

    private val caller =
        "import { make } from \"./api.js\";\n" +
            "export const q = make;\n"

    private val callerFile = "/work/caller.ts"

    private fun vfs() = InMemoryVfs(
        mapOf(
            "/work/tsconfig.json" to """{"compilerOptions":{"module":"esnext","target":"es2020"}}""",
            "/work/a_probe.ts" to probe,
            "/work/api.ts" to api,
            "/work/caller.ts" to caller,
            "/work/types.ts" to types,
        ),
    )

    /**
     * Every identifier span of `caller.ts`, by the population `Project` asks about.
     *
     * Iterative, like every full-tree walk in this repo, and the raw `(pos, end)` pair
     * is used purely as an IDENTITY — `Node.end` overshoots by a token (round 910) and
     * the capture matches on the same raw pair.
     */
    private fun callerSpans(): List<TypeCaptureSpan> {
        val file = Parser(caller, callerFile).parse()
        val spans = ArrayList<TypeCaptureSpan>()
        val stack = ArrayList<Node>()
        stack.add(file)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            if (node is Identifier) spans.add(TypeCaptureSpan(callerFile, node.pos, node.end))
            forEachChild(node) { child -> stack.add(child) }
        }
        return spans.distinct()
    }

    /** The rendered type of the `make` reference in `export const q = make`. */
    private fun renderedMakeType(recheckOnly: Set<String>?): String {
        val result = ProjectCompiler(vfs()).build(
            "/work",
            noEmit = true,
            recheckOnly = recheckOnly,
            typeCapture = TypeCaptureRequest(callerSpans()),
        )
        val captured = result.capturedTypes.filter {
            it.fileName == callerFile && it.typeText.startsWith("(file:")
        }
        assert(captured.isNotEmpty())
        return captured.first().typeText
    }

    @Test
    fun `a narrowed build renders a Pick member with its declared type`() {
        val narrowed = renderedMakeType(setOf(callerFile))
        assert("fileName: string" in narrowed)
        assert("fileName: any" !in narrowed)
    }

    @Test
    fun `a narrowed build renders a Readonly member with its declared type`() {
        val narrowed = renderedMakeType(setOf(callerFile))
        assert("count?: number | undefined" in narrowed)
        assert("count?: any | undefined" !in narrowed)
    }

    @Test
    fun `the whole-program control renders the same string as the narrowed build`() {
        // THE INVARIANT the whole item is about: the display is a function of the
        // TYPE, so the two arms may not disagree. Compared as strings — never as a
        // Type — for the power-assert renderer's sake.
        assert(renderedMakeType(null) == renderedMakeType(setOf(callerFile)))
    }

    @Test
    fun `negative control - the whole-program arm is not itself collapsed to any`() {
        // Without this the agreement above could be an agreement on the WRONG answer,
        // which is exactly what the same fixture measures with `a_probe.ts` removed.
        val full = renderedMakeType(null)
        assert("fileName: string" in full)
        assert("any" !in full)
    }
}
