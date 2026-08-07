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
 * (REL.1)(c) round 749: the `import("<base>").<Name>` half of tsc's
 * `getFullyQualifiedName`, which is what let `checkEnumAsgInFunctionScopes` (B583) retire.
 *
 * A fully-qualified enum name walks the symbol's containers. Round 746 added the NAMESPACE
 * containers (`numerics.DiagnosticCategory`); this round adds the last one, the FILE:
 * a top-level EXPORTED enum of an external module is named through its module, because at
 * a position where its bare name has been shadowed there is no other way to say it.
 *
 * The pins split three ways, deliberately (round 745's placement rule):
 *
 *  - the RULE pins use containers B583 could not structurally reach — its shadow mapping
 *    lived only in `eafsScanIife`, so a plain function body, an arrow held by a `const`,
 *    and an assignment nested in an `if` were all invisible to it. They measure the general
 *    path, not the retirement, and they FAIL on a build of unmodified `d92ebe6a`;
 *  - the RETIREMENT pin is B583's own shape and lives in [EnumShadowedInFunctionScopeTest];
 *  - the CONTROLS pin the two gates. Without the `export`/top-level gate every enum of a
 *    module file would qualify, and without the collision gate (round 746's, reused
 *    unchanged) two differently-named enums would too — round 747 measured that exact
 *    mistake as a divergence when B463 qualified unconditionally.
 */
class EnumModuleQualifiedDisplayTest {

    /**
     * B583 shadow-mapped an inner enum ONLY inside an immediately-invoked arrow. A plain
     * function body is the same language rule and it could not answer there: it resolved
     * `y: DC` to the OUTER enum, found source and target identical, and stayed silent.
     */
    @Test
    fun `an enum shadowed inside a function body names the module scoped one through its module`() {
        val diagnostics = diagnose(
            """
            export enum DC { Warning, Error }
            export let x: DC;
            function outer() {
                enum DC { Warning = "Warning", Error = "Error" }
                function f(y: DC) {
                    x = y;
                    y = x;
                }
            }
            """,
        ).filter { it.code == 2322 }
        assert(diagnostics.size == 2)
        assert(diagnostics[0].message == "Type 'DC' is not assignable to type 'import(\"t\").DC'.")
        assert(
            diagnostics[0].messageChain == listOf(
                "  Each declaration of 'DC.Warning' differs in its value, " +
                    "where '0' was expected but '\"Warning\"' was given.",
            ),
        )
        assert(diagnostics[1].message == "Type 'import(\"t\").DC' is not assignable to type 'DC'.")
        assert(
            diagnostics[1].messageChain == listOf(
                "  Each declaration of 'DC.Warning' differs in its value, " +
                    "where '\"Warning\"' was expected but '0' was given.",
            ),
        )
    }

    /**
     * An arrow held by a `const` — never invoked, so not an IIFE. B583's top-level scan
     * looked at `FunctionDeclaration` and at an `ExpressionStatement` holding a call, so a
     * `VariableStatement` never entered it at all.
     */
    @Test
    fun `an enum shadowed inside an uninvoked arrow still qualifies the module scoped one`() {
        val diagnostics = diagnose(
            """
            export enum DC { Warning, Error }
            export let x: DC;
            const g = () => {
                enum DC { Warning = "Warning", Error = "Error" }
                function f(y: DC) {
                    x = y;
                }
            };
            """,
        ).filter { it.code == 2322 }
        assert(diagnostics.size == 1)
        assert(diagnostics[0].message == "Type 'DC' is not assignable to type 'import(\"t\").DC'.")
    }

    /**
     * The assignment nested one statement deeper than B583 walked — its body scan recursed
     * into a bare `Block` but into no other statement kind, so an `if` body was a dead end.
     */
    @Test
    fun `the qualification survives an assignment nested inside an if block`() {
        val diagnostics = diagnose(
            """
            export enum DC { Warning, Error }
            export let x: DC;
            (() => {
                enum DC { Warning = "Warning", Error = "Error" }
                function f(y: DC, flag: boolean) {
                    if (flag) {
                        x = y;
                    }
                }
            })()
            """,
        ).filter { it.code == 2322 }
        assert(diagnostics.size == 1)
        assert(diagnostics[0].message == "Type 'DC' is not assignable to type 'import(\"t\").DC'.")
    }

    /**
     * Control for the `export` gate. In tsc a symbol has a container only when it lives in
     * that container's `exports`, so a file-LOCAL enum of a module file has no module to be
     * named through and both sides keep the bare name — the same string on both, which is
     * what tsc prints once its retry cannot separate them either.
     */
    @Test
    fun `negative control - a non exported top level enum is not named through its module`() {
        val diagnostics = diagnose(
            """
            export const marker = 1;
            enum DC { Warning, Error }
            let x: DC;
            (() => {
                enum DC { Warning = "Warning", Error = "Error" }
                function f(y: DC) {
                    x = y;
                }
            })()
            """,
        ).filter { it.code == 2322 }
        assert(diagnostics.size == 1)
        assert(diagnostics[0].message == "Type 'DC' is not assignable to type 'DC'.")
    }

    /**
     * Control for the container walk's ORDER. A namespace container answers the question
     * first, so the file step is never taken — and the namespace here is itself file-local,
     * which is why tsc stops at `ns.DC` rather than continuing to `import("t").ns.DC`.
     */
    @Test
    fun `negative control - a namespace nested enum keeps its namespace path`() {
        val diagnostics = diagnose(
            """
            export const marker = 1;
            namespace ns { export enum DC { Warning, Error } }
            let x: ns.DC;
            (() => {
                enum DC { Warning = "Warning", Error = "Error" }
                function f(y: DC) {
                    x = y;
                }
            })()
            """,
        ).filter { it.code == 2322 }
        assert(diagnostics.size == 1)
        assert(diagnostics[0].message == "Type 'DC' is not assignable to type 'ns.DC'.")
    }

    /**
     * Control for round 746's collision gate, reused unchanged by this round. Two enums that
     * already print differently are never re-rendered, so the exported one stays `Outer`
     * rather than becoming `import("t").Outer`. Round 747 measured the unconditional form as
     * a real divergence from tsc, which is why the gate is load-bearing rather than an
     * optimisation.
     */
    @Test
    fun `negative control - two differently named enums keep their bare names`() {
        val diagnostics = diagnose(
            """
            export enum Outer { Warning, Error }
            export let x: Outer;
            (() => {
                enum Inner { Warning = "Warning", Error = "Error" }
                function f(y: Inner) {
                    x = y;
                }
            })()
            """,
        ).filter { it.code == 2322 }
        assert(diagnostics.size == 1)
        assert(diagnostics[0].message == "Type 'Inner' is not assignable to type 'Outer'.")
    }
}
