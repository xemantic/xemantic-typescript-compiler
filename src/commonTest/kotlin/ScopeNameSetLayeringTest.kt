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
 * Round 487 (M5.1 perf): the `checkTypeAsValue*` (TS2693/TS2708) walker family
 * copied the enclosing scope's ~1000-name file-level name sets at EVERY nested
 * function/class/method (`HashSet(parent)` — the top allocation churn on tsc's own
 * checker.ts). The round replaces the flat-copy with a two-level `ScopeNameSet`: a
 * shared, never-copied file-level BASE plus a small per-scope OVERLAY that `child()`
 * copies alone. Membership is `base∪overlay` (depth-independent, ≤2 lookups). The
 * refactor relies on two facts the walker already had: every type-only / namespace-
 * only read is value-gated, and the sets grow purely additively inward — so the
 * former per-scope `remove` (param / namespace self-name shadowing a type name) is
 * subsumed by the value overlay. These tests pin the invariants the LAYERED
 * structure must uphold beyond simple propagation (covered by
 * [NestedScopeNameSetPropagationTest]): sibling-overlay isolation, namespace
 * self-name masking without an explicit remove, and depth-independent membership.
 */
class ScopeNameSetLayeringTest {

    @Test
    fun `a param shadow in one branch does NOT leak into a sibling branch`() {
        // `T` is type-only at file scope. `a`'s parameter `T` shadows it to a value,
        // so `T;` inside `a` is fine. But `b` (a SIBLING function, not nested in `a`)
        // has no such shadow — its `T;` must still fire TS2693. A layered overlay that
        // aliased instead of copied would leak `a`'s value-shadow into `b`.
        diagnose(
            """
            interface T {}
            function a(T: number) {
                T;
            }
            function b() {
                T;
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2693 && "'T'" in it.message })
            // exactly one TS2693 — only b's use, not a's shadowed one
            have(count { it.code == 2693 } == 1)
        }
    }

    @Test
    fun `an intermediate param shadow reaches a deeper read but a non-shadowing sibling still fires`() {
        // `T` type-only at file scope. `mid`'s param `T` shadows to a value; the
        // overlay must carry that value down into `deep` (no TS2693 on its `T;`).
        // `other`, nested at the same depth as `mid` but WITHOUT the shadow, must
        // still fire on its own `T;`. Stresses per-branch overlay copies.
        diagnose(
            """
            interface T {}
            function outer() {
                function mid(T: number) {
                    function deep() {
                        T;
                    }
                }
                function other() {
                    T;
                }
            }
            """.trimIndent()
        ) should {
            have(count { it.code == 2693 && "'T'" in it.message } == 1)
        }
    }

    @Test
    fun `a namespace self-reference does not fire TS2708 without an explicit remove`() {
        // `N` is a type-only (uninstantiated) namespace at file scope, so a bare `N`
        // in value position is TS2708 — EXCEPT the namespace's own body may reference
        // its own name (the IIFE binds it locally). The refactor drops the former
        // `namespaceOnlyNames.remove(selfName)`; masking now comes from adding `N` to
        // the value overlay. The inner `N;` must stay clean.
        diagnose(
            """
            namespace N {
                interface I {}
                N;
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2708 })
        }
    }

    @Test
    fun `a sibling type-only namespace referenced inside another namespace body still fires TS2708`() {
        // Masking is scoped to the SELF name only: inside `N`'s body a reference to a
        // sibling type-only namespace `M` must still fire TS2708 (the self-mask must
        // not accidentally suppress other namespace-only names).
        diagnose(
            """
            namespace M {
                interface I {}
            }
            namespace N {
                M;
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2708 && "'M'" in it.message })
        }
    }

    @Test
    fun `a hoisted var in a nested block masks a same-named type keyword`() {
        // `checkTypeAsValueInStatements` hoists a body's own value declarations into a
        // value-overlay child (`effValues`). A block-scoped `var number = 0` must make
        // the later value-position `number` resolve as the hoisted value, not the
        // `number` type keyword — no TS2693.
        diagnose(
            """
            function f() {
                {
                    number;
                    var number = 0;
                }
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2693 })
        }
    }
}
