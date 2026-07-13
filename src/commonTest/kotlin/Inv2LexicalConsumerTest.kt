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
 * INV.2(d) pilot invariants: the `this.X` member check inside a BLOCK-SCOPED
 * class (B83.5 — the conventional binder never binds it) resolves the class
 * through the INV.2(c) lexical scope tables instead of synthesizing a
 * transient Symbol per visit. The diagnostics must be unchanged for the
 * single-declaration shapes, and a legal block-level class+interface MERGE —
 * which the transient (single-declaration) synthesis could not see — now
 * contributes its interface members.
 */
class Inv2LexicalConsumerTest {

    @Test
    fun `a block-scoped class still reports TS2551 with the spelling suggestion for a missing this member`() {
        // An if-body class inside a function — invisible to the conventional
        // binder (B83.5), resolved through the lexical tables. `methodA` is one
        // transposition from `methodB`, so the member check emits the TS2551
        // suggestion variant — proving the lexically-resolved class type feeds
        // the full member machinery, suggestions included.
        diagnose(
            """
            function g() {
              if (true) {
                class B {
                  methodB() {
                    this.methodA;
                  }
                }
              }
            }
            """
        ) should {
            have(any { it.code == 2551 && it.message.contains("Did you mean 'methodB'") })
        }
    }

    @Test
    fun `a deeply nested block-scoped class resolves through the scope chain`() {
        diagnose(
            """
            function f() {
              for (;;) {
                if (true) {
                  class C {
                    m() {
                      this.nope;
                    }
                  }
                }
              }
            }
            """
        ) should {
            have(any { it.code == 2339 && it.message.contains("nope") })
        }
    }

    @Test
    fun `negative control - a present member on a block-scoped class draws nothing`() {
        diagnose(
            """
            while (0) {
              class B {
                methodA() {}
                methodB() {
                  this.methodA;
                }
              }
            }
            """
        ) should {
            have(none { it.code == 2339 || it.code == 2551 })
        }
    }

    @Test
    fun `a block-level class-interface merge contributes interface members to this`() {
        // The lexical tables MERGE the block-level interface into the class
        // symbol (canMerge Class+Interface) — the old transient synthesis saw
        // only the class declaration, so `this.extra` had no chance to resolve
        // (measured: the pre-pilot checker emitted a false TS2339 here).
        diagnose(
            """
            function g() {
              if (true) {
                interface B {
                  extra: number;
                }
                class B {
                  m() {
                    this.extra;
                  }
                }
              }
            }
            """
        ) should {
            have(none { it.code == 2339 || it.code == 2551 })
        }
    }
}
