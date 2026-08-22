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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (INC.6) A `Readonly<T>` MEMBER MUST RENDER ITS DECLARED TYPE FROM INSIDE A
 * `namespace` BODY TOO — WHICH IS WHERE THE PREVIOUS ROUND'S FIX DID NOT REACH.
 *
 * ## What was still wrong after (INC.5)
 *
 * `materializeModifierUtility` builds `Readonly<T>` by minting a FRESH copy Symbol
 * per member, carrying the source's declarations. `typeToString` renders such a
 * member by reading `symbolTypes[id]` RAW and printing `any` when the entry is
 * absent, and the ONLY writer of that entry is [Checker.getTypeOfSymbol] — whose
 * write is gated on round 778's EMPTY instantiation context.
 *
 * So (INC.5)'s capture-time force, which asks `getTypeOfSymbol` and then lets
 * `typeToString` read the cache back, works exactly where the ambient context is
 * empty and is a NO-OP where it is not. Inside a `namespace` body there is a symbol
 * on `inferenceNamespaceStack`, the write is refused, and the member prints `any`
 * — the resolution having succeeded and been thrown away. Traced on tsc's own
 * `builderState.ts`: `getTypeOfSymbol` answered
 * `Map<string & { __pathBrand: any; }, FileInfo>` on every one of eight members and
 * `symbolTypes` held none of them.
 *
 * That is why this fixture puts the capture INSIDE a namespace: the same fixture
 * with the capture at file level is [CaptureDisplayResolutionTest]'s, and it was
 * already green.
 *
 * ## What the fix is, and why it is not the capture path
 *
 * The copy's type is now populated AT MINT TIME, ungated — the idiom
 * `resolveReferenceMembers` already uses for the symbols it mints itself. It is
 * sound where [Checker.getTypeOfSymbol]'s write is not, because the id was minted by
 * that same materialization: the copy is reachable only from the type just built, so
 * the value can never be read as another symbol's frozen type.
 *
 * ## Why BOTH arms are asserted
 *
 * This is a display defect, not a partition defect — the whole-program arm renders
 * `any` too, and it was only ever a `recheckOnly` sweep that made it visible,
 * because on tsc's sources some other file's check happened to warm one arm and not
 * the other. So every assertion is made against the narrowed build AND the
 * whole-program control, and the last test states the invariant directly.
 */
class CaptureModifierMemberDisplayTest {

    private val types = "export interface Info { fileName: string; count?: number }\n"

    /** `builderState.ts`'s shape: an interface merged with a namespace whose body
     *  takes a `Readonly<>` of it. */
    private val state =
        "import { Info } from \"./types.js\";\n" +
            "export interface State { fileInfos: Info; readonly extra?: Info | undefined }\n" +
            "export namespace State {\n" +
            "  export function create(s: Readonly<State>): void {}\n" +
            "}\n"

    /** The capture site is INSIDE a namespace body — see the class KDoc, this is the
     *  whole difference from the (INC.5) fixture. */
    private val caller =
        "import { State } from \"./state.js\";\n" +
            "export namespace Caller {\n" +
            "  export const q = State.create;\n" +
            "}\n"

    private val callerFile = "/work/caller.ts"

    private fun vfs() = InMemoryVfs(
        mapOf(
            "/work/tsconfig.json" to """{"compilerOptions":{"module":"esnext","target":"es2020"}}""",
            "/work/state.ts" to state,
            "/work/caller.ts" to caller,
            "/work/types.ts" to types,
        ),
    )

    /** Every identifier span of `caller.ts`, the population `Project` asks about. */
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

    /** The rendered type of the `State.create` reference in `export const q = …`. */
    private fun renderedCreateType(recheckOnly: Set<String>?): String {
        val result = ProjectCompiler(vfs()).build(
            "/work",
            noEmit = true,
            recheckOnly = recheckOnly,
            typeCapture = TypeCaptureRequest(callerSpans()),
        )
        val captured = result.capturedTypes.filter {
            it.fileName == callerFile && it.typeText.startsWith("(s:")
        }
        // The reference arm must be NON-EMPTY or every assertion below is vacuous.
        assert(captured.isNotEmpty())
        return captured.first().typeText
    }

    @Test
    fun `a narrowed build renders a Readonly member inside a namespace with its declared type`() {
        val narrowed = renderedCreateType(setOf(callerFile))
        assert("fileInfos: Info" in narrowed)
        assert("fileInfos: any" !in narrowed)
    }

    @Test
    fun `a narrowed build renders an OPTIONAL Readonly member with its declared type`() {
        val narrowed = renderedCreateType(setOf(callerFile))
        assert("extra?: Info" in narrowed)
        assert("extra?: any" !in narrowed)
    }

    @Test
    fun `negative control - the whole-program arm is not itself collapsed to any`() {
        // On the un-fixed compiler BOTH arms render `{ fileInfos: any; extra?: any | …`,
        // so an arms-agree assertion alone would pass vacuously. This is the assertion
        // that was measured red before the fix.
        val full = renderedCreateType(null)
        assert("fileInfos: Info" in full)
        assert("fileInfos: any" !in full)
    }

    @Test
    fun `the whole-program control renders the same string as the narrowed build`() {
        assert(renderedCreateType(null) == renderedCreateType(setOf(callerFile)))
    }

    @Test
    fun `the readonly mark still makes a write an error`() {
        // Populating the copy's type must not disturb what the copy is FOR: the
        // `readonly` modifier the wrapper supplies through `mappedReadonlyMemberIds`.
        diagnose(
            """
            interface Info { fileName: string }
            declare const r: Readonly<Info>;
            r.fileName = "x";
            """,
        ) should { have(any { it.code == 2540 }) }
    }

    @Test
    fun `negative control - a plain non-Readonly member stays writable`() {
        diagnose(
            """
            interface Info { fileName: string }
            declare const r: Info;
            r.fileName = "x";
            """,
        ) should { have(none { it.code == 2540 }) }
    }
}
