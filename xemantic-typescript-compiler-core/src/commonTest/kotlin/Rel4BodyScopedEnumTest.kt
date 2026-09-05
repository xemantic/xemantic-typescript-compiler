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
 * (REL.4)(a) round 782 — an `enum` declared inside a FUNCTION BODY narrows like any
 * other enum.
 *
 * B83.5: the binder never binds a block-scoped declaration, so such an enum is in
 * neither the file's locals nor a namespace's exports — the only two scopes
 * `resolveEnumSymbolForDiscriminant` knew about (file level round 425, enclosing
 * namespace round 769). It therefore answered null for the name, which blinds EVERY
 * narrowing direction at once: it is the enum's DECLARATION site that decides, not
 * the code's. tsc's own `projectServiceStateLogger.ts:412` is exactly this — a
 * 4-member `enum PrintPropertyWhen` declared inside `patchServiceForStateBaseline`,
 * switched on exhaustively in a sibling nested function, with
 * `Debug.assertNever(printWhen)` in the `default:`.
 *
 * The resolution goes FIRST rather than last because `resolveTypeNameToSymbol`
 * already prefers the scope-space symbol (round 748) — a discriminant reader
 * preferring the file-level answer for a SHADOWING name would key one enum through
 * two `Symbol` instances, which is the round-425 split key space.
 *
 * **EVERY SUBJECT HERE IS A PARAMETER, and that is not cosmetic.** The obvious probe
 * — `const k = k0 as unknown as E` — is silent at the argument gate for a FILE-LEVEL
 * enum too, so pins written that way pass on a broken build for a reason that has
 * nothing to do with this fix (measured: two of this class's first-draft pins did
 * exactly that).
 *
 * Measured against a binary with `R782_LEXICAL_ENUM_DISCRIMINANT` flipped to
 * `false`, not assumed. The discriminating pins prefer MESSAGE over silence — the
 * message names what the narrowing left behind, so no unrelated rule going quiet
 * can satisfy them.
 */
class Rel4BodyScopedEnumTest {

    private val assertNever = "declare function assertNever(x: never): never;\n"

    /**
     * DISCRIMINATES — TS2345 naming the whole enum `'BodyScoped'` without the fix.
     * The tsc shape exactly: the enum is declared in the OUTER function body and
     * switched on inside a SIBLING nested function.
     */
    @Test
    fun `an exhaustive switch on a function-body-scoped enum narrows its default to never`() {
        val diagnostics = diagnose(
            assertNever + """
                export function outer(): void {
                    enum BodyScoped { Always, Truthy, Changed, Defined }
                    function inner(w: BodyScoped, v: unknown): number {
                        switch (w) {
                            case BodyScoped.Always: break;
                            case BodyScoped.Truthy: if (!v) return 1; break;
                            case BodyScoped.Changed: if (!v) return 2; break;
                            case BodyScoped.Defined: if (v === undefined) return 3; break;
                            default: assertNever(w);
                        }
                        return 0;
                    }
                    inner(BodyScoped.Always, 1);
                }
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2345 })
    }

    /**
     * DISCRIMINATES BY MESSAGE — the ablated build names the whole enum
     * `'BodyScoped'`, the fixed one names exactly the two UNCOVERED members. That is
     * what proves the subtraction ran, rather than some other rule going silent.
     */
    @Test
    fun `a partial switch on a function-body-scoped enum reports only the uncovered members`() {
        val diagnostics = diagnose(
            assertNever + """
                export function outer(): void {
                    enum BodyScoped { A, B, C }
                    function inner(k: BodyScoped): number {
                        switch (k) {
                            case BodyScoped.A: return 1;
                            default: return assertNever(k) as number;
                        }
                    }
                    inner(BodyScoped.A);
                }
            """.trimIndent()
        )
        assert(diagnostics.any {
            it.code == 2345 &&
                it.message ==
                "Argument of type 'BodyScoped.B | BodyScoped.C' is not assignable to parameter of type 'never'."
        })
    }

    /**
     * DISCRIMINATES — the enum declared inside a nested BLOCK rather than directly in
     * the function body. The ancestor walk has to climb through block scopes, not
     * only function ones.
     */
    @Test
    fun `an enum declared in a nested block of a function body narrows too`() {
        val diagnostics = diagnose(
            assertNever + """
                export function outer(): void {
                    {
                        enum Deep { X, Y }
                        function inner(k: Deep): string {
                            switch (k) {
                                case Deep.X: return "x";
                                case Deep.Y: return "y";
                                default: return assertNever(k);
                            }
                        }
                        inner(Deep.X);
                    }
                }
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2345 })
    }

    /**
     * DISCRIMINATES BY MESSAGE — a positive equality guard subtracts its member, so
     * the fall-through reports only `'G.Q'`; without the fix it reports the whole
     * enum `'G'`. Narrowing a body-scoped enum is not switch-specific.
     */
    @Test
    fun `an equality guard on a function-body-scoped enum subtracts the guarded member`() {
        val diagnostics = diagnose(
            assertNever + """
                export function outer(): void {
                    enum G { P, Q }
                    function inner(k: G): number {
                        if (k === G.P) return 1;
                        return assertNever(k) as number;
                    }
                    inner(G.P);
                }
            """.trimIndent()
        )
        assert(diagnostics.any {
            it.code == 2345 &&
                it.message ==
                "Argument of type 'G.Q' is not assignable to parameter of type 'never'."
        })
    }

    // --- negative controls ----------------------------------------------------

    /**
     * NEGATIVE CONTROL — a FILE-LEVEL enum already narrowed before this round, so
     * this pin holds on BOTH binaries. It is what makes the pins above readable as
     * "the function-body scope space was the gap" rather than as "enum switch
     * narrowing arrived here".
     */
    @Test
    fun `negative control - a file-level enum switch already narrowed`() {
        val diagnostics = diagnose(
            assertNever + """
                enum FileLevel { A, B, C }
                export function f(k: FileLevel): string {
                    switch (k) {
                        case FileLevel.A: return "a";
                        case FileLevel.B: return "b";
                        case FileLevel.C: return "c";
                        default: return assertNever(k);
                    }
                }
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2345 })
    }

    /**
     * CONTROL **AND** DISCRIMINATOR — an INCOMPLETE switch must still REJECT, which
     * is what proves the fix did not simply make a `never` parameter accept
     * everything; TS2345 fires on BOTH binaries. It nonetheless fails ablated,
     * because the assertion is on the MESSAGE: the fixed build names the one member
     * the cases left uncovered (`'N.B'`), the ablated one the whole enum (`'N'`).
     */
    @Test
    fun `negative control - an incomplete body-scoped switch still rejects`() {
        val diagnostics = diagnose(
            assertNever + """
                export function outer(): void {
                    enum N { A, B }
                    function inner(k: N): number {
                        switch (k) {
                            case N.A: return 1;
                            default: return assertNever(k) as number;
                        }
                    }
                    inner(N.A);
                }
            """.trimIndent()
        )
        assert(diagnostics.any {
            it.code == 2345 &&
                it.message ==
                "Argument of type 'N.B' is not assignable to parameter of type 'never'."
        })
    }

    /**
     * NEGATIVE CONTROL — a body-scoped enum member still assigns to its own enum
     * type. The scope-space resolution must not have minted a second identity for
     * the enum; a round-425-style split would surface here as a spurious TS2322.
     */
    @Test
    fun `negative control - a body-scoped enum member assigns to its own enum type`() {
        val diagnostics = diagnose(
            """
                export function outer(): number {
                    enum V { A, B }
                    const a: V = V.A;
                    const b: V = V.B;
                    return a === b ? 0 : 1;
                }
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
        // (CHK.85)(b): an annotated `const a: V = V.A` is flow-REDUCED to `V.A` (tsc's
        // `getAssignmentReducedType`), so `a === b` IS an unintentional comparison in
        // both references — measured byte-identical against tsgo 7.0.2 and pristine
        // 6.0.3 for this fixture and its file-level twin. The control's `isEmpty()`
        // predated that read; what it guards (no spurious TS2322 from a split enum
        // identity) is the assertion above, and the row's DISPLAY is the identity
        // control now: a split would name two different enums.
        assert(diagnostics.map { it.message } == listOf(
            "This comparison appears to be unintentional because the types 'V.A' and 'V.B' have no overlap."
        ))
    }
}
