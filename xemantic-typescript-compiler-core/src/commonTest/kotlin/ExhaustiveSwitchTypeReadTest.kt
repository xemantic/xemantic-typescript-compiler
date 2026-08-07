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
 * (REL.1)(c) step 5b, round 752: the EXHAUSTIVE-SWITCH gate reads each union member's
 * discriminant from its RESOLVED TYPE, with the annotation walk kept as the fallback.
 *
 * The gate ([Checker.isExhaustiveEnumSwitch]) proves a `default`-less switch terminating by
 * comparing the cases against the discriminant's full value domain, which
 * [Checker.unionDiscriminantKeysOfType] reads per union member. Until this round that read was
 * the AST pair `enumSwitchKeysFromTypeNode ?: enumMemberKeysOfTypeNode` — a fixed list of
 * TypeNode shapes. The type-first read is the pair's arm-for-arm twin:
 * [Checker.enumSwitchKeysFromType] expands a BARE enum to its whole member domain (and folds
 * the nullish constituents), [Checker.enumDiscriminantKeysOfType] keys an enum-MEMBER type.
 *
 * A PARENTHESIZED annotation is the witness, as in [EnumDiscriminantTypeReadTest]: legal
 * TypeScript with no `ParenthesizedType` arm in either AST reader, so the member contributes
 * NO keys and the whole switch is conservatively not-exhaustive. The failure direction is
 * FP-SAFE — the gate only ever SUPPRESSES TS2366/TS7030/TS2355 — which is exactly why it is
 * silent: it shows up as a diagnostic tsc does not emit, never as a wrong answer, and only a
 * pin like these makes it visible.
 *
 * MEASURED over the whole compiler profile before the flip: **213 sightings, 213 AGREE, 0
 * mismatched, 0 where the type path lost a key the AST path had, 0 where it gained one**. The
 * bare-enum arm is load-bearing rather than decorative — with the member-type reader ALONE, 9
 * of those sightings went TYPEBLIND (7× a bare `kind: SyntaxKind` expanding to 396 members,
 * 2× `newLine: NewLineKind`), which is what made [Checker.enumSwitchKeysFromType] part of the
 * pair. With the AST fallback ABLATED the profile stayed byte-identical at 46 errors.
 *
 * The cross-FILE shape is deliberate throughout, per [EnumDiscriminantKeySpaceTest].
 */
class ExhaustiveSwitchTypeReadTest {

    private val memberTyped = """
        // @filename: /src/types.ts
        export enum Kind { Alpha, Beta }
        export interface A { readonly kind: (Kind.Alpha); a: number }
        export interface B { readonly kind: (Kind.Beta); b: string }
        export type AB = A | B
    """.trimIndent()

    /**
     * The [Checker.enumDiscriminantKeysOfType] arm: each member's discriminant is ONE enum
     * member, so the required domain is the two of them and the two cases cover it.
     *
     * Fails on an unflipped build with TS2366 — the AST reader has no `ParenthesizedType`
     * arm, so member `A` yields no keys, the walk bails, and the switch is not accepted as
     * exhaustive. Narrowing in the same function already worked before this round
     * (round 751), which is what made this the sharpest available before/after.
     */
    @Test
    fun `a parenthesized enum member discriminant proves the switch exhaustive`() {
        val diagnostics = diagnose(
            """
            $memberTyped
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
        assert(diagnostics.none { it.code == 2366 })
        assert(diagnostics.none { it.code == 2339 })
    }

    /**
     * The [Checker.enumSwitchKeysFromType] arm, and the one the profile measurement forced:
     * a BARE enum discriminant is the whole member domain, not a single member. Nine of the
     * 213 profile sightings are this shape, so a flip that only read enum-MEMBER types would
     * have gone blind on them and started reporting switches tsc accepts.
     *
     * The two members are structurally distinct so the union does not collapse, and neither
     * case body reads a member-specific property — with a full-domain discriminant on both
     * members there is nothing to discriminate, which is the point: exhaustiveness and
     * narrowing are separate questions over the same key space.
     */
    @Test
    fun `a parenthesized bare enum discriminant expands to the whole member domain`() {
        val diagnostics = diagnose(
            """
            // @filename: /src/types.ts
            export enum Kind { Alpha, Beta }
            export interface A { readonly kind: (Kind); a: number }
            export interface B { readonly kind: (Kind); b: string }
            export type AB = A | B
            // @filename: /src/user.ts
            import { Kind, AB } from "./types";
            export function read(x: AB): number {
                switch (x.kind) {
                    case Kind.Alpha: return 1;
                    case Kind.Beta: return 2;
                }
            }
            """,
        )
        assert(diagnostics.none { it.code == 2366 })
    }

    /**
     * The type path supplies the MEMBERS; the round-423 optional rule still supplies the
     * `@undefined` marker from `PropertyDeclaration.questionToken`. Pinned because the flip
     * deliberately left that rule alone — the resolved type of an optional property does not
     * carry the marker here, so a flip that had tried to derive everything from the type
     * would have silently dropped it and then rejected a switch that covers `undefined`.
     */
    @Test
    fun `an optional parenthesized discriminant still contributes the undefined marker`() {
        val diagnostics = diagnose(
            """
            // @filename: /src/types.ts
            export enum Kind { Alpha, Beta }
            export interface A { readonly kind?: (Kind.Alpha); a: number }
            export interface B { readonly kind?: (Kind.Beta); b: string }
            export type AB = A | B
            // @filename: /src/user.ts
            import { Kind, AB } from "./types";
            export function read(x: AB): number {
                switch (x.kind) {
                    case Kind.Alpha: return 1;
                    case Kind.Beta: return 2;
                    case undefined: return 3;
                }
            }
            """,
        )
        assert(diagnostics.none { it.code == 2366 })
    }

    /**
     * The no-false-suppression control: a switch that genuinely misses a member must still
     * report, so the flip cannot be a blanket suppression.
     *
     * It passes on an unflipped build TOO, and that is stated rather than hidden — the TS2366
     * is there for a different reason on each side (unflipped: the gate cannot read the
     * parenthesized keys at all; flipped: it reads them and finds `Kind.Beta` uncovered).
     * Same code, opposite causes, so this pin cannot DISCRIMINATE the two builds; it guards
     * the direction the discriminating pins above could otherwise be satisfied by.
     */
    @Test
    fun `negative control - a genuinely non exhaustive switch still reports`() {
        val diagnostics = diagnose(
            """
            $memberTyped
            // @filename: /src/user.ts
            import { Kind, AB } from "./types";
            export function read(x: AB): number {
                switch (x.kind) {
                    case Kind.Alpha: return x.a;
                }
            }
            """,
        )
        assert(diagnostics.any { it.code == 2366 })
    }
}
