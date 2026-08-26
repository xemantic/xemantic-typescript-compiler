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
 * (CHK.46) AN **UN-ANNOTATED BODY-LOCAL `const`** RECEIVER HAD NO TYPE AT ALL.
 *
 * `function f() { const c = h; c.zzznope }` reported nothing where tsgo 7.0.2
 * reports TS2339. Unlike the round's other two mechanisms this one IS a
 * block-scoping gap — CLAUDE.md's B83.5 leaves the declaration unbound, so
 * `lookupPerFileForNode` misses and `getTypeOfIdentifier` falls through to `anyType`
 * — and it is the half of (CHK.45)'s population (b) that its fix did not reach: the
 * file-level half was the all-missing whitelist, this half has no type to whitelist.
 *
 * `Checker.cmamUnannotatedLocalReceiverType` reads the INITIALIZER's type through the
 * INV.2(c) lexical tables. It is deliberately complementary to
 * `cmamBlockScopedReceiverType`, which reads such a local's ANNOTATION and supplies
 * only a `Type.Union` because (CHK.44) measured a non-union declared type at that
 * site as 3 rows on services/server/harness that tsc does not report.
 *
 * ### Two refusals that are measurements
 *
 *  - `const` ONLY. A `let`/`var` is exactly the population (CHK.44)'s 3 rows came
 *    from (a reassigned local narrowed by a type guard in a `while` condition), and a
 *    binding that cannot be reassigned removes the reaching-definition question.
 *  - a WHITELIST of initializer forms — a reference or an object literal, nothing
 *    else. A `new X(…)` initializer costs the corpus's
 *    `isolatedModulesShadowGlobalTypeNotValue` three baselines: `Date` there is a
 *    TYPE-ONLY import shadowing the global `Date` VALUE, and
 *    `getTypeOfExpression(new Date(…))` answers the imported INTERFACE, so
 *    `b.getTime()` reads as missing. That is a value-vs-type resolution gap of its
 *    own, and a receiver may not paper over it.
 *
 * ### Vacuity, and one label that is NOT a claim
 *
 * `added=0 removed=0` on all eight tsc profiles, knip 66 -> 66 byte-identical, zero
 * corpus baselines moved. Two shapes that report and are NOT this mechanism's are
 * labelled as controls below: an ANNOTATED body-local and a CALL-initialized one
 * both already reported on the parent binary, through
 * `cmamNarrowedAnyReceiverType`'s flow recovery of the declaration's assignment.
 * Every positive here goes red under arm c1 (the helper returns null) and both
 * controls stay green, which is what separates them.
 */
class UnannotatedLocalReceiverTest {

    private val prelude = """
        interface Inner { alpha: string }
        interface Holder { inner: Inner }
        declare const h: Holder;
        declare function use(x: unknown): void;
        declare function mk(): Holder;
    """.trimIndent() + "\n"

    // --- POSITIVES ------------------------------------------------------------

    @Test
    fun `an un-annotated body-local const reports a missing property`() {
        val d = diagnose(prelude + "export function f() { const c = h; use(c.zzznope); }")
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'Holder'.")
    }

    @Test
    fun `an OBJECT-LITERAL initializer reports a missing property`() {
        val d = diagnose(prelude + "export function f() { const c = { a: 1 }; use(c.zzznope); }")
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type '{ a: number; }'.")
    }

    @Test
    fun `a PROPERTY-ACCESS initializer reports a missing property`() {
        val d = diagnose(prelude + "export function f() { const c = h.inner; use(c.zzznope); }")
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'Inner'.")
    }

    @Test
    fun `an ARROW body reports a missing property`() {
        val d = diagnose(prelude + "export const g = () => { const c = h; use(c.zzznope); };")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a METHOD body reports a missing property`() {
        val d = diagnose(prelude + "export class K { m() { const c = h; use(c.zzznope); } }")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a NESTED function body reports a missing property`() {
        val d = diagnose(
            prelude + "export function outer() { function inner2() { const c = h; use(c.zzznope); } return inner2; }"
        )
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a nested BLOCK reports a missing property`() {
        val d = diagnose(prelude + "export function f() { { const c = h; use(c.zzznope); } }")
        assert(d.count { it.code == 2339 } == 1)
    }

    // --- NEGATIVES ------------------------------------------------------------

    @Test
    fun `negative control - a member that EXISTS stays silent`() {
        val d = diagnose(prelude + "export function f() { const c = h; use(c.inner); }")
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `negative control - an IN guard on the local suppresses the emission`() {
        val d = diagnose(prelude + "export function f() { const c = h; if ('zzznope' in c) { use(c.zzznope); } }")
        assert(d.none { it.code == 2339 })
    }

    // --- REFUSALS -------------------------------------------------------------

    /**
     * `let` is refused, and it is the population (CHK.44)'s three measured false
     * positives came from. tsc reports here; we do not.
     */
    @Test
    fun `refusal - a LET binding is not typed`() {
        val d = diagnose(prelude + "export function f() { let c = h; use(c.zzznope); }")
        assert(d.none { it.code == 2339 })
    }

    /**
     * The whitelist's measured boundary — see the class KDoc. tsc reports here.
     *
     * The pin is NOT what discriminates the whitelist and says so: arm c3 (drop the
     * whitelist) leaves it GREEN, because this particular `new Shape()` is a CLASS
     * instance and the trust predicate refuses it anyway. What c3 reddens is the
     * CORPUS — `isolatedModulesShadowGlobalTypeNotValue`'s three baselines — and no
     * hand-written fixture in this class can reproduce that shape, which needs a
     * type-only import shadowing a lib global.
     */
    @Test
    fun `refusal - a NEW-expression initializer is not typed`() {
        val d = diagnose(
            prelude + "class Shape { area: number = 0 }\nexport function f() { const c = new Shape(); use(c.zzznope); }"
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * A heritage-carrying interface is the B153 population — the one that measured
     * as knip's two false positives. tsc reports here; we do not.
     *
     * Which LAYER refuses it is not what reading the code predicts, and the arms say
     * so: dropping this helper's `cmamAllMissingTrustedMember` call leaves the pin
     * GREEN (arm c5), because `cmamCheckResolvedObjectType` refuses a
     * heritage-carrying `Type.Interface` downstream anyway. Recorded as a redundant
     * guard TODAY rather than claimed — it stays because it is the one firewall the
     * three (CHK.46) mechanisms share, and it is the one knip calibrated.
     */
    @Test
    fun `refusal - a HERITAGE-interface initializer is not typed`() {
        val d = diagnose(
            """
            interface Inner { alpha: string }
            interface Base { b: number }
            interface Derived extends Base { d: string }
            interface Q { der: Derived }
            declare const q: Q;
            declare function use(x: unknown): void;
            export function f() { const c = q.der; use(c.zzznope); }
            """.trimIndent()
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * A `T | undefined` initializer exists in order to be narrowed. tsc reports
     * TS18048 AND TS2339 here; we report neither.
     *
     * The layer that refuses it is `t !is Type.Object` and NOT a nullish predicate:
     * a nullish type is a UNION (or an intrinsic), so the Object test has already
     * decided it. (CHK.44)'s `typeHasNullishConstituent` line was therefore dropped
     * from this helper as provably dead rather than shipped un-gateable.
     */
    @Test
    fun `refusal - a NULLISH initializer is not typed`() {
        val d = diagnose(
            """
            interface Inner { alpha: string }
            interface Q { maybe: Inner | undefined }
            declare const q: Q;
            declare function use(x: unknown): void;
            export function f() { const c = q.maybe; use(c.zzznope); }
            """.trimIndent()
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * An ARRAY-LIKE initializer is refused for `cmamCheckNestedObjectReceiver`'s
     * measured reason: a tuple reaches `slice`/`map` through the global `Array`,
     * which `getApparentType` does not supply here, so an emission against one is a
     * false positive on real code. tsc reports here; we do not.
     *
     * A BOUNDARY pin, not a guard pin, and the arms say so: dropping this helper's
     * array-like line (arm c7), its trust-predicate call (c5), or BOTH (c9) all read
     * 0 RED against it — every shape this class can build is refused downstream by
     * `cmamCheckResolvedObjectType` as well. Both lines stay anyway: the array-like
     * one is MEASURED load-bearing on the sibling nested-receiver path (the harness
     * profile's `options.description.slice(1)`), and the trust predicate is the one
     * firewall the three (CHK.46) mechanisms share and the one knip calibrated.
     */
    @Test
    fun `refusal - a TUPLE initializer is not typed`() {
        val d = diagnose(
            """
            interface Q { tup: [number, string] }
            declare const q: Q;
            declare function use(x: unknown): void;
            export function f() { const c = q.tup; use(c.zzznope); }
            """.trimIndent()
        )
        assert(d.none { it.code == 2339 })
    }

    // --- CONTROLS: these report, and NOT through this mechanism ----------------

    /**
     * CONTROL, not a claim: an ANNOTATED body-local already reported on the parent
     * binary (`cmamNarrowedAnyReceiverType` recovers the declaration's assignment
     * through the flow), which is why this helper refuses an annotated declaration
     * outright rather than competing with `cmamBlockScopedReceiverType`.
     */
    @Test
    fun `control - an ANNOTATED body-local reports, through a different mechanism`() {
        val d = diagnose(prelude + "export function f() { const c: Holder = h; use(c.zzznope); }")
        assert(d.count { it.code == 2339 } == 1)
    }

    /**
     * CONTROL: a CALL initializer is outside the whitelist and still reports, for the
     * same flow-recovery reason. Its presence here is what says the whitelist costs
     * less than it looks like it does.
     */
    @Test
    fun `control - a CALL initializer reports, through a different mechanism`() {
        val d = diagnose(prelude + "export function f() { const c = mk(); use(c.zzznope); }")
        assert(d.count { it.code == 2339 } == 1)
    }
}
