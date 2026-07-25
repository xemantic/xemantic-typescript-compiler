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
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * EP.2g (round 678): a const enum reached through a VARIABLE whose declared type
 * is `typeof <namespace>` must inline.
 *
 * tsc's own tracing.ts is the shape:
 *
 *     export namespace tracingEnabled { export const enum Phase { Bind = "bind" } }
 *     export let tracing: typeof tracingEnabled | undefined;
 *
 * and every call site writes `tracing?.push(tracing.Phase.Bind, …)`. The
 * receiver is a runtime VARIABLE, not a namespace, so name resolution stopped
 * there and the member never inlined; tsc reaches the enum through the
 * variable's declared TYPE. This was the entire residual const-enum gap after
 * round 677 — 34 reads across seven files.
 *
 * The variable keeps its runtime identity: only the ENUM MEMBER is substituted,
 * the receiver's own access and its import must survive. Those are the negative
 * controls below, and they are the ones that matter — over-eager elision here
 * would delete a real binding.
 */
class TypeofNamespaceConstEnumTest {

    /** tsc's exact layout: namespace + `typeof` variable, behind a star barrel. */
    private val prelude = """
        // @module: commonjs
        // @filename: tracing.ts
        export namespace tracingEnabled {
            export const enum Phase { Parse = "parse", Bind = "bind" }
            export function push(p: Phase, s: string): void {}
        }
        export let tracing: typeof tracingEnabled | undefined;
        // @filename: barrel.ts
        export * from "./tracing";
        // @filename: main.ts
    """.trimIndent() + "\n"

    private fun emit(main: String): String =
        TypeScriptCompiler().compile(prelude + main.trimIndent(), "main.ts")
            .jsOutputs.joinToString("\n") { it.second }

    @Test
    fun `a const enum behind a typeof-namespace variable inlines through a barrel`() {
        val js = emit(
            """
            import { tracing } from "./barrel";
            export function f() { tracing?.push(tracing.Phase.Bind, "x"); }
            """
        )
        assertContains(js, "\"bind\" /* tracing.Phase.Bind */")
    }

    @Test
    fun `the same shape inlines on a DIRECT import`() {
        val js = TypeScriptCompiler().compile(
            """
            // @module: commonjs
            // @filename: tracing.ts
            export namespace tracingEnabled {
                export const enum Phase { Bind = "bind" }
                export function push(p: Phase): void {}
            }
            export let tracing: typeof tracingEnabled | undefined;
            // @filename: main.ts
            import { tracing } from "./tracing";
            export function f() { tracing?.push(tracing.Phase.Bind); }
            """.trimIndent(),
            "main.ts",
        ).jsOutputs.joinToString("\n") { it.second }
        assertContains(js, "\"bind\" /* tracing.Phase.Bind */")
    }

    @Test
    fun `a NON-optional typeof variable works too`() {
        val js = emit(
            """
            import { tracingEnabled } from "./barrel";
            declare const t: typeof tracingEnabled;
            export function f() { return t.Phase.Parse; }
            """
        )
        assertContains(js, "\"parse\"")
    }

    // ── negative controls: the VARIABLE is real runtime state ──────────────

    @Test
    fun `the receiver's own access survives — only the member is substituted`() {
        val js = emit(
            """
            import { tracing } from "./barrel";
            export function f() { tracing?.push(tracing.Phase.Bind, "x"); }
            """
        )
        assertTrue("tracing?.push" in js, "the receiver call must survive, got:\n$js")
    }

    @Test
    fun `the import of a typeof-namespace VARIABLE is NOT elided`() {
        // The variable is a real binding — eliding it would break the emit.
        val js = emit(
            """
            import { tracing } from "./barrel";
            export function f() { tracing?.push(tracing.Phase.Bind, "x"); }
            """
        )
        assertTrue("require(\"./barrel\")" in js, "a real binding's import must survive, got:\n$js")
    }

    @Test
    fun `negative control - a NON-const enum behind the same shape is not inlined`() {
        val js = TypeScriptCompiler().compile(
            """
            // @module: commonjs
            // @filename: t.ts
            export namespace nsEnabled { export enum Phase { Bind = "bind" } }
            export let ns: typeof nsEnabled | undefined;
            // @filename: main.ts
            import { ns } from "./t";
            export function f() { return ns?.Phase.Bind; }
            """.trimIndent(),
            "main.ts",
        ).jsOutputs.joinToString("\n") { it.second }
        assertFalse("\"bind\" /* ns.Phase.Bind */" in js, "a regular enum keeps its access, got:\n$js")
    }

    @Test
    fun `negative control - a variable typed as a plain interface resolves nothing`() {
        val js = TypeScriptCompiler().compile(
            """
            // @module: commonjs
            // @filename: t.ts
            export interface Holder { Phase: string }
            export declare const h: Holder;
            // @filename: main.ts
            import { h } from "./t";
            export function f() { return h.Phase; }
            """.trimIndent(),
            "main.ts",
        ).jsOutputs.joinToString("\n") { it.second }
        assertTrue("h.Phase" in js, "an ordinary property access must survive, got:\n$js")
    }
}
