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
 * (CHK.203) An arrow assigned to `recv.member` is contextually typed by the member, where
 * `recv` is a contextually-typed callback parameter, a type-parameter-typed parameter or a
 * receiver whose member is inherited through a generic base — zod's `$constructor`
 * initializers (`inst._zod.check = (payload) => …`). Every positive cell carries a
 * WRONG-typed use inside the arrow, which must report: a fix that only silenced TS7006
 * would have typed nothing. Every expectation is tsgo 7.0.2's own output for the cell
 * (`build/bench/p18268-agent/cells/<name>`).
 */
class AssignmentTargetReceiverContextTest {

    private fun rows(source: String): List<String> =
        diagnose(source).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    /** tsgo 7.0.2, cell `ctx_param_generic_callee`. */
    @Test
    fun `a callback parameter typed by an inferred generic callee types the assigned arrow`() {
        assert(rows("interface Internals { process?: (ctx: string, n: number) => void; run: (p: string) => number }\ninterface Inst { _zod: Internals; min: (v: number) => Inst; u: ((a: string) => void) | ((a: string, b: number) => void) }\ndeclare function make<T extends Inst>(name: string, cb: (inst: T) => void): void; declare function make2(name: string, cb: (inst: Inst) => void): void;\nmake(\"x\", (inst) => { inst._zod.run = (p) => { const w: boolean = p; return 1 } }); export {}") == listOf(
            "4:54 TS2322 Type 'string' is not assignable to type 'boolean'.",
        ))
    }

    /** tsgo 7.0.2, cell `ctx_param_explicit_type_argument`. */
    @Test
    fun `a callback parameter typed through an explicit type argument types the assigned arrow`() {
        assert(rows("interface Internals { process?: (ctx: string, n: number) => void; run: (p: string) => number }\ninterface Inst { _zod: Internals; min: (v: number) => Inst; u: ((a: string) => void) | ((a: string, b: number) => void) }\ndeclare function make<T extends Inst>(name: string, cb: (inst: T) => void): void; declare function make2(name: string, cb: (inst: Inst) => void): void;\nmake<Inst>(\"x\", (inst) => { inst._zod.process = (ctx, n) => { const w: boolean = ctx } }); export {}") == listOf(
            "4:69 TS2322 Type 'string' is not assignable to type 'boolean'.",
        ))
    }

    /** tsgo 7.0.2, cell `ctx_param_non_generic_callee`. */
    @Test
    fun `a callback parameter typed by a non-generic callee types the assigned arrow`() {
        assert(rows("interface Internals { process?: (ctx: string, n: number) => void; run: (p: string) => number }\ninterface Inst { _zod: Internals; min: (v: number) => Inst; u: ((a: string) => void) | ((a: string, b: number) => void) }\ndeclare function make<T extends Inst>(name: string, cb: (inst: T) => void): void; declare function make2(name: string, cb: (inst: Inst) => void): void;\nmake2(\"x\", (inst) => { inst._zod.process = (ctx, n) => { const w: boolean = n } }); export {}") == listOf(
            "4:64 TS2322 Type 'number' is not assignable to type 'boolean'.",
        ))
    }

    /** tsgo 7.0.2, cell `ctx_param_direct_member`. */
    @Test
    fun `a direct member of a contextually typed callback parameter types the assigned arrow`() {
        assert(rows("interface Internals { process?: (ctx: string, n: number) => void; run: (p: string) => number }\ninterface Inst { _zod: Internals; min: (v: number) => Inst; u: ((a: string) => void) | ((a: string, b: number) => void) }\ndeclare function make<T extends Inst>(name: string, cb: (inst: T) => void): void; declare function make2(name: string, cb: (inst: Inst) => void): void;\nmake2(\"x\", (inst) => { inst.min = (v) => { const w: boolean = v; return inst } }); export {}") == listOf(
            "4:50 TS2322 Type 'number' is not assignable to type 'boolean'.",
        ))
    }

    /** tsgo 7.0.2, cell `type_parameter_receiver`. */
    @Test
    fun `a receiver typed by a type parameter supplies members through its constraint`() {
        assert(rows("interface Internals { process?: (ctx: string, n: number) => void; run: (p: string) => number }\ninterface Inst { _zod: Internals; min: (v: number) => Inst; u: ((a: string) => void) | ((a: string, b: number) => void) }\ndeclare function make<T extends Inst>(name: string, cb: (inst: T) => void): void; declare function make2(name: string, cb: (inst: Inst) => void): void;\nfunction f<T extends Inst>(inst: T) { inst._zod.process = (ctx, n) => { const w: boolean = ctx } } export {}") == listOf(
            "4:79 TS2322 Type 'string' is not assignable to type 'boolean'.",
        ))
    }

    /** tsgo 7.0.2, cell `type_parameter_receiver_direct`. */
    @Test
    fun `a direct member of a type-parameter-typed receiver types the assigned arrow`() {
        assert(rows("interface Internals { process?: (ctx: string, n: number) => void; run: (p: string) => number }\ninterface Inst { _zod: Internals; min: (v: number) => Inst; u: ((a: string) => void) | ((a: string, b: number) => void) }\ndeclare function make<T extends Inst>(name: string, cb: (inst: T) => void): void; declare function make2(name: string, cb: (inst: Inst) => void): void;\nfunction f<T extends Inst>(inst: T) { inst.min = (v) => { const w: boolean = v; return inst } } export {}") == listOf(
            "4:65 TS2322 Type 'number' is not assignable to type 'boolean'.",
        ))
    }

    /** tsgo 7.0.2, cell `annotated_receiver_control`. */
    @Test
    fun `control - an annotated receiver types the assigned arrow`() {
        assert(rows("interface Internals { process?: (ctx: string, n: number) => void; run: (p: string) => number }\ninterface Inst { _zod: Internals; min: (v: number) => Inst; u: ((a: string) => void) | ((a: string, b: number) => void) }\ndeclare function make<T extends Inst>(name: string, cb: (inst: T) => void): void; declare function make2(name: string, cb: (inst: Inst) => void): void;\nfunction f(inst: Inst) { inst._zod.process = (ctx, n) => { const w: boolean = ctx } } export {}") == listOf(
            "4:66 TS2322 Type 'string' is not assignable to type 'boolean'.",
        ))
    }

    /** tsgo 7.0.2, cell `element_access_control`. */
    @Test
    fun `an element-access target rooted at a callback parameter types the assigned arrow`() {
        assert(rows("interface Internals { process?: (ctx: string, n: number) => void; run: (p: string) => number }\ninterface Inst { _zod: Internals; min: (v: number) => Inst; u: ((a: string) => void) | ((a: string, b: number) => void) }\ndeclare function make<T extends Inst>(name: string, cb: (inst: T) => void): void; declare function make2(name: string, cb: (inst: Inst) => void): void;\nmake2(\"x\", (inst) => { inst[\"min\"] = (v) => { const w: boolean = v; return inst } }); export {}") == listOf(
            "4:53 TS2322 Type 'number' is not assignable to type 'boolean'.",
        ))
    }

    /** tsgo 7.0.2, cell `differing_union_member_keeps_ts7006`. */
    @Test
    fun `control - a union of differing callables still gives no contextual signature`() {
        assert(rows("interface Internals { process?: (ctx: string, n: number) => void; run: (p: string) => number }\ninterface Inst { _zod: Internals; min: (v: number) => Inst; u: ((a: string) => void) | ((a: string, b: number) => void) }\ndeclare function make<T extends Inst>(name: string, cb: (inst: T) => void): void; declare function make2(name: string, cb: (inst: Inst) => void): void;\nmake2(\"x\", (inst) => { inst.u = (a) => {} }); export {}") == listOf(
            "4:34 TS7006 Parameter 'a' implicitly has an 'any' type.",
        ))
    }

    /** tsgo 7.0.2, cell `missing_member_keeps_ts7006`. */
    @Test
    fun `control - a missing member reports TS2339 and keeps TS7006`() {
        assert(rows("interface Internals { process?: (ctx: string, n: number) => void; run: (p: string) => number }\ninterface Inst { _zod: Internals; min: (v: number) => Inst; u: ((a: string) => void) | ((a: string, b: number) => void) }\ndeclare function make<T extends Inst>(name: string, cb: (inst: T) => void): void; declare function make2(name: string, cb: (inst: Inst) => void): void;\nmake2(\"x\", (inst) => { (inst as any).nope; inst.nope = (a) => {} }); export {}") == listOf(
            "4:49 TS2339 Property 'nope' does not exist on type 'Inst'.",
            "4:57 TS7006 Parameter 'a' implicitly has an 'any' type.",
        ))
    }

    /** tsgo 7.0.2, cell `beyond_arity_keeps_ts7006`. */
    @Test
    fun `control - an arrow longer than the member signature keeps TS7006`() {
        assert(rows("interface Internals { process?: (ctx: string, n: number) => void; run: (p: string) => number }\ninterface Inst { _zod: Internals; min: (v: number) => Inst; u: ((a: string) => void) | ((a: string, b: number) => void) }\ndeclare function make<T extends Inst>(name: string, cb: (inst: T) => void): void; declare function make2(name: string, cb: (inst: Inst) => void): void;\nmake2(\"x\", (inst) => { inst._zod.run = (p, extra) => 1 }); export {}") == listOf(
            "4:24 TS2322 Type '(p: any, extra: any) => number' is not assignable to type '(p: string) => number'.",
            "4:41 TS7006 Parameter 'p' implicitly has an 'any' type.",
            "4:44 TS7006 Parameter 'extra' implicitly has an 'any' type.",
        ))
    }

    /** tsgo 7.0.2, cell `inner_uncontextual_param_keeps_ts7006`. */
    @Test
    fun `control - an inner uncontextual parameter shadows the contextual one`() {
        assert(rows("interface Internals { process?: (ctx: string, n: number) => void; run: (p: string) => number }\ninterface Inst { _zod: Internals; min: (v: number) => Inst; u: ((a: string) => void) | ((a: string, b: number) => void) }\ndeclare function make<T extends Inst>(name: string, cb: (inst: T) => void): void; declare function make2(name: string, cb: (inst: Inst) => void): void;\nmake2(\"x\", (inst) => { const g = (inst) => { inst.min = (v) => inst } }); export {}") == listOf(
            "4:35 TS7006 Parameter 'inst' implicitly has an 'any' type.",
            "4:58 TS7006 Parameter 'v' implicitly has an 'any' type.",
        ))
    }

    /** tsgo 7.0.2, cell `inherited_generic_member`. */
    @Test
    fun `a member inherited through a generic base reference types the assigned arrow`() {
        assert(rows("type ZodTrait = { _zod: { def: any; [k: string]: any } };\ninterface Ctor<T extends ZodTrait, D = T[\"_zod\"][\"def\"]> { new (def: D): T; init(inst: T, def: D): void }\ndeclare function ctor<T extends ZodTrait, D = T[\"_zod\"][\"def\"]>(name: string, init: (inst: T, def: D) => void): Ctor<T, D>;\ninterface BaseI { def: { type: string }; check?: ((payload: { value: string }) => void) | undefined }\ninterface Core<I extends BaseI = BaseI> { _zod: I }\ninterface Sub<I extends BaseI = BaseI> extends Core<I> {}\ninterface Leaf extends Sub<BaseI> {}\nfunction f(i: Leaf) { i._zod.check = (payload) => { const w: boolean = payload } } export {}") == listOf(
            "8:59 TS2322 Type '{ value: string; }' is not assignable to type 'boolean'.",
        ))
    }

    /** tsgo 7.0.2, cell `return_context_with_omitted_default`. */
    @Test
    fun `return-type inference matches an annotation that omits a defaulted type argument`() {
        assert(rows("type ZodTrait = { _zod: { def: any; [k: string]: any } };\ninterface Ctor<T extends ZodTrait, D = T[\"_zod\"][\"def\"]> { new (def: D): T; init(inst: T, def: D): void }\ndeclare function ctor<T extends ZodTrait, D = T[\"_zod\"][\"def\"]>(name: string, init: (inst: T, def: D) => void): Ctor<T, D>;\ninterface BaseI { def: { type: string }; check?: ((payload: { value: string }) => void) | undefined }\ninterface Core<I extends BaseI = BaseI> { _zod: I }\ninterface Sub<I extends BaseI = BaseI> extends Core<I> {}\ninterface Leaf extends Sub<BaseI> {}\nexport const C: Ctor<Leaf> = ctor(\"C\", (inst, def) => { inst._zod.check = (payload) => { const w: boolean = payload } });") == listOf(
            "8:96 TS2322 Type '{ value: string; }' is not assignable to type 'boolean'.",
        ))
    }

    /** tsgo 7.0.2, cell `return_context_infers_type_argument`. */
    @Test
    fun `the callback parameter reads the type argument inferred from the return context`() {
        assert(rows("type ZodTrait = { _zod: { def: any; [k: string]: any } };\ninterface Ctor<T extends ZodTrait, D = T[\"_zod\"][\"def\"]> { new (def: D): T; init(inst: T, def: D): void }\ndeclare function ctor<T extends ZodTrait, D = T[\"_zod\"][\"def\"]>(name: string, init: (inst: T, def: D) => void): Ctor<T, D>;\ninterface BaseI { def: { type: string }; check?: ((payload: { value: string }) => void) | undefined }\ninterface Core<I extends BaseI = BaseI> { _zod: I }\ninterface Sub<I extends BaseI = BaseI> extends Core<I> {}\ninterface Leaf extends Sub<BaseI> {}\nexport const C: Ctor<Leaf> = ctor(\"C\", (inst) => { const w: boolean = inst });") == listOf(
            "8:58 TS2322 Type 'Leaf' is not assignable to type 'boolean'.",
        ))
    }

    /**
     * tsgo 7.0.2 reports `4:62 TS7006` here (plus `4:55 TS2339 … on type 'never'`, a
     * narrowing row this checker does not produce — out of this pin's scope): an
     * un-annotated, un-initialised local of the same name SHADOWS the contextual
     * parameter, so the receiver is not the parameter's type.
     */
    @Test
    fun `control - a block-local untyped declaration shadows the contextual parameter`() {
        assert("4:62 TS7006 Parameter 'v' implicitly has an 'any' type." in rows("interface Internals { process?: (ctx: string, n: number) => void; run: (p: string) => number }\ninterface Inst { _zod: Internals; min: (v: number) => Inst; u: ((a: string) => void) | ((a: string, b: number) => void) }\ndeclare function make<T extends Inst>(name: string, cb: (inst: T) => void): void; declare function make2(name: string, cb: (inst: Inst) => void): void;\nmake2(\"x\", (inst) => { { let inst; inst = null!; inst.min = (v) => { const w: boolean = v; return inst } } }); export {}"))
    }
}
