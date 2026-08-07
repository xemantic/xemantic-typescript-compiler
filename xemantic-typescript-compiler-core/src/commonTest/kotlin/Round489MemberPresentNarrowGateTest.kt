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
 * Round 489 (M5.1 perf): the round-418 single-type narrow-DOWN suppression in
 * `checkMemberAccessMissing` ran TWO flow-narrowing walks for EVERY concrete non-union
 * receiver. Its only purpose is to suppress a would-be TS2339 when `raw` LACKS the property
 * but a narrowed strict subtype HAS it. If `raw` ALREADY resolves the property, no TS2339 can
 * fire below, so the round-489 gate skips both walks when
 * `getPropertyOfType(raw / apparent, propName) != null`.
 *
 * This pins the fast path is behavior-preserving in BOTH directions:
 *  - a property present on the declared type draws no error, even inside a guarded/narrowed
 *    context (the fast path must not accidentally emit or mis-suppress);
 *  - a property present ONLY on the narrowed subtype is still suppressed (round-418 path,
 *    which the gate must NOT skip — `raw` lacks the prop);
 *  - a genuinely-missing property still fires.
 */
class Round489MemberPresentNarrowGateTest {

    @Test
    fun `a member present on the declared type draws no error even under an active narrowing`() {
        // `n` is `Base`, which HAS `flags`. There is also a nested guard narrowing `n` DOWN
        // to `Sub` in the branch. The `n.flags` read (on the declared `Base`) takes the
        // round-489 fast path (property present on raw → skip the narrowing walks) and must
        // remain error-free.
        diagnose(
            """
            interface Base { flags: number; }
            interface Sub extends Base { extra: string; }

            export function make() {
                function isSub(x: Base): x is Sub { return "extra" in x; }
                function f(n: Base) {
                    if (isSub(n)) {
                        return n.extra + n.flags; // both present after narrow-down
                    }
                    return n.flags; // present on the declared Base — fast path
                }
                return f;
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a member present only on the narrowed subtype is still suppressed`() {
        // `extra` is NOT on `Base`; it appears only after `isSub` narrows `n` DOWN to `Sub`.
        // The gate must NOT skip the narrow-DOWN block here (raw lacks the prop), so the
        // round-418 suppression still fires and no TS2339 is emitted.
        diagnose(
            """
            interface Base { flags: number; }
            interface Sub extends Base { extra: string; }

            export function make() {
                function isSub(x: Base): x is Sub { return "extra" in x; }
                function f(n: Base) {
                    if (isSub(n)) {
                        return n.extra; // present only on the narrowed Sub
                    }
                    return 0;
                }
                return f;
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a genuinely-missing member still fires`() {
        // `absent` is on neither `Base` nor `Sub` — TS2339 MUST still fire (the gate only
        // skips when the property is present, never suppresses a real error).
        diagnose(
            """
            interface Base { flags: number; }
            interface Sub extends Base { extra: string; }

            export function make() {
                function isSub(x: Base): x is Sub { return "extra" in x; }
                function f(n: Base) {
                    if (isSub(n)) {
                        return n.absent; // on neither type
                    }
                    return 0;
                }
                return f;
            }
            """,
        ) should {
            have(any { it.code == 2339 && "'absent'" in it.message })
        }
    }
}
