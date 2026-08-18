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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * Round 933 — a computed member key written as a NO-SUBSTITUTION TEMPLATE names a
 * member, exactly as the quote-spelled twin one character away always did.
 *
 * THE ROUND'S INVARIANT, AND THE ONLY THING WORTH PINNING HERE: a computed key
 * whose inner expression is a literal spells ONE FIXED NAME at parse time, and the
 * three literal spellings — `[2]`, `["p"]` and a backtick-quoted `p` — must be
 * indistinguishable to every member-name extraction in the checker. Before this
 * round the backtick was the one spelling nothing could see, so it produced three
 * false positives tsc does not have (measured against `tsc 7.0.2`):
 *
 *  * `` { [`p`]: v } `` did NOT supply a required `p` — TS2741;
 *  * an interface's own `` [`ip`] `` member did not resolve — TS2339;
 *  * a class's own `` [`cp`] `` member resolved for the ASSIGNABILITY check and
 *    simultaneously FP'd TS2339, in one compile, because the type-building site
 *    and the class-AST walker are two independent extractions.
 *
 * WHAT MUST STAY REFUSED, and it is the pin that keeps this from being a licence:
 * a SUBSTITUTING template (`` [`p${x}`] ``) names no fixed member — its value is
 * decided at run time — so it supplies nothing and tsc reports TS2741 for it.
 * That row is asserted here in the positive, against the exact tsc-measured
 * message, so a future widening of the key rule that swallows it reddens.
 *
 * THE PAIRED SHAPE IS THE POINT. Every backtick pin below is written beside its
 * quote-spelled control in the SAME fixture: the control has been correct since
 * B451 and is what makes a red backtick row mean "the two spellings disagree"
 * rather than "member resolution is broken". That is also the archive's own
 * standing warning about this family (B451: member-name extraction has >= 5
 * INDEPENDENT sites, and adding computed-key support to one silently leaves the
 * others FP'ing) turned into a gate.
 *
 * A NARROWING-STYLE PROBE IS USED FOR THE RESOLUTION DIRECTION rather than a bare
 * read. Asserting that a property access is SILENT cannot tell "the member
 * resolved" from "the receiver washed to something that swallows every access"
 * (round 751's law one family over). So every resolution pin assigns the member
 * to an INCOMPATIBLE primitive and asserts the exact TS2322, which names the
 * member's real type and can only be produced by a build that found the member.
 */
class TemplateComputedMemberKeyTest {

    private fun check(@Language("typescript") source: String): List<Diagnostic> =
        diagnose(source.trimIndent(), directives = "// @strict: true")

    // ── the named false positive: supplying a REQUIRED member ──────────────

    @Test
    fun `a backtick-quoted computed key supplies a required member`() {
        // Fails against the missing `computedLiteralKey` template arm alone: the
        // key is dropped, the literal's type is `{}` and TS2741 fires.
        check(
            """
            interface Req { p: number }
            const r: Req = { [`p`]: 1 };
            """
        ) should { have(none { it.code == 2741 }) }
    }

    @Test
    fun `a quote-spelled computed key supplies a required member - the B451 control`() {
        check(
            """
            interface Req { p: number }
            const r: Req = { ["p"]: 1 };
            """
        ) should { have(none { it.code == 2741 }) }
    }

    @Test
    fun `a numeric computed key supplies a required member - the B451 control`() {
        check(
            """
            interface Req { 1: number }
            const r: Req = { [1]: 1 };
            """
        ) should { have(none { it.code == 2741 }) }
    }

    @Test
    fun `a SUBSTITUTING template key supplies nothing and stays refused`() {
        // tsc 7.0.2 emits the same TS2741 here - the key names no fixed member.
        // Asserted in the POSITIVE so a widening that swallows it reddens.
        check(
            """
            interface Req { p: number }
            declare const x: string;
            const r: Req = { [`p${'$'}{x}`]: 1 };
            """
        ) should {
            have(any {
                it.code == 2741 &&
                    it.message == "Property 'p' is missing in type '{}' but required in type 'Req'."
            })
        }
    }

    // ── the resolution direction: an INTERFACE's own member ────────────────

    @Test
    fun `an interface backtick-quoted member resolves`() {
        check(
            """
            interface I { [`ip`]: number }
            declare const i: I;
            const probe: string = i.ip;
            """
        ) should {
            have(none { it.code == 2339 })
            have(any {
                it.code == 2322 &&
                    it.message == "Type 'number' is not assignable to type 'string'."
            })
        }
    }

    @Test
    fun `an interface quote-spelled member resolves - the B451 control`() {
        check(
            """
            interface I { ["is"]: number }
            declare const i: I;
            const probe: string = i.is;
            """
        ) should {
            have(none { it.code == 2339 })
            have(any {
                it.code == 2322 &&
                    it.message == "Type 'number' is not assignable to type 'string'."
            })
        }
    }

    // ── the resolution direction: a CLASS's own member ─────────────────────

    @Test
    fun `a class backtick-quoted member resolves at BOTH extraction sites`() {
        // THE SECOND-SITE PIN. `computedLiteralKey` alone makes the TS2322 appear
        // (the type-building site found the member); `classMemberNameText` still
        // refusing the spelling makes the class-AST walker FP TS2339 beside it, in
        // the same compile. Only both assertions together see that state, which is
        // exactly what the round measured before delegating the second site.
        check(
            """
            class C { [`cp`]: number = 1; }
            declare const c: C;
            const probe: string = c.cp;
            """
        ) should {
            have(none { it.code == 2339 })
            have(any {
                it.code == 2322 &&
                    it.message == "Type 'number' is not assignable to type 'string'."
            })
        }
    }

    @Test
    fun `a class quote-spelled member resolves at both sites - the B451 control`() {
        check(
            """
            class C { ["cs"]: number = 1; }
            declare const c: C;
            const probe: string = c.cs;
            """
        ) should {
            have(none { it.code == 2339 })
            have(any {
                it.code == 2322 &&
                    it.message == "Type 'number' is not assignable to type 'string'."
            })
        }
    }

    // ── the resolution direction: an OBJECT LITERAL's own member ───────────

    @Test
    fun `an object literal backtick-quoted member resolves on a read`() {
        check(
            """
            const o = { [`op`]: 1 };
            const probe: string = o.op;
            """
        ) should {
            have(none { it.code == 2339 })
            have(any {
                it.code == 2322 &&
                    it.message == "Type 'number' is not assignable to type 'string'."
            })
        }
    }

    // ── negative controls: the arm must not invent a name ──────────────────

    @Test
    fun `negative control - a backtick-quoted key names ITS OWN text and not a neighbour`() {
        // A key that spells `other` must NOT satisfy a required `p`: the arm reads
        // the template's cooked text, it does not merely admit every template. An
        // arm that invented the name "p" emits NOTHING here, which is what this
        // discriminates.
        //
        // ROUND 934 REWROTE THE EXPECTED DIAGNOSTIC, and the rewrite is a move
        // TOWARDS tsc rather than away from it. Round 933 asserted the TS2741 this
        // compiler happened to produce; `tsc 7.0.2` reports **TS2353** for all four
        // spellings of this shape (measured: `` [`other`] ``, `["other"]`, `other`,
        // `[1]`, each naming the key as written), because the excess check runs
        // first and returns. Round 934 gave the excess check the computed key, so
        // the row now matches tsc — and asserting the message names the key's own
        // TEXT keeps the same mistake in view, more sharply than TS2741 did.
        check(
            """
            interface Req { p: number }
            const r: Req = { [`other`]: 1 };
            """
        ) should {
            have(any {
                it.code == 2353 &&
                    it.message == "Object literal may only specify known properties, " +
                    "and '[`other`]' does not exist in type 'Req'."
            })
        }
    }

    @Test
    fun `negative control - a backtick-quoted member does not answer a differently spelled read`() {
        check(
            """
            interface I { [`ip`]: number }
            declare const i: I;
            const probe = i.nope;
            """
        ) should { have(any { it.code == 2339 }) }
    }
}
