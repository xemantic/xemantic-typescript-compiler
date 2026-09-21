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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.136): an assignment whose target is an ELEMENT ACCESS was never type-checked at all.
 *
 * `bag[key] = "not a function"` against `interface Bag { [k: string]: (t: {type: string}) => string }`
 * was accepted in SILENCE, while the identical mismatch through a PROPERTY target reported
 * TS2322 correctly — so the defect was exactly the element-access target and nothing else.
 * It is a FALSE-NEGATIVE class that surfaces as FALSE POSITIVES: real code guards such a write
 * with `// @ts-expect-error`, and a missing diagnostic makes that directive unused, i.e. an
 * ours-only TS2578.
 *
 * EVERY EXPECTATION HERE WAS ADJUDICATED AGAINST tsgo 7.0.2 BEFORE THE CODE WAS WRITTEN
 * (63 fixtures over five scratch projects). Two of tsgo's rules the matrix established and
 * these pins encode:
 *  - the ANCHOR is the LHS node at full width (`bag[key]` spans 8), not the whole assignment;
 *  - a COMPOUND assignment through an element access is NOT this check: `arr[i] += 123` on a
 *    `string[]` is legal, because `string + number` is `string`. Only `=` reaches the reader.
 *
 * The probes are VALUE probes wherever a silence could be ambiguous: a pin that only asserts
 * "no error" cannot tell a correctly-typed slot from a slot that resolved to `any`, which is
 * the mechanism this round is about.
 */
class ElementAccessWriteCheckTest {

    private val prelude = """
        type Fn = (t: { type: string }) => string;
        interface Bag { [k: string]: Fn }
        declare const bag: Bag;
        declare const key: string;
    """.trimIndent() + "\n"

    @Test
    fun `a string-index-signature slot rejects a primitive write`() {
        val d = diagnose(prelude + """bag[key] = "not a function";""")
        assert(d.size == 1)
        val r = d.single()
        assert(r.code == 2322)
        // tsgo prints the ALIAS here too (`Type 'number' is not assignable to type 'Fn2'.`
        // in the adjudication matrix), so the alias display is the reference answer.
        assert(r.message == "Type 'string' is not assignable to type 'Fn'.")
    }

    @Test
    fun `the element-access row is anchored at the whole LHS, tsgo's own span`() {
        // tsgo squiggles `bag[key]` — 8 characters starting at column 1 of the write.
        val d = diagnose(prelude + """bag[key] = "not a function";""")
        val r = d.single { it.code == 2322 }
        assert(r.character == 1)
        assert(r.length == 8)
    }

    @Test
    fun `an array element write rejects a mismatched element type`() {
        val d = diagnose(
            """
            declare const arr: string[];
            declare const i: number;
            arr[i] = 42;
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(d.single().code == 2322)
        assert(d.single().message == "Type 'number' is not assignable to type 'string'.")
    }

    @Test
    fun `a numeric-index-signature slot rejects a mismatched write`() {
        val d = diagnose(
            """
            declare const nmap: { [n: number]: string };
            declare const i: number;
            nmap[i] = true;
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(d.single().code == 2322)
        // The MESSAGE is deliberately not pinned here. tsgo prints the WIDENED source
        // (`Type 'boolean'`) and we print the literal (`Type 'true'`) — a divergence this
        // round neither introduces nor fixes: the PROPERTY reader, untouched here, prints
        // `Type 'true'` for `holder.u = true` too, so it is one shared display family and
        // pinning today's value would be a countdown rather than a guard.
    }

    @Test
    fun `a tuple slot at a literal index rejects a mismatched write`() {
        val d = diagnose(
            """
            declare const tup: [string, number];
            tup[0] = 42;
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(d.single().code == 2322)
        assert(d.single().message == "Type 'number' is not assignable to type 'string'.")
    }

    @Test
    fun `negative control - a correct write through an index signature is silent`() {
        val d = diagnose(prelude + """bag[key] = (t) => t.type;""")
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `negative control - an any-typed receiver is silent`() {
        val d = diagnose(
            """
            declare const anyBag: any;
            declare const key: string;
            anyBag[key] = 123;
            """.trimIndent()
        )
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - a compound assignment is not this check`() {
        // `string + number` is `string`, so tsgo accepts this. Only `=` reaches the reader,
        // and a future compound arm must compute the OPERATOR's result type rather than
        // comparing the right-hand side directly.
        val d = diagnose(
            """
            declare const arr: string[];
            declare const i: number;
            arr[i] += 123;
            """.trimIndent()
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `negative control - a nullish write into a nullable slot is silent`() {
        val d = diagnose(
            """
            declare const bag: { [k: string]: string | undefined };
            declare const key: string;
            bag[key] = undefined;
            """.trimIndent()
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `a nullish write into a non-nullable slot reports under strictNullChecks`() {
        val d = diagnose(
            """
            declare const bag: { [k: string]: string };
            declare const key: string;
            bag[key] = null;
            """.trimIndent()
        )
        assert(d.any { it.code == 2322 })
    }

    @Test
    fun `the element target and the property target agree on the same mismatch`() {
        // THE INVERSION THIS ROUND ESTABLISHES. Before it, the element row was missing while
        // the property row fired — the two readers disagreed about identical code. An ablation
        // that removes the general arm fails here and nowhere else in this class that a
        // shape-specific pin would not also catch.
        val d = diagnose(
            prelude + """
            declare const holder: { m: Fn };
            bag[key] = "wrong";
            holder.m = "wrong";
            """.trimIndent()
        )
        val rows = d.filter { it.code == 2322 }
        assert(rows.size == 2)
        assert(rows.map { it.message }.distinct().size == 1)
    }

    @Test
    fun `a receiver reached through a property access is checked`() {
        val d = diagnose(
            prelude + """
            declare const outer: { inner: Bag };
            outer.inner[key] = 7;
            """.trimIndent()
        )
        assert(d.count { it.code == 2322 } == 1)
    }

    @Test
    fun `negative control - an array literal into a tuple-bearing slot is refused, not decided`() {
        // Round 459's finding one reader over: this engine cannot decide an ARRAY LITERAL
        // against a TUPLE target, so the pair is declined. Without the refusal, tsc's own
        // `builder.ts:1423` grows an ours-only TS2322 on every profile that compiles it.
        val d = diagnose(
            """
            type Pair = [number, number];
            declare const rows: (Pair | number)[];
            declare const i: number;
            declare const a: number;
            rows[i] = [a, a];
            """.trimIndent()
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `an unresolvable slot stays silent rather than guessing`() {
        // `Map` has no index signature: tsgo answers TS7052 and runs NO assignability check.
        // We answer `anyType` for the slot and therefore emit nothing, which is the firewall
        // this reader is built on — an undecided slot is silence, never a diagnostic.
        val d = diagnose(
            """
            declare const m: Map<string, string>;
            declare const key: string;
            m[key] = "x";
            """.trimIndent()
        )
        assert(d.none { it.code == 2322 })
    }
}
