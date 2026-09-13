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
 * (LEGACY.0b) F6a — TypeScript 7's missing-property HEAD SUPPRESSION, both directions.
 *
 * Every expectation below was measured against `tools/tsgo-7.0.2/lib/tsc` on 2026-09-13
 * over the same source, and the rule's own corpus receipt is 27 `tsgoPendingBaselines`
 * rows that now reproduce tsgo's `.errors.txt` byte for byte.
 *
 * The whole rule is ONE comparison — does the outer head name the SAME two type displays
 * the missing-property sentence already names — so a pin set that only showed suppression
 * would be satisfied by a binary that always suppresses. Each suppressing case therefore
 * has a KEPT twin, and the two exclusion conjuncts (a conversion head, an
 * interface-implementation head) have their own.
 */
class RelationHeadSuppressionTest {

    // ------------------------------------------------------------- suppressed

    @Test
    fun `an argument whose head names the same two types reports the missing property alone`() {
        val d = diagnose(
            """
            interface ZzzQ { zid: number; zname?: string }
            declare function zg(q: ZzzQ): void;
            zg({ zname: "hello" });
            """
        )
        assert(d.size == 1)
        assert(d[0].code == 2741)
        assert(d[0].message == "Property 'zid' is missing in type '{ zname: string; }' but required in type 'ZzzQ'.")
        assert(d[0].messageChain.isEmpty())
    }

    @Test
    fun `two missing members make the leaf the TS2739 list`() {
        val d = diagnose(
            """
            interface ZzzArgs { zfile: string; zstart: number; zend: number }
            declare function zg(a: ZzzArgs): void;
            zg({ zfile: "x" });
            """
        )
        assert(d.size == 1)
        assert(d[0].code == 2739)
        assert(
            d[0].message ==
                "Type '{ zfile: string; }' is missing the following properties from type 'ZzzArgs': zstart, zend"
        )
        assert(d[0].messageChain.isEmpty())
    }

    @Test
    fun `a type-argument constraint head is suppressed the same way`() {
        val d = diagnose(
            """
            interface ZzzA { za: number }
            interface ZzzB { zb: string }
            interface ZzzC<T extends ZzzA> { zx: T }
            declare var zv: ZzzC<ZzzB>;
            """
        )
        assert(d.size == 1)
        assert(d[0].code == 2741)
        assert(d[0].message == "Property 'za' is missing in type 'ZzzB' but required in type 'ZzzA'.")
        assert(d[0].messageChain.isEmpty())
    }

    // ------------------------------------------------------------------ kept

    @Test
    fun `a head is KEPT when the missing member is declared on a base of the target`() {
        // `zc2 = zc` — the head compares `ZzzC` with `ZzzC2`, the sentence names the
        // BASE `ZzzA` that declares `x`, so the two do not match and TypeScript 7
        // prints both. The TS2720 at the `implements` clause is the fixture's own
        // second row and is asserted so the pin cannot pass on a truncated answer.
        val d = diagnose(
            """
            class ZzzA { private x = 1; foo(): number { return 1; } }
            class ZzzC implements ZzzA { foo() { return 1; } }
            class ZzzC2 extends ZzzA {}
            declare var zc: ZzzC;
            declare var zc2: ZzzC2;
            zc2 = zc;
            """
        )
        val kept = d.filter { it.code == 2322 }
        assert(kept.size == 1)
        assert(kept[0].message == "Type 'ZzzC' is not assignable to type 'ZzzC2'.")
        assert(
            kept[0].messageChain ==
                listOf("  Property 'x' is missing in type 'ZzzC' but required in type 'ZzzA'.")
        )
        assert(d.any { it.code == 2720 })
    }

    @Test
    fun `a head is KEPT when an empty subclass source renders as its base`() {
        // `zs = zi` — the head names `ZzzImage`, the sentence names the base
        // `ZzzControl` it displays as, so the displays disagree and the head stays.
        val d = diagnose(
            """
            interface ZzzSel { zstate: string; zselect(): void }
            class ZzzControl { zstate: string = ""; }
            class ZzzImage extends ZzzControl { }
            declare var zs: ZzzSel;
            declare var zi: ZzzImage;
            zs = zi;
            """
        )
        assert(d.size == 1)
        assert(d[0].code == 2322)
        assert(d[0].message == "Type 'ZzzImage' is not assignable to type 'ZzzSel'.")
        assert(
            d[0].messageChain ==
                listOf("  Property 'zselect' is missing in type 'ZzzControl' but required in type 'ZzzSel'.")
        )
    }

    // ------------------------------------------------------- the exclusions

    @Test
    fun `an interface-implementation head is EXCLUDED even though its displays match`() {
        // `isConversionOrInterfaceImplementationMessage`. The two displays here are
        // exactly the sentence's, so only the exclusion keeps this head.
        val d = diagnose(
            """
            interface ZzzI { zx: number }
            class ZzzC implements ZzzI { }
            """
        )
        assert(d.size == 1)
        assert(d[0].code == 2420)
        assert(d[0].message == "Class 'ZzzC' incorrectly implements interface 'ZzzI'.")
        assert(
            d[0].messageChain ==
                listOf("  Property 'zx' is missing in type 'ZzzC' but required in type 'ZzzI'.")
        )
    }

    @Test
    fun `a conversion head is EXCLUDED even though its displays match`() {
        val d = diagnose(
            """
            interface ZzzFoo { za: number; zb: string }
            const zx = (<ZzzFoo>{ za: null });
            """
        )
        assert(d.size == 1)
        assert(d[0].code == 2352)
        assert(
            d[0].message ==
                "Conversion of type '{ za: null; }' to type 'ZzzFoo' may be a mistake because " +
                "neither type sufficiently overlaps with the other. If this was intentional, " +
                "convert the expression to 'unknown' first."
        )
        assert(
            d[0].messageChain ==
                listOf("  Property 'zb' is missing in type '{ za: null; }' but required in type 'ZzzFoo'.")
        )
    }

    @Test
    fun `a head shape this rule cannot parse keeps its head - the fail-closed direction`() {
        // TS2684's *The 'this' context of type X is not assignable to method's 'this' of
        // type Y* carries a `(source, target)` pair that MATCHES its chain entry exactly,
        // so the only thing keeping this head is that `parseRelationHead` does not know
        // the shape and refuses rather than guessing. TypeScript 7 keeps it too: its own
        // switch lists the three missing-property messages and nothing about the head,
        // and TS2684 reaches `reportError` by a different route.
        val d = diagnose(
            """
            interface ZzzA { n: number }
            function zzzF(this: ZzzA, x: string): number { return this.n + x.length }
            const zzzBad = { m: "s", f: zzzF };
            zzzBad.f("x");
            export {};
            """
        )
        val row = d.first { it.code == 2684 }
        assert(
            row.message ==
                "The 'this' context of type '{ m: string; f: (this: ZzzA, x: string) => number; }' " +
                "is not assignable to method's 'this' of type 'ZzzA'."
        )
        assert(
            row.messageChain == listOf(
                "  Property 'n' is missing in type '{ m: string; f: (this: ZzzA, x: string) => number; }' " +
                    "but required in type 'ZzzA'."
            )
        )
    }

    // ------------------------------------------------------------- unrelated

    @Test
    fun `negative control - a chain that is not a missing-property sentence keeps its head`() {
        val d = diagnose(
            """
            declare function zf(p: string): void;
            declare const zu: string | number;
            zf(zu);
            """
        )
        assert(d.size == 1)
        assert(d[0].code == 2345)
        assert(d[0].message == "Argument of type 'string | number' is not assignable to parameter of type 'string'.")
        assert(d[0].messageChain == listOf("  Type 'number' is not assignable to type 'string'."))
    }
}
