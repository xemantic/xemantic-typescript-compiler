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
 * (M0.4, round 638): pins for the checkArgumentsCollision (TS2396/TS1215)
 * spine migration — the simplest downward context of the migrated passes
 * (one CONSTANT-per-file isModule boolean + per-construct declare gates),
 * but a WIDER reach than the gIdx walker: arrows / fn-expressions /
 * class-EXPRESSION members / object-literal members / template spans /
 * typeof operands are all descended, while if/ternary CONDITIONS,
 * for/while/do/switch HEADS, and class-DECLARATION property initializers
 * stay silent. Per-construct param-check gates: FunctionDeclaration needs
 * body + !declare, class-DECLARATION method/ctor need body + !class-declare
 * (its set-accessors are body-walked but never param-checked), while
 * class-EXPRESSION and object-literal members param-check unconditionally.
 * All expectations verified against the pre-migration walker.
 */
class M04ArgsCollisionSpineMigrationTest {

    // ── the emitter's gates ────────────────────────────────────────────────

    @Test
    fun `TS2396 - a param named arguments alongside a rest param in a non-module`() {
        diagnose(
            """
            function f(arguments: string, ...rest: any[]) {}
            """
        ) should {
            have(any { it.code == 2396 && it.message == "Duplicate identifier 'arguments'. Compiler uses 'arguments' to initialize rest parameters." })
        }
    }

    @Test
    fun `negative control - no rest param in a non-module draws nothing`() {
        diagnose(
            """
            function f(arguments: string) {}
            """
        ) should {
            have(none { it.code == 2396 || it.code == 1215 })
        }
    }

    @Test
    fun `TS1215 - a param named arguments in a MODULE file needs no rest param`() {
        diagnose(
            """
            export {};
            function f(arguments: string) {}
            """
        ) should {
            have(any { it.code == 1215 && it.message == "Invalid use of 'arguments'. Modules are automatically in strict mode." })
        }
    }

    @Test
    fun `TS2396 - the REST param itself named arguments`() {
        diagnose(
            """
            function f(...arguments: any[]) {}
            """
        ) should {
            have(any { it.code == 2396 })
        }
    }

    @Test
    fun `negative control - a declare function is never param-checked`() {
        diagnose(
            """
            declare function f(arguments: string, ...rest: any[]): void;
            """
        ) should {
            have(none { it.code == 2396 })
        }
    }

    // ── class members: declaration vs expression asymmetries ──────────────

    @Test
    fun `TS2396 - class method and constructor params fire`() {
        diagnose(
            """
            class C {
                constructor(arguments: string, ...r: any[]) {}
                m(arguments: string, ...r: any[]) {}
            }
            """
        ) should {
            have(any { it.code == 2396 && it.line == 2 })
            have(any { it.code == 2396 && it.line == 3 })
        }
    }

    @Test
    fun `negative control - a declare class member is never param-checked`() {
        diagnose(
            """
            declare class C {
                m(arguments: string, ...r: any[]): void;
            }
            """
        ) should {
            have(none { it.code == 2396 })
        }
    }

    @Test
    fun `frozen - a class-DECLARATION set accessor is body-walked but never param-checked`() {
        diagnose(
            """
            export {};
            class C {
                set s(arguments: string) {}
            }
            """
        ) should {
            have(none { it.code == 1215 })
        }
    }

    @Test
    fun `TS1215 - a class-EXPRESSION set accessor param-checks unconditionally`() {
        diagnose(
            """
            export {};
            const C = class {
                set s(arguments: string) {}
            };
            """
        ) should {
            have(any { it.code == 1215 })
        }
    }

    @Test
    fun `TS2396 - class-expression method params fire`() {
        diagnose(
            """
            const C = class {
                m(arguments: string, ...r: any[]) {}
            };
            """
        ) should {
            have(any { it.code == 2396 })
        }
    }

    @Test
    fun `frozen - a class-DECLARATION property initializer is never walked`() {
        diagnose(
            """
            export {};
            class C {
                p = (arguments: string) => 1;
            }
            """
        ) should {
            have(none { it.code == 1215 })
        }
    }

    @Test
    fun `TS1215 - a class-EXPRESSION property initializer IS walked`() {
        diagnose(
            """
            export {};
            const C = class {
                p = (arguments: string) => 1;
            };
            """
        ) should {
            have(any { it.code == 1215 })
        }
    }

    // ── object-literal members, arrows, fn-expressions ─────────────────────

    @Test
    fun `TS2396 - object-literal method and setter params fire`() {
        diagnose(
            """
            const o = {
                m(arguments: string, ...r: any[]) {},
            };
            """
        ) should {
            have(any { it.code == 2396 })
        }
    }

    @Test
    fun `TS1215 - object-literal setter param fires in a module`() {
        diagnose(
            """
            export {};
            const o = {
                set s(arguments: string) {},
            };
            """
        ) should {
            have(any { it.code == 1215 })
        }
    }

    @Test
    fun `TS2396 - arrow and function-expression params fire`() {
        diagnose(
            """
            const g = (arguments: string, ...r: any[]) => {};
            const h = function (arguments: string, ...r: any[]) {};
            """
        ) should {
            have(any { it.code == 2396 && it.line == 1 })
            have(any { it.code == 2396 && it.line == 2 })
        }
    }

    // ── namespaces ─────────────────────────────────────────────────────────

    @Test
    fun `TS2396 - a function inside a non-declare namespace fires`() {
        diagnose(
            """
            namespace N {
                export function f(arguments: string, ...r: any[]) {}
            }
            """
        ) should {
            have(any { it.code == 2396 })
        }
    }

    @Test
    fun `frozen - a declare namespace body is never walked`() {
        diagnose(
            """
            export {};
            declare namespace N {
                class C {
                    m(arguments: string, ...r: any[]): void;
                }
            }
            """
        ) should {
            have(none { it.code == 1215 || it.code == 2396 })
        }
    }

    // ── reach: legacy-walked positions fire, legacy silences stay silent ───

    @Test
    fun `frozen - an if CONDITION is never walked`() {
        diagnose(
            """
            if (((arguments: string, ...r: any[]) => 1)("x")) {}
            """
        ) should {
            have(none { it.code == 2396 })
        }
    }

    @Test
    fun `frozen - a for HEAD is never walked (body is)`() {
        diagnose(
            """
            for (let g = (arguments: string, ...r: any[]) => 1; ; ) {
                const h = (arguments: string, ...r: any[]) => 2;
                break;
            }
            """
        ) should {
            have(none { it.code == 2396 && it.line == 1 })
            have(any { it.code == 2396 && it.line == 2 })
        }
    }

    @Test
    fun `frozen - a ternary CONDITION is never walked but its branches are`() {
        diagnose(
            """
            const x = ((arguments: string, ...r: any[]) => 1) ? 1 : 2;
            const y = 1 ? ((arguments: string, ...r: any[]) => 1) : 2;
            """
        ) should {
            have(none { it.code == 2396 && it.line == 1 })
            have(any { it.code == 2396 && it.line == 2 })
        }
    }

    @Test
    fun `frozen - a switch SUBJECT is never walked but clause bodies are`() {
        diagnose(
            """
            declare const n: number;
            switch (((arguments: string, ...r: any[]) => 1)("x")) {
                case 1:
                    const g = (arguments: string, ...r: any[]) => 2;
                    break;
            }
            """
        ) should {
            have(none { it.code == 2396 && it.line == 2 })
            have(any { it.code == 2396 && it.line == 4 })
        }
    }

    @Test
    fun `TS2396 - template spans and typeof operands are walked`() {
        diagnose(
            """
            const s = `x${"$"}{(arguments: string, ...r: any[]) => 1}`;
            const t = typeof ((arguments: string, ...r: any[]) => 1);
            """
        ) should {
            have(any { it.code == 2396 && it.line == 1 })
            have(any { it.code == 2396 && it.line == 2 })
        }
    }

    @Test
    fun `TS2396 - try-finally bodies and labeled statements are walked`() {
        diagnose(
            """
            try {
                const a = (arguments: string, ...r: any[]) => 1;
            } finally {
                const b = (arguments: string, ...r: any[]) => 2;
            }
            lbl: (function (arguments: string, ...r: any[]) {})();
            """
        ) should {
            have(count { it.code == 2396 } == 3)
        }
    }

    @Test
    fun `TS1215 - an export-default expression is walked`() {
        diagnose(
            """
            export default ((arguments: string) => 1);
            """
        ) should {
            have(any { it.code == 1215 })
        }
    }

    @Test
    fun `negative control - a get accessor has no params to check anywhere`() {
        diagnose(
            """
            export {};
            const o = {
                get g() { return (arguments: string) => 1; },
            };
            """
        ) should {
            have(any { it.code == 1215 }) // the nested arrow inside the body IS walked
        }
    }
}
