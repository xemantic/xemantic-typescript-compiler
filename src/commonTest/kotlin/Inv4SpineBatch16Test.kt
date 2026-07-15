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
 * INV.4(c)(iii) batch 2 (round 525): the checkUnresolvedNames family's
 * STATEMENT-LEVEL emissions migrated onto the check spine — the recursive
 * `checkUnresolvedInStatements`/`checkUnresolvedInStatement(Core)` walkers are
 * deleted; per-statement dispatch lives in `spineUResDispatch` against the
 * batch-1 maintained NameScope chain, FunctionDeclaration signature positions
 * dispatch at child enters (lazy-population staging), and the legacy walk's
 * under-visits are reproduced as suppressed REGIONS (with-statement bodies,
 * skipped outside-function returns, `declare` functions/classes) plus the
 * declare-module body post-filter (only TS2304/TS2552 survive). The
 * expression/class-element walkers' 10 statement descents are cut (the spine
 * reaches nested bodies itself). These tests pin the region/staging invariants
 * beyond the corpus shapes.
 */
class Inv4SpineBatch16Test {

    // ── suppressed regions ──────────────────────────────────────────────

    @Test
    fun `with-statement body names are never checked`() {
        diagnose(
            """
            declare const obj: any;
            with (obj) {
                totallyMissingName;
            }
            """,
            directives = "// @strict: false",
        ) should {
            have(none { it.code == 2304 || it.code == 2552 })
        }
    }

    @Test
    fun `negative control - with-statement EXPRESSION is checked`() {
        diagnose(
            """
            with (totallyMissingName) {
            }
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `with-statement body suppression covers nested arrow bodies`() {
        diagnose(
            """
            declare const obj: any;
            with (obj) {
                const f = () => { totallyMissingName; };
            }
            """,
            directives = "// @strict: false",
        ) should {
            have(none { it.code == 2304 || it.code == 2552 })
        }
    }

    @Test
    fun `outside-function return expression is not checked in a module`() {
        diagnose(
            """
            export {};
            return totallyMissingName;
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `outside-function return suppression covers a nested arrow body`() {
        diagnose(
            """
            export {};
            return () => { totallyMissingName; };
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `negative control - in-function return expression is checked`() {
        diagnose(
            """
            function f() {
                return totallyMissingName;
            }
            """,
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `script-file outside-function return with a call IS checked`() {
        // tsc's emit resolver re-checks a script return containing this/call
        // (parseErrorIncorrectReturnToken's `return n.toString()` family).
        diagnose(
            """
            return totallyMissingName();
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `declare function signature types are never checked`() {
        diagnose(
            """
            declare function df(x: MissingParamType): MissingReturnType;
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `negative control - non-ambient function signature types are checked`() {
        diagnose(
            """
            function g(x: MissingParamType) {}
            """,
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `declare class body and heritage names are not TS2304-checked`() {
        diagnose(
            """
            declare class C extends MissingBase {
                m(x: MissingParamType): void;
            }
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `declare class heritage still gets the TS2314 arity check`() {
        diagnose(
            """
            class G<T> {}
            declare class D extends G {}
            """,
        ) should {
            have(any { it.code == 2314 })
        }
    }

    // ── declare-module body post-filter ─────────────────────────────────

    @Test
    fun `declare namespace body keeps bare-name TS2304`() {
        diagnose(
            """
            declare namespace N {
                const x: MissingTypeName;
            }
            """,
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `declare namespace body drops non-2304 family codes`() {
        // TS2845 (NaN comparison) fires at top level but is filtered inside a
        // declare-namespace body (the legacy walk's 2304/2552-only filter).
        diagnose(
            """
            declare namespace N {
                const b: boolean = NaN === 1;
            }
            """,
            directives = "// @strict: false",
        ) should {
            have(none { it.code == 2845 })
        }
    }

    @Test
    fun `negative control - NaN comparison fires TS2845 at top level`() {
        diagnose(
            """
            const b: boolean = NaN === 1;
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 2845 })
        }
    }

    // ── FunctionDeclaration signature staging (lazy population) ────────

    @Test
    fun `type parameter constraint does not see function parameters`() {
        // tsc evaluates TP constraints WITHOUT params in scope — `p` in the
        // constraint is unresolved even though a parameter `p` exists.
        diagnose(
            """
            function g<T extends typeof p>(p: number) {}
            """,
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `negative control - parameter default sees earlier parameters`() {
        diagnose(
            """
            function h(a: number, b = a) {}
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `unresolved name in TP constraint fires TS2304`() {
        diagnose(
            """
            function f<T extends MissingConstraint>(x: T) {}
            """,
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `es5 target hoists body vars into parameter defaults`() {
        // Below ES2015 let/const downlevels to hoisted var — a param default
        // referencing a body local is suppressed (legacy ES5-hoist collect).
        diagnose(
            """
            function f(x = later) { var later = 1; }
            """,
            directives = "// @strict: false\n// @target: es5",
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `bodyless declare function destructured rename fires TS2842`() {
        diagnose(
            """
            declare function df({ a: b }: { a: number }): void;
            """,
        ) should {
            have(any { it.code == 2842 })
        }
    }

    // ── statement-position dispatch reach ───────────────────────────────

    @Test
    fun `nested arrow block body statements are checked`() {
        diagnose(
            """
            const f = () => { totallyMissingName; };
            """,
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `object literal method body statements are checked`() {
        diagnose(
            """
            const o = { m() { totallyMissingName; } };
            """,
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `class method body statements are checked`() {
        diagnose(
            """
            class C { m() { totallyMissingName; } }
            """,
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `while and do-while conditions are checked`() {
        diagnose(
            """
            while (missingCond1) {}
            do {} while (missingCond2);
            """,
            directives = "// @strict: false",
        ) should {
            have(count { it.code == 2304 } == 2)
        }
    }

    @Test
    fun `for header initializer and condition are checked`() {
        diagnose(
            """
            for (let i = missingInit; missingCond; missingInc) {}
            """,
            directives = "// @strict: false",
        ) should {
            have(count { it.code == 2304 } == 3)
        }
    }

    @Test
    fun `for-in and for-of iterated expressions are checked`() {
        diagnose(
            """
            for (const k in missingObj1) {}
            for (const v of missingObj2) {}
            """,
            directives = "// @strict: false",
        ) should {
            have(count { it.code == 2304 } == 2)
        }
    }

    @Test
    fun `switch case clauses share one lexical scope`() {
        diagnose(
            """
            switch (1) {
                case 0:
                    let shared = 1;
                    break;
                default:
                    shared;
            }
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `switch subject and case expressions are checked`() {
        diagnose(
            """
            switch (missingSubject) {
                case missingCaseExpr:
                    break;
            }
            """,
            directives = "// @strict: false",
        ) should {
            have(count { it.code == 2304 } == 2)
        }
    }

    @Test
    fun `enum member initializers see sibling members`() {
        diagnose(
            """
            enum E { A = 1, B = A }
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `enum member initializer with unresolved name fires TS2304`() {
        diagnose(
            """
            enum E { A = totallyMissingName }
            """,
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `class with own TP in implements clause fires TS2422`() {
        diagnose(
            """
            class C<T> implements T {}
            """,
        ) should {
            have(any { it.code == 2422 })
        }
    }

    @Test
    fun `interface extending its own TP fires TS2312`() {
        diagnose(
            """
            interface I<T> extends T {}
            """,
        ) should {
            have(any { it.code == 2312 })
        }
    }

    @Test
    fun `import-equals with unresolved namespace root fires TS2503`() {
        diagnose(
            """
            import A = MissingNs.Sub;
            """,
        ) should {
            have(any { it.code == 2503 })
        }
    }

    @Test
    fun `export declaration specifier resolution is checked`() {
        diagnose(
            """
            export { totallyMissingName };
            """,
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `emitDeclarationOnly still reports unresolved names`() {
        // B95g: the declarationOnly checker runs the family via the minimal
        // spineUResOnly driver (checkSpine does not run in that mode).
        diagnose(
            """
            const x: MissingTypeName = null as any;
            """,
            directives = "// @strict: true\n// @declaration: true\n// @emitDeclarationOnly: true",
        ) should {
            have(any { it.code == 2304 })
        }
    }
}
