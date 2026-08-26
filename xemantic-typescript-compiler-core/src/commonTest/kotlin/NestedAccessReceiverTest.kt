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
 * (CHK.46) A **NESTED** ACCESS WHOSE LEAF IS A SINGLE OBJECT TYPE HAD NO EMITTER.
 *
 * `h.inner.zzznope` reported NOTHING where tsgo 7.0.2 reports TS2339 — for a
 * parameter, a file-level `const` and a body-local alike, so this is not a
 * block-scoping gap either. The receiver's TYPE was never the problem:
 * `cmamGeneralReceiverType` already reads `Inner` for `h.inner`. What was missing is
 * a CONSUMER. A union receiver is decided by `cmamCheckUnionReceiverNarrowing`, which
 * accepts a `PropertyAccessExpression` and whose tail emits for a narrowed single
 * object; every non-union receiver falls into the `objectExpr !is Identifier` branch,
 * whose three handlers are about `X.prototype` and namespace members.
 * `Checker.cmamCheckNestedObjectReceiver` is the missing single-Object emission.
 *
 * ### The FP firewall is (CHK.45)'s trust predicate, plus two measured refusals
 *
 * `cmamAllMissingTrustedMember` — calibrated on knip in the round that built it —
 * refuses a heritage-carrying interface (the B153 population knip's two measured
 * false positives came from), a `Type.Reference` (so `Inner[]` too), a class
 * instance, an intersection, a type parameter, an enum-flavoured object, a NAMED
 * anonymous object, a content-free one, and anything an index signature supplies.
 * Two refusals are this round's own and both are MEASUREMENTS:
 *  - an ARRAY-LIKE (`raw.numberIndexInfo != null`): the one row the harness profile
 *    gained before it existed was `fourslashImpl.ts`'s `options.description.slice(1)`
 *    on `[string, (string | number)[]]` — a tuple reaches `slice` through the global
 *    `Array`, which `getApparentType` does not supply here, and tsc is silent;
 *  - an `in` GUARD on the path: `if ('zzznope' in h.inner) { h.inner.zzznope }` is
 *    LEGAL, and `narrowByInOperator`'s non-union arm deliberately answers the
 *    UNCHANGED type for it, so the `narrowed !== raw` refusal cannot see it.
 *
 * ### Vacuity
 *
 * Every positive was measured SILENT on a parent binary rebuilt in this session,
 * over identical source through the CLI, and then byte-identical to tsgo in position
 * and message. Arm a1 (the helper returns false) reddens every positive and no
 * refusal; each refusal has its own arm.
 */
class NestedAccessReceiverTest {

    private val prelude = """
        interface Inner { alpha: string }
        interface Mid { inner: Inner }
        interface Base { b: number }
        interface Derived extends Base { d: string }
        interface Holder {
          inner: Inner; mid: Mid; derived: Derived; list: Inner[];
          dict: { [k: string]: Inner }; tup: [number, string]; fn: () => Inner; lit: { m: Inner };
        }
        declare const h: Holder;
        declare function use(x: unknown): void;
    """.trimIndent() + "\n"

    // --- POSITIVES ------------------------------------------------------------

    @Test
    fun `a NESTED access on a single interface leaf reports a missing property`() {
        val d = diagnose(prelude + "use(h.inner.zzznope);")
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'Inner'.")
    }

    @Test
    fun `a THREE-deep chain reports a missing property`() {
        val d = diagnose(prelude + "use(h.mid.inner.zzznope);")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `an ANONYMOUS type-literal link in the chain reports a missing property`() {
        val d = diagnose(prelude + "use(h.lit.m.zzznope);")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `an OPTIONAL chain reports a missing property`() {
        val d = diagnose(prelude + "use(h.inner?.zzznope);")
        assert(d.count { it.code == 2339 } == 1)
    }

    /**
     * The declaration sites of the ROOT, which is what makes this a receiver-shape
     * gap and not a block-scoping one — both were silent before this round.
     *
     * A BODY-LOCAL root is NOT pinned and is recorded instead (round 765): there the
     * root itself answers `any` (B83.5, gap (b)), so the chain never reaches this
     * emission at all. It is the SAME missing mechanism, one link further down.
     */
    @Test
    fun `a PARAMETER-rooted chain reports a missing property`() {
        val d = diagnose(prelude + "export function p(q: Holder) { use(q.mid.inner.zzznope); }")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a FILE-LEVEL const-rooted chain reports a missing property`() {
        val d = diagnose(prelude + "const c: Holder = h;\nuse(c.inner.zzznope);")
        assert(d.count { it.code == 2339 } == 1)
    }

    // --- NEGATIVES ------------------------------------------------------------

    @Test
    fun `negative control - a member that EXISTS stays silent`() {
        val d = diagnose(prelude + "use(h.inner.alpha);")
        assert(d.none { it.code == 2339 })
    }

    /**
     * The load-bearing negative. tsc narrows an object type by `in` (to
     * `T & Record<'zzznope', unknown>`), so this access is LEGAL — measured on tsgo
     * 7.0.2 — and `narrowByInOperator`'s non-union arm answers the UNCHANGED type,
     * which no identity test on the narrowed type can see.
     */
    @Test
    fun `negative control - an IN guard on the path suppresses the emission`() {
        val d = diagnose(prelude + "if ('zzznope' in h.inner) { use(h.inner.zzznope); }")
        assert(d.none { it.code == 2339 })
    }

    /**
     * The `in` guard is matched by PROPERTY NAME and by reference PATH, not by
     * "some guard exists": a guard about a DIFFERENT property, or about a different
     * path, must leave the emission alone.
     */
    @Test
    fun `an IN guard for a DIFFERENT property does not suppress the emission`() {
        val d = diagnose(prelude + "if ('other' in h.inner) { use(h.inner.zzznope); }")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `an IN guard on a DIFFERENT path does not suppress the emission`() {
        val d = diagnose(prelude + "if ('zzznope' in h.mid) { use(h.inner.zzznope); }")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `negative control - an INDEX-SIGNATURE leaf stays silent, as tsc does`() {
        val d = diagnose(prelude + "use(h.dict.zzznope);")
        assert(d.none { it.code == 2339 })
    }

    // --- REFUSALS: each a measured false NEGATIVE tsc reports -----------------

    /**
     * A heritage-carrying interface is the B153 population — the one
     * (CHK.45) measured as knip's two false positives. tsc reports here.
     */
    @Test
    fun `refusal - a HERITAGE interface leaf is not reported`() {
        val d = diagnose(prelude + "use(h.derived.zzznope);")
        assert(d.none { it.code == 2339 })
    }

    /**
     * A `Type.Reference` (a generic instantiation, `Inner[]` included) resolves its
     * properties through `resolveGenericPropertyType`, which this does not consult.
     * tsc reports.
     */
    @Test
    fun `refusal - a GENERIC-instantiation leaf is not reported`() {
        val d = diagnose(prelude + "use(h.list.zzznope);")
        assert(d.none { it.code == 2339 })
    }

    /**
     * The MEASURED refusal: an array-like reaches `slice`/`map`/`filter` through the
     * global `Array` and `getApparentType` does not supply those here, so an emission
     * against a tuple is a false positive on real code — `fourslashImpl.ts`'s
     * `options.description.slice(1)` was the ONE row the harness profile gained. The
     * price is this false negative, which tsc reports.
     */
    @Test
    fun `refusal - a TUPLE leaf is not reported`() {
        val d = diagnose(prelude + "use(h.tup.zzznope);")
        assert(d.none { it.code == 2339 })
    }

    /**
     * A CALL receiver has no reference path, so `narrowingEligible` refuses it before
     * this emission is reached — the flow could not be consulted for it at all. tsc
     * reports.
     */
    @Test
    fun `refusal - a CALL receiver is not reported`() {
        val d = diagnose(prelude + "use(h.fn().zzznope);")
        assert(d.none { it.code == 2339 })
    }
}
