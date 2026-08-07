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
import kotlin.test.Test

/**
 * Round 462: [typeContainsForeignTypeParam]'s name-based membership test wrongly
 * claimed a CALLEE's un-inferred type parameter as the enclosing function's own when
 * they share a NAME — tsc's `getUpToDateStatusWorker<T extends BuilderProgram>`
 * returns `forEachKey(...)` whose UNCONSTRAINED `T` collided, so the foreign-TP bail
 * never fired and the bare T FP'd TS2322 (tsbuildPublic.ts:1778; the same
 * name-collision poisoned transformer.ts:271's memoize member). A same-named TP with
 * a mismatched constraint SHAPE (own declared constrained, instance unconstrained —
 * or vice versa) is now FOREIGN. Scoped to names with a KNOWN enclosing declaration,
 * so signature-own TP names keep the pure name test (the round-431e
 * generic-function-VALUE corpus pins).
 *
 * The collision shape itself does not fire minimally (whole-program-only, verified by
 * a strictly-removal listAll diff on the self-compile dashboard) — these tests pin
 * the invariant PAIR: the collision shape stays clean while a genuine own-TP
 * mismatch keeps firing.
 */
class ForeignTpNameCollisionTest {

    @Test
    fun `a callee TP sharing the enclosing fn's TP name does not fire on the un-inferred return`() {
        diagnose("""
            interface Builder { b: number }
            interface Status { type: string }
            declare function forEachKey<K, T>(map: readonly K[], cb: (key: K) => T | undefined): T | undefined;
            declare function check(s: string): Status | undefined;
            function worker<T extends Builder>(state: T, keys: readonly string[] | undefined): Status {
                const dep = keys && forEachKey(keys, p => check(p));
                if (dep) return dep;
                return { type: "ok" };
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a genuine own-TP return mismatch still fires TS2322`() {
        diagnose("""
            interface Builder { b: number }
            function bad<T extends Builder>(x: T): string {
                return x;
            }
        """) should {
            have(any { it.code == 2322 })
        }
    }
}
