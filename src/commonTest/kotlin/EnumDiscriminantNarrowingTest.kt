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
import kotlin.test.Test

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

    private val decls = """
        enum Kind { A, B, C }
        interface AStatus { type: Kind.A; aField: string; }
        interface BStatus { type: Kind.B; bField: number; }
        interface CStatus { type: Kind.C; cField: boolean; }
        type Status = AStatus | BStatus | CStatus;
    """

    @Test
    fun `if equality narrows enum discriminant - no error`() {
        diagnose("$decls\nexport function f(s: Status){ if (s.type === Kind.A) { const x: string = s.aField; } }") should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `switch narrows enum discriminant - no error`() {
        diagnose("$decls\nexport function g(s: Status){ switch (s.type) { case Kind.B: { const y: number = s.bField; break; } } }") should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative equality else branch narrows - no error`() {
        // `if (s.type !== Kind.A) {} else { ... }` — the else branch is `s.type === Kind.A`.
        diagnose("$decls\nexport function h(s: Status){ if (s.type !== Kind.A) {} else { const x: string = s.aField; } }") should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `multi-value enum discriminant narrows positively`() {
        // A member whose discriminant is a UNION of enum members: `=== Kind.A` keeps it.
        diagnose(
            """
            enum Kind { A, B, C, D }
            interface AB { type: Kind.A | Kind.B; abField: string; }
            interface CD { type: Kind.C | Kind.D; cdField: number; }
            type S = AB | CD;
            export function f(s: S){ if (s.type === Kind.A) { const x: string = s.abField; } }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `multi-value member survives a single negative comparison - keeps member`() {
        // `!== Kind.A` must NOT drop a `Kind.A | Kind.B` member (it could still be Kind.B), so a
        // member access valid on that member must not become an error.
        diagnose(
            """
            enum Kind { A, B, C, D }
            interface AB { type: Kind.A | Kind.B; abField: string; }
            interface CD { type: Kind.C | Kind.D; cdField: number; }
            type S = AB | CD;
            export function f(s: S){ if (s.type !== Kind.A) { /* s could be AB (Kind.B) or CD */ } }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `wrong-branch field still errors - negative control`() {
        // In the `Kind.A` branch, accessing BStatus's field must still fire TS2339 (correct):
        // narrowing to AStatus makes `bField` genuinely absent.
        diagnose("$decls\nexport function f(s: Status){ if (s.type === Kind.A) { const x: number = s.bField; } }") should {
            have(any { it.code == 2339 && it.message.contains("bField") })
        }
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
        result.diagnostics should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `non-discriminant property access still errors - negative control`() {
        // A property on NO member is still an error even after narrowing.
        diagnose("$decls\nexport function f(s: Status){ if (s.type === Kind.A) { const x = (s as any).nope; s.doesNotExist; } }") should {
            have(any { it.code == 2339 && it.message.contains("doesNotExist") })
        }
    }

    @Test
    fun `round 420 - a member whose discriminant is a TYPE-ALIAS union of enum members is filtered`() {
        // tsc's `ProjectReferenceFile { kind: ProjectReferenceFileKind }` where
        // `ProjectReferenceFileKind = FileIncludeKind.A | FileIncludeKind.B`. The switch case
        // `case Kind.AutoType` must drop ProjectRefFile even though its `.kind` is an ALIAS.
        diagnose(
            """
            enum Kind { Root, Source, Output, AutoType }
            type ProjectRefKind = Kind.Source | Kind.Output;
            interface RootFile { kind: Kind.Root; }
            interface ProjectRefFile { kind: ProjectRefKind; index: number; }
            interface AutoTypeFile { kind: Kind.AutoType; typeReference: string; }
            type Reason = RootFile | ProjectRefFile | AutoTypeFile;

            export function f(reason: Reason): string | undefined {
                switch (reason.kind) {
                    case Kind.AutoType:
                        return reason.typeReference; // ProjectRefFile filtered via its alias-kind
                }
                return undefined;
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }
}
