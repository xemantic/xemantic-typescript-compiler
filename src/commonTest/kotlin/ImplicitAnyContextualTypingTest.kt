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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 431 (M3.2): TS7006 contextual-typing slice — the self-compile TS7006×301
 * bucket's two dominant mechanisms.
 *
 *  (a) Callee RESOLVABILITY: a call to a NESTED FunctionDeclaration (unbound,
 *      B83.5 — tsc's `filterType`/`mapType` inside `createTypeChecker`) or to a
 *      lexically-scoped param/body-local suppresses TS7006 on callback args, the
 *      same permissive rule file-level callees already got.
 *  (b) Assignment-RHS contextual typing (tsc getContextualTypeForBinaryOperand):
 *      `lhs = arrow` contextually types the arrow from lhs's DECLARED type —
 *      body-local/param annotations via a new lexical scope map, `as T` casts,
 *      property-access targets via the receiver's resolved member. The sharp
 *      negatives are pinned by tsc's own baselines and must KEEP firing:
 *      an UNTYPED local (`let mark; mark = tag => …`,
 *      uncalledFunctionChecksInConditional2), a TWO-signature LHS
 *      (contextualTypingWithGenericAndNonGenericSignature — tsc only intersects
 *      overloads under strictFunctionTypes, unmodeled), the LEFT operand of
 *      `&&`/comma (contextuallyTypeLogicalAnd03/contextuallyTypeCommaOperator03;
 *      `||`/`??` propagate to BOTH operands), and params BEYOND the contextual
 *      signature's arity (B224).
 */
class ImplicitAnyContextualTypingTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile("// @strict: true\n" + source.trimIndent(), "t.ts").diagnostics

    private fun ts7006Params(source: String): List<String> =
        diags(source).filter { it.code == 7006 }
            .map { it.message.removePrefix("Parameter '").substringBefore("'") }

    // ------------------------------------------------------------------
    // (a) callee resolvability
    // ------------------------------------------------------------------

    @Test
    fun `nested function callee suppresses TS7006 on callback params`() {
        val params = ts7006Params(
            """
            export function outer() {
                function filterType(v: number, f: (t: number) => boolean): number { return f(v) ? v : 0; }
                return filterType(1, t => t > 0);
            }
            """
        )
        assertEquals(emptyList(), params, "nested-fn callee must contextually type the callback")
    }

    @Test
    fun `function-typed param callee suppresses TS7006 on callback params`() {
        val params = ts7006Params(
            """
            export function uses(fsWatch: (dir: string, cb: (ev: string) => void) => void) {
                fsWatch("d", ev => ev.length);
            }
            """
        )
        assertEquals(emptyList(), params, "param callee resolves lexically")
    }

    @Test
    fun `negative control - unresolvable callee still fires TS7006`() {
        val d = diags("missingFn(zz => zz);")
        assertTrue(d.any { it.code == 2304 }, "expected TS2304 on the callee, got: $d")
        assertEquals(listOf("zz"), d.filter { it.code == 7006 }
            .map { it.message.removePrefix("Parameter '").substringBefore("'") })
    }

    // ------------------------------------------------------------------
    // (b) assignment-RHS contextual typing
    // ------------------------------------------------------------------

    @Test
    fun `assignment to annotated body-local contextually types the arrow`() {
        val params = ts7006Params(
            """
            export function h() {
                let resolveWorker: (names: string[], file: string) => void;
                resolveWorker = (names, file) => { void names; void file; };
                resolveWorker(["a"], "b");
            }
            """
        )
        assertEquals(emptyList(), params)
    }

    @Test
    fun `assignment to property of annotated receiver contextually types the arrow`() {
        val params = ts7006Params(
            """
            export function withHost(host: { write: (a: string, b: number) => void }) {
                host.write = (a, b) => { void a; void b; };
            }
            """
        )
        assertEquals(emptyList(), params)
    }

    @Test
    fun `as-cast local receiver provides the member context`() {
        val params = ts7006Params(
            """
            interface WHost { after: (p: string) => void; }
            export function k(sys: unknown) {
                const result = sys as WHost;
                result.after = p => { void p; };
            }
            """
        )
        assertEquals(emptyList(), params)
    }

    @Test
    fun `optional fn-or-undefined member still provides the signature`() {
        val params = ts7006Params(
            """
            export function m(host: { onDiag?: ((d: string) => void) | undefined }) {
                host.onDiag = d => { void d; };
            }
            """
        )
        assertEquals(emptyList(), params)
    }

    @Test
    fun `negative control - untyped local keeps TS7006 firing on assignment`() {
        // uncalledFunctionChecksInConditional2's pinned rule: `let mark;` is
        // implicitly any → the assignment RHS gets NO contextual signature.
        val params = ts7006Params(
            """
            export function g() {
                let mark;
                mark = (tag) => tag;
                return mark;
            }
            """
        )
        assertEquals(listOf("tag"), params)
    }

    @Test
    fun `negative control - two-signature LHS keeps TS7006 firing`() {
        // contextualTypingWithGenericAndNonGenericSignature's pinned rule: ≥2
        // arity-applicable call signatures → no single contextual signature.
        val params = ts7006Params(
            """
            var f2: { (x: string, y: number): string; <T, U>(x: T, y: U): T };
            f2 = (x2, y2) => { return x2; };
            """
        )
        assertEquals(listOf("x2", "y2"), params)
    }

    @Test
    fun `negative control - params beyond the contextual arity still fire`() {
        val params = ts7006Params(
            """
            export function h() {
                let cb: (a: string) => void;
                cb = (a, extra?) => { void a; void extra; };
            }
            """
        )
        assertEquals(listOf("extra"), params)
    }

    // ------------------------------------------------------------------
    // logical-operator propagation (tsc getContextualTypeForBinaryOperand)
    // ------------------------------------------------------------------

    @Test
    fun `logical-and left operand fires, right operand is contextually typed`() {
        // contextuallyTypeLogicalAnd03's pinned asymmetry.
        val params = ts7006Params(
            """
            let x5: (a: string) => string;
            x5 = (a5 => a5) && (b5 => b5);
            """
        )
        assertEquals(listOf("a5"), params)
    }

    @Test
    fun `logical-or propagates context to both operands`() {
        val params = ts7006Params(
            """
            declare function take(cb: (v: number) => void): void;
            declare let maybe: ((v: number) => void) | undefined;
            take(maybe || (v7 => { void v7; }));
            let x6: (a: string) => string;
            x6 = (a6 => a6) || (b6 => b6);
            """
        )
        assertEquals(emptyList(), params)
    }

    @Test
    fun `comma left operand fires, right operand is contextually typed`() {
        // contextuallyTypeCommaOperator03's pinned asymmetry.
        val params = ts7006Params(
            """
            let x8: (a: string) => string;
            x8 = (a8 => a8, b8 => b8);
            """
        )
        assertEquals(listOf("a8"), params)
    }
}
