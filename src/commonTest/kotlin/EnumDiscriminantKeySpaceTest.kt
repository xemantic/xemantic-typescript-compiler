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
 * (REL.1)(c) step 5a, round 750: the `"<enumSymbolId>#<member>"` key space is CANONICAL,
 * and every producer mints it through one helper.
 *
 * The same enum reaches the key builders as several `Symbol` instances depending on which
 * file was being checked at first touch and which resolution path was taken — the merged
 * global, the declaring file's local, a barrel resolver's target, or an enum member's own
 * `parent`. Keys built from different ids look pairwise DISJOINT, which is how round 425
 * read the whole `SyntaxKind` space as mutually exclusive and dropped every guard-narrowed
 * member to `never`. The failure is SILENT in the small: narrowing simply stops matching,
 * and only a downstream TS2339 on a case body reveals it.
 *
 * These pins are REGRESSION GUARDS, not targets: step 5a unified the six mint sites without
 * changing any key (measured — 153 distinct incoming enum symbols on the compiler profile,
 * all already canonical, none redirected), so every one of them passes on unmodified
 * `de24c764` too. That is the intended result and it is what makes the pins useful: step 5b
 * adds a SEVENTH producer that reaches the enum through a resolved TYPE rather than the AST,
 * and these are the assertions that will fail if its keys land in a different space.
 *
 * The cross-FILE shape is load-bearing in each of them. Within one file every path tends to
 * find the same `Symbol` instance, so a single-file pin cannot distinguish a canonical key
 * space from an accidental one; an `import` is what forces the annotation side and the
 * expression side to resolve separately.
 *
 * Each pin covers a different pair of producers:
 *  - the two AST producers (`enumMemberKeysOfTypeNode` for the union member's `kind`
 *    annotation, `enumMemberKeyOfExpr` for the `case` expression), in both directions;
 *  - the resolved-TYPE producer (`enumSwitchKeysFromType`) against the case expression,
 *    via the exhaustive-switch `neverType` gate — its coverage probe compares the two;
 *  - the type-INTERNING producer (`getDeclaredTypeOfEnumMember`, which reaches the enum
 *    through `memberSym.parent` — a third path), via enum-member type identity.
 *
 * The last pin is the sharpest and the reason the mint helper takes a symbol rather than a
 * name: canonicalization is DECLARATION-IDENTITY based, so two genuinely distinct enums that
 * merely share a name must keep distinct keys. A "canonicalize by name" simplification would
 * intern both to one `Type` and silence it.
 */
class EnumDiscriminantKeySpaceTest {

    private val types = """
        // @filename: /src/types.ts
        export enum Kind { Alpha, Beta }
        export interface A { readonly kind: Kind.Alpha; a: number }
        export interface B { readonly kind: Kind.Beta; b: string }
        export type AB = A | B
    """.trimIndent()

    @Test
    fun `an imported enum member case narrows a union declared in another file`() {
        val diagnostics = diagnose(
            """
            $types
            // @filename: /src/user.ts
            import { Kind, AB } from "./types";
            export function read(x: AB): number {
                switch (x.kind) {
                    case Kind.Alpha: return x.a;
                    case Kind.Beta: return x.b.length;
                }
            }
            """,
        )
        assert(diagnostics.isEmpty())
    }

    @Test
    fun `the narrowing also REMOVES the other constituent across the file boundary`() {
        val diagnostics = diagnose(
            """
            $types
            // @filename: /src/user.ts
            import { Kind, AB } from "./types";
            export function wrong(x: AB): string {
                switch (x.kind) {
                    case Kind.Alpha: return x.b;
                    default: return "";
                }
            }
            """,
        )
        assert(diagnostics.any { it.code == 2339 })
    }

    @Test
    fun `an imported enum subject is exhausted by its own member cases`() {
        val diagnostics = diagnose(
            """
            $types
            // @filename: /src/user.ts
            import { Kind } from "./types";
            export function exhaustive(k: Kind): string {
                switch (k) {
                    case Kind.Alpha: return "a";
                    case Kind.Beta: return "b";
                }
            }
            """,
        )
        assert(diagnostics.none { it.code == 2366 })
        assert(diagnostics.isEmpty())
    }

    @Test
    fun `an imported enum member type is the same type as the annotation names`() {
        val diagnostics = diagnose(
            """
            $types
            // @filename: /src/user.ts
            import { Kind } from "./types";
            export const same: Kind.Alpha = Kind.Alpha;
            """,
        )
        assert(diagnostics.isEmpty())
    }

    @Test
    fun `a sibling member of an imported enum is still a different member`() {
        val diagnostics = diagnose(
            """
            $types
            // @filename: /src/user.ts
            import { Kind } from "./types";
            export const cross: Kind.Alpha = Kind.Beta;
            """,
        )
        assert(diagnostics.any { it.code == 2322 })
    }

    @Test
    fun `negative control - a same-named enum is a different enum and never merged by name`() {
        val diagnostics = diagnose(
            """
            $types
            // @filename: /src/user.ts
            import { Kind } from "./types";
            namespace Other { export enum Kind { Alpha = 5, Beta = 6 } }
            export const shifted: Kind.Alpha = Other.Kind.Alpha;
            """,
        )
        assert(diagnostics.any { it.code == 2322 })
    }
}
