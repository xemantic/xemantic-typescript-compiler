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
 * EP.1 (round 669): a const enum reached through an `export * from` BARREL must
 * INLINE to its VALUE followed by a block comment naming the member (tsc's
 * form), exactly as for a direct import.
 *
 * The gap: both const-enum entry points ([Checker.resolveConstEnumMemberAccess]
 * and [Checker.isConstEnumAlias]) reach the enum via `resolveAlias` /
 * `resolveNamePath`, which walk `symbol.exports` — and a star re-export never
 * populates the barrel's own export table. So through a barrel the emit kept
 * `barrel_1.Kind.B`, retained a real `require("./barrel")`, and dragged in the
 * whole `__importStar` helper that tsc elides. That is tsc's own
 * `_namespaces/ts.js` layout, which is why round 483 first saw the symptom in
 * `utilities.js`.
 *
 * The corpus never exercises this shape (its const-enum emit tests are
 * single-file or direct-import), so these are the only pins for it.
 */
class BarrelConstEnumInliningTest {

    /** enums -> barrel (`export *`) -> consumer, emitted as CommonJS. */
    private fun emit(consumer: String): String {
        val source = """
            // @module: commonjs
            // @filename: enums.ts
            export const enum Kind { A = 0, B = 1 }
            export const enum Names { X = "x" }
            // @filename: barrel.ts
            export * from "./enums";
            // @filename: main.ts
            $consumer
        """.trimIndent()
        val out = TypeScriptCompiler().compile(source, "main.ts")
        return out.jsOutputs.joinToString("\n") { it.second }
    }

    @Test
    fun `a named import through a barrel inlines the const enum member`() {
        val js = emit(
            """
            import { Kind } from "./barrel";
            export function p(k: Kind): number { return k === Kind.B ? 1 : 0; }
            """.trimIndent()
        )
        assertContains(js, "1 /* Kind.B */")
        assertFalse("barrel_1.Kind" in js, "must not keep a qualified access, got:\n$js")
    }

    @Test
    fun `a namespace import through a barrel inlines the const enum member`() {
        val js = emit(
            """
            import * as B from "./barrel";
            export function q(k: B.Kind): number { return k === B.Kind.A ? 1 : 0; }
            """.trimIndent()
        )
        assertContains(js, "0 /* B.Kind.A */")
        assertFalse("B.Kind.A" in js.substringAfter("0 /* B.Kind.A */"), "no residual access")
    }

    @Test
    fun `a STRING-valued const enum through a barrel inlines too`() {
        val js = emit(
            """
            import { Names } from "./barrel";
            export function s(): string { return Names.X; }
            """.trimIndent()
        )
        assertContains(js, "\"x\" /* Names.X */")
    }

    @Test
    fun `the barrel import is ELIDED once every member is inlined`() {
        val js = emit(
            """
            import { Kind } from "./barrel";
            export function p(k: Kind): number { return k === Kind.B ? 1 : 0; }
            """.trimIndent()
        )
        // tsc emits no require and no interop helper for a const-enum-only import.
        assertFalse("require(\"./barrel\")" in js, "import must be elided, got:\n$js")
        assertFalse("__importStar" in js, "no interop helper for an elided import, got:\n$js")
    }

    @Test
    fun `a member reached through TWO chained barrels still inlines`() {
        val source = """
            // @module: commonjs
            // @filename: enums.ts
            export const enum Kind { A = 0, B = 1 }
            // @filename: barrel.ts
            export * from "./enums";
            // @filename: outer.ts
            export * from "./barrel";
            // @filename: main.ts
            import { Kind } from "./outer";
            export function t(k: Kind): number { return k === Kind.B ? 1 : 0; }
        """.trimIndent()
        val js = TypeScriptCompiler().compile(source, "main.ts").jsOutputs
            .joinToString("\n") { it.second }
        assertContains(js, "1 /* Kind.B */")
    }

    // ── EP.2e (round 677): the const enum nested in a NAMESPACE ────────────
    //
    // The barrel's star closure yields the NAMESPACE for the first path segment,
    // and the binder puts `SymbolFlags.ConstEnum` on a namespace holding only
    // const enums — so the flag test passed on the namespace while the
    // `enumValues` lookup (keyed by the ENUM's symbol id) missed, and the emit
    // kept `barrel_1.tracing.Phase.Bind`. This is tsc's own `tracing.Phase`
    // shape, the largest residual group in the emit-parity diff.

    private val nsEnums = """
        // @module: commonjs
        // @filename: tracing.ts
        export namespace tracing { export const enum Phase { Bind = "bind", Check = "check" } }
        // @filename: barrel.ts
        export * from "./tracing";
        // @filename: main.ts
    """.trimIndent() + "\n"

    @Test
    fun `a const enum nested in a NAMESPACE inlines through a barrel`() {
        val js = TypeScriptCompiler().compile(
            nsEnums + """
            import { tracing } from "./barrel";
            export function f() { return tracing.Phase.Bind; }
            """.trimIndent(),
            "main.ts",
        ).jsOutputs.joinToString("\n") { it.second }
        assertContains(js, "\"bind\" /* tracing.Phase.Bind */")
        assertFalse("barrel_1.tracing" in js, "must not keep a qualified access, got:\n$js")
    }

    @Test
    fun `a namespace-nested const enum inlines through a NAMESPACE import of the barrel`() {
        val js = TypeScriptCompiler().compile(
            nsEnums + """
            import * as B from "./barrel";
            export function f() { return B.tracing.Phase.Check; }
            """.trimIndent(),
            "main.ts",
        ).jsOutputs.joinToString("\n") { it.second }
        assertContains(js, "\"check\"")
    }

    @Test
    fun `a namespace-nested const enum still inlines on a DIRECT import`() {
        // Control: the direct path always worked — the descent must not break it.
        val js = TypeScriptCompiler().compile(
            """
            // @module: commonjs
            // @filename: tracing.ts
            export namespace tracing { export const enum Phase { Bind = "bind" } }
            // @filename: main.ts
            import { tracing } from "./tracing";
            export function f() { return tracing.Phase.Bind; }
            """.trimIndent(),
            "main.ts",
        ).jsOutputs.joinToString("\n") { it.second }
        assertContains(js, "\"bind\" /* tracing.Phase.Bind */")
    }

    @Test
    fun `negative control - a namespace holding a VALUE is not treated as a const enum`() {
        // The namespace is instantiated (it has a runtime export), so it carries
        // no ConstEnum flag and the access must survive.
        val js = TypeScriptCompiler().compile(
            """
            // @module: commonjs
            // @filename: ns.ts
            export namespace mixed { export const v = 1; export const enum P { A = 0 } }
            // @filename: barrel.ts
            export * from "./ns";
            // @filename: main.ts
            import { mixed } from "./barrel";
            export function f() { return mixed.v; }
            """.trimIndent(),
            "main.ts",
        ).jsOutputs.joinToString("\n") { it.second }
        assertTrue("mixed" in js, "an instantiated namespace must keep its access, got:\n$js")
    }

    @Test
    fun `negative control - a missing member under a namespace does not inline`() {
        val js = TypeScriptCompiler().compile(
            nsEnums + """
            import { tracing } from "./barrel";
            export function f() { return tracing.Phase; }
            """.trimIndent(),
            "main.ts",
        ).jsOutputs.joinToString("\n") { it.second }
        // `tracing.Phase` with no member selected names the enum itself — there is
        // no constant to inline, so nothing may be substituted.
        assertFalse("\"bind\"" in js, "must not invent a value, got:\n$js")
    }

    // ── Negative controls: a REGULAR enum must keep its runtime access ─────

    @Test
    fun `negative control - a NON-const enum through a barrel is NOT inlined`() {
        val source = """
            // @module: commonjs
            // @filename: enums.ts
            export enum Plain { A = 0, B = 1 }
            // @filename: barrel.ts
            export * from "./enums";
            // @filename: main.ts
            import { Plain } from "./barrel";
            export function u(k: Plain): number { return k === Plain.B ? 1 : 0; }
        """.trimIndent()
        val js = TypeScriptCompiler().compile(source, "main.ts").jsOutputs
            .joinToString("\n") { it.second }
        assertFalse("1 /* Plain.B */" in js, "a regular enum must keep its runtime access, got:\n$js")
        assertTrue("Plain" in js, "the enum reference must survive")
    }

    @Test
    fun `negative control - preserveConstEnums keeps the runtime access`() {
        val source = """
            // @module: commonjs
            // @preserveConstEnums: true
            // @isolatedModules: true
            // @filename: enums.ts
            export const enum Kind { A = 0, B = 1 }
            // @filename: barrel.ts
            export * from "./enums";
            // @filename: main.ts
            import { Kind } from "./barrel";
            export function v(k: Kind): number { return k === Kind.B ? 1 : 0; }
        """.trimIndent()
        val js = TypeScriptCompiler().compile(source, "main.ts").jsOutputs
            .joinToString("\n") { it.second }
        assertFalse("1 /* Kind.B */" in js, "isolatedModules must not inline, got:\n$js")
    }
}
