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
import kotlin.test.assertTrue

/**
 * M3.4 (self-compile burn-down): discriminated-union narrowing keyed on an ENUM-MEMBER
 * discriminant (`type: Kind.A`). Enum-member types resolve to `anyType` in our engine (they are
 * not modeled as literals), so neither the `if (s.type === Kind.A)` equality path
 * (`narrowByDiscriminantProperty`) nor the `switch (s.type) { case Kind.B: }` path
 * (`narrowBySwitchClause`) narrowed — a member access in the narrowed branch FP'd TS2339 on the
 * whole union. tsc's own `tsbuildPublic.ts` (23 sites keyed on `UpToDateStatus.type:
 * UpToDateStatusType.X`) relies on this.
 *
 * The fix matches on the enum member's canonical identity read from the AST (the property's
 * declared `type: Enum.Member` annotation vs the `Enum.Member` on the comparison/case), without
 * modeling enum-member types generally (which would be nominal-enum / B425-risky).
 */
class EnumDiscriminantNarrowingTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile("// @strict: true\n" + source.trimIndent(), "t.ts").diagnostics

    private val decls = """
        enum Kind { A, B, C }
        interface AStatus { type: Kind.A; aField: string; }
        interface BStatus { type: Kind.B; bField: number; }
        interface CStatus { type: Kind.C; cField: boolean; }
        type Status = AStatus | BStatus | CStatus;
    """

    @Test
    fun `if equality narrows enum discriminant - no error`() {
        val d = diags("$decls\nexport function f(s: Status){ if (s.type === Kind.A) { const x: string = s.aField; } }")
        assertTrue(
            d.none { it.code == 2339 },
            "`if (s.type === Kind.A)` must narrow `s` to AStatus so `s.aField` resolves; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `switch narrows enum discriminant - no error`() {
        val d = diags("$decls\nexport function g(s: Status){ switch (s.type) { case Kind.B: { const y: number = s.bField; break; } } }")
        assertTrue(
            d.none { it.code == 2339 },
            "`switch (s.type) { case Kind.B }` must narrow `s` to BStatus so `s.bField` resolves; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `negative equality else branch narrows - no error`() {
        // `if (s.type !== Kind.A) {} else { ... }` — the else branch is `s.type === Kind.A`.
        val d = diags("$decls\nexport function h(s: Status){ if (s.type !== Kind.A) {} else { const x: string = s.aField; } }")
        assertTrue(
            d.none { it.code == 2339 },
            "the else of `if (s.type !== Kind.A)` must narrow `s` to AStatus; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `multi-value enum discriminant narrows positively`() {
        // A member whose discriminant is a UNION of enum members: `=== Kind.A` keeps it.
        val d = diags(
            """
            enum Kind { A, B, C, D }
            interface AB { type: Kind.A | Kind.B; abField: string; }
            interface CD { type: Kind.C | Kind.D; cdField: number; }
            type S = AB | CD;
            export function f(s: S){ if (s.type === Kind.A) { const x: string = s.abField; } }
            """,
        )
        assertTrue(
            d.none { it.code == 2339 },
            "`=== Kind.A` must keep the `Kind.A | Kind.B` member so `abField` resolves; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `multi-value member survives a single negative comparison - keeps member`() {
        // `!== Kind.A` must NOT drop a `Kind.A | Kind.B` member (it could still be Kind.B), so a
        // member access valid on that member must not become an error.
        val d = diags(
            """
            enum Kind { A, B, C, D }
            interface AB { type: Kind.A | Kind.B; abField: string; }
            interface CD { type: Kind.C | Kind.D; cdField: number; }
            type S = AB | CD;
            export function f(s: S){ if (s.type !== Kind.A) { /* s could be AB (Kind.B) or CD */ } }
            """,
        )
        assertTrue(d.none { it.code == 2339 }, "got: " + d.joinToString { "TS${it.code}: ${it.message}" })
    }

    @Test
    fun `wrong-branch field still errors - negative control`() {
        // In the `Kind.A` branch, accessing BStatus's field must still fire TS2339 (correct):
        // narrowing to AStatus makes `bField` genuinely absent.
        val d = diags("$decls\nexport function f(s: Status){ if (s.type === Kind.A) { const x: number = s.bField; } }")
        assertTrue(
            d.any { it.code == 2339 && it.message.contains("bField") },
            "accessing `bField` in the AStatus-narrowed branch must still fire TS2339; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `enum imported through an export-star barrel narrows the discriminant`() {
        // The exact self-compile shape: tsc's `tsbuildPublic.ts` narrows `UpToDateStatus` (keyed
        // on `type: UpToDateStatusType.X`) where the enum is imported via `export *` barrels. The
        // general resolveAlias can't follow the ESM `.js` specifier + star barrel, so the enum
        // resolves flow-only.
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to
                    """{ "compilerOptions": { "strict": true, "outDir": "./dist" }, "include": ["src/**/*.ts"] }""",
                "/proj/src/kind.ts" to "export enum Kind { A, B, C }",
                "/proj/src/barrel.ts" to """export * from "./kind.js";""",
                "/proj/src/status.ts" to """
                    import { Kind } from "./barrel.js";
                    interface AStatus { type: Kind.A; aField: string; }
                    interface BStatus { type: Kind.B; bField: number; }
                    type Status = AStatus | BStatus;
                    export function f(s: Status): string {
                        if (s.type === Kind.A) { return s.aField; }
                        switch (s.type) { case Kind.B: return String(s.bField); }
                        return "";
                    }
                """.trimIndent(),
            )
        )
        val result = ProjectCompiler(vfs).build("/proj", noEmit = true)
        assertTrue(
            result.diagnostics.none { it.code == 2339 },
            "a barrel-imported enum discriminant must still narrow (both `===` and `switch`); got: " +
                result.diagnostics.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `non-discriminant property access still errors - negative control`() {
        // A property on NO member is still an error even after narrowing.
        val d = diags("$decls\nexport function f(s: Status){ if (s.type === Kind.A) { const x = (s as any).nope; s.doesNotExist; } }")
        assertTrue(
            d.any { it.code == 2339 && it.message.contains("doesNotExist") },
            "a genuinely-missing property must still fire TS2339; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
