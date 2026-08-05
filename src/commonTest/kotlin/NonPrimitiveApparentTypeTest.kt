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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (M3.0 / NONPRIM.2, round 835) The APPARENT type of the `object` keyword.
 *
 * tsc has no `object`-specific elaboration. `structuredTypeRelatedTo` compares
 * `getApparentType(source)`, and for `TypeFlags.NonPrimitive` that is
 * `emptyObjectType` — so `propertiesRelatedTo` sees a member-LESS object,
 * `reportUnmatchedProperty` fires, and the failure is reported as a MISSING
 * PROPERTY (TS2741 for one, TS2739/TS2740 for several) whose source is displayed
 * as `{}`, never as `object`.
 *
 * We keep `object` as a `Type.Intrinsic`, and `collectMissingProperties` requires a
 * `Type.Object` on both sides — so the missing set was empty by CONSTRUCTION and
 * both assignability emission paths degraded to the coarse
 * `Type 'object' is not assignable to type 'X'.` That is the whole of the
 * `nonPrimitiveAssignError` conformance case's residual diff.
 *
 * The substitution is keyed on the SOURCE's `NonPrimitive` flag and applied at the
 * two missing-property emission sites only — deliberately NOT inside
 * `getApparentType`, whose ~40 consumers ask a different question. The relation
 * VERDICT is untouched in both directions: this decides only which shape an
 * already-failing assignment is reported in.
 *
 * Every positive pin below fails on the pre-round-835 binary, which reports TS2322
 * at those positions. The negative controls fail on the two naive implementations:
 * keying on `Type.Intrinsic` instead of the `NonPrimitive` flag (which would drag
 * in `string`/`undefined`/`never` sources), and substituting the `{}` display for
 * every source rather than only the `object` one.
 */
class NonPrimitiveApparentTypeTest {

    // ---------------------------------------------------------------------
    // The assignment path — the `nonPrimitiveAssignError` shape itself.
    // ---------------------------------------------------------------------

    /**
     * Verbatim the conformance case: `y` is inferred `{ foo: string; }` and `a` is
     * `object`, so tsc reports the missing property against `{}` plus a TS2728
     * "'foo' is declared here." related info. Pre-835 this was
     * `Type 'object' is not assignable to type '{ foo: string; }'.`
     */
    @Test
    fun `an object source assigned to an object literal type reports the missing property`() {
        val diagnostics = diagnose(
            """
            var y = {foo: "bar"};
            var a: object = {};
            y = a;
            """,
        )
        diagnostics should {
            have(any {
                it.code == 2741 &&
                    it.message == "Property 'foo' is missing in type '{}' but required in type '{ foo: string; }'."
            })
        }
        val missing = diagnostics.first { it.code == 2741 }
        assert(missing.relatedInformation.any {
            it.code == 2728 && it.message == "'foo' is declared here."
        })
        // The related info anchors at the property DECLARATION — baseline
        // `nonPrimitiveAssignError.ts:2:10`, i.e. the `foo` of `var y = {foo: "bar"}`,
        // which is two lines above the assignment and at character 10 - `character` is
        // 1-based here, so the pin reads tsc's column verbatim. The
        // line is asserted RELATIVE so the pin does not encode how many prelude lines
        // `diagnose` prepends.
        val declaredHere = missing.relatedInformation.first { it.code == 2728 }
        val lineOffset = (missing.line ?: -1) - (declaredHere.line ?: -1)
        val relatedCharacter = declaredHere.character
        assert(lineOffset == 2)
        assert(relatedCharacter == 10)
    }

    /**
     * The rest of `nonPrimitiveAssignError` must not move: the six primitive
     * mismatches around it stay coarse TS2322, in BOTH directions. This is the
     * control that the substitution did not leak onto the target side — a rule
     * keyed on the TARGET's `NonPrimitive` flag would rewrite `a = n`.
     */
    @Test
    fun `primitive mismatches on either side of object stay coarse`() {
        diagnose(
            """
            var a: object = {};
            var n = 123;
            a = n;
            n = a;
            """,
        ) should {
            have(any { it.message == "Type 'number' is not assignable to type 'object'." })
            have(any { it.message == "Type 'object' is not assignable to type 'number'." })
            have(none { it.code == 2741 })
            have(none { it.code == 2739 })
        }
    }

    // ---------------------------------------------------------------------
    // The var-decl path — the assignment path's twin.
    // ---------------------------------------------------------------------

    /** The same rule at an annotated declaration, which is a separate emitter. */
    @Test
    fun `an object initializer reports the missing property at a var declaration`() {
        diagnose(
            """
            declare var a: object;
            var one: { foo: string } = a;
            """,
        ) should {
            have(any {
                it.code == 2741 &&
                    it.message == "Property 'foo' is missing in type '{}' but required in type '{ foo: string; }'."
            })
        }
    }

    /**
     * Two or more missing properties take the TS2739 shape, and its source display
     * has to be substituted too — a fix applied only to the single-property branch
     * would leave `Type 'object' is missing the following properties`.
     */
    @Test
    fun `two missing properties report TS2739 against the apparent empty type`() {
        diagnose(
            """
            declare var a: object;
            var two: { p: string; q: number } = a;
            """,
        ) should {
            have(any {
                it.code == 2739 &&
                    it.message == "Type '{}' is missing the following properties from type '{ p: string; q: number; }': p, q"
            })
        }
    }

    /** Only REQUIRED properties count as missing; an optional one is not reported. */
    @Test
    fun `an optional target property is not reported as missing`() {
        val diagnostics = diagnose(
            """
            declare var a: object;
            var three: { foo: string; bar?: number } = a;
            """,
        )
        diagnostics should {
            have(any { it.code == 2741 })
            have(none { it.code == 2739 })
        }
        assert(diagnostics.first { it.code == 2741 }
            .message.startsWith("Property 'foo' is missing in type '{}'"))
    }

    // ---------------------------------------------------------------------
    // Negative controls.
    // ---------------------------------------------------------------------

    /**
     * Negative control - the empty-object target has nothing missing, so `object`
     * still assigns to it silently. A rule that reported the apparent type's
     * emptiness rather than the TARGET's requirements would emit here.
     */
    @Test
    fun `an object source still assigns to an empty object target`() {
        diagnose(
            """
            declare var a: object;
            var four: {} = a;
            """,
        ) should { have(none { it.code == 2741 || it.code == 2739 || it.code == 2322 }) }
    }

    /**
     * Negative control - a `string` source is ALSO a `Type.Intrinsic`, so an
     * implementation keyed on the class rather than on the `NonPrimitive` flag
     * would rewrite this into "Property 'foo' is missing in type '{}'".
     */
    @Test
    fun `a primitive source keeps its own coarse message`() {
        diagnose(
            """
            declare var s: string;
            var five: { foo: string } = s;
            """,
        ) should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type 'string' is not assignable to type '{ foo: string; }'."
            })
            have(none { it.code == 2741 })
        }
    }

    /**
     * Negative control - a NAMED source that genuinely misses a property keeps its
     * own name in the message. An implementation that substituted `{}` for every
     * source of a missing-property report would rename this to `{}`.
     */
    @Test
    fun `a named interface source keeps its name in the missing property message`() {
        diagnose(
            """
            interface Src { bar: number }
            declare var src: Src;
            var six: { foo: string } = src;
            """,
        ) should {
            have(any {
                it.code == 2741 &&
                    it.message == "Property 'foo' is missing in type 'Src' but required in type '{ foo: string; }'."
            })
        }
    }
}
