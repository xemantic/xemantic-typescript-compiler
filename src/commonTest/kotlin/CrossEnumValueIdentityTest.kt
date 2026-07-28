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
 * (REL.1)(c) step 2, round 746: two DISTINCT enums relate by VALUE — tsc's
 * `isEnumTypeRelatedTo`, i.e. every source member present in the target with an equal
 * value. This is the question round 744's `enumMemberTypesAreSameMember` deliberately
 * did not ask, and the one that kept `checkEnumToEnumAssignments` (B425) and
 * `checkNamespaceEnumUnionAssignments` (B266) alive after round 745 made the relation
 * value-aware WITHIN one enum. Both passes are retired with this rule.
 *
 * **PIN PLACEMENT IS LOAD-BEARING, and it differs per shape**, because the retired
 * walkers saw different regions:
 *  - B425 scanned only TOP-LEVEL `[declare] var X: E` annotations plus top-level
 *    `X = Y` ExpressionStatements. A pin in that exact shape therefore measures the
 *    RETIREMENT (it passed before, it must keep passing byte-for-byte); a pin in a
 *    function body or on a var-decl INITIALIZER measures the RULE, because the walker
 *    could never have answered it.
 *  - `checkEnumAsgInFunctionScopes` (B583) descends into function bodies with
 *    enum-typed PARAMETERS, so the function-body pins here use `const` initializers
 *    rather than parameters and assignments, which B583 does not claim either.
 *
 * NON-VACUITY IS MEASURED, not argued: every pin was run against a build of unmodified
 * `8a120fc6`. The first four rule pins are SILENT there and fire here; the fifth is the
 * sharpest in the file and fails on the pre-746 build in the OTHER direction — the
 * round-744 name-plus-enum-name verdict rejected a value-identical twin's member as a
 * false positive, and only the value comparison accepts it. The three retirement pins
 * pass on BOTH builds, byte-identically including the chain, which is the point: they
 * are what proves the general path reproduces the retired walker rather than
 * approximating it. "a member of a value-SHIFTED twin enum is a different member" also
 * passes on both — it is a regression guard for round 744's rule, not a target.
 */
class CrossEnumValueIdentityTest {

    private val enums = """
        namespace Same { export enum Colour { Red, Green } }
        namespace Copy { export enum Colour { Red, Green } }
        namespace Shift { export enum Colour { Red = 5, Green = 6 } }
        namespace Extra { export enum Colour { Red, Green, Blue } }
        namespace Konst { export const enum Colour { Red, Green } }
        namespace Amb { export declare enum Colour { Red, Green } }
        namespace Other { export enum Shade { Red, Green } }

    """.trimIndent()

    // --- the RULE: shapes neither retired walker could see -------------------

    @Test
    fun `an enum is not assignable to a same-named enum whose values differ`() {
        diagnose(
            enums + """
            function f(): void {
                const a: Same.Colour = Shift.Colour.Red
            }
            """,
        ) should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `an enum member is not assignable to a same-named enum whose values differ`() {
        diagnose(
            enums + """
            function f(): void {
                const b: Same.Colour = Shift.Colour.Green
            }
            """,
        ) should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `an enum missing a source member is not an assignable target`() {
        diagnose(
            enums + """
            function f(): void {
                const c: Same.Colour = Extra.Colour.Red
            }
            """,
        ) should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `a const enum relates only to itself`() {
        diagnose(
            enums + """
            function f(): void {
                const d: Same.Colour = Konst.Colour.Red
            }
            """,
        ) should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `a member of a value-identical twin enum IS the same member`() {
        diagnose(
            enums + """
            function f(): void {
                const e: Same.Colour.Red = Copy.Colour.Red
            }
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `a member of a value-SHIFTED twin enum is a different member`() {
        diagnose(
            enums + """
            function f(): void {
                const g: Same.Colour.Red = Shift.Colour.Red
            }
            """,
        ) should { have(any { it.code == 2322 }) }
    }

    // --- the FP firewall: assignability is one-directional and value-based ---

    @Test
    fun `negative control - a value-identical twin enum is assignable`() {
        diagnose(
            enums + """
            function f(): void {
                const h: Same.Colour = Copy.Colour.Green
            }
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `negative control - a target with EXTRA members still accepts the source`() {
        diagnose(
            enums + """
            function f(): void {
                const i: Extra.Colour = Same.Colour.Red
            }
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `negative control - a differently NAMED enum never reaches the value check`() {
        diagnose(
            enums + """
            function f(): void {
                const j: Other.Shade = Other.Shade.Red
            }
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * The domain-completeness trap, and the reason [enumMemberEntries] cannot read
     * `enumValues` directly: a member with NO initializer in an AMBIENT non-const enum
     * has NO value in tsc, which declines to auto-number it. We DO auto-number those —
     * the Transformer needs a value to emit — so reading them as `0, 1` would make this
     * pair look value-SHIFTED and reject a program tsc accepts.
     */
    @Test
    fun `negative control - an ambient enums opaque members are compatible with any numeric twin`() {
        diagnose(
            enums + """
            function f(): void {
                const k: Amb.Colour = Shift.Colour.Red
                const l: Shift.Colour = Amb.Colour.Red
            }
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    // --- the RETIREMENT: B425's own shape, message and elaboration ----------

    @Test
    fun `a top level enum var assignment keeps tscs qualified message and value chain`() {
        val diagnostics = diagnose(
            enums + """
            declare var same: Same.Colour
            declare var shift: Shift.Colour
            same = shift
            """,
        ).filter { it.code == 2322 }
        assert(diagnostics.size == 1)
        assert(diagnostics[0].message == "Type 'Shift.Colour' is not assignable to type 'Same.Colour'.")
        assert(
            diagnostics[0].messageChain == listOf(
                "  Each declaration of 'Colour.Red' differs in its value, " +
                    "where '0' was expected but '5' was given.",
            ),
        )
    }

    @Test
    fun `a top level enum var assignment reports the MISSING member in the chain`() {
        val diagnostics = diagnose(
            enums + """
            declare var same: Same.Colour
            declare var extra: Extra.Colour
            same = extra
            """,
        ).filter { it.code == 2322 }
        assert(diagnostics.size == 1)
        assert(diagnostics[0].message == "Type 'Extra.Colour' is not assignable to type 'Same.Colour'.")
        assert(
            diagnostics[0].messageChain == listOf(
                "  Property 'Blue' is missing in type 'Same.Colour'.",
            ),
        )
    }

    @Test
    fun `an enum pair with DIFFERENT simple names prints unqualified and without a chain`() {
        val diagnostics = diagnose(
            enums + """
            declare var same: Same.Colour
            declare var shade: Other.Shade
            same = shade
            """,
        ).filter { it.code == 2322 }
        assert(diagnostics.size == 1)
        assert(diagnostics[0].message == "Type 'Shade' is not assignable to type 'Colour'.")
        assert(diagnostics[0].messageChain.isEmpty())
    }

    @Test
    fun `negative control - a top level assignment between value-identical twins stays silent`() {
        diagnose(
            enums + """
            declare var same: Same.Colour
            declare var copy: Copy.Colour
            same = copy
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    // --- the RETIREMENT: B266's union DISPLAY rule ---------------------------
    // `checkNamespaceEnumUnionAssignments` owned three display decisions the
    // annotation-text path cannot make, and all three had to move with it: a member
    // prints QUALIFIED, a consecutive run covering every member of one enum COLLAPSES
    // to the bare enum name, and non-enum constituents sort FIRST (tsc id-orders union
    // constituents, and every intrinsic predates every enum type).

    @Test
    fun `a union target collapses a fully covered member set to the bare enum name`() {
        val diagnostics = diagnose(
            enums + """
            const u: Same.Colour.Red | Same.Colour.Green | boolean = Shift.Colour.Red
            """,
        ).filter { it.code == 2322 }
        assert(diagnostics.size == 1)
        assert(diagnostics[0].message == "Type 'Colour.Red' is not assignable to type 'boolean | Colour'.")
    }

    @Test
    fun `a union target prints a PARTIAL member set qualified and after the primitives`() {
        val diagnostics = diagnose(
            enums + """
            const v: Same.Colour.Green | boolean = Shift.Colour.Red
            """,
        ).filter { it.code == 2322 }
        assert(diagnostics.size == 1)
        assert(diagnostics[0].message == "Type 'Colour.Red' is not assignable to type 'boolean | Colour.Green'.")
    }

    @Test
    fun `a whole-enum constituent of a union target prints the bare enum name`() {
        val diagnostics = diagnose(
            enums + """
            const w: Same.Colour | boolean = Shift.Colour.Red
            """,
        ).filter { it.code == 2322 }
        assert(diagnostics.size == 1)
        assert(diagnostics[0].message == "Type 'Colour.Red' is not assignable to type 'boolean | Colour'.")
    }

    @Test
    fun `negative control - a union target containing the sources own enum accepts it`() {
        diagnose(
            enums + """
            const x: Copy.Colour | boolean = Same.Colour.Red
            """,
        ) should { have(none { it.code == 2322 }) }
    }
}
