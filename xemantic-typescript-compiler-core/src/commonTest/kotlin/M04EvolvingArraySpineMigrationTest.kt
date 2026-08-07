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
 * (M0.4, round 639): pins for the checkEvolvingEmptyArrayImplicitAny
 * (TS7034/TS7005 evolving empty-array implicit-any + the round-316
 * single-array/branch-merge/snapshot push TS2345s) spine migration — a
 * per-STATEMENT-LIST scope pass. Scope owners (statement lists processed
 * with the whole-list simulation): the file, fn-declaration bodies,
 * class-DECLARATION method/ctor/accessor bodies, ModuleBlocks, and bare or
 * control-flow-position Blocks. Frozen reach quirks pinned both directions:
 * try/catch/finally clause statements and case-clause statements are
 * recursed for NESTED scopes without themselves forming a scope list (a
 * candidate declared directly there never fires), while a Block statement
 * inside them IS a scope; arrow/fn-EXPRESSION bodies and class-EXPRESSION
 * member bodies are never scopes (the deleted evRecurseScopes had no
 * expression descent); a dotted `namespace A.B` IS a scope (the parser
 * keeps one ModuleDeclaration with a direct ModuleBlock body). All
 * expectations verified against the pre-migration pass.
 */
class M04EvolvingArraySpineMigrationTest {

    // ── trigger A: `let/var/const x = []` ──────────────────────────────────

    @Test
    fun `TS7034 plus TS7005 - a same-scope read of an evolving empty array`() {
        diagnose(
            """
            let x = [];
            x;
            """
        ) should {
            have(count { it.code == 7034 && it.message == "Variable 'x' implicitly has type 'any[]' in some locations where its type cannot be determined." } == 1)
            have(count { it.code == 7005 && it.message == "Variable 'x' implicitly has an 'any[]' type." } == 1)
        }
    }

    @Test
    fun `a concretizing push gates LATER same-scope reads only`() {
        diagnose(
            """
            let x = [];
            x;
            x.push(1);
            x;
            """
        ) should {
            have(count { it.code == 7034 } == 1)
            have(count { it.code == 7005 } == 1)
        }
    }

    @Test
    fun `trigger B - an uninitialized let established by a same-scope empty-array assignment`() {
        diagnose(
            """
            let x;
            x = [];
            x;
            """
        ) should {
            have(count { it.code == 7034 } == 1)
            have(count { it.code == 7005 } == 1)
        }
    }

    @Test
    fun `negative control - trigger B excludes CAPTURED reads`() {
        diagnose(
            """
            var x;
            x = [];
            function g() { x; }
            """
        ) should {
            have(none { it.code == 7034 || it.code == 7005 })
        }
    }

    @Test
    fun `trigger A includes captured reads in a nested function`() {
        diagnose(
            """
            let x = [];
            function g() { x; }
            """
        ) should {
            have(count { it.code == 7034 } == 1)
            have(count { it.code == 7005 } == 1)
        }
    }

    @Test
    fun `a captured read fires even AFTER an outer concretizer`() {
        diagnose(
            """
            let x = [];
            x.push(1);
            function g() { x; }
            """
        ) should {
            have(count { it.code == 7034 } == 1)
            have(count { it.code == 7005 } == 1)
        }
    }

    @Test
    fun `negative control - an in-scope re-declaration suppresses`() {
        diagnose(
            """
            let x = [];
            var x;
            x;
            """
        ) should {
            have(none { it.code == 7034 || it.code == 7005 })
        }
    }

    @Test
    fun `negative control - a non-array whole-var assignment suppresses`() {
        diagnose(
            """
            let x = [];
            x = 5;
            x;
            """
        ) should {
            have(none { it.code == 7034 || it.code == 7005 })
        }
    }

    @Test
    fun `negative control - a destructuring source suppresses`() {
        diagnose(
            """
            let x = [];
            const [a] = x;
            """
        ) should {
            have(none { it.code == 7034 || it.code == 7005 })
        }
    }

    @Test
    fun `a self-spread reassign keeps the array evolving and reads the spread`() {
        diagnose(
            """
            let x = [];
            x = [...x, 1];
            x;
            """
        ) should {
            have(count { it.code == 7034 } == 1)
            have(count { it.code == 7005 } == 2)
        }
    }

    @Test
    fun `an empty-array reset is neither a read nor a concretizer`() {
        diagnose(
            """
            let x = [];
            x = [];
            x;
            """
        ) should {
            have(count { it.code == 7034 } == 1)
            have(count { it.code == 7005 } == 1)
        }
    }

    @Test
    fun `a non-empty array assignment concretizes`() {
        diagnose(
            """
            let x = [];
            x;
            x = [1];
            x;
            """
        ) should {
            have(count { it.code == 7005 } == 1)
        }
    }

    @Test
    fun `negative control - an optional-chained receiver is not a read`() {
        diagnose(
            """
            let x = [];
            x?.length;
            """
        ) should {
            have(none { it.code == 7034 || it.code == 7005 })
        }
    }

    // ── scope reach (the deleted evRecurseScopes arms, both directions) ────

    @Test
    fun `a bare Block is its own scope`() {
        diagnose(
            """
            {
                let x = [];
                x;
            }
            """
        ) should {
            have(count { it.code == 7034 } == 1)
            have(count { it.code == 7005 } == 1)
        }
    }

    @Test
    fun `an if-body Block is its own scope`() {
        diagnose(
            """
            declare const c: boolean;
            if (c) {
                let x = [];
                x;
            }
            """
        ) should {
            have(count { it.code == 7034 } == 1)
            have(count { it.code == 7005 } == 1)
        }
    }

    @Test
    fun `frozen - try-block DIRECT statements never form a scope`() {
        diagnose(
            """
            try {
                let x = [];
                x;
            } catch (e) {}
            """
        ) should {
            have(none { it.code == 7034 || it.code == 7005 })
        }
    }

    @Test
    fun `a Block statement nested in a try clause IS a scope`() {
        diagnose(
            """
            try {
                {
                    let x = [];
                    x;
                }
            } catch (e) {}
            """
        ) should {
            have(count { it.code == 7034 } == 1)
            have(count { it.code == 7005 } == 1)
        }
    }

    @Test
    fun `frozen - case-clause DIRECT statements never form a scope`() {
        diagnose(
            """
            declare const n: number;
            switch (n) {
                case 1:
                    let x = [];
                    x;
            }
            """
        ) should {
            have(none { it.code == 7034 || it.code == 7005 })
        }
    }

    @Test
    fun `a Block statement in a case clause IS a scope`() {
        diagnose(
            """
            declare const n: number;
            switch (n) {
                case 1: {
                    let x = [];
                    x;
                }
            }
            """
        ) should {
            have(count { it.code == 7034 } == 1)
            have(count { it.code == 7005 } == 1)
        }
    }

    @Test
    fun `frozen - an arrow body is never a scope`() {
        diagnose(
            """
            const f = () => {
                let x = [];
                x;
            };
            """
        ) should {
            have(none { it.code == 7034 || it.code == 7005 })
        }
    }

    @Test
    fun `frozen - a function-expression body is never a scope`() {
        diagnose(
            """
            const f = function () {
                let x = [];
                x;
            };
            """
        ) should {
            have(none { it.code == 7034 || it.code == 7005 })
        }
    }

    @Test
    fun `a nested function declaration chains scopes`() {
        diagnose(
            """
            function outer() {
                function inner() {
                    let x = [];
                    x;
                }
            }
            """
        ) should {
            have(count { it.code == 7034 } == 1)
            have(count { it.code == 7005 } == 1)
        }
    }

    @Test
    fun `a class-declaration method body is a scope`() {
        diagnose(
            """
            class C {
                m() {
                    let x = [];
                    x;
                }
            }
            """
        ) should {
            have(count { it.code == 7034 } == 1)
            have(count { it.code == 7005 } == 1)
        }
    }

    @Test
    fun `frozen - a class-EXPRESSION method body is never a scope`() {
        diagnose(
            """
            const C = class {
                m() {
                    let x = [];
                    x;
                }
            };
            """
        ) should {
            have(none { it.code == 7034 || it.code == 7005 })
        }
    }

    @Test
    fun `a namespace ModuleBlock is a scope`() {
        diagnose(
            """
            namespace N {
                let x = [];
                x;
            }
            """
        ) should {
            have(count { it.code == 7034 } == 1)
            have(count { it.code == 7005 } == 1)
        }
    }

    @Test
    fun `a dotted namespace body is a scope too`() {
        // The parser keeps `namespace A.B` as ONE ModuleDeclaration with a
        // direct ModuleBlock body, so the deleted evRecurseScopes'
        // `body as? ModuleBlock` arm matched it.
        diagnose(
            """
            namespace A.B {
                let x = [];
                x;
            }
            """
        ) should {
            have(count { it.code == 7034 } == 1)
            have(count { it.code == 7005 } == 1)
        }
    }

    @Test
    fun `a for-loop body Block is a scope`() {
        diagnose(
            """
            for (let i = 0; i < 1; i++) {
                let x = [];
                x;
            }
            """
        ) should {
            have(count { it.code == 7034 } == 1)
            have(count { it.code == 7005 } == 1)
        }
    }

    // ── the round-316 push checks (Parts 2/3/4) ────────────────────────────

    @Test
    fun `Part 2 - a push against a single-array-literal local checks the element type`() {
        // 2 = the Part-2 emission + the general call-arg checker's (x infers
        // `number[]` here, so both paths fire); a migration double-emit
        // would make it 3.
        diagnose(
            """
            let x = [5];
            x.push("s");
            """
        ) should {
            have(count { it.code == 2345 && it.message == "Argument of type 'string' is not assignable to parameter of type 'number'." } == 2)
        }
    }

    @Test
    fun `Part 2 - the assignment-established variant`() {
        diagnose(
            """
            let x;
            x = [5];
            x.push("s");
            """
        ) should {
            have(count { it.code == 2345 && it.message == "Argument of type 'string' is not assignable to parameter of type 'number'." } == 1)
            have(none { it.code == 7034 || it.code == 7005 })
        }
    }

    @Test
    fun `Part 3 - branch-merged element sets intersect to never`() {
        diagnose(
            """
            declare const c: boolean;
            let x;
            if (c) {
                x = [5];
            } else {
                x = [true];
            }
            x.push("s");
            """
        ) should {
            have(count { it.code == 2345 && it.message == "Argument of type 'string' is not assignable to parameter of type 'never'." } == 1)
        }
    }

    @Test
    fun `Part 4 - a snapshot local freezes the element type`() {
        diagnose(
            """
            let x = [];
            x.push(5);
            let y = x;
            y.push("s");
            """
        ) should {
            have(count { it.code == 2345 && it.message == "Argument of type 'string' is not assignable to parameter of type 'number'." } == 1)
            have(none { it.code == 7034 || it.code == 7005 })
        }
    }

    // ── run gates ──────────────────────────────────────────────────────────

    @Test
    fun `negative control - noImplicitAny explicitly false suppresses the family`() {
        diagnose(
            """
            let x = [];
            x;
            """,
            directives = "// @strict: true\n// @noImplicitAny: false",
        ) should {
            have(none { it.code == 7034 || it.code == 7005 })
        }
    }

    @Test
    fun `negative control - a js file is skipped`() {
        diagnose(
            """
            let x = [];
            x;
            """,
            directives = "// @strict: true\n// @allowJs: true\n// @checkJs: true",
            fileName = "t.js",
        ) should {
            have(none { it.code == 7034 || it.code == 7005 })
        }
    }
}
