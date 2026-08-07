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
import com.xemantic.kotlin.test.should
import org.intellij.lang.annotations.Language
import com.xemantic.kotlin.test.assert
import kotlin.test.Test

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
    private fun emit(@Language("typescript") consumer: String): String = emitProgram(
        """
        // @module: commonjs
        // @filename: enums.ts
        export const enum Kind { A = 0, B = 1 }
        export const enum Names { X = "x" }
        // @filename: barrel.ts
        export * from "./enums";
        // @filename: main.ts
        $consumer
        """
    )

    /** Emits a whole multi-file program and joins every JS output. */
    private fun emitProgram(@Language("typescript") source: String): String =
        TypeScriptCompiler().compile(source.trimIndent(), "main.ts")
            .jsOutputs.joinToString("\n") { it.second }

    @Test
    fun `a named import through a barrel inlines the const enum member`() {
        emit(
            """
            import { Kind } from "./barrel";
            export function p(k: Kind): number { return k === Kind.B ? 1 : 0; }
            """
        ) should {
            have("1 /* Kind.B */" in this)
            have("barrel_1.Kind" !in this)
        }
    }

    @Test
    fun `a namespace import through a barrel inlines the const enum member`() {
        emit(
            """
            import * as B from "./barrel";
            export function q(k: B.Kind): number { return k === B.Kind.A ? 1 : 0; }
            """
        ) should {
            have("0 /* B.Kind.A */" in this)
            have("B.Kind.A" !in substringAfter("0 /* B.Kind.A */"))
        }
    }

    @Test
    fun `a STRING-valued const enum through a barrel inlines too`() {
        emit(
            """
            import { Names } from "./barrel";
            export function s(): string { return Names.X; }
            """
        ) should {
            have("\"x\" /* Names.X */" in this)
        }
    }

    @Test
    fun `the barrel import is ELIDED once every member is inlined`() {
        emit(
            """
            import { Kind } from "./barrel";
            export function p(k: Kind): number { return k === Kind.B ? 1 : 0; }
            """
        ) should {
            // tsc emits no require and no interop helper for a const-enum-only import.
            have("require(\"./barrel\")" !in this)
            have("__importStar" !in this)
        }
    }

    @Test
    fun `a member reached through TWO chained barrels still inlines`() {
        emitProgram(
            """
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
            """
        ) should {
            have("1 /* Kind.B */" in this)
        }
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
        assert("\"bind\" /* tracing.Phase.Bind */" in js)
        // Inlined: no qualified access through the barrel may remain.
        assert("barrel_1.tracing" !in js)
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
        assert("\"check\"" in js)
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
        assert("\"bind\" /* tracing.Phase.Bind */" in js)
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
        // An instantiated namespace must keep its runtime access.
        assert("mixed" in js)
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
        // ...and no value may be invented where nothing resolves.
        assert("\"bind\"" !in js)
    }

    // ── Negative controls: a REGULAR enum must keep its runtime access ─────

    @Test
    fun `negative control - a NON-const enum through a barrel is NOT inlined`() {
        emitProgram(
            """
            // @module: commonjs
            // @filename: enums.ts
            export enum Plain { A = 0, B = 1 }
            // @filename: barrel.ts
            export * from "./enums";
            // @filename: main.ts
            import { Plain } from "./barrel";
            export function u(k: Plain): number { return k === Plain.B ? 1 : 0; }
            """
        ) should {
            have("1 /* Plain.B */" !in this)
            have("Plain" in this)
        }
    }

    @Test
    fun `negative control - preserveConstEnums keeps the runtime access`() {
        emitProgram(
            """
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
            """
        ) should {
            have("1 /* Kind.B */" !in this)
        }
    }
}
