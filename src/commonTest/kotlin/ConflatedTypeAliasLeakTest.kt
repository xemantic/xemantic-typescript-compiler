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
 * Round 443 (Blocker #3, self-compile burn-down): a module-file-local `type X = A | B` alias leaks
 * into `globals` and shadows the real global `interface X` in every OTHER file (the TYPE-space analog
 * of round 442's module-file-local VARIABLE leak). services/importTracker.ts's non-exported
 * `type SourceFileLike = SourceFile | AmbientModuleDeclaration` shadowed compiler/types.ts's
 * `interface SourceFileLike`, so a `sourceFile: SourceFileLike` param in OTHER files resolved to the
 * bogus union and `sourceFile.text` FP-fired TS2339 (44 on the services profile → 0).
 *
 * The cross-file leak is a whole-program (file-order/pollution) phenomenon that does NOT reproduce in
 * a small program — it is validated by the services profile. These tests pin the FP FIREWALL: the
 * suppression must be gated to CONFLATED type-alias/interface names AND must still fire in the alias's
 * own file, so it can only ever suppress the cross-file conflation FP, never an ordinary union error.
 */
class ConflatedTypeAliasLeakTest {

    private fun compile(source: String, primary: String = "b.ts") =
        TypeScriptCompiler().compile(source.trimIndent(), primary).diagnostics

    @Test
    fun `a conflated-name union accessed in the alias's own file still reports a missing member - TS2339`() {
        // `interface Shape` (a.ts) conflates with `type Shape` (b.ts). The suppression is gated to
        // files OTHER than the alias's own, so a genuine missing-member access in b.ts must still fire.
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            export interface Shape { area: number; }

            // @Filename: b.ts
            type Shape = { width: number } | { height: number };
            export function f(s: Shape): number { return s.nonexistent; }
            """
        ) should {
            have(any { it.code == 2339 && it.message.contains("nonexistent") })
        }
    }

    @Test
    fun `in the type-alias's own file a union-member access does not FP when the wrong interface won the merge - no TS2339`() {
        // Round 444 (alias's-own-file complement): `type Info = A | B` (a.ts) conflates with
        // `interface Info` (b.ts, c.ts). A sibling `interface Info` can win the merge, so a receiver
        // typed `Info` in a.ts resolves to that interface and a union-member access (`.kind` — present
        // on both A and B) FP'd TS2339. The alias's-own-file bail resolves the file-local union.
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            interface A { kind: 1; label: string; }
            interface B { kind: 2; label: string; }
            type Info = A | B;
            declare function getInfo(): Info | undefined;
            export function run(): string {
                const info = getInfo();
                if (info === undefined) return "";
                return info.kind + info.label;
            }

            // @Filename: b.ts
            export interface Info { other: string; }

            // @Filename: c.ts
            export interface Info { another: number; }
            """,
            primary = "a.ts",
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a member on NO union constituent still fires in the alias's own file - TS2339`() {
        // Firewall for the round-444 bail: it resolves the file-local union and only suppresses when
        // the property is on SOME constituent. A genuinely-missing name still fires.
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            interface A { kind: 1; }
            interface B { kind: 2; }
            type Info = A | B;
            declare function getInfo(): Info | undefined;
            export function run(): void {
                const info = getInfo();
                if (info === undefined) return;
                const bad = info.nonexistent;
            }

            // @Filename: b.ts
            export interface Info { other: string; }
            """,
            primary = "a.ts",
        ) should {
            have(any { it.code == 2339 && it.message.contains("nonexistent") })
        }
    }

    @Test
    fun `a plain interface member access is unaffected - no TS2339`() {
        // Sanity: the added early-bail must not disturb ordinary interface member resolution.
        diagnose(
            """
            interface Widget { name: string; size: number; }
            export function f(w: Widget): string { return w.name + w.size; }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }
}
