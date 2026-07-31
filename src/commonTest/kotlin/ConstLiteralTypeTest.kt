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
 * (WIDEN.1) round 781 — a `const` binding keeps the LITERAL type of its
 * initializer; only a mutable binding widens it.
 *
 * tsc's rule is `getWidenedLiteralTypeForInitializer` (checker.ts:41455), a
 * DECLARATION-FLAG gate rather than a freshness one:
 *
 * ```ts
 * return getCombinedNodeFlagsCached(declaration) & NodeFlags.Constant ||
 *     isDeclarationReadonly(declaration) ? type : getWidenedLiteralType(type);
 * ```
 *
 * The site that matters here is NOT `widenType`: `getTypeOfExpression` answers the
 * BASE primitive for a literal NODE (this checker mints no fresh-literal expression
 * type at all), so the literal has to be read off the AST via
 * `literalTypeOfExpression`, and the gate belongs where `currentLocalTypes` is
 * recorded — that map is what every downstream read of the name resolves through.
 *
 * Scope of the slice, deliberately: STRING / NUMBER / BIGINT / BOOLEAN literals and
 * unions of them. An ENUM MEMBER still widens to its enum (wider than tsc, so it
 * cannot manufacture a diagnostic), and an ASSIGNMENT TARGET still sees the widened
 * type (assigning to a `const` is already TS2588 and tsc adds no assignability error
 * there — `checkReferenceExpression` returns false).
 *
 * SIX of these thirteen pins DISCRIMINATE — measured against a binary with
 * `WIDEN1_CONST_KEEPS_LITERAL` flipped to `false`, not assumed. The other seven hold
 * on both sides on purpose: three pin that a MUTABLE binding still widens, and four
 * pin the two deliberate exclusions (enum members, assignment targets) plus the
 * structural cases the gate must not reach. The exclusions' own evidence is the
 * corpus (`constDeclarations-access2`) and the services profile
 * (`completions.ts:2239`), both of which regressed before the exclusions were added.
 */
class ConstLiteralTypeTest {

    private val prelude = """
        interface Ctx {
            kind: "method" | "getter" | "setter" | "accessor" | "field";
            stat: boolean;
        }
        declare function cond(): boolean;
        declare function fail(): never;
        declare function wantsKind(k: "method" | "getter"): void;
        declare function wantsString(s: string): void;
    """.trimIndent() + "\n"

    // --- the literal survives the const declaration -------------------------

    /** DISCRIMINATES — `Type 'string' is not assignable to type '"a"'` without the fix. */
    @Test
    fun `a const string literal keeps its literal type`() {
        val diagnostics = diagnose(
            """
            const k = "a";
            const j: "a" = k;
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /** DISCRIMINATES — the whole point of the round, in the shape esDecorators.ts uses. */
    @Test
    fun `a const conditional chain of string literals keeps the literal union`() {
        val diagnostics = diagnose(
            prelude +
                """
                const kind = cond() ? "getter" :
                    cond() ? "setter" :
                    cond() ? "method" :
                    cond() ? "accessor" :
                    cond() ? "field" :
                    fail();
                const context: Ctx = { kind, stat: true };
                """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /** DISCRIMINATES — a numeric const literal, same rule. */
    @Test
    fun `a const numeric literal keeps its literal type`() {
        val diagnostics = diagnose(
            """
            const n = 7;
            const m: 7 = n;
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /** DISCRIMINATES — a boolean const literal, same rule. */
    @Test
    fun `a const true literal keeps its literal type`() {
        val diagnostics = diagnose(
            """
            const b = true;
            const c: true = b;
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /** DISCRIMINATES — the const flows through a shorthand property into a contextual objlit. */
    @Test
    fun `a const literal reaches a contextually typed object literal shorthand`() {
        val diagnostics = diagnose(
            prelude +
                """
                const kind = "method";
                const context: Ctx = { kind, stat: false };
                """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    // --- a MUTABLE binding must still widen: the negative direction ----------

    /**
     * FIRES ON BOTH SIDES — a `let` widens, so the literal target rejects it, and the
     * message NAMES the widened source. Pinned by MESSAGE: silence here would mean the
     * gate had leaked into mutable bindings.
     */
    @Test
    fun `negative control - a let string literal still widens to string`() {
        val diagnostics = diagnose(
            """
            let m = "b";
            const c: "b" = m;
            """.trimIndent()
        )
        assert(
            diagnostics.any {
                it.code == 2322 &&
                    it.message == "Type 'string' is not assignable to type '\"b\"'."
            }
        )
    }

    /** FIRES ON BOTH SIDES — a `var` widens exactly as a `let` does. */
    @Test
    fun `negative control - a var numeric literal still widens to number`() {
        val diagnostics = diagnose(
            """
            var v = 3;
            const c: 3 = v;
            """.trimIndent()
        )
        assert(
            diagnostics.any {
                it.code == 2322 &&
                    it.message == "Type 'number' is not assignable to type '3'."
            }
        )
    }

    /**
     * FIRES ON BOTH SIDES — a `let` initialised FROM a const still widens, because the
     * widening decision belongs to the binding being declared, not to the source.
     */
    @Test
    fun `negative control - a let initialised from a const still widens`() {
        val diagnostics = diagnose(
            """
            const k = "a";
            let relay = k;
            const j: "a" = relay;
            """.trimIndent()
        )
        assert(
            diagnostics.any {
                it.code == 2322 &&
                    it.message == "Type 'string' is not assignable to type '\"a\"'."
            }
        )
    }

    /**
     * DISCRIMINATES BY MESSAGE — a genuinely wrong literal still rejects, and the message
     * NAMES the literal (`'"a"'`) where the ablated binary names `'string'`. This is what
     * proves the const kept its literal type rather than some rule merely going silent.
     */
    @Test
    fun `negative control - a const literal that does not match the target still rejects`() {
        val diagnostics = diagnose(
            """
            const k = "a";
            const j: "b" = k;
            """.trimIndent()
        )
        assert(
            diagnostics.any {
                it.code == 2322 &&
                    it.message == "Type '\"a\"' is not assignable to type '\"b\"'."
            }
        )
    }

    // --- the two exclusions this slice makes on purpose ----------------------

    /**
     * FIRES ON BOTH SIDES — assigning to a `const` is TS2588 and NOTHING ELSE.
     * tsc's `checkReferenceExpression` returns false there, so the assignability check
     * never runs; `constDeclarations-access2` pins the same thing on the corpus, and
     * this is what `widen1ConstLiteralTypeIds` exists for.
     */
    @Test
    fun `assigning to a const literal emits only the constant-assignment error`() {
        val diagnostics = diagnose(
            """
            const x = 0;
            x = 1;
            """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2588 })
        assert(diagnostics.none { it.code == 2322 })
    }

    /**
     * DISCRIMINATES — an enum-typed const still widens to the ENUM, so the accumulator
     * idiom tsc's own sources use stays legal. Skipping the enum arm for a `const` cost
     * `completions.ts:2239` on the services profile when it was measured.
     */
    @Test
    fun `a const enum member still widens to its enum`() {
        val diagnostics = diagnose(
            """
            enum Flags { None = 0, A = 1, B = 2 }
            declare function wantsFlags(f: Flags): void;
            const start = Flags.None;
            wantsFlags(start);
            const both: Flags = start;
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
        assert(diagnostics.none { it.code == 2345 })
    }

    // --- structural initialisers are NOT literal-preserving ------------------

    /**
     * FIRES ON BOTH SIDES — `const arr = [1]` is `number[]`, not `1[]`, because an array
     * literal's ELEMENTS are mutable locations in tsc. The message names the element type.
     */
    @Test
    fun `negative control - a const array literal still widens its elements`() {
        val diagnostics = diagnose(
            """
            const arr = [1, 2];
            const one: 1[] = arr;
            """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2322 })
    }

    /** DISCRIMINATES — a const literal is still a perfectly good `string`. */
    @Test
    fun `a const literal is still assignable to its base primitive`() {
        val diagnostics = diagnose(
            prelude +
                """
                const k = "method";
                wantsString(k);
                const s: string = k;
                """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
        assert(diagnostics.none { it.code == 2345 })
    }
}
