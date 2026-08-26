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
 * (CHK.44) A **BLOCK-SCOPED LOCAL** WITH A UNION ANNOTATION IS A RECEIVER THE
 * PROPERTY-ACCESS FAMILY COULD NOT SEE AT ALL — `const c: A | F = u; c.files`
 * inside any function body was SILENT where tsc 7.0.2 reports TS2339.
 *
 * CLAUDE.md's B83.5: such a declaration is bound by nothing, so
 * `getTypeOfIdentifier` answers `anyType` and every gate below it bails. The
 * fix reads the declaration back out of the INV.2(c) lexical scope tables at the
 * one call that asks whether a property exists on the receiver — see
 * `Checker.cmamBlockScopedReceiverType`.
 *
 * ### THIS CLASS IS ITSELF THE VACUITY TRAP (CLAUDE.md, (CHK.41))
 *
 * A TS2339 fixture written with a local passed on the BROKEN binary, which is
 * how the whole class survived unnoticed. So every positive here declares its
 * receiver INSIDE a function body — a file-level declaration has always worked
 * and would pass either way. `a FILE-LEVEL union receiver reports - the control
 * that was already green` is in the class deliberately, labelled as the control
 * it is, so the two populations can never be confused again.
 *
 * ### The refusals are pinned as refusals, not left unstated
 *
 * Three shapes are still silent BY DECISION and their tests say so:
 *  - a NULLISH union (`A | undefined`) — supplying its declared type costs **11
 *    rows on the compiler profile and 16 on harness**, none of which tsgo
 *    reports, because a nullish annotation exists to be narrowed and narrowing a
 *    body-local reference is not what this round fixes;
 *  - a NON-union annotation — it already resolves through
 *    `cmamNarrowedAnyReceiverType`'s flow recovery, and routing it through the
 *    new path instead costs 3 rows on services/server/harness
 *    (`let next: Symbol` narrowed by a type guard inside a `while` condition);
 *  - an UN-ANNOTATED local — a measured KNOWN GAP, not a decision.
 *
 * Every expectation is tsc 7.0.2's, read off `tools/tsgo-7.0.2/lib/tsc --noEmit`
 * over the same source.
 */
class BlockScopedReceiverTypeTest {

    private val prelude = """
        interface A { files: string[] }
        type F = () => string[];
        interface B { other: number }
        declare const u: A | F;
        declare const ab: A | B;
        declare const au: A | undefined;
    """.trimIndent() + "\n"

    // --- POSITIVES: the population the round closed --------------------------

    @Test
    fun `a union-annotated const in a function body reports a member on no constituent`() {
        val d = diagnose(prelude + "export function f() { const c: A | F = u; c.files; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a union-annotated let in a function body reports a member on no constituent`() {
        val d = diagnose(prelude + "export function f() { let c: A | F = u; c.files; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a union-annotated var in a function body reports a member on no constituent`() {
        val d = diagnose(prelude + "export function f() { var c: A | F = u; c.files; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a union-annotated local in a METHOD body reports`() {
        val d = diagnose(prelude + "export class K { m() { const c: A | F = u; c.files; } }")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a union-annotated local in an ARROW body reports`() {
        val d = diagnose(prelude + "export const f = () => { const c: A | F = u; c.files; };")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a union-annotated local in a NESTED function body reports`() {
        val d = diagnose(
            prelude + "export function outer() { function inner() { const c: A | F = u; c.files; } inner(); }"
        )
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a union-annotated local in a NESTED block reports`() {
        val d = diagnose(prelude + "export function f() { { const c: A | F = u; c.files; } }")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a union-annotated local in a FILE-LEVEL block reports`() {
        val d = diagnose(prelude + "{ const c: A | F = u; c.files; }\nexport {};")
        assert(d.count { it.code == 2339 } == 1)
    }

    /**
     * The message must name the DECLARED UNION, not `any` and not one member —
     * a count-only pin cannot tell the receiver was typed from the annotation
     * rather than recovered from somewhere else.
     */
    @Test
    fun `the reported receiver type is the declared union`() {
        val d = diagnose(prelude + "export function f() { const c: A | F = u; c.files; }")
        val m = d.single { it.code == 2339 }.message
        assert(m.contains("Property 'files' does not exist on type 'A |"))
    }

    // --- NEGATIVES a silencing fix could not satisfy -------------------------

    @Test
    fun `a member present on EVERY constituent stays silent`() {
        val d = diagnose(prelude + "export function f() { const c: A | A = u as A; c.files; }")
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `a member reached after a correct narrow stays silent`() {
        val d = diagnose(
            prelude + "export function f() { const c: A | F = u; if (typeof c !== 'function') { c.files; } }"
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `a member present on one constituent of an object union reports once, naming the union`() {
        val d = diagnose(prelude + "export function f() { const c: A | B = ab; c.files; }")
        val hits = d.filter { it.code == 2339 }
        assert(hits.size == 1)
        assert(hits[0].message.contains("'A | B'"))
    }

    // --- REFUSALS, pinned as refusals ---------------------------------------

    /**
     * MEASURED, not conservative: a `T | undefined` local exists to be narrowed,
     * and narrowing a body-local reference is the gap this round leaves open.
     * Supplying the declared type here is 11 rows on the compiler profile that
     * tsgo does not report. tsc DOES report this one — it is a KNOWN GAP.
     */
    @Test
    fun `a NULLISH union local is deliberately still silent - a measured refusal`() {
        val d = diagnose(prelude + "export function f() { const c: A | undefined = au; c.files; }")
        assert(d.none { it.code == 2339 })
    }

    /**
     * The un-annotated local: still `any` to the property-access family, so still
     * silent where tsc reports. A KNOWN GAP, pinned so a future change is noticed.
     */
    @Test
    fun `an UN-ANNOTATED local is still silent - the remaining known gap`() {
        val d = diagnose(prelude + "export function f() { const c = u; c.files; }")
        assert(d.none { it.code == 2339 })
    }

    // --- CONTROLS -----------------------------------------------------------

    /**
     * THE CONTROL THAT NAMES THE TRAP: this shape was green before the round too,
     * because a file-level declaration IS bound. It is here so that nobody reads a
     * green file-level TS2339 fixture as coverage of the body-local population.
     */
    @Test
    fun `a FILE-LEVEL union receiver reports - the control that was already green`() {
        val d = diagnose(prelude + "const c: A | F = u;\nc.files;\nexport {};")
        assert(d.count { it.code == 2339 } == 1)
    }

    /** A parameter has always been checked — the other half of the same control. */
    @Test
    fun `a PARAMETER union receiver reports - the control that was already green`() {
        val d = diagnose(prelude + "export function f(c: A | F) { c.files; }")
        assert(d.count { it.code == 2339 } == 1)
    }
}
