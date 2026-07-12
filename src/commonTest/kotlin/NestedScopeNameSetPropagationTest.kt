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
 * Round 486 (M5.1 perf): the `checkTypeAsValue*` (TS2693/TS2708) and `visitExpando*`
 * (TS2339) walkers copy the enclosing scope's name sets (`typeOnlyNames` /
 * `valueNames` / `namespaceOnlyNames` / expando `shadowed`) at EVERY nested
 * function/arrow so a child scope can add its own names without mutating the parent.
 * Those copies were `.toMutableSet()` / `Set.plus` — both produce a `LinkedHashSet`,
 * whose per-element `afterNodeInsertion` + insertion-ordered linked list are pure
 * overhead here (the sets are membership-only; their iteration order is never
 * consumed). On tsc's own checker.ts (one `createTypeChecker` with hundreds of nested
 * functions, a large accumulated name set) this is quadratic LinkedHashSet churn — a
 * top set-allocation source on the compiler-profile JFR. The round converts them to
 * plain `HashSet`. This test pins the INVARIANT the copies must preserve: name-set
 * MEMBERSHIP propagates correctly across deep nesting and per-scope shadowing.
 */
class NestedScopeNameSetPropagationTest {

    @Test
    fun `a type-only name used as a value fires TS2693 three functions deep`() {
        // `typeOnlyNames` must survive every nested-scope copy: `Widget` is type-only
        // and is used in value position at depth 3. A dropped copy would silence it.
        diagnose(
            """
            interface Widget {}
            function a() {
                function b() {
                    function c() {
                        Widget;
                    }
                }
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2693 && "'Widget'" in it.message })
        }
    }

    @Test
    fun `a param that shadows a type name as a value propagates into a nested function`() {
        // `T` is type-only at file scope, but `a`'s parameter `T` shadows it to a
        // VALUE. That value-name must be carried through the copy into `b`'s scope so
        // the inner `T;` is NOT flagged. A dropped `valueNames` entry re-fires TS2693.
        diagnose(
            """
            interface T {}
            function a(T: number) {
                function b() {
                    T;
                }
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2693 })
        }
    }

    @Test
    fun `a nested-function read of an undeclared expando property fires TS2339`() {
        // The expando candidate set + declared-prop map must reach the nested body:
        // `make.count` is declared, `make.missing` is not.
        diagnose(
            """
            function make() {}
            make.count = 0;
            function outer() {
                make.count;
                make.missing;
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2339 && "'missing'" in it.message })
            have(none { it.code == 2339 && "'count'" in it.message })
        }
    }

    @Test
    fun `a nested-function param shadowing the expando base suppresses TS2339`() {
        // `outer`'s parameter `make` shadows the file-level expando candidate; the
        // shadow set (copied per nested scope) must carry it so `make.missing` inside
        // `outer` reads the parameter, not the candidate — no TS2339.
        diagnose(
            """
            function make() {}
            make.count = 0;
            function outer(make: any) {
                make.missing;
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2339 })
        }
    }
}
