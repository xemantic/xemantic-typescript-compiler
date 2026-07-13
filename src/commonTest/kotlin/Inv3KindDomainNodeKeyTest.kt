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
 * INV.3(c)(ii) (round 505): the kind-domain/enum-discriminant narrowing
 * readers key their merged-globals fallback by the NODE'S OWNING FILE
 * (`lookupPerFileForNode`) instead of the raw conflated `globals` consult —
 * a types.ts member annotation resolves under types.ts's visibility whatever
 * file is being checked (resolution PRESERVED, the acceptance bar), while a
 * checking-file expression naming a module-only enum the file never imports
 * no longer narrows through a foreign file's leaked local (the leak, killed —
 * real tsc sees TS2304 there and never narrows).
 */
class Inv3KindDomainNodeKeyTest {

    /** The discriminated-union fixture: narrowing `x.kind === Kind.A` must drop
     *  Square for `x.r` to be legal — the observable for every case below. */
    private val typesTs = """
        // @filename: types.ts
        export enum Kind { A, B }
        export type SquareKind = Kind.B;
        export interface Circle { kind: Kind.A; r: number; }
        export interface Square { kind: SquareKind; s: number; }
        export type Shape = Circle | Square;
    """

    @Test
    fun `a foreign annotation's alias resolves under its OWNING file so narrowing keeps working`() {
        // use.ts never imports SquareKind — Square's `kind: SquareKind` annotation
        // (a types.ts node) must resolve under TYPES.TS's visibility for the
        // discriminant filter to drop Square. Nulling foreign-node names (a naive
        // currentCheckFileName flip) would keep Square and FP TS2339 on `x.r`.
        diagnose(
            typesTs + """

            // @filename: use.ts
            import { Shape, Kind } from "./types";
            export function f(x: Shape): number {
                if (x.kind === Kind.A) { return x.r; }
                return 0;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `an IMPORTED alias annotation still resolves through the import-alias fallback`() {
        // use.ts imports SquareKind, so the checking file's local is the import
        // ALIAS (ImportSpecifier declarations, no TypeAliasDeclaration) — the
        // round-477 fallback must recover the declaring file's alias through the
        // node-keyed consult.
        diagnose(
            typesTs + """

            // @filename: use.ts
            import { Shape, Kind, SquareKind } from "./types";
            export function f(x: Shape): number {
                if (x.kind === Kind.A) { return x.r; }
                return 0;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a checking-file comparison naming an UNIMPORTED module-only enum no longer narrows`() {
        // leak.ts references Kind without importing it — invalid TS (TS2304 on
        // `Kind`), so real tsc never narrows `x.kind === Kind.A`. The leaked
        // merged-globals resolution used to narrow anyway, hiding the TS2339 on
        // `x.r` (Square has no `r`). The node-keyed consult kills the leak: the
        // comparison node's owning file (leak.ts) has no meaning for `Kind`.
        diagnose(
            typesTs + """

            // @filename: leak.ts
            import { Shape } from "./types";
            export function f(x: Shape): number {
                if (x.kind === Kind.A) { return x.r; }
                return 0;
            }
            """
        ) should {
            have(any { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a same-module-file discriminant keeps narrowing`() {
        diagnose(
            """
            export enum Kind { A, B }
            export interface Circle { kind: Kind.A; r: number; }
            export interface Square { kind: Kind.B; s: number; }
            export type Shape = Circle | Square;
            export function f(x: Shape): number {
                if (x.kind === Kind.A) { return x.r; }
                return 0;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - cross-file SCRIPT declarations are globally visible and keep narrowing`() {
        // Script-file locals are legitimately global (nonModuleVisible) — the
        // per-file gate never applies, so cross-file narrowing without imports
        // keeps working for script programs.
        diagnose(
            """
            // @filename: a.ts
            enum Kind { A, B }
            interface Circle { kind: Kind.A; r: number; }
            interface Square { kind: Kind.B; s: number; }
            type Shape = Circle | Square;

            // @filename: b.ts
            function f(x: Shape): number {
                if (x.kind === Kind.A) { return x.r; }
                return 0;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }
}
