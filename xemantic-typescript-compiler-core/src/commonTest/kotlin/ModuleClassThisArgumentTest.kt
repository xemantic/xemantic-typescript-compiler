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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.169), round P18.200 — inside a class declared in a MODULE file, `this` (and the class's
 * type parameters) are typed at the call-argument reader, and a `super(...)` / `super.m(...)`
 * argument is checked against a base declared in a module.
 *
 * Both readers — the spine's `ccetEnterClassDeclaration` and the legacy mirror in
 * `checkCallTypesInStatement` — looked the class up in `globals` alone, and INV.3(d) keeps a
 * module file's locals out of `globals`: `this` was untyped in every method of every module
 * class, so `pn(this.s)` with a `string` member was silent, and `export class Map` got the LIB
 * `Map`. `Checker.callWalkClassSymbol` now answers the class's OWN symbol (it must declare the
 * node; tsgo's `tryGetThisTypeAtEx` is `getDeclaredTypeOfSymbol(getSymbolOfDeclaration(
 * container.Parent)).thisType`), and the base goes through `lookupPerFileForNode` (tsgo checks the
 * heritage expression as an ordinary expression). `this` is also typed in a constructor body.
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW over the identical text (`build/scratch-p18200/pins`,
 * `strict`, `target: es2022`, `module: esnext`). A row is `file line:column code message`.
 * The SCRIPT twin of each module shape is the control: it always reported.
 *
 * The same resolution is applied to the legacy mirror (`checkCallTypesInStatement`'s class arm)
 * for parity, but NO pin here can reach it: that walker has no entry of its own any more — it is
 * reached only from `checkCallTypesInExpr` (a function-expression body inside a destructuring
 * computed key), where a class declaration is a B83.5 nested class with no binder symbol. It is
 * not the `emitDeclarationOnly` path either: (CHK.172) deleted that path's checker whitelist, so an
 * `emitDeclarationOnly` compile runs the argument checks exactly as these pins do (tsgo reports
 * `pn(this.s)` there as well).
 */
class ModuleClassThisArgumentTest {

    private val directives = "// @strict: true\n// @target: es2022\n// @module: esnext"

    private val pn = "declare function pn(n: number): void;\n"

    private fun rows(source: String): List<String> =
        diagnose(source, directives = directives, fileName = "a.ts").map { d ->
            "${d.fileName?.substringAfterLast('/')} ${d.line}:${d.character} ${d.code} ${d.message}"
        }.sorted()

    private fun arg(file: String, line: Int, col: Int, type: String = "string") =
        "$file $line:$col 2345 Argument of type '$type' is not assignable to parameter of type 'number'."

    @Test
    fun `a module class method types this`() {
        val module = rows(pn + """export class C { s = "x"; m() { pn(this.s); } }""")
        val script = rows(pn + """class C { s = "x"; m() { pn(this.s); } }""")
        assert(module == listOf(arg("a.ts", 2, 36)))
        assert(script == listOf(arg("a.ts", 2, 29)))
    }

    @Test
    fun `a module generic class types this through its type parameter`() {
        val module = rows(pn + "export class G<T> { v!: T; m() { pn(this.v); } }")
        val script = rows(pn + "class G<T> { v!: T; m() { pn(this.v); } }")
        assert(module == listOf(arg("a.ts", 2, 37, "T")))
        assert(script == listOf(arg("a.ts", 2, 30, "T")))
    }

    @Test
    fun `a module generic class's type parameter is in scope in its constructor`() {
        val module = rows(pn + "export class G<T> { constructor(o: T) { pn(o); } }")
        val script = rows(pn + "class G<T> { constructor(o: T) { pn(o); } }")
        assert(module == listOf(arg("a.ts", 2, 44, "T")))
        assert(script == listOf(arg("a.ts", 2, 37, "T")))
    }

    @Test
    fun `this is typed in a constructor body - module and script`() {
        val module = rows(pn + """export class C { s = "x"; constructor() { pn(this.s); } }""")
        val script = rows(pn + """class C { s = "x"; constructor() { pn(this.s); } }""")
        assert(module == listOf(arg("a.ts", 2, 46)))
        assert(script == listOf(arg("a.ts", 2, 39)))
    }

    @Test
    fun `a super call argument is checked against a module base`() {
        val module = rows("class B { constructor(n: number) {} }\nexport class D extends B { constructor() { super(\"s\"); } }")
        val script = rows("class B { constructor(n: number) {} }\nclass D extends B { constructor() { super(\"s\"); } }")
        assert(module == listOf(arg("a.ts", 2, 50)))
        assert(script == listOf(arg("a.ts", 2, 43)))
    }

    @Test
    fun `a super method argument is checked against a module base`() {
        val module = rows("class B { f(n: number) {} }\nexport class D extends B { m() { super.f(\"s\"); } }")
        val script = rows("class B { f(n: number) {} }\nclass D extends B { m() { super.f(\"s\"); } }")
        assert(module == listOf(arg("a.ts", 2, 42)))
        assert(script == listOf(arg("a.ts", 2, 35)))
    }

    @Test
    fun `a member inherited from a module base is typed on this`() {
        val module = rows(pn + "class B { s = \"x\"; }\nexport class D extends B { m() { pn(this.s); } }")
        assert(module == listOf(arg("a.ts", 3, 37)))
    }

    @Test
    fun `a module class named like a lib global is not the lib global`() {
        // Before: `globals["Map"]` is the LIB `Map`, so `this` was the lib Map and `this.s` read nothing.
        val module = rows(pn + """export class Map { s = "x"; m() { pn(this.s); } }""")
        assert(module == listOf(arg("a.ts", 2, 38)))
    }

    @Test
    fun `negative control - a correctly typed this member is legal in a module class`() {
        val module = rows(pn + "export class C { n = 1; m() { pn(this.n); } }")
        assert(module.isEmpty())
    }

    @Test
    fun `two module files each declaring a class C at the same offsets get their own this`() {
        // The two class declarations span identical offsets, so `nodeKey(pos, end)` collides
        // across the files ((BIND.1)); each must be typed from its OWN file's symbol.
        val all = rows(
            """
            // @Filename: a.ts
            declare function pn(n: number): void;
            export class C { s = "x"; m() { pn(this.s); } }
            // @Filename: b.ts
            declare function pn(n: number): void;
            export class C { s = 1.0; m() { pn(this.s); } }
            """,
        )
        assert(all == listOf(arg("a.ts", 2, 36)))
    }

    @Test
    fun `a super schedule subclass does not make this unassignable to its own this-typed callback`() {
        // The rxjs `AsyncAction` shape (census `rxmin`, reduced further here): with the class
        // typed but the base still read from `globals`, `this.work(state)` grew a false TS2684
        // `'AsyncAction<T>' is not assignable to method's 'this' of type 'SchedulerAction<any>'`.
        // Its ingredients: a PRIVATE parameter property on the root class (`SchedulerAction`
        // extends that class), and the `super.schedule` subclass checked FIRST. tsgo: clean.
        val all = rows(
            """
            // @Filename: QueueAction.ts
            import { AsyncAction } from './AsyncAction';
            import { Subscription } from './Subscription';
            import { SchedulerAction } from './types';
            export class QueueAction<T> extends AsyncAction<T> {
              constructor(protected scheduler: object, protected work: (this: SchedulerAction<T>, state?: T) => void) {
                super(scheduler, work);
              }
              public schedule(state?: T, delay: number = 0): Subscription {
                return super.schedule(state, delay);
              }
            }
            // @Filename: AsyncAction.ts
            import { Action } from './Action';
            import { SchedulerAction } from './types';
            export class AsyncAction<T> extends Action<T> {
              constructor(protected scheduler: object, protected work: (this: SchedulerAction<T>, state?: T) => void) {
                super(scheduler, work);
              }
              protected _execute(state: T, _delay: number): any { this.work(state); }
            }
            // @Filename: Action.ts
            import { Subscription } from './Subscription';
            import { SchedulerAction } from './types';
            export class Action<T> extends Subscription {
              constructor(scheduler: object, work: (this: SchedulerAction<T>, state?: T) => void) {
                super();
              }
              public schedule(state?: T, delay: number = 0): Subscription {
                return this;
              }
            }
            // @Filename: Subscription.ts
            export class Subscription {
              constructor(private initialTeardown?: () => void) {}
            }
            // @Filename: types.ts
            import { Subscription } from './Subscription';
            export interface SchedulerAction<T> extends Subscription {
              schedule(state?: T, delay?: number): Subscription;
            }
            """,
        )
        assert(all.isEmpty())
    }
}
