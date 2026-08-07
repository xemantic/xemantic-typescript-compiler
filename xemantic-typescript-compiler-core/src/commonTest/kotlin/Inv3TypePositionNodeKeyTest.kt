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
 * INV.3(c)(iv) (round 508): the type-position resolution tail keys its
 * merged-globals fallbacks by the NAME NODE'S owning file —
 * `resolveTypeNameToSymbol`'s Identifier branch (general type resolution via
 * `getTypeFromTypeReference`, whose trailing `?: globals[name]` is gated to
 * QualifiedName so the node-keyed null survives), the TS2315 emitter's
 * fallback, and `typeNodeDefinitelyNonNullish`'s two fallbacks (flipped
 * JOINTLY per the round-507c order constraint: the classifier's leak is fully
 * shadowed by the general-resolution leak through
 * `resolvedCallReturnTypeForFlow`, so the pair must move together).
 * A module-only type name with no meaning in the node's file resolves null
 * (real tsc: TS2304 → the annotation types as error/any) instead of a foreign
 * module file's leaked alias; every visible name keeps resolving to the SAME
 * merged instance.
 */
class Inv3TypePositionNodeKeyTest {

    // ── The joint flow observable: a callee return-annotation naming a
    // foreign UNIMPORTED nullable alias no longer types the assigned
    // reference as the leaked union — the reference degrades to `any`
    // (what real tsc sees after its TS2304), so the closure-captured
    // TS18048 that the LEAKED resolution manufactured dies.

    @Test
    fun `an UNIMPORTED foreign nullable alias return-annotation no longer types the reference - the leaked TS18048 dies`() {
        diagnose(
            """
            // @filename: a.ts
            export const anchor = 1;
            type Nully = { v: string } | undefined;

            // @filename: b.ts
            export declare function get2(): Nully;
            export function f(): void {
                let x: { v: string } | undefined;
                x = get2();
                const g = () => x.v;
                g();
            }
            """
        ) should {
            have(none { it.code == 18048 })
        }
    }

    @Test
    fun `negative control - an IMPORTED nullable alias return-annotation keeps typing - the real TS18048 fires`() {
        diagnose(
            """
            // @filename: a.ts
            export type Nully = { v: string } | undefined;

            // @filename: b.ts
            import { Nully } from "./a";
            export declare function get2(): Nully;
            export function f(): void {
                let x: { v: string } | undefined;
                x = get2();
                const g = () => x.v;
                g();
            }
            """
        ) should {
            have(any { it.code == 18048 })
        }
    }

    @Test
    fun `negative control - an OWN-FILE nullable alias return-annotation keeps typing - TS18048 fires`() {
        diagnose(
            """
            // @filename: b.ts
            export type Nully = { v: string } | undefined;
            export declare function get2(): Nully;
            export function f(): void {
                let x: { v: string } | undefined;
                x = get2();
                const g = () => x.v;
                g();
            }
            """
        ) should {
            have(any { it.code == 18048 })
        }
    }

    // ── typeNodeDefinitelyNonNullish preservation: a VISIBLE non-nullish
    // alias/interface annotation still classifies non-nullish, so the
    // assignment strips nullish and the captured read stays silent.

    @Test
    fun `negative control - an IMPORTED non-nullish alias return-annotation keeps stripping nullish - no TS18048`() {
        diagnose(
            """
            // @filename: a.ts
            export type Solid = { v: string };

            // @filename: b.ts
            import { Solid } from "./a";
            export declare function get2(): Solid;
            export function f(): void {
                let x: { v: string } | undefined;
                x = get2();
                const g = () => x.v;
                g();
            }
            """
        ) should {
            have(none { it.code == 18048 })
        }
    }

    @Test
    fun `negative control - an IMPORTED interface return-annotation keeps stripping nullish - no TS18048`() {
        diagnose(
            """
            // @filename: a.ts
            export interface Payload { v: string }

            // @filename: b.ts
            import { Payload } from "./a";
            export declare function get2(): Payload;
            export function f(): void {
                let x: Payload | undefined;
                x = get2();
                const g = () => x.v;
                g();
            }
            """
        ) should {
            have(none { it.code == 18048 })
        }
    }

    // ── Annotation-position observable: an UNIMPORTED foreign alias in a
    // var-decl annotation must resolve to errorType (assignability bails)
    // instead of the leaked foreign body.

    @Test
    fun `an UNIMPORTED foreign alias annotation no longer manufactures TS2322`() {
        diagnose(
            """
            // @filename: a.ts
            export const anchor = 1;
            type Shape2 = { v: string };

            // @filename: b.ts
            export const q: Shape2 = { v: 42 };
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an IMPORTED alias annotation keeps checking - the real TS2322 fires`() {
        diagnose(
            """
            // @filename: a.ts
            export type Shape2 = { v: string };

            // @filename: b.ts
            import { Shape2 } from "./a";
            export const q: Shape2 = { v: 42 };
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    // ── TS2315 observable: "Type 'X' is not generic" about a name the file
    // never imports is always bogus (real tsc: TS2304 territory).

    @Test
    fun `an UNIMPORTED foreign non-generic alias no longer manufactures TS2315`() {
        diagnose(
            """
            // @filename: a.ts
            export const anchor = 1;
            type Plain = string;

            // @filename: b.ts
            export let w: Plain<number>;
            """
        ) should {
            have(none { it.code == 2315 })
        }
    }

    @Test
    fun `negative control - an OWN-FILE non-generic alias keeps firing TS2315`() {
        diagnose(
            """
            // @filename: b.ts
            export type Plain = string;
            export let w: Plain<number>;
            """
        ) should {
            have(any { it.code == 2315 })
        }
    }
}
