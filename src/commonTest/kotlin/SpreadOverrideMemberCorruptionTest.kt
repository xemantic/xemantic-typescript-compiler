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
 * Round 449 (self-compile burn-down, services 321 → 310; the `readonly ApplicableRefactorInfo[]`
 * TS2322 ×9 family + convertExport `ExportInfo | RefactorErrorInfo` cascade, deferred across
 * rounds 446–448 as a "type-param-scope pollution needing a whole-program probe").
 *
 * ROOT CAUSE: `getTypeOfObjectLiteral`'s spread merge added the spread SOURCE's member SYMBOLS to
 * the object-literal's member table BY REFERENCE (`members[pn] = psym`). A later explicit member
 * with the same name (`{ ...info, actions: [...] }`) hit the `existing != null` override branch,
 * which wrote `symbolTypes[existing.id] = <override type>` — mutating the spread SOURCE's member
 * type cache GLOBALLY. In the real code the override was a contextually-generic-inferred `U[]`, so
 * `ApplicableRefactorInfo.actions`'s cached type became `U[]` and every later relation against
 * `ApplicableRefactorInfo` FP-fired. The fix copies each spread member into a fresh literal-owned
 * symbol so overrides can never mutate the shared source member.
 */
class SpreadOverrideMemberCorruptionTest {

    @Test
    fun `refactor-shaped spread+override return does not FP against the interface array`() {
        diagnose(
            """
            interface RefactorActionInfo { name: string; description: string; kind?: string; }
            interface ApplicableRefactorInfo {
                name: string;
                description: string;
                actions: RefactorActionInfo[];
            }
            declare const info: ApplicableRefactorInfo;
            function getRefactors(): readonly ApplicableRefactorInfo[] {
                return [{
                    ...info,
                    actions: [{ name: "a", description: "d", kind: "k" }],
                }];
            }
            """,
        ) should {
            have(none { it.code == 2322 })
            have(none { it.code == 2739 })
            have(none { it.code == 2740 })
            have(none { it.code == 2353 })
        }
    }

    @Test
    fun `a spread+override does not corrupt the source member's type for a LATER relation`() {
        // If the override (`actions: [{ name, extra }]`) corrupted `Info.actions`'s shared member
        // symbol to `{ name; extra }[]`, then `ok`'s `{ name }` element would read as missing the
        // required `extra` -> FP. `corrupt` is checked (and its object literal typed) before `ok`.
        diagnose(
            """
            interface Action { name: string; }
            interface Info { description: string; actions: Action[]; }
            declare const base: Info;
            const corrupt = { ...base, actions: [{ name: "a", extra: 99 }] };
            const use = corrupt.description;
            const ok: Info = { description: "d", actions: [{ name: "b" }] };
            """,
        ) should {
            have(none { it.code == 2322 })
            have(none { it.code == 2739 })
            have(none { it.code == 2741 })
            have(none { it.code == 2353 })
        }
    }

    @Test
    fun `negative control - an override with a genuinely wrong element type still fires`() {
        diagnose(
            """
            interface Action { name: string; }
            interface Info { description: string; actions: Action[]; }
            declare const base: Info;
            function f(): Info {
                // actions element is missing the required `name` -> a real error, must still fire.
                return { ...base, actions: [{ nope: 1 }] };
            }
            """,
        ) should {
            have(any { it.code == 2322 || it.code == 2739 || it.code == 2741 || it.code == 2353 })
        }
    }
}
