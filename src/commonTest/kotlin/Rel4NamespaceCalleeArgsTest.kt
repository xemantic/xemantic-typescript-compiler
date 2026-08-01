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
 * (REL.4) round 782 — a call whose callee is a member of an IMPORTED NAMESPACE is
 * argument-checked.
 *
 * Round 766 isolated the blind spot: a same-file `namespace Debug` reached the
 * argument gate and a plain exported function imported from another file reached
 * it, but an EXPORTED namespace imported from another file did not — in the direct
 * form and the `export * from` barrel form alike. That is 1,127 `PropertyAccess`
 * callees on tsc's compiler profile and 1,551 on services whose arguments no
 * check ever saw, `Debug.assert` / `checkDefined` / `fail` / `assertNever` among
 * them.
 *
 * The cause is a PAIR of missing steps, each measurably inert alone:
 * `computeRawTypeOfPropertyAccess`'s namespace fallback fired only on a
 * `SymbolFlags.Module` receiver — which an import alias never carries — and
 * following the alias with the general `resolveAlias` resolves **0** of the
 * compiler profile's 4,383 alias receivers, because it cannot follow an ESM `.js`
 * specifier into an `export *` barrel and per round 409 must never be taught to
 * (the TS2315 flood). Only the barrel-aware `resolveImportedNamespaceSymbol`
 * binds them.
 *
 * Measured against a binary with `REL4_NS_ALIAS_RECEIVER` flipped to `false`, not
 * assumed. The positive pins discriminate BY MESSAGE, so a rule going silent
 * elsewhere cannot satisfy them; the controls fire (or stay silent) on BOTH
 * binaries and are what distinguish this fix from pre-existing behaviour.
 */
class Rel4NamespaceCalleeArgsTest {

    private val debugModule = """
        // @filename: debug.ts
        export namespace Debug {
            export function take(x: number): void {}
            export function fail(message: string): never { throw new Error(message); }
        }
    """.trimIndent() + "\n"

    private val barrel = """
        // @filename: _namespaces.ts
        export * from "./debug";
    """.trimIndent() + "\n"

    /** DISCRIMINATES BY MESSAGE — nothing at all is reported without the flip. */
    @Test
    fun `an argument to a directly imported namespace member is checked`() {
        val diagnostics = diagnose(
            debugModule + """
                // @filename: main.ts
                import { Debug } from "./debug";
                export function f(): void { Debug.take("not a number"); }
            """.trimIndent(),
            "// @module: commonjs",
        )
        assert(diagnostics.any {
            it.code == 2345 &&
                it.message ==
                "Argument of type 'string' is not assignable to parameter of type 'number'."
        })
    }

    /**
     * DISCRIMINATES BY MESSAGE — the `export * from` BARREL form, which is what
     * tsc's own `_namespaces/ts.js` is and what the general `resolveAlias`
     * deliberately cannot follow.
     */
    @Test
    fun `an argument to a namespace member imported through a star-export barrel is checked`() {
        val diagnostics = diagnose(
            debugModule + barrel + """
                // @filename: main.ts
                import { Debug } from "./_namespaces";
                export function f(): void { Debug.take("not a number"); }
            """.trimIndent(),
            "// @module: commonjs",
        )
        assert(diagnostics.any {
            it.code == 2345 &&
                it.message ==
                "Argument of type 'string' is not assignable to parameter of type 'number'."
        })
    }

    /**
     * DISCRIMINATES — ARITY is checked too, not only argument types, which shows the
     * whole signature machinery is reached rather than one assignability arm.
     */
    @Test
    fun `an arity error on an imported namespace member is reported`() {
        val diagnostics = diagnose(
            debugModule + """
                // @filename: main.ts
                import { Debug } from "./debug";
                export function f(): void { Debug.take(1, 2); }
            """.trimIndent(),
            "// @module: commonjs",
        )
        assert(diagnostics.any { it.code == 2554 })
    }

    // --- negative controls ----------------------------------------------------

    /**
     * NEGATIVE CONTROL — a CORRECT argument must stay silent. Without it, the pins
     * above would also be satisfied by a flip that simply started rejecting every
     * namespace-member call.
     */
    @Test
    fun `negative control - a correct argument to an imported namespace member is silent`() {
        val diagnostics = diagnose(
            debugModule + """
                // @filename: main.ts
                import { Debug } from "./debug";
                export function f(): void { Debug.take(42); }
            """.trimIndent(),
            "// @module: commonjs",
        )
        assert(diagnostics.none { it.code == 2345 || it.code == 2554 })
    }

    /**
     * NEGATIVE CONTROL — a plain imported FUNCTION was already argument-checked
     * (round 766 measured exactly this asymmetry), so this pin FIRES on both
     * binaries. It is what makes the pins above readable as "the namespace-alias
     * receiver was the gap" rather than as "cross-file argument checking arrived".
     */
    @Test
    fun `negative control - a plain imported function was already checked`() {
        val diagnostics = diagnose(
            """
                // @filename: plain.ts
                export function take(x: number): void {}
            """.trimIndent() + "\n" + """
                // @filename: main.ts
                import { take } from "./plain";
                export function f(): void { take("not a number"); }
            """.trimIndent(),
            "// @module: commonjs",
        )
        assert(diagnostics.any {
            it.code == 2345 &&
                it.message ==
                "Argument of type 'string' is not assignable to parameter of type 'number'."
        })
    }

    /**
     * NEGATIVE CONTROL — a SAME-FILE namespace member was already checked (round
     * 766's other half), so this also fires on both binaries.
     */
    @Test
    fun `negative control - a same-file namespace member was already checked`() {
        val diagnostics = diagnose(
            """
                namespace Local {
                    export function take(x: number): void {}
                }
                export function f(): void { Local.take("not a number"); }
            """.trimIndent()
        )
        assert(diagnostics.any {
            it.code == 2345 &&
                it.message ==
                "Argument of type 'string' is not assignable to parameter of type 'number'."
        })
    }

    /**
     * NEGATIVE CONTROL — an import alias that is NOT a namespace must not acquire a
     * namespace member type. A property access on an imported plain function still
     * resolves to nothing checkable, so no TS2339 may be invented here: the flip
     * only ever ADDS a resolution where the receiver really is a namespace.
     */
    @Test
    fun `negative control - a non-namespace import alias receiver invents nothing`() {
        val diagnostics = diagnose(
            """
                // @filename: plain.ts
                export function take(x: number): void {}
            """.trimIndent() + "\n" + """
                // @filename: main.ts
                import { take } from "./plain";
                export const n: number = take.length;
            """.trimIndent(),
            "// @module: commonjs",
        )
        assert(diagnostics.none { it.code == 2339 })
    }
}
