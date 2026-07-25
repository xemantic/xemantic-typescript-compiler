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
 * (M0.4, round 625): pins for the checkImplicitThis (TS2683/TS7041/TS7017)
 * spine migration — the legacy walk's downward context (thisIsTyped /
 * insideFunction / shadowFunctionPos / insideArrowFunction) rebuilt as a
 * pull-based ancestor fold, its REACH quirks (param defaults and
 * object-literal accessors unreached; the CallExpression-CALLEE edge alone
 * drops the arrow context while the PropertyAccess receiver keeps it), the B123
 * shadow-related-info rule, and the round-79h/B374 contextual-`this`
 * suppressions. All expectations verified against the pre-migration walker.
 */
class M04ImplicitThisSpineMigrationTest {

    @Test
    fun `this in a plain function body fires TS2683`() {
        diagnose(
            """
            function f() {
                this;
            }
            """
        ) should {
            have(any { it.code == 2683 })
        }
    }

    @Test
    fun `a this-colon parameter suppresses TS2683`() {
        diagnose(
            """
            interface Ctx { n: number }
            function f(this: Ctx) {
                this;
            }
            """
        ) should {
            have(none { it.code == 2683 })
        }
    }

    @Test
    fun `function expression shadowing a class-typed this carries the TS2738 related info`() {
        diagnose(
            """
            class C {
                m() {
                    const f = function () { return this; };
                }
            }
            """
        ) should {
            have(any { d ->
                d.code == 2683 && d.relatedInformation.any { it.code == 2738 }
            })
        }
    }

    @Test
    fun `B123 - a function nested in an already-untyped function gets TS2683 without TS2738`() {
        diagnose(
            """
            function outer() {
                function inner() {
                    this;
                }
            }
            """
        ) should {
            have(any { d -> d.code == 2683 && d.relatedInformation.isEmpty() })
            have(none { d -> d.relatedInformation.any { it.code == 2738 } })
        }
    }

    @Test
    fun `class property initializer function expression shadows the typed this`() {
        diagnose(
            """
            class C {
                p = function () { this; };
            }
            """
        ) should {
            have(any { d ->
                d.code == 2683 && d.relatedInformation.any { it.code == 2738 }
            })
        }
    }

    @Test
    fun `negative control - class method and arrow inside it draw nothing`() {
        diagnose(
            """
            class C {
                m() {
                    this;
                    const a = () => this;
                }
            }
            """
        ) should {
            have(none { it.code == 2683 })
            have(none { it.code == 7041 })
        }
    }

    @Test
    fun `negative control - object literal method and function-valued property have typed this`() {
        diagnose(
            """
            const o = {
                m() { this; },
                p: function () { this; },
            };
            """
        ) should {
            have(none { it.code == 2683 })
        }
    }

    @Test
    fun `top-level arrow capturing global this fires TS7041`() {
        diagnose(
            """
            const f = () => { this; };
            """
        ) should {
            have(any { it.code == 7041 })
        }
    }

    @Test
    fun `legacy quirk - the call-callee edge drops the arrow context so this draws no TS7041`() {
        diagnose(
            """
            const f = () => this();
            """
        ) should {
            have(none { it.code == 7041 })
        }
    }

    @Test
    fun `top-level arrow this-dot-unknown fires TS7017 and TS7041 at the receiver`() {
        // The PropertyAccess RECEIVER edge keeps the arrow context (only the
        // call-callee edge drops it) — both codes fire, like tsc's
        // emitCapturingThisInTupleDestructuring1 baseline.
        diagnose(
            """
            const f = () => this.unknownPropXyz;
            """
        ) should {
            have(any { it.code == 7017 })
            have(any { it.code == 7041 })
        }
    }

    @Test
    fun `negative control - this-dot-known-global in a top-level arrow draws no TS7017`() {
        diagnose(
            """
            const f = () => this.Math;
            """
        ) should {
            have(none { it.code == 7017 })
        }
    }

    @Test
    fun `contextual this from an aliased function-type annotation suppresses TS2683`() {
        diagnose(
            """
            interface Ctx { n: number }
            type F = (this: Ctx) => void;
            const f: F = function () { this; };
            """
        ) should {
            have(none { it.code == 2683 })
        }
    }

    @Test
    fun `contextual this from a callback parameter with a this-colon suppresses TS2683`() {
        diagnose(
            """
            interface Ctx { n: number }
            declare function each(cb: (this: Ctx, x: number) => void): void;
            each(function (x) { this; });
            """
        ) should {
            have(none { it.code == 2683 })
        }
    }

    @Test
    fun `negative control - a callback parameter without this-colon keeps TS2683 firing`() {
        diagnose(
            """
            declare function each(cb: (x: number) => void): void;
            each(function (x) { this; });
            """
        ) should {
            have(any { it.code == 2683 })
        }
    }

    @Test
    fun `plain namespace body function fires TS2683`() {
        diagnose(
            """
            namespace A {
                export function f() { this; }
            }
            """
        ) should {
            have(any { it.code == 2683 })
        }
    }

    @Test
    fun `dotted namespace body is reached like a plain one`() {
        // A dotted `namespace A.B` is ONE ModuleDeclaration with a ModuleBlock
        // body (the name is dotted, not the nesting), so the walk reaches it.
        diagnose(
            """
            namespace A.B {
                export function f() { this; }
            }
            """
        ) should {
            have(any { it.code == 2683 })
        }
    }

    @Test
    fun `legacy quirk - parameter defaults are unreached`() {
        diagnose(
            """
            function g(x = this) {
            }
            """
        ) should {
            have(none { it.code == 2683 })
        }
    }

    @Test
    fun `legacy quirk - object literal accessor bodies are unreached`() {
        diagnose(
            """
            const o = {
                get p() { return this; },
            };
            """
        ) should {
            have(none { it.code == 2683 })
            have(none { it.code == 7041 })
        }
    }
}
