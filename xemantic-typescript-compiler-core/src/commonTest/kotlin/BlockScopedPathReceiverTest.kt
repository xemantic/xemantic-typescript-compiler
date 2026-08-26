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
 * (CHK.47) A NESTED ACCESS WHOSE **ROOT** IS A BLOCK-SCOPED LOCAL WAS SILENT —
 * (CHK.46)'s TWO HALVES DID NOT COMPOSE.
 *
 * (CHK.46)(b) substitutes an un-annotated body-local's initializer type at the
 * bare-Identifier bail, and (CHK.46)(d) added a single-OBJECT emission for a NESTED
 * access. Each works alone and together they did nothing: the nested path asks
 * `getTypeOfExpression` for the WHOLE chain, and an `any` root makes the whole chain
 * `any` long before either bail is reached. Measured against
 * `tools/tsgo-7.0.2/lib/tsc` on the parent binary:
 *
 * | shape | ours | tsgo |
 * |---|---|---|
 * | `const c = h; c.zzznope` (one hop shorter) | `Holder` | `Holder` |
 * | `f(p: Holder) { p.inner.zzznope }` (parameter root) | `Inner` | `Inner` |
 * | `const c = h; c.inner.zzznope` | **SILENT** | `Inner` |
 *
 * `Checker.cmamBlockScopedPathType` resolves the chain by hand from a root type the
 * two (CHK.46) helpers can name, hop by hop through the already-resolved member
 * tables. It adds no typing rule of its own, and the emission it feeds
 * (`cmamCheckNestedObjectReceiver`) keeps every refusal it already had.
 *
 * ### WHAT IT DOES NOT CLOSE, AND WHY IT IS NOT PINNED HERE
 *
 * The DESTRUCTURING composition — `const c = h; const { inner } = c;
 * inner.zzznope` — is still silent, and it is a different site: the pattern's source
 * comes from `typeCaptureDestructured`'s `VariableDeclaration` arm, which reads
 * `getTypeOfExpression(initializer)` and so answers `any` for the same reason. That
 * helper is SHARED with the (API.3d) capture channel, so the substitution has to be
 * made locally in `cmamDestructuredReceiverType`, and doing so opens a re-entrancy
 * cycle (`cmamDestructuredReceiverType` -> the fallback -> `cmamBlockScopedPathType`
 * -> `cmamDestructuredReceiverType`) that needs a depth guard of its own. Recorded
 * in the round note rather than pinned (round 765).
 *
 * ### CALIBRATION AND THE ARMS
 *
 * `added=0 removed=0` on all eight tsc profiles, knip unchanged at 49 rows, zero
 * corpus baselines moved. Three arms, one mistake each:
 *
 * | arm | mistake | RED |
 * |---|---|---|
 * | b1 | `cmamBlockScopedPathType` returns null | 3 — every positive; both controls and every refusal stay green |
 * | b2 | drop the `any`/`error`/`unknown` HOP refusal | **0**, and knip unchanged |
 * | b3 | drop the "the root must be UNTYPED" refusal | **0**, and knip unchanged |
 *
 * So the `any`-hop pin below is NOT what discriminates that line and says so: a
 * chain ending in `anyType` is refused one layer on by
 * `cmamCheckNestedObjectReceiver`'s `raw !is Type.Object`, and an intermediate one by
 * the walk's own `t as? Type.Object`. Both refusals are recorded as REDUNDANT GUARDS
 * TODAY (round 807) and kept as one-line statements of the walk's invariant — b3's
 * population is close to empty by construction, since a root the two (CHK.46)
 * helpers can type is by definition one `getTypeOfIdentifier` leaves untyped.
 */
class BlockScopedPathReceiverTest {

    private val prelude = """
        interface Leaf { alpha: string }
        interface Mid { leaf: Leaf }
        interface Top2 { mid: Mid }
        declare const t: Top2;
        declare const anyish: { m: any };
        declare const arr: { xs: Leaf[] };
        declare function use(x: unknown): void;
    """.trimIndent() + "\n"

    // --- POSITIVES ------------------------------------------------------------

    @Test
    fun `ONE hop from a block-scoped root reports the intermediate type`() {
        val d = diagnose(prelude + "export function f() { const c = t; use(c.mid.zzznope); }")
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'Mid'.")
    }

    @Test
    fun `TWO hops from a block-scoped root report the LEAF type`() {
        val d = diagnose(prelude + "export function f() { const c = t; use(c.mid.leaf.zzznope); }")
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'Leaf'.")
    }

    @Test
    fun `an ARROW body reports through the chain`() {
        val d = diagnose(prelude + "export const g = () => { const c = t; use(c.mid.zzznope); };")
        assert(d.count { it.code == 2339 } == 1)
    }

    // --- NEGATIVES ------------------------------------------------------------

    @Test
    fun `negative control - a member that EXISTS at the end of the chain stays silent`() {
        val d = diagnose(prelude + "export function f() { const c = t; use(c.mid.leaf.alpha); }")
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `negative control - an IN guard on the chain suppresses the emission`() {
        val d = diagnose(
            prelude + "export function f() { const c = t; if ('zzznope' in c.mid) { use(c.mid.zzznope); } }"
        )
        assert(d.none { it.code == 2339 })
    }

    // --- REFUSALS -------------------------------------------------------------

    /**
     * A hop that resolves to `any` STOPS the walk. tsgo is silent here too, so this
     * is parity and not a false negative.
     *
     * NOT a discriminating pin, and arm b2 says so: with the hop refusal deleted this
     * stays GREEN, because `cmamCheckNestedObjectReceiver` refuses a non-`Type.Object`
     * receiver one layer on. Recorded as an ARM pin for the shape.
     */
    @Test
    fun `refusal - a hop that resolves to any stops the walk`() {
        val d = diagnose(prelude + "export function f() { const c = anyish; use(c.m.zzznope); }")
        assert(d.none { it.code == 2339 })
    }

    /**
     * An ARRAY-LIKE leaf, which `cmamCheckNestedObjectReceiver` refuses for
     * `fourslashImpl.ts`'s measured reason (a tuple/array reaches `slice`/`map`
     * through the global `Array` interface, which `getApparentType` does not supply
     * there). tsgo reports `Leaf[]` here; we do not.
     */
    @Test
    fun `refusal - an ARRAY-LIKE leaf is not reported`() {
        val d = diagnose(prelude + "export function f() { const c = arr; use(c.xs.zzznope); }")
        assert(d.none { it.code == 2339 })
    }

    /**
     * A root that is TYPED needs no repair and must not take this path — the
     * ordinary receiver machinery owns it, and it already reported on the parent
     * binary. Labelled a CONTROL: green in both arms.
     */
    @Test
    fun `control - a PARAMETER root already reported and still does`() {
        val d = diagnose(prelude + "export function f(p: Top2) { use(p.mid.zzznope); }")
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'Mid'.")
    }
}
