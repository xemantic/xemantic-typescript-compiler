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
 * (REL.2) cause (G), round 784 — a RETURNED property read sees a receiver that a
 * type-guard call narrowed DOWN from a single (non-union) type.
 *
 * `computeRawTypeOfPropertyAccess` flow-narrows a receiver only when its raw type is
 * ALREADY a `Type.Union` — a deliberate gate, since narrowing every `a.b` would put a
 * flow walk on the hottest path in the checker. So `isModifier(node): node is Modifier`
 * on a `node: Node` never reached the read, and `node.kind` answered `SyntaxKind` where
 * tsc answers `ModifierSyntaxKind` (tsc `services/completions.ts:2234`). The path-based
 * walk could not recover it either: `narrowByCallPredicate`'s PREFIX arm resolves the
 * tail on the guard TARGET, and its round-462 gate deliberately refuses a UNION tail.
 *
 * [Checker.propertyTypeFromNarrowedReceiver] is a SECOND CHANCE: it is consulted only
 * after the raw property type has already been REJECTED, and adopted only when it makes
 * the return relate — so it can turn a rejection into an acceptance and can never
 * introduce a diagnostic. That asymmetry is why every positive pin here is a SILENCE
 * pin, and why each is paired with a control that must still fire.
 *
 * DISCRIMINATION, measured against an ablated binary (`R784_NARROWED_RECEIVER = false`,
 * recompiled, the pin shapes re-run side by side through the scratch-project CLI): the
 * union-TAIL pin and both enum pins fail ablated and pass fixed. The union-TARGET pin
 * holds on BOTH sides on purpose and is documented as such — a guard target that is
 * itself a union already resolves its tail through the prefix machinery, which is
 * exactly what made the single-target/union-tail shape the one that had no answer.
 */
class NarrowedReceiverPropTest {

    private val prelude = """
        interface Node2 { text: string | number | boolean; }
        interface Wide extends Node2 { text: string | boolean; }
        interface Str extends Node2 { text: string; }
        interface Bool extends Node2 { text: boolean; }
        declare function isWide(n: Node2): n is Wide;
        declare function isStrOrBool(n: Node2): n is Str | Bool;
    """.trimIndent() + "\n"

    /**
     * DISCRIMINATES — fires ablated, silent fixed. The guard target is a SINGLE type
     * whose property is a UNION, which is precisely the shape the prefix arm's
     * round-462 union-tail gate refuses.
     */
    @Test
    fun `a returned property read sees a guard narrow whose tail is a union`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(n: Node2): string | boolean {
                    if (isWide(n)) {
                        return n.text;
                    }
                    return "";
                }
                """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /**
     * HOLDS ON BOTH SIDES ON PURPOSE — a UNION guard target already resolves its tail
     * through `resolvePrefixTailSegment`, so this shape never needed the second chance.
     * It is pinned because it is the shape the queue item NAMED, and because a future
     * change to the prefix arm must not silently lose it.
     */
    @Test
    fun `a returned property read sees a guard narrow to a union target`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(n: Node2): string | boolean {
                    if (isStrOrBool(n)) {
                        return n.text;
                    }
                    return "";
                }
                """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /** NEGATIVE CONTROL — with no guard the wide property type must still be reported. */
    @Test
    fun `negative control - an unguarded property read still reports the declared type`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(n: Node2): string | boolean {
                    return n.text;
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2322 && it.message == "Type 'string | number | boolean' is not assignable to type 'string | boolean'." })
    }

    /**
     * NEGATIVE CONTROL — the guard narrows a DIFFERENT reference, so the second chance
     * must find nothing and the diagnostic must survive.
     */
    @Test
    fun `negative control - a guard on another reference does not narrow this one`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(n: Node2, m: Node2): string | boolean {
                    if (isWide(m)) {
                        return n.text;
                    }
                    return "";
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2322 && it.message == "Type 'string | number | boolean' is not assignable to type 'string | boolean'." })
    }

    /**
     * NEGATIVE CONTROL — a GENUINE mismatch must survive the guard. The narrowed
     * property is `string | boolean`, which still does not relate to a `string` target,
     * so the second chance declines and the mismatch is reported.
     *
     * Round 785 SHARPENED THE MESSAGE, and the control is kept, not weakened: the
     * diagnostic still fires with the same code at the same position, but (NARROW.1)'s
     * guard-call arm now records `n: Wide` into `currentLocalTypes`, so the reported
     * source type is the narrowed `'string | boolean'` rather than the declared
     * `'string | number | boolean'`. That is tsc's own wording for this shape — inside
     * `isWide(n)` the receiver IS a `Wide` — so the pre-785 text was the less accurate
     * of the two. The `boolean` elaboration is unchanged, which is what makes this still
     * a mismatch control rather than a silence.
     */
    @Test
    fun `negative control - a genuine mismatch survives the narrowed receiver`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(n: Node2): string {
                    if (isWide(n)) {
                        return n.text;
                    }
                    return "";
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2322 && it.message == "Type 'string | boolean' is not assignable to type 'string'." })
    }
}
