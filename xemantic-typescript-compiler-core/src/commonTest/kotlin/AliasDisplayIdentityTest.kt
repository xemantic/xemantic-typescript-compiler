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
 * (INC.11) A GENERIC ALIAS THAT ANSWERS ONE OF ITS OWN ARGUMENTS UNCHANGED IS NOT A
 * NAME FOR THAT ARGUMENT, AND REGISTERING IT AS ONE IS PROGRAM-WIDE.
 *
 * `aliasDisplayMap` is keyed by `Type.id` and `typeToString` consults it BEFORE the
 * structural union-alias table, so one wrong entry renames a type at every later
 * reference in the whole program — in a diagnostic and in a hover alike.
 *
 * The registration at the generic-instantiation site is unconditional (last wins),
 * and a conditional alias whose condition cannot be decided — because one of its
 * arguments still mentions a free type parameter — answers its CHECK type itself.
 * So `Pass<Shape, Pick<T, "a">>` used in a signature registered the name `Pass` onto
 * `Shape`, and every mention of `Shape` afterwards printed as
 * `Pass<Shape, Pick<T, "a">>`, an unbound `T` and all.
 *
 * Measured on tsc's own sources before the fix: a caret on the type reference
 * `ClassLikeDeclaration` in `es2015.ts` reported
 * `Extract<ClassDeclaration | ClassExpression, Pick<T, "kind">>`, because
 * `classThis.ts` and `namedEvaluation.ts` declare overloads returning
 * `Extract<ClassLikeDeclaration, Pick<T, "kind">>` and the setup pass resolves every
 * file-level declaration before anything is checked.
 *
 * ## Why the assertion is on a DIAGNOSTIC and not on a capture
 *
 * Type capture is off in a plain build, so a capture assertion would need the
 * project harness; the rendering is the same `typeToString` either way, and a
 * `TS2322` names the source type verbatim. That also makes this pin the cheapest
 * available statement about a display defect the 8 dashboard profiles cannot see
 * — they are one codebase, and this shape lives in two of its files.
 */
class AliasDisplayIdentityTest {

    /**
     * A conditional alias applied to a free type parameter cannot decide, answers
     * its check type, and must NOT rename it.
     */
    @Test
    fun `a conditional alias that cannot decide does not rename its own argument`() {
        val diagnostics = diagnose(
            """
            interface Shape { a: number }
            type Pass<T, U> = T extends U ? T : never;
            declare function pick<T>(x: T): Pass<Shape, Pick<T, "a">>;
            declare const s: Shape;
            const bad: number = s;
            """,
            // `Pick` is a LIB utility type, and the embedded lib declares NONE of
            // them — an undeclared name degrades to `any`, which decides the
            // conditional and takes the fixture off the mechanism entirely. The
            // first draft of this pin was silent for exactly that reason and only
            // the ablation said so (CLAUDE.md's round-725/806 pair).
            directives = "// @strict: true\n// @useRealLibs: true",
        )
        val ts2322 = diagnostics.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        val message = ts2322.single().message
        // The source type is `Shape`, under its own name.
        assert("'Shape'" in message)
        // …and NOT under the name of an alias that contributed nothing to it.
        assert("Pass<" !in message)
    }

    /**
     * The negative control: an alias that DOES build a new type keeps its name, so
     * the rule above is an identity test and not a blanket refusal to register.
     */
    @Test
    fun `negative control - an alias that builds a new type still names it`() {
        val diagnostics = diagnose(
            """
            interface Shape { a: number }
            type Boxed<T> = { inner: T };
            declare function box(): Boxed<Shape>;
            declare const b: Boxed<Shape>;
            const bad: number = b;
            """,
            directives = "// @strict: true\n// @useRealLibs: true",
        )
        val ts2322 = diagnostics.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert("Boxed<Shape>" in ts2322.single().message)
    }

    /**
     * (INC.26) AN ALIAS WHOSE BODY IS A TYPE THAT ALREADY HAS ITS OWN NAME MUST NOT
     * TAKE THAT NAME OVER — `type FunctionBody = Block` does not rename `Block`.
     *
     * `aliasDisplayMap` is keyed by `Type.id`, so registering the alias there writes
     * it onto the INTERFACE's own type, and `typeToString` consults the map before
     * the structural fallback: every occurrence of that interface in the whole
     * program then renders under the alias's name. Reproduced on four lines with no
     * partition and no arm, and confirmed against the reference compiler on the box
     * (`tools/tsgo-7.0.2/lib/tsc`), which reports `Type 'Block'` where we reported
     * `Type 'FunctionBody'`.
     *
     * This is the largest single family of `scripts/capture-equivalence.sh`'s
     * narrowed divergence, and the direction is the one that matters: the FULL build
     * is the WRONG arm. A narrowed build never resolves the unrelated alias, so it
     * renders the honest name, and the gate reported the two disagreeing without
     * being able to say which was right.
     */
    @Test
    fun `an alias to a named interface does not rename that interface`() {
        val diagnostics = diagnose(
            """
            interface Block { stmts: number }
            type FunctionBody = Block;
            declare const b: Block;
            const bad: number = b;
            """,
        )
        val ts2322 = diagnostics.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        val message = ts2322.single().message
        // The declared type is `Block`, and that is what tsc 7.0.2 reports.
        assert("'Block'" in message)
        // …not the name of an alias the declaration never mentions.
        assert("FunctionBody" !in message)
    }

    /**
     * The same rule one shape over: the alias body is a CLASS rather than an
     * interface. Both carry their own symbol, which is the property the rule reads,
     * so a fixture that only ever tested an interface would not say that.
     */
    @Test
    fun `an alias to a named class does not rename that class`() {
        val diagnostics = diagnose(
            """
            class Widget { w: number = 1 }
            type Gadget = Widget;
            declare const w: Widget;
            const bad: number = w;
            """,
        )
        val ts2322 = diagnostics.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        val message = ts2322.single().message
        assert("'Widget'" in message)
        assert("Gadget" !in message)
    }

    /**
     * (INC.26) THE NEGATIVE CONTROL, and it is what keeps the rule an ANONYMITY test
     * rather than a blanket refusal: an alias whose body is a type literal is the
     * ONLY name that type has, so it must still register.
     *
     * `getTypeFromTypeLiteral` leaves `Type.Object.symbol` null, which is exactly the
     * property the rule branches on — so this fixture fails if the fix is widened to
     * refuse every object body.
     */
    @Test
    fun `negative control - an alias to an anonymous type literal still names it`() {
        val diagnostics = diagnose(
            """
            type Config = { retries: number };
            declare const c: Config;
            const bad: number = c;
            """,
        )
        val ts2322 = diagnostics.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert("'Config'" in ts2322.single().message)
    }

    /**
     * (INC.26) THE GENERIC EXCLUSION, and it is round 754's invariant restated as a
     * pin so the refusal above cannot be widened back over it.
     *
     * A bare reference to a generic whose every parameter has a DEFAULT resolves to
     * the RAW `Type.Interface` — the defaulted instantiation is applied at the
     * relation boundary on purpose, so that a bare `TableClass` and an explicit
     * `TableClass<any>` never intern as one instance. That raw stand-in renders
     * `TableClass<any>`, which is not a name the source spells, so the alias is the
     * only complete name in hand and must still be registered.
     *
     * The corpus pins the same thing from the other side —
     * `typeVariableConstraintedToAliasNotAssignableToUnion` has four `Table` rows
     * whose baseline is pristine tsc's — but that baseline lives in generated code
     * and states nothing about WHY, which is what this test is for.
     */
    @Test
    fun `an alias to a generic named type with defaulted parameters still names it`() {
        val diagnostics = diagnose(
            """
            declare class TableClass<S = any> { _field: S }
            type Table = TableClass;
            declare const o: Table;
            const bad: boolean = o;
            """,
        )
        val ts2322 = diagnostics.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        // tsc 7.0.2 and the pristine baseline both report the alias here.
        assert("'Table'" in ts2322.single().message)
    }
}
