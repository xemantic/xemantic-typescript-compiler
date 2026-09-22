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
 */


package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.144): a BLOCK-bodied un-annotated function whose `return` is a bare identifier
 * must infer that identifier's type, not `any`.
 *
 * `inferReturnTypeFromBody`'s `is Identifier` arm answered nothing but `true`/`false`,
 * so `() => { return u }` read `() => any` where tsgo 7.0.2 reads `() => unknown` - for
 * an `unknown` / `string` / object / union / type-parameter binding alike, arrow and
 * `function` both - while the EXPRESSION body `() => u` was already right. Measured over
 * a 39-row CLI matrix: 15 of 39 cells agreed with tsgo before, 36 of 39 after.
 *
 * It is a SUPPRESSION, so closing it ADDS diagnostics. On `marked` the arrow at
 * `Instance.ts:180` returns a bare `ret`, whose `any` satisfied an intersection slot a
 * `@ts-expect-error` was written for - the directive read as unused, a false TS2578.
 * That library goes 3 ours-only rows to 2 with no new row.
 *
 * EVERY PIN BELOW ASSERTS A TYPE, NEVER A SILENCE. `const probe: never = <fn>` makes the
 * TS2322 message print whatever the function really inferred, so a pin cannot pass
 * because the answer degraded to `any` - which is exactly the defect. Every expected
 * value was MEASURED against `tools/tsgo-7.0.2/lib/tsc` on the same fixture rather than
 * reasoned out.
 *
 * The negative half is as load-bearing as the positive one: the arm is deliberately ONE
 * node class resolved LEXICALLY, not a general `getTypeOfExpression` fallback (the
 * archive refuses that three times as "blast radius"), so the shapes that must still
 * infer `any` are pinned too.
 */
class BlockBodyIdentifierReturnInferenceTest {

    private val prelude = """
        declare const u: unknown;
        declare const s: string;
        declare const o: { q: number };
        declare const un: string | number;
        type UF = (...a: unknown[]) => unknown;
        declare const uf: UF;
        const modConst: string = "m";
        declare function get(k: string): number | undefined;
        declare const maybe: string | undefined;
    """.trimIndent() + "\n"

    /** The SOURCE type tsc names in the single TS2322 - i.e. what was inferred. */
    private fun inferred(d: List<Diagnostic>): String =
        d.single { it.code == 2322 }.message
            .substringAfter("Type '").substringBefore("' is not assignable")

    private fun inferredOf(body: String): String =
        inferred(diagnose(prelude + "const probe: never = $body;"))

    // ---- the bare identifier resolves, by declaration form and declared type ----

    @Test
    fun `a block body returning a bare unknown-typed name infers unknown`() {
        assert(inferredOf("() => { return u; }") == "() => unknown")
    }

    @Test
    fun `a block body returning a bare string-typed name infers string`() {
        assert(inferredOf("() => { return s; }") == "() => string")
    }

    @Test
    fun `a block body returning a bare object-typed name infers that object type`() {
        assert(inferredOf("() => { return o; }") == "() => { q: number; }")
    }

    @Test
    fun `a block body returning a bare union-typed name infers the union`() {
        assert(inferredOf("() => { return un; }") == "() => string | number")
    }

    @Test
    fun `a block body returning an annotated parameter infers the parameter type`() {
        assert(inferredOf("(p: string) => { return p; }") == "(p: string) => string")
    }

    @Test
    fun `a block body returning an annotated body-local const infers its annotation`() {
        assert(inferredOf("() => { const x: string = \"a\"; return x; }") == "() => string")
    }

    @Test
    fun `a block body returning an annotated body-local let infers its annotation`() {
        assert(inferredOf("() => { let x: string = \"a\"; return x; }") == "() => string")
    }

    @Test
    fun `a block body returning an annotated body-local var infers its annotation`() {
        assert(inferredOf("() => { var x: string = \"a\"; return x; }") == "() => string")
    }

    @Test
    fun `a block body returning an annotated module-level const infers its annotation`() {
        assert(inferredOf("() => { return modConst; }") == "() => string")
    }

    @Test
    fun `a block body returning an un-annotated local widens its literal initializer`() {
        assert(inferredOf("() => { const x = 1; return x; }") == "() => number")
    }

    /**
     * THE `marked` SHAPE, reduced. `let ret = tokenizerFunc.apply(tokenizer, args)` has
     * no annotation, so the un-annotated-initializer leg is what reaches it - an
     * annotation-only resolver leaves this cell at `any` and the library's false TS2578
     * standing.
     */
    @Test
    fun `a block body returning an un-annotated local typed by a call initializer infers the call return`() {
        assert(inferredOf("() => { let r = uf.apply(null, []); return r; }") == "() => unknown")
    }

    @Test
    fun `a block body returning a name captured from an enclosing function resolves the capture`() {
        assert(
            inferredOf("() => { const cap: string = \"c\"; const inner = () => { return cap; }; return inner; }")
                == "() => () => string"
        )
    }

    @Test
    fun `a block-bodied function expression returning a bare name infers it too`() {
        assert(inferredOf("function () { return s; }") == "() => string")
    }

    @Test
    fun `a parameter shadowing a module-level binding of the same name wins`() {
        // `s` is `string` at module level; the parameter is `number`. Lexical resolution
        // is innermost-first, so the answer must be the parameter's.
        assert(inferredOf("(s: number) => { return s; }") == "(s: number) => number")
    }

    @Test
    fun `a block body returning a bare type-parameter-typed parameter infers the type parameter`() {
        assert(inferredOf("<T,>(p: T) => { return p; }") == "<T>(p: T) => T")
    }

    // ---- shadow safety: the whole reason the resolution is lexical ----

    /**
     * THE PINS THE DESIGN EXISTS FOR.
     *
     * `inferReturnTypeFromBody` can run in the CALLER's scope, and `currentLocalTypes` is
     * a flat `String -> Type` carrying no declaration identity - so a caller's same-named
     * local answers for the callee's. That leak is REAL: it was measured on the
     * PRE-change binary through the concise-body path's own `getTypeOfExpression` (which
     * has no lexical guard), and it is what ablation arm a2 - the obvious implementation,
     * a plain `currentLocalTypes[id.text]` probe ahead of the lexical walk - reintroduces
     * for the block body. Under a2 both pins below read `() => number` against tsgo's
     * `() => string`; renaming the caller's local makes a2 answer `string` again, i.e. it
     * is a name collision through that map and nothing else.
     *
     * The shipped resolution walks the AST ancestry instead, which is a function of the
     * PROGRAM and not of when the question is asked, so it cannot be shadowed wrong.
     *
     * TWO THINGS MAKE OR BREAK THESE FIXTURES, both measured:
     *
     *  - the callee must be an ARROW. The same fixture with `function callee() {...}`
     *    reads `() => string` even under a2, because a function declaration's return is
     *    resolved through `getReturnTypeOfCallable` rather than
     *    `getTypeOfArrowFunction`, and that path does not carry the caller's locals. The
     *    first draft of this pin used the function form and was BLIND - a2 read 0 RED on
     *    it while silently mistyping every arrow.
     *  - the callee must be declared AFTER the caller. Declared before, its type is
     *    resolved when its own declaration is checked and the leak never happens.
     */
    @Test
    fun `an arrow callee returning a bare name is not re-typed by a caller local of that name`() {
        val d = diagnose(
            """
            export function caller() { const zz: number = 1; const probe: never = callee; return zz; }
            const zz: string = "s";
            const callee = () => { return zz; };
            """.trimIndent()
        )
        assert(inferred(d) == "() => string")
    }

    @Test
    fun `an arrow callee returning a bare name is not re-typed by a caller parameter of that name`() {
        val d = diagnose(
            """
            export function caller(xx: number) { const probe: never = callee; return xx; }
            const xx: string = "s";
            const callee = () => { return xx; };
            """.trimIndent()
        )
        assert(inferred(d) == "() => string")
    }

    /**
     * The same leak one container over: the arrow is an object-literal member, so the
     * read goes through a property access rather than a bare name. Measured RED under
     * arm a2 as well - included because it is the shape a real codebase writes (a
     * handler table), and because it proves the guard is a property of the RESOLUTION
     * and not of how the callee is reached.
     */
    @Test
    fun `an arrow callee held in an object literal is not re-typed by a caller local`() {
        val d = diagnose(
            """
            export function caller() { const ww: number = 1; const probe: never = holder.f; return ww; }
            const ww: string = "s";
            const holder = { f: () => { return ww; } };
            """.trimIndent()
        )
        assert(inferred(d) == "() => string")
    }

    /**
     * The NON-discriminating sibling, kept and labelled so the next agent does not
     * rediscover it as a pin: a `function` callee answers correctly even under arm a2.
     * It is a control on the OTHER resolution path, never evidence about the guard.
     */
    @Test
    fun `a function-declaration callee returning a bare name resolves to its own binding`() {
        val d = diagnose(
            """
            export function caller() { const yy: number = 1; const probe: never = callee; return yy; }
            const yy: string = "s";
            function callee() { return yy; }
            """.trimIndent()
        )
        assert(inferred(d) == "() => string")
    }

    /**
     * THE OWNER-IDENTITY GATE on the one `currentLocalTypes` consult that survives.
     *
     * An UN-annotated parameter is read from that map only when it belongs to the very
     * function-like whose body is being inferred, and only for the two forms contextual
     * typing reaches - because `getTypeOfArrowFunction` pushes the contextual parameter
     * types a few lines before calling the inference (B83.4f-c), so that read is OUR
     * push. For any other parameter the map is somebody else's.
     *
     * These two pins were RED against the round's own first implementation, which DID
     * read that map for an un-annotated parameter behind an owner-identity gate. The
     * gate does not save it: the push is itself conditional on the parameter's type
     * being concrete, so exactly the un-annotated parameter it would serve is the one
     * never pushed, and the read falls through to the caller's layer - measured
     * `(r: any) => number` against tsgo's `(r: any) => any`. The consult was REMOVED and
     * the resolver is lexical and nothing else; these pins are what keeps it that way.
     */
    @Test
    fun `an un-annotated arrow parameter is not typed from a caller binding of that name`() {
        val d = diagnose(
            """
            export function caller() { const r: number = 1; const probe: never = cal; return r; }
            const r: string = "s";
            const cal = (r) => { return r; };
            """.trimIndent()
        )
        assert(inferred(d) == "(r: any) => any")
    }

    @Test
    fun `an un-annotated parameter of a nested function is not typed from a caller binding`() {
        val d = diagnose(
            """
            export function caller() { const z: number = 1; const probe: never = cal; return z; }
            const z: string = "s";
            const cal = () => { const inner = function (z) { return z; }; return inner; };
            """.trimIndent()
        )
        assert(inferred(d) == "() => (z: any) => any")
    }

    // ---- the NULLISH policy: `!` strips, a bare nullish answer is refused ----

    /**
     * THE 8-PROFILE GRID FOUND THIS AND NOTHING ELSE COULD.
     *
     * `inferReturnTypeFromBody` unwraps `!` as a "value-preserving wrapper", which is
     * true of every arm that answers from the expression's own syntax and FALSE of this
     * one, whose answer is the binding's DECLARED type - stripping the nullish part is
     * the entire point of writing `return value!`. The first cut ignored it and added
     * three false positives to tsc's own sources, all of them one spurious `| undefined`:
     * `memoizeOne`'s `return value!` over `let value = map.get(key)` (core.ts:1909),
     * `fileNamePropertyReader.getScriptKind`'s `return result!` over
     * `let result: ScriptKind | undefined` (editorServices.ts:548), and
     * `getCombinedDiagnostics`'s bare `return computedDiagnostics`
     * (programDiagnostics.ts:108).
     *
     * The corpus screen was 0 mismatches over 8,725 and all 28 pins were green on that
     * same binary. Only a real codebase carries `return x!` over a nullish local.
     */
    @Test
    fun `a non-null assertion on a bare name strips the nullish part of its type`() {
        assert(inferredOf("() => { let v = get(\"k\"); return v!; }") == "() => number")
    }

    @Test
    fun `a non-null assertion on an annotated nullish local strips undefined`() {
        assert(inferredOf("() => { let r: string | undefined; return r!; }") == "() => string")
    }

    @Test
    fun `a non-null assertion on a nullish parameter strips null`() {
        assert(inferredOf("(p: string | null) => { return p!; }") == "(p: string | null) => string")
    }

    /**
     * WITHOUT the assertion a nullish answer is REFUSED, not reported. A declaration's
     * type is not what the RETURN sees - the reference is flow-narrowed there and this
     * reader does not narrow - so tsgo reads `() => string | undefined` and we read
     * `() => any`, which is the pre-change answer. Monotone and deliberate; a
     * flow-narrowing consult at the return site is the successor that closes it.
     */
    @Test
    fun `a bare nullish name is refused rather than reported`() {
        assert(inferredOf("() => { return maybe; }") == "() => any")
    }

    @Test
    fun `a bare local whose initializer is nullish is refused`() {
        assert(inferredOf("() => { let v = get(\"k\"); return v; }") == "() => any")
    }

    /**
     * The exact `programDiagnostics.getCombinedDiagnostics` shape: assigned on every path
     * to the return, so only FLOW makes it non-nullish. tsgo reads `() => string`.
     */
    @Test
    fun `a nullish local assigned before the return is refused rather than reported`() {
        assert(inferredOf("() => { let c: string | undefined; c = \"x\"; return c; }") == "() => any")
    }

    /**
     * The assertion must stay value-preserving for the arms that do NOT read a
     * declaration - otherwise the carry-through would change five neighbouring arms.
     */
    @Test
    fun `a non-null assertion on a literal is still value-preserving`() {
        assert(inferredOf("() => { return \"lit\"!; }") == "() => string")
    }

    // ---- negative controls: what must STILL infer `any`, and what never changed ----

    @Test
    fun `an unresolvable name still infers any`() {
        val d = diagnose(prelude + "const probe: never = () => { return nope; };")
        val t = d.filter { it.code == 2322 }.single().message
            .substringAfter("Type '").substringBefore("' is not assignable")
        assert(t == "() => any")
    }

    /**
     * The SHADOW-STOP. A `function` declaration binds the name in the inner scope, and
     * this resolver cannot type that form - so it must stop there and fall back to
     * `any`, NOT walk out to the module-level `const s: string` it shadows. Answering
     * `() => string` here would be a confidently wrong type; tsgo says `() => () => void`
     * and `any` is the honest residue.
     */
    @Test
    fun `an inner function declaration shadowing an outer const stops the walk`() {
        assert(inferredOf("() => { function s() {} return s; }") == "() => any")
    }

    /**
     * The archive refuses the object-literal branch three times as suite-wide blast
     * radius. This pin is what says the round did NOT quietly open it: tsgo infers
     * `() => { a: number; }` and we deliberately still infer `any`.
     */
    @Test
    fun `a returned object literal still infers any - the refused branch stays closed`() {
        assert(inferredOf("() => { return { a: 1 }; }") == "() => any")
    }

    @Test
    fun `a block body with no returned value still infers void`() {
        assert(inferredOf("() => { return; }") == "() => void")
    }

    @Test
    fun `a return inside a nested closure is not attributed to the outer function`() {
        assert(inferredOf("() => { const inner = () => { return s; }; }") == "() => void")
    }

    @Test
    fun `a bare true still infers boolean rather than resolving as a name`() {
        assert(inferredOf("() => { return true; }") == "() => boolean")
    }

    @Test
    fun `a returned template literal still infers string`() {
        assert(inferredOf("() => { return `tpl`; }") == "() => string")
    }
}
