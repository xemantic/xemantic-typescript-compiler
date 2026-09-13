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
 * (LEGACY.0b) — the LOGIC that `jsxRuntimePragma(jsx=react-jsxdev).js` used to pin, now
 * that its corpus baseline is switched off as a logical-parity divergence.
 *
 * **Why the baseline had to go.** Under `jsx: react-jsxdev` the transform hoists one
 * `const _jsxFileName = "<this file>"` per emitted module and threads it into every
 * `jsxDEV(...)` debug-info argument. tsgo's harness mounts a test's files on a virtual
 * filesystem rooted at `/.src/`, so ITS baseline records `"/.src/two.tsx"` where ours (and
 * tsc 6's, byte-for-byte, on the same case) records `"two.tsx"`. That prefix is a property
 * of tsgo's HARNESS, not of TypeScript 7: no `tsconfig`, no directive and no source text
 * produces it here, so following it is not an implementable row — it is a fact about where
 * someone else's test runner put the file. `docs/tsgo-baselines.md` § 3 calls this class
 * out by name and § 7 lists it as risk 2.
 *
 * **What is therefore pinned here instead** — everything the baseline actually tested about
 * the dev runtime, minus the root:
 *
 *  - the hoisted `_jsxFileName` binding exists, and carries THIS file's own name (a
 *    mistake that dropped the hoist, or emitted a constant, reddens this);
 *  - every `jsxDEV` call refers to it through that binding rather than repeating a literal,
 *    which is the reason the hoist exists at all;
 *  - the debug-info argument carries `fileName` / `lineNumber` / `columnNumber`, with the
 *    line number tracking the element's real position — so a wrong or frozen position fails
 *    rather than passing silently;
 *  - an `@jsxRuntime classic` block-comment pragma still overrides the option, i.e. the
 *    dev runtime is not emitted at all there (the case's `one.tsx`);
 *  - the NEGATIVE control: nothing anywhere in the emit carries a `/.src/` prefix. That is
 *    the assertion the divergence is really about, and it is also what the generator's
 *    generation-time guard enforces on the baseline side.
 *
 * The emitted JavaScript is the only channel any of this reaches — no diagnostic moves — so
 * the `--noEmit` 8-profile grid is structurally blind to every pin in this class.
 */
class JsxDevRuntimeFileNameTest {

    private fun emit(source: String, fileName: String = "two.tsx"): String =
        TypeScriptCompiler().compile(source.trimIndent(), fileName)
            .jsOutputs.joinToString("\n") { it.second }

    /** The corpus case's `two.tsx`, verbatim but for the `/// <reference>` line. */
    private val twoTsx = """
        // @jsx: react-jsxdev
        // @module: commonjs
        // @target: es2015
        /* @jsxRuntime automatic */
        export const HelloWorld = () => <h1>Hello world</h1>;
        export const frag = <><div></div></>;
        export const selfClosing = <img src="./image.png" />;
    """

    @Test
    fun `the dev runtime hoists _jsxFileName carrying this file's own name`() {
        val js = emit(twoTsx)
        assert("""const _jsxFileName = "two.tsx";""" in js)
    }

    @Test
    fun `the hoisted name follows the compiled file rather than being a constant`() {
        val js = emit(twoTsx, fileName = "three.tsx")
        assert("""const _jsxFileName = "three.tsx";""" in js)
        assert("two.tsx" !in js)
    }

    @Test
    fun `every jsxDEV debug argument refers to the hoisted binding, never a literal`() {
        val js = emit(twoTsx)
        // three elements plus the fragment's child = four dev calls in the case, each
        // reaching the name through the hoisted binding.
        assert(js.split("fileName: _jsxFileName").size - 1 == 4)
        assert(js.split("jsxDEV").size - 1 >= 4)
        // the literal appears exactly once: in the hoisted declaration
        assert(js.split("\"two.tsx\"").size - 1 == 1)
    }

    @Test
    fun `the debug argument carries the element's real line and column`() {
        val js = emit(twoTsx)
        // `HelloWorld` is on line 4 of the emitted module's source (the three `@`
        // directives are consumed by the harness, the pragma comment is line 1).
        assert("{ fileName: _jsxFileName, lineNumber: 2, columnNumber: 33 }" in js)
        assert("{ fileName: _jsxFileName, lineNumber: 3, columnNumber: 21 }" in js)
    }

    @Test
    fun `a classic pragma suppresses the dev runtime entirely`() {
        val js = emit(
            """
            // @jsx: react-jsxdev
            // @module: commonjs
            // @target: es2015
            /* @jsxRuntime classic */
            import * as React from "react";
            export const HelloWorld = () => <h1>Hello world</h1>;
            """,
            fileName = "one.tsx",
        )
        assert("React.createElement" in js)
        assert("_jsxFileName" !in js)
    }

    /**
     * NEGATIVE CONTROL, and the whole point of the divergence: tsgo's `/.src/` virtual root
     * is not reachable from any input we accept. If this ever fails, the divergence entry is
     * wrong and the baseline should come back rather than stay switched off.
     */
    @Test
    fun `negative control - no emitted path carries tsgo's virtual filesystem root`() {
        assert("/.src/" !in emit(twoTsx))
    }
}
