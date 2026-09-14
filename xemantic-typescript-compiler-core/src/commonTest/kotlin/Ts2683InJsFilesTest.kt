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

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (LEGACY.0b) TypeScript 7 checks a `checkJs` JavaScript file for implicit `this`
 * exactly as it checks a TypeScript one.
 *
 * tsgo's `checkThisExpression` (checker.go ~12082) gates the emission on
 * `c.noImplicitThis` alone and has NO JS-file carve-out; `c.noImplicitThis` is
 * `GetStrictOptionValue(NoImplicitThis)` — the flag when EXPLICITLY set, else
 * `strict != false`. Before this round `spineItSetup` skipped every JS-like file
 * unless `noImplicitThis` was explicitly TRUE (round 79h / B438b, which modelled
 * tsc 6), and the run gate ignored an explicit `noImplicitThis: false`.
 *
 * Two tsc-6 walkers went with the change, because TypeScript 7 answers `any` for a
 * `this` it has just reported TS2683 for and so reports nothing downstream:
 * `checkJsConstructorThisReads` (B424) is RETIRED, and `checkJsPrototypeMethodThisReads`
 * (B432) now names the PROTOTYPE OBJECT LITERAL's own type rather than the
 * constructor function's name.
 *
 * Every expectation here was measured against `tools/tsgo-7.0.2/lib/tsc` on the same
 * fixture; tsgo agrees with all of them.
 */
class Ts2683InJsFilesTest {

    private val jsDirectives =
        "// @target: es2015\n// @allowJs: true\n// @checkJs: true\n// @noEmit: true\n// @filename: a.js"

    @Test
    fun `a checkJs function declaration whose this is untyped fires TS2683`() {
        diagnose(
            """
            function Ctor() {
                this.x = 1;
            }
            """,
            directives = jsDirectives,
            fileName = "a.js",
        ) should {
            have(any { it.code == 2683 })
        }
    }

    @Test
    fun `a checkJs const function expression whose this is untyped fires TS2683`() {
        diagnose(
            """
            const A = function () {
                this.x = 1;
            };
            """,
            directives = jsDirectives,
            fileName = "a.js",
        ) should {
            have(any { it.code == 2683 })
        }
    }

    @Test
    fun `negative control - allowJs WITHOUT checkJs leaves a JS file unchecked`() {
        diagnose(
            """
            function Ctor() {
                this.x = 1;
            }
            """,
            directives =
                "// @target: es2015\n// @allowJs: true\n// @noEmit: true\n// @filename: a.js",
            fileName = "a.js",
        ) should {
            have(none { it.code == 2683 })
        }
    }

    /**
     * The `.d.ts` skip predates this round ([spineItSetup]'s `!spineIsDts`) and is
     * NOT tsgo-faithful: measured, tsgo 7.0.2 answers BOTH
     * `a.d.ts(2,21): error TS1183: An implementation cannot be declared in ambient
     * contexts.` AND `a.d.ts(3,5): error TS2683` for this fixture, where we answer
     * neither. A declaration file carrying a function BODY is a degenerate,
     * already-erroneous shape and closing it belongs to whatever round owns TS1183;
     * the pin exists so the skip cannot be removed silently, and so the divergence is
     * recorded rather than rediscovered.
     *
     * The shape matters: a WELL-FORMED `.d.ts` has no function body and therefore no
     * `this` anchor at all, so a fixture without one measures nothing (the first
     * version of this pin was vacuous and its ablation arm read 0 RED).
     */
    @Test
    fun `residue - a d_ts file carrying a function body is not checked for implicit this`() {
        diagnose(
            """
            declare function h(x?: any): void;
            export function k() {
                this.x = 1;
            }
            """,
            directives = "// @target: es2015\n// @noEmit: true\n// @filename: a.d.ts",
            fileName = "a.d.ts",
        ) should {
            have(none { it.code == 2683 })
        }
    }

    @Test
    fun `negative control - a JSDoc this tag types this and suppresses TS2683`() {
        diagnose(
            """
            /** @this {{ x: number }} */
            function Ctor() {
                this.x = 1;
            }
            """,
            directives = jsDirectives,
            fileName = "a.js",
        ) should {
            have(none { it.code == 2683 })
        }
    }

    @Test
    fun `negative control - an explicit noImplicitThis false wins over the harness default`() {
        diagnose(
            """
            function Ctor() {
                this.x = 1;
            }
            """,
            directives = jsDirectives + "\n// @noImplicitThis: false",
            fileName = "a.js",
        ) should {
            have(none { it.code == 2683 })
        }
    }

    @Test
    fun `negative control - an explicit strict false suppresses TS2683 in a JS file`() {
        diagnose(
            """
            function Ctor() {
                this.x = 1;
            }
            """,
            directives = jsDirectives + "\n// @strict: false",
            fileName = "a.js",
        ) should {
            have(none { it.code == 2683 })
        }
    }

    @Test
    fun `a shadowed outer this carries the TS2738 related information in a JS file`() {
        val d = diagnose(
            """
            class K {
                constructor() { this.d = [1]; }
                m() {
                    this.d.forEach(function (v) { return v === this.d.length; });
                }
            }
            """,
            directives = jsDirectives,
            fileName = "a.js",
        )
        val row = d.firstOrNull { it.code == 2683 }
        assert(row != null)
        val related = row.relatedInformation.count {
            it.message == "An outer value of 'this' is shadowed by this container."
        }
        assert(related == 1)
    }

    @Test
    fun `B424 retired - a JS constructor function reports TS2683 and no TS2339 for a this read`() {
        val d = diagnose(
            """
            function toString() {
                this.yadda
                this.someValue = "";
            }
            """,
            directives = jsDirectives,
            fileName = "a.js",
        )
        assert(d.count { it.code == 2683 } == 2)
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `B432 names the prototype object literal type, not the constructor function`() {
        val d = diagnose(
            """
            function Widget(o) {
                this.example = true
            }
            Widget.prototype = {
                one: function () {return this;},
                two: function () {return this.missing();},
            };
            """,
            directives = jsDirectives + "\n// @noErrorTruncation: true",
            fileName = "a.js",
        )
        val row = d.firstOrNull { it.code == 2339 }
        assert(row != null)
        assert(
            row.message ==
                "Property 'missing' does not exist on type '{ one: () => any; two: () => any; }'."
        )
    }

    @Test
    fun `TS7009 fires for a plain function target in a checkJs file`() {
        diagnose(
            """
            function Plain() {}
            const p = new Plain();
            """,
            directives = jsDirectives,
            fileName = "a.js",
        ) should {
            have(any { it.code == 7009 })
        }
    }

    @Test
    fun `negative control - an explicit noImplicitAny false suppresses TS7009`() {
        diagnose(
            """
            function Plain() {}
            const p = new Plain();
            """,
            directives = jsDirectives + "\n// @noImplicitAny: false",
            fileName = "a.js",
        ) should {
            have(none { it.code == 7009 })
        }
    }

    @Test
    fun `a named function expression referring to itself fires TS7009`() {
        diagnose(
            """
            const S = function Named() { return new Named(); };
            """,
            directives = jsDirectives,
            fileName = "a.js",
        ) should {
            have(any { it.code == 7009 })
        }
    }

    @Test
    fun `negative control - a same-named class is not adopted as a function expression self-reference`() {
        diagnose(
            """
            class Named { constructor() {} }
            const S = function () {
                return new Named();
            };
            """,
            directives = "// @target: es2015\n// @noEmit: true",
            fileName = "t.ts",
        ) should {
            have(none { it.code == 7009 })
        }
    }

    @Test
    fun `negative control - a parameter shadowing the function expression name is not a self-reference`() {
        diagnose(
            """
            class Named { constructor() {} }
            const S = function Outer() {
                return function (Named) { return new Named(); };
            };
            """,
            directives = "// @target: es2015\n// @noEmit: true",
            fileName = "t.ts",
        ) should {
            have(none { it.code == 7009 })
        }
    }

    @Test
    fun `emitDeclarationOnly still reports TS2683 and TS7009`() {
        val d = diagnose(
            """
            const A = function () {
                this.x = 1;
            };
            function Plain() {}
            const p = new Plain();
            """,
            directives =
                "// @target: es2015\n// @checkJs: true\n// @declaration: true\n" +
                    "// @emitDeclarationOnly: true\n// @filename: a.js",
            fileName = "a.js",
        )
        assert(d.any { it.code == 2683 })
        assert(d.any { it.code == 7009 })
    }

    @Test
    fun `negative control - emitDeclarationOnly honours an explicit strict false`() {
        diagnose(
            """
            const A = function () {
                this.x = 1;
            };
            """,
            directives =
                "// @target: es2015\n// @checkJs: true\n// @declaration: true\n" +
                    "// @emitDeclarationOnly: true\n// @strict: false\n// @filename: a.js",
            fileName = "a.js",
        ) should {
            have(none { it.code == 2683 })
        }
    }
}
