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
 * (M0.4, round 643): pins for the checkTypeParameterDefaults (TS2368
 * reserved type-parameter names + TS2744 forward/self type-parameter
 * default references + the circularDefaultTypeParamCount side-set) spine
 * migration. The pass SPLITS: the side-set write (consumed by the
 * `Name` → `Name<any, ...>` no-args display, cross-file/earlier-in-file)
 * stays in a pre-spine producer; the emissions ride the spine. Frozen
 * reach quirks pinned both directions: if CONDITIONS, switch SUBJECT +
 * case EXPRESSIONS, for-in/of heads, expression-bodied arrow BODIES,
 * object-literal ACCESSORS, enum member initializers, heritage clauses,
 * call/new TYPE ARGUMENTS, TP constraint/default INTERIORS, computed
 * property names, static blocks, and mapped/keyof type interiors are all
 * UNREACHED; for-head initializer/condition/incrementor, declare-namespace
 * bodies, catch blocks, template spans, ternary CONDITIONS, and As-cast
 * type positions ARE reached. All expectations verified against the
 * pre-migration walker.
 */
class M04TpDefaultsSpineMigrationTest {

    // ── TS2368 fires: anchor kinds ─────────────────────────────────────────

    @Test
    fun `TS2368 - function declaration type parameter named string`() {
        diagnose(
            """
            function f<string>(): void {}
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - class declaration type parameter`() {
        diagnose(
            """
            class C<string> {}
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - interface declaration type parameter`() {
        diagnose(
            """
            interface I<string> {}
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - type alias type parameter`() {
        diagnose(
            """
            type A<string> = number;
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - function expression type parameter`() {
        diagnose(
            """
            const f = function g<string>() {};
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - class expression type parameter`() {
        diagnose(
            """
            const C = class<string> {};
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - object literal method type parameter`() {
        diagnose(
            """
            const o = { m<string>() {} };
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - class method type parameter`() {
        diagnose(
            """
            class C { m<string>() {} }
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - interface method type parameter`() {
        diagnose(
            """
            interface I { m<string>(): void; }
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - function type in variable annotation`() {
        diagnose(
            """
            let f: <string>() => void;
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - constructor type in variable annotation`() {
        diagnose(
            """
            let c: new <string>() => object;
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - function type nested in union and parens`() {
        diagnose(
            """
            let f: null | (<string>() => void);
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - function type in array type`() {
        diagnose(
            """
            let f: (<string>() => void)[];
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - function type in tuple type`() {
        diagnose(
            """
            let f: [<string>() => void];
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - function type in type reference arguments`() {
        diagnose(
            """
            let f: Promise<(<string>() => void)>;
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - method member of a type literal annotation`() {
        diagnose(
            """
            let o: { m<string>(): void };
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - function type as type alias body`() {
        diagnose(
            """
            type A = <string>() => void;
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - function type in parameter annotation`() {
        diagnose(
            """
            function f(cb: <string>() => void) {}
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - function type in return annotation`() {
        diagnose(
            """
            function f(): <string>() => void { return null as any; }
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    // ── TS2744 fires ───────────────────────────────────────────────────────

    @Test
    fun `TS2744 - forward default reference on type alias`() {
        diagnose(
            """
            type A<T = U, U = number> = T;
            """
        ) should {
            have(any { it.code == 2744 })
        }
    }

    @Test
    fun `TS2744 - self default reference`() {
        diagnose(
            """
            type A<T = T> = number;
            """
        ) should {
            have(any { it.code == 2744 })
        }
    }

    @Test
    fun `TS2744 - forward default on function declaration`() {
        diagnose(
            """
            function f<T = U, U = number>(): void {}
            """
        ) should {
            have(any { it.code == 2744 })
        }
    }

    @Test
    fun `TS2744 - forward default on generic arrow`() {
        diagnose(
            """
            const f = <T = U, U = number>(x: T) => x;
            """
        ) should {
            have(any { it.code == 2744 })
        }
    }

    @Test
    fun `TS2744 - forward default on function type annotation`() {
        diagnose(
            """
            let f: <T = U, U = number>(x: T) => U;
            """
        ) should {
            have(any { it.code == 2744 })
        }
    }

    @Test
    fun `negative control - backward default reference is legal`() {
        diagnose(
            """
            type A<T = number, U = T> = U;
            """
        ) should {
            have(none { it.code == 2744 })
        }
    }

    @Test
    fun `TS2744 - function type default without own TPs descends`() {
        diagnose(
            """
            type A<T = () => U, U = number> = T;
            """
        ) should {
            have(any { it.code == 2744 })
        }
    }

    @Test
    fun `negative control - function type default with own TPs is inner scope`() {
        diagnose(
            """
            type B<T = <V>() => U, U = number> = T;
            """
        ) should {
            have(none { it.code == 2744 })
        }
    }

    // ── reach quirks: unreached positions stay silent ──────────────────────

    @Test
    fun `negative control - function type inside a TP default is not validated`() {
        diagnose(
            """
            type B<T = <string>() => void> = T;
            """
        ) should {
            have(none { it.code == 2368 })
        }
    }

    @Test
    fun `negative control - function type inside a TP constraint is not validated`() {
        diagnose(
            """
            function f<T extends <string>() => void>(): void {}
            """
        ) should {
            have(none { it.code == 2368 })
        }
    }

    @Test
    fun `negative control - heritage clause type arguments are not walked`() {
        diagnose(
            """
            interface I2 extends Array<(<string>() => void)> {}
            """
        ) should {
            have(none { it.code == 2368 })
        }
    }

    @Test
    fun `negative control - call type arguments are not walked`() {
        diagnose(
            """
            declare function g<T>(): void;
            g<(<string>() => void)>();
            """
        ) should {
            have(none { it.code == 2368 })
        }
    }

    @Test
    fun `negative control - if condition is not walked`() {
        diagnose(
            """
            if (function h<string>() {}) {}
            """
        ) should {
            have(none { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - if then-branch is walked`() {
        diagnose(
            """
            if (true) { function h<string>() {} }
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `negative control - expression-bodied arrow body is not walked`() {
        diagnose(
            """
            const f = () => function g<string>() {};
            """
        ) should {
            have(none { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - block-bodied arrow body is walked`() {
        diagnose(
            """
            const f = () => { const g = function h<string>() {}; };
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `negative control - switch subject and case expressions are not walked`() {
        diagnose(
            """
            declare const x: any;
            switch (function f<string>() {}) {
                case (function g<string>() {}): break;
            }
            """
        ) should {
            have(none { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - switch clause statements are walked`() {
        diagnose(
            """
            declare const x: any;
            switch (x) { case 1: const g = function f<string>() {}; }
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - for-head initializer condition and incrementor are walked`() {
        diagnose(
            """
            for (let i = (function f<string>() {}, 0); ; ) break;
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `negative control - enum member initializers are not walked`() {
        diagnose(
            """
            enum E { A = (function f<string>() {}, 1) as any }
            """
        ) should {
            have(none { it.code == 2368 })
        }
    }

    @Test
    fun `negative control - object literal accessors are not walked`() {
        diagnose(
            """
            const o = { get a() { const f = function g<string>() {}; return 0; } };
            """
        ) should {
            have(none { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - class accessor bodies are walked`() {
        diagnose(
            """
            class C { get a() { const f = function g<string>() {}; return 0; } }
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `negative control - class static blocks are not walked`() {
        diagnose(
            """
            class C { static { function f<string>() {} } }
            """
        ) should {
            have(none { it.code == 2368 })
        }
    }

    @Test
    fun `negative control - computed property names are not walked`() {
        diagnose(
            """
            const o = { [(function f<string>() {}, "k")]: 1 };
            """
        ) should {
            have(none { it.code == 2368 })
        }
    }

    @Test
    fun `negative control - mapped type values are not walked`() {
        diagnose(
            """
            type M<T> = { [K in keyof T]: <string>() => void };
            """
        ) should {
            have(none { it.code == 2368 })
        }
    }

    @Test
    fun `negative control - keyof operand is not walked`() {
        diagnose(
            """
            type C2 = keyof { m<string>(): void };
            """
        ) should {
            have(none { it.code == 2368 })
        }
    }

    @Test
    fun `negative control - dts files are skipped entirely`() {
        diagnose(
            """
            type A<T = U, U = number> = T;
            """,
            fileName = "t.d.ts",
        ) should {
            have(none { it.code == 2744 })
        }
    }

    // ── reached positions the sibling classifiers treat differently ───────

    @Test
    fun `TS2368 - declare namespace bodies are walked`() {
        diagnose(
            """
            declare namespace N { function f<string>(): void; }
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2744 - function-body-nested type alias still emits`() {
        diagnose(
            """
            function f() { type A<T = T> = number; }
            """
        ) should {
            have(any { it.code == 2744 })
        }
    }

    @Test
    fun `TS2744 - namespace body type alias`() {
        diagnose(
            """
            namespace N { export type A<T = U, U = number> = T; }
            """
        ) should {
            have(any { it.code == 2744 })
        }
    }

    @Test
    fun `TS2368 - catch block statements are walked`() {
        diagnose(
            """
            try {} catch (e) { function f<string>() {} }
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - as-cast type position is walked`() {
        diagnose(
            """
            const x = null as any as (<string>() => void);
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - template span expressions are walked`() {
        diagnose(
            """
            const s = `a${'$'}{function f<string>() {}}b`;
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - ternary condition is walked`() {
        diagnose(
            """
            const r = (function f<string>() {}) ? 1 : 2;
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    @Test
    fun `TS2368 - class property initializer is walked`() {
        diagnose(
            """
            class C { p = function f<string>() {}; }
            """
        ) should {
            have(any { it.code == 2368 })
        }
    }

    // ── the circularDefaultTypeParamCount side-set (producer) ──────────────

    @Test
    fun `side-set - circular-default alias displays with any substitution`() {
        diagnose(
            """
            type Test<T extends string = T> = { value: T };
            let zz: Test = { foo: "abc" };
            """
        ) should {
            have(any { it.code == 2744 })
            have(any { it.code == 2353 && "Test<any>" in it.message })
        }
    }

    @Test
    fun `side-set - non-circular alias display is unaffected`() {
        diagnose(
            """
            type Test<T extends string = string> = { value: T };
            let zz: Test = { foo: "abc" };
            """
        ) should {
            have(none { it.code == 2744 })
            have(none { "Test<any>" in it.message })
        }
    }
}
