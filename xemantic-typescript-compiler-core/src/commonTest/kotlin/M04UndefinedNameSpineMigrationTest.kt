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
 * (M0.4, round 640): pins for the checkUndefinedClassInterfaceName
 * (TS2414/TS2427/TS2457 predefined-type-name checks + the piggy-backed
 * TS1163 yield-outside-generator walk) spine migration — TWO interleaved
 * reach shapes in one pass: the NAME-check recursion (descends
 * Block/ModuleBlock/if/loops/switch-clauses/try/labeled but NEVER fn or
 * class-member bodies) and the yield walk (starts ONLY at the name
 * recursion's FunctionDeclaration statements, tracks generator state
 * through fn-decl/fn-expr/objlit-method asteriskToken boundaries; arrows
 * always non-generator; class-EXPRESSION members restricted to
 * method/ctor and objlit members to methods — frozen quirks).
 * All expectations verified against the pre-migration walker.
 */
class M04UndefinedNameSpineMigrationTest {

    // ── the name emitters ──────────────────────────────────────────────────

    @Test
    fun `TS2414 - a top-level class named undefined`() {
        diagnose(
            """
            class undefined {}
            """
        ) should {
            have(any { it.code == 2414 && it.message == "Class name cannot be 'undefined'." })
        }
    }

    @Test
    fun `TS2414 - a class named any`() {
        diagnose(
            """
            class any {}
            """
        ) should {
            have(any { it.code == 2414 && it.message == "Class name cannot be 'any'." })
        }
    }

    @Test
    fun `TS2427 - an interface named string`() {
        diagnose(
            """
            interface string {}
            """
        ) should {
            have(any { it.code == 2427 && it.message == "Interface name cannot be 'string'." })
        }
    }

    @Test
    fun `TS2457 - a type alias named never`() {
        diagnose(
            """
            type never = number;
            """
        ) should {
            have(any { it.code == 2457 && it.message == "Type alias name cannot be 'never'." })
        }
    }

    @Test
    fun `negative control - a capitalized name draws nothing`() {
        diagnose(
            """
            class Undefined {}
            interface Stringy {}
            type Nevermore = number;
            """
        ) should {
            have(none { it.code == 2414 || it.code == 2427 || it.code == 2457 })
        }
    }

    @Test
    fun `negative control - a dts file is never checked`() {
        diagnose(
            """
            declare class undefined {}
            """,
            fileName = "t.d.ts",
        ) should {
            have(none { it.code == 2414 })
        }
    }

    // ── name reach: walked statement positions fire ────────────────────────

    @Test
    fun `TS2414 - name positions inside blocks - namespaces - and control flow fire`() {
        diagnose(
            """
            {
                class undefined {}
            }
            namespace N {
                class any {}
            }
            if (1) {
                class boolean {}
            }
            while (0) {
                class string {}
            }
            lbl: {
                class object {}
            }
            """
        ) should {
            have(count { it.code == 2414 } == 5)
        }
    }

    @Test
    fun `TS2427 - switch clauses and try blocks fire`() {
        diagnose(
            """
            declare const n: number;
            switch (n) {
                case 1:
                    interface number {}
                    break;
                default:
                    interface bigint {}
            }
            try {
                interface symbol {}
            } catch (e) {
                interface unknown {}
            } finally {
                interface object {}
            }
            """
        ) should {
            have(count { it.code == 2427 } == 5)
        }
    }

    // ── name reach: frozen silences (never descended) ──────────────────────

    @Test
    fun `frozen - a class inside a FUNCTION body is never name-checked`() {
        diagnose(
            """
            function f() {
                class undefined {}
                type any = number;
            }
            """
        ) should {
            have(none { it.code == 2414 || it.code == 2457 })
        }
    }

    @Test
    fun `frozen - a class inside a class METHOD body is never name-checked`() {
        diagnose(
            """
            class C {
                m() {
                    class undefined {}
                }
            }
            """
        ) should {
            have(none { it.code == 2414 })
        }
    }

    @Test
    fun `frozen - a class inside an ARROW body is never name-checked`() {
        diagnose(
            """
            const f = () => {
                class undefined {}
            };
            """
        ) should {
            have(none { it.code == 2414 })
        }
    }

    // ── the yield emitter and generator-state tracking ─────────────────────

    @Test
    fun `TS1163 - yield in a non-generator function body`() {
        diagnose(
            """
            function f() {
                yield 1;
            }
            """
        ) should {
            have(any { it.code == 1163 && it.message == "A 'yield' expression is only allowed in a generator body." })
        }
    }

    @Test
    fun `negative control - yield in a generator draws nothing`() {
        diagnose(
            """
            function* g() {
                yield 1;
            }
            """
        ) should {
            have(none { it.code == 1163 })
        }
    }

    @Test
    fun `TS1163 - a non-generator nested in a generator resets the state`() {
        diagnose(
            """
            function* g() {
                function f() {
                    yield 1;
                }
            }
            """
        ) should {
            have(any { it.code == 1163 })
        }
    }

    @Test
    fun `negative control - a generator nested in a non-generator resets the state`() {
        diagnose(
            """
            function f() {
                function* g() {
                    yield 1;
                }
            }
            """
        ) should {
            have(none { it.code == 1163 })
        }
    }

    @Test
    fun `TS1163 - an arrow inside a generator is never a generator`() {
        diagnose(
            """
            function* g() {
                const a = () => {
                    yield 1;
                };
            }
            """
        ) should {
            have(any { it.code == 1163 })
        }
    }

    @Test
    fun `TS1163 - an expression-bodied arrow inside a generator fires too`() {
        diagnose(
            """
            function* g() {
                const a = () => (yield 1);
            }
            """
        ) should {
            have(any { it.code == 1163 })
        }
    }

    @Test
    fun `function-expression generator state - starred silent - unstarred fires`() {
        diagnose(
            """
            function f() {
                const h = function* () { yield 1; };
                const i = function () { yield 2; };
            }
            """
        ) should {
            have(count { it.code == 1163 } == 1)
            have(any { it.code == 1163 && it.line == 3 })
        }
    }

    @Test
    fun `object-literal method generator state - starred silent - unstarred fires`() {
        diagnose(
            """
            function f() {
                const o = {
                    *gm() { yield 1; },
                    m() { yield 2; },
                };
            }
            """
        ) should {
            have(count { it.code == 1163 } == 1)
            have(any { it.code == 1163 && it.line == 4 })
        }
    }

    @Test
    fun `frozen - an object-literal GET accessor body is never yield-walked`() {
        diagnose(
            """
            function f() {
                const o = {
                    get g() { yield 1; return 1; },
                };
            }
            """
        ) should {
            have(none { it.code == 1163 })
        }
    }

    @Test
    fun `class-declaration members inside a walked fn body - methods - ctor - and prop initializers`() {
        diagnose(
            """
            function f() {
                class C {
                    *gm() { yield 1; }
                    m() { yield 2; }
                    constructor() { yield 3; }
                    p = (yield 4);
                }
            }
            """
        ) should {
            have(none { it.code == 1163 && it.line == 3 })
            have(any { it.code == 1163 && it.line == 4 })
            have(any { it.code == 1163 && it.line == 5 })
            have(any { it.code == 1163 && it.line == 6 })
        }
    }

    @Test
    fun `frozen - class-EXPRESSION accessors and prop initializers are never yield-walked`() {
        diagnose(
            """
            function f() {
                const C = class {
                    get g() { yield 1; return 1; }
                    p = (yield 2);
                };
            }
            """
        ) should {
            have(none { it.code == 1163 })
        }
    }

    @Test
    fun `TS1163 - class-EXPRESSION methods and constructors ARE yield-walked`() {
        diagnose(
            """
            function f() {
                const C = class {
                    m() { yield 1; }
                    constructor() { yield 2; }
                };
            }
            """
        ) should {
            have(count { it.code == 1163 } == 2)
        }
    }

    // ── the yield walk starts ONLY at name-reached FunctionDeclarations ────

    @Test
    fun `frozen - a TOP-LEVEL class method body is never yield-walked`() {
        diagnose(
            """
            class C {
                m() { yield 1; }
            }
            """
        ) should {
            have(none { it.code == 1163 })
        }
    }

    @Test
    fun `frozen - a TOP-LEVEL function expression is never yield-walked`() {
        diagnose(
            """
            const h = function () { yield 1; };
            """
        ) should {
            have(none { it.code == 1163 })
        }
    }

    @Test
    fun `frozen - a top-level yield statement is never walked`() {
        diagnose(
            """
            yield;
            """
        ) should {
            have(none { it.code == 1163 })
        }
    }

    @Test
    fun `TS1163 - a FunctionDeclaration inside a block or namespace starts the walk`() {
        diagnose(
            """
            {
                function f() { yield 1; }
            }
            namespace N {
                export function g() { yield 2; }
            }
            """
        ) should {
            have(count { it.code == 1163 } == 2)
        }
    }

    // ── yield reach within a walked body ───────────────────────────────────

    @Test
    fun `frozen - a for-INITIALIZER is never yield-walked but condition and incrementor are`() {
        diagnose(
            """
            function f() {
                for (yield 1; ;) { break; }
            }
            function g() {
                for (; yield 2;) { break; }
            }
            function h() {
                for (; ; yield 3) { break; }
            }
            """
        ) should {
            have(none { it.code == 1163 && it.line == 2 })
            have(any { it.code == 1163 && it.line == 5 })
            have(any { it.code == 1163 && it.line == 8 })
        }
    }

    @Test
    fun `TS1163 - switch subject and case expressions are yield-walked`() {
        diagnose(
            """
            function f() {
                switch (yield 1) {
                    case (yield 2):
                        yield 3;
                        break;
                }
            }
            """
        ) should {
            have(count { it.code == 1163 } == 3)
        }
    }

    @Test
    fun `TS1163 - variable initializers - returns - and template spans are yield-walked`() {
        diagnose(
            """
            function f() {
                const x = yield 1;
                const s = `v${"$"}{yield 2}`;
                return yield 3;
            }
            """
        ) should {
            have(count { it.code == 1163 } == 3)
        }
    }

    @Test
    fun `TS1163 - a nested yield operand emits for both yields`() {
        diagnose(
            """
            function f() {
                yield (yield 1);
            }
            """
        ) should {
            have(count { it.code == 1163 } == 2)
        }
    }

    @Test
    fun `TS1163 - binary chains and call arguments are yield-walked`() {
        diagnose(
            """
            declare function use(n: any): void;
            function f() {
                const x = 1 + (yield 1) + 2;
                use(yield 2);
            }
            """
        ) should {
            have(count { it.code == 1163 } == 2)
        }
    }
}
