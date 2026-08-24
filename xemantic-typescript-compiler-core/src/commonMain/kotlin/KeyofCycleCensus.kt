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
 * (INC.25) THE POPULATION COUNTER for `keyof` over a type whose member table is
 * IN FLIGHT.
 *
 * `resolveStructuredTypeMembersCore` returns SILENTLY, leaving `properties` null,
 * when asked about a type that is already being resolved further up the stack —
 * correct for the circular-heritage error inputs it was written for, and TRUNCATED
 * for anything that reads the resulting key set. `keyof` used to answer `string`
 * there, and a mapped type above it then degrades to `any` — which round 778's
 * write gate happily freezes into `symbolTypes`, because the ambient instantiation
 * context is empty throughout.
 *
 * The shipped instance of that shape is the lib's
 * `interface Array<T> { readonly [Symbol.unscopables]: { [K in keyof any[]]?: boolean } }`:
 * resolving `Array<any>`'s member table asks for that member's type, whose `keyof
 * any[]` asks back for `Array<any>`'s member table. **Measured before the fix, on a
 * THREE-LINE project with no partition at all, a completion on an array receiver
 * rendered `[Symbol.unscopables]: any`.**
 *
 * ## Why a counter and not a flag
 *
 * The answer this repairs is a captured TYPE, so no diagnostic, no emitted byte and
 * no `cost_gate.py` counter moves — the corpus and the eight profiles are green
 * either way, exactly as (INC.2) says of the whole capture channel. A pin therefore
 * needs to assert BOTH that the rendered type is right AND that the repaired path
 * was REACHED, or it is a pin over an empty population (CLAUDE.md, round 794).
 *
 * Both counters are two unconditional `Int` increments on a path that fires a
 * handful of times per compile; there is no mode to install and nothing to restore
 * except the counters themselves, which [reset] does.
 */
object KeyofCycleCensus {

    /**
     * How often `keyof` answered a truncated member table from the DECLARATIONS —
     * i.e. how often the defect above was repaired rather than observed.
     */
    var answeredFromDeclarations: Int = 0

    /**
     * How often it could not, and fell back to the pre-(INC.25) `string`. Nonzero
     * is not a defect by itself: a genuinely circular `interface A extends B` /
     * `interface B extends A` names no complete key set, and refusing is what keeps
     * a PARTIAL domain from being manufactured (round 463's law — a partial key
     * domain is a wrong answer, not a coarse one).
     */
    var refused: Int = 0

    fun reset() {
        answeredFromDeclarations = 0
        refused = 0
    }
}
