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

/**
 * (INC.23) THE CANDIDATE FOURTH DIMENSION OF ROUND 778's `symbolTypes` WRITE GATE,
 * as a measurement arm.
 *
 * Round 778 gated the write on the ambient INSTANTIATION context being empty —
 * `currentTypeParamScope`, `currentTypeAliasArgs`, `inferenceNamespaceStack` — so
 * that a member whose annotation IS a type parameter cannot be frozen globally as
 * whatever the first toucher saw. That closes the "who INSTANTIATED it" axis. It
 * does not close "what was ALREADY RESOLVED when it was asked".
 *
 * (INC.23) measured that second axis on the compiler profile.
 * `resolveStructuredTypeMembersCore` returns SILENTLY, leaving `properties` null,
 * when the type it is asked about is already being resolved further up the stack —
 * a correct answer for the circular-heritage inputs it was written for, and a
 * TRUNCATED one for anything that reads the resulting key set. `keyof` over such a
 * type is empty, and a mapped type above it degrades to `any`. The ambient is empty
 * throughout, so round 778's gate says "cacheable" and the `any` is frozen for the
 * rest of the compile.
 *
 * ## What it is worth, measured
 *
 * Partition-scoping `init:buildFileLocalTypeMaps` ([FltmDefer.Scope.PARTITION])
 * takes `capture-channel-equivalence` from 286 divergent spans to 1,457. An exact
 * per-element census of all 1,457 says **78 of them are a member collapsing to
 * `any`, and all 78 are ONE member** — `[Symbol.unscopables]`, whose lib type is
 * `{ [K in keyof any[]]?: boolean }` and therefore needs `Array<any>`'s member
 * table. The other 1,379 carry no `any` on either side; they are the id-keyed
 * first-wins alias DISPLAY family (INC.11)(a) already named.
 *
 * ## IT IS REFUTED, AND THE REFUTATION IS THE ROUND'S SECOND RESULT
 *
 * Armed over the whole sweep it changes **nothing**: 1,457 divergent spans, the same
 * 78 lost-to-`any` rows, and a NARROW capture digest byte-identical to the un-gated
 * arm (`1253201111801484108`). The arm is not dead — the single-file positive
 * control shows `refusedWrites` 593 -> 594 and the victim symbol going
 * `persisted=true resolves=1` -> `persisted=false resolves=2`, i.e. the write WAS
 * refused and the symbol WAS resolved a second time. It answered `any` again.
 *
 * So the truncation is not a one-shot race that a fresh resolution would win: the
 * capture asks for the member from INSIDE `resolveReferenceMembers` for the very
 * type whose key set the mapped type needs, so every ask re-enters the same guard.
 * **The lever is the cycle handling, not the cache** — a `keyof` over a type whose
 * member table is in flight must answer from the DECLARATIONS rather than degrade,
 * which is a change to member resolution with a corpus-wide blast radius and is not
 * attempted here.
 *
 * Kept as measured negative knowledge, with the boolean it costs: off — the shipped
 * configuration and the default — `persisted` is exactly round 778's verdict and
 * this is one static boolean read.
 */
object SymTypeOrderGate {

    /**
     * When true, a resolution cut short by [Checker]'s member-resolution cycle guard
     * is NOT persisted into `symbolTypes`, so the next asker recomputes it.
     *
     * REFUSED — the recomputation answers the same `any`. False is the whole
     * compiler: the write gate is then exactly round 778's.
     */
    var refuseTruncatedWrites: Boolean = false
}
