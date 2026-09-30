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
 * (CHK.180) stage 1 — the property-access walk types `this` and a block-scoped body local
 * as `any`, so TS7053 (`r[k]`), TS18048 (`r.opt.p`) and, for a CAST local, TS2339 / literal
 * TS7053 went silent where tsgo reports. [WrittenReceiverTypes] installs the WRITTEN type of
 * the access root — `this` of an instance member of a class declaration, or a single-
 * declaration `const` with an annotation or an `as T` / `<T>` initializer — for that one
 * access only. Every expectation is tsgo 7.0.2's row (`build/scratch-p18237-census/solo/`
 * cells, `build/bench/p18239-agent/`), rendered `line,col: TScode message | chain`; each
 * source keeps its cell's line numbering (the directive line stands in for `export {};`).
 *
 * Residues NOT closed here (tsgo reports, we stay silent) — recorded, not pinned:
 * `let` / `var` receivers (refused: assignment-in-condition narrowing gaps, see the `let`
 * control), a static member's `this` (`typeof D`), a class-expression `this`, an
 * object-literal method's `this`, a `this:` parameter, an `as const` local, a body-local
 * `const k: string` KEY (the key side reads `any` the same way), and a receiver whose class
 * declares a constructor (see the `residue -` pin).
 */
class Chk180WrittenReceiverTypeTest {

    private fun rows(body: String): List<String> =
        diagnose(body.trimIndent() + "\nexport {}").map { d ->
            (listOf("${d.line},${d.character}: TS${d.code} ${d.message}") + d.messageChain.map { it.trim() })
                .joinToString(" | ")
        }

    private fun key(line: Int, col: Int, type: String = "D") =
        "$line,$col: TS7053 Element implicitly has an 'any' type because expression of type 'string' can't be used to index type '$type'." +
            " | No index signature with a parameter of type 'string' was found on type '$type'."

    private fun lit(line: Int, col: Int) =
        "$line,$col: TS7053 Element implicitly has an 'any' type because expression of type '\"lit\"' can't be used to index type 'D'." +
            " | Property 'lit' does not exist on type 'D'."

    private fun nul(line: Int, col: Int) = "$line,$col: TS18048 'x.opt' is possibly 'undefined'."

    private fun dot(line: Int, col: Int) = "$line,$col: TS2339 Property 'nope' does not exist on type 'D'."

    // --- `this` of an instance member: the key cells --------------------------------

    @Test
    fun `this key in a method`() {
        val r = rows("""
            class D { p = 0; opt?: D; m(k: string) { const v = this[k]; } }
        """)
        assert(r == listOf(key(1, 52)))
    }

    @Test
    fun `this key in a nested block of a method`() {
        val r = rows("""
            class D { p = 0; opt?: D; m(k: string, c: boolean) { if (c) { const v = this[k]; } } }
        """)
        assert(r == listOf(key(1, 73)))
    }

    @Test
    fun `this key in an arrow inside a method`() {
        val r = rows("""
            class D { p = 0; opt?: D; m(k: string) { const f = () => { const v = this[k]; }; } }
        """)
        assert(r == listOf(key(1, 70)))
    }

    @Test
    fun `this key in a property-initializer arrow`() {
        val r = rows("""
            class D { p = 0; opt?: D; f = (k: string) => { const v = this[k]; } }
        """)
        assert(r == listOf(key(1, 58)))
    }

    // --- an annotated body-local const: the key and nullish cells --------------------

    private val annPrelude = "interface D { p: number; opt?: D }\ndeclare function mk(): D;\n"

    @Test
    fun `annotated const key and nullish member in a function body`() {
        val r = rows(annPrelude + """
            function g(k: string) { const x: D = mk(); const v = x[k]; }
            function h(k: string) { const x: D = mk(); const v = x.opt.p; }
        """.trimIndent())
        assert(r == listOf(key(3, 54), nul(4, 54)))
    }

    @Test
    fun `annotated const key and nullish member in a nested block`() {
        val r = rows(annPrelude + """
            function g(k: string, c: boolean) { if (c) { const x: D = mk(); const v = x[k]; } }
            function h(k: string, c: boolean) { if (c) { const x: D = mk(); const v = x.opt.p; } }
        """.trimIndent())
        assert(r == listOf(key(3, 75), nul(4, 75)))
    }

    @Test
    fun `annotated const key and nullish member in an arrow body`() {
        val r = rows(annPrelude + """
            const g = (k: string) => { const x: D = mk(); const v = x[k]; };
            const h = (k: string) => { const x: D = mk(); const v = x.opt.p; };
        """.trimIndent())
        assert(r == listOf(key(3, 57), nul(4, 57)))
    }

    @Test
    fun `annotated const read from a nested function declaration`() {
        val r = rows(annPrelude + """
            function nested() { const x: D = { p: 1 }; function inner(k: string) { const v = x[k]; } }
        """.trimIndent())
        assert(r == listOf(key(3, 82)))
    }

    // --- a cast-initialized const: all four access forms ------------------------------

    private val castPrelude = "interface D { p: number; opt?: D }\ndeclare const u: unknown;\n"

    @Test
    fun `as-cast const reports all four access forms`() {
        val r = rows(castPrelude + """
            function a(k: string) { const x = u as D; const v = x.nope; }
            function b(k: string) { const x = u as D; const v = x[k]; }
            function c(k: string) { const x = u as D; const v = x["lit"]; }
            function d(k: string) { const x = u as D; const v = x.opt.p; }
        """.trimIndent())
        assert(r == listOf(dot(3, 55), key(4, 53), lit(5, 53), nul(6, 53)))
    }

    @Test
    fun `catch-variable cast const reports all four access forms`() {
        val r = rows("interface D { p: number; opt?: D }\n" + """
            function a(k: string) { try {} catch (e) { const x = e as D; const v = x.nope; } }
            function b(k: string) { try {} catch (e) { const x = e as D; const v = x[k]; } }
            function c(k: string) { try {} catch (e) { const x = e as D; const v = x["lit"]; } }
            function d(k: string) { try {} catch (e) { const x = e as D; const v = x.opt.p; } }
        """.trimIndent())
        assert(r == listOf(dot(2, 74), key(3, 72), lit(4, 72), nul(5, 72)))
    }

    @Test
    fun `angle-bracket and parenthesized casts are written types too`() {
        val r = rows(castPrelude + """
            function ta(k: string) { const x = <D>u; const v = x[k]; const w = x.nope; }
            function pa(k: string) { const x = (u as D); const v = x[k]; }
        """.trimIndent())
        assert(r == listOf(key(3, 52), dot(3, 70), key(4, 56)))
    }

    // --- tsgo-silent controls: the refusals ------------------------------------------

    /**
     * marked `Lexer.ts:113` (the census's R1): a `let` narrowed by an assignment in an `if`
     * condition after a closure that also assigns it. This checker's flow collapses the second
     * `token` to `never`, so admitting `let` reports `Property 'raw' does not exist on type 'never'`.
     */
    @Test
    fun `negative control - a let receiver narrowed by assignment in a condition stays silent`() {
        val r = rows("""
            interface Generic { [index: string]: any; type: string; raw: string }
            interface Space { type: 'space'; raw: string }
            interface Code { type: 'code'; raw: string; text: string }
            class Tokenizer { space(s: string): Space | undefined { return undefined; } code(s: string): Code | undefined { return undefined; } }
            class Lexer {
              tokenizer = new Tokenizer();
              exts: ((s: string) => Generic | undefined)[] = [];
              blockTokens(src: string) {
                while (src) {
                  let token: Generic | undefined;
                  if (this.exts.some((e) => { if (token = e(src)) { src = src.substring(token.raw.length); return true; } return false; })) { continue; }
                  if (token = this.tokenizer.space(src)) { src = src.substring(token.raw.length); continue; }
                  if (token = this.tokenizer.code(src)) { src = src.substring(token.raw.length); continue; }
                }
              }
            }
        """)
        assert(r.isEmpty())
    }

    /**
     * marked `Instance.ts:77` (the census's R7): a nullable annotated const whose initializer
     * rules the nullish part out, read two closures deep. B6's veto keeps it silent; without it
     * this checker reports TS18049 on `extensions` (the fixture is the marked method reduced
     * verbatim — its comment lines are load-bearing for the reproduction, an unexplained
     * sensitivity recorded in the (P18.239) note).
     */
    @Test
    fun `negative control - a nullable annotated const with a non-nullish initializer stays silent`() {
        val r = rows("""
            class M {
              use(...args: { extensions?: ({ name: string; renderer: () => void } | { name: string; level: string })[] | null }[]) {
                const extensions: { renderers: { [k: string]: number } } | null | undefined = { renderers: {} };

                args.forEach((pack) => {
                  // copy options to new object

                  // ==-- Parse "addon" extensions --== //
                  if (pack.extensions) {
                    pack.extensions.forEach((ext) => {
                      if (!ext.name) {
                        throw new Error('extension name required');
                      }
                      if ('renderer' in ext) {
                        const prevRenderer = extensions.renderers[ext.name];
                      }
                    });
                  }
                });
                return this;
              }
            }
        """)
        assert(r.isEmpty())
    }

    /**
     * A narrowed local read inside a CLASS-EXPRESSION method: tsgo narrows through it
     * (`isObjectLiteralOrClassExpressionMethodOrAccessor`), this checker's flow graph does not,
     * so the written `A | undefined` must not be installed there
     * ([BodyLocalAssignments.readCrossesUnmodeledContainer]) — or `x.req` reports TS18048.
     */
    @Test
    fun `negative control - a narrowed nullable local read inside a class-expression method stays silent`() {
        val r = rows("""
            interface A { kind: 'a'; req: { n: number } }
            declare const v: A | undefined;
            function f(k: string) { const x: A | undefined = v; if (x) { const C = class { m(k: string) { return x.req.n; } }; } }
        """)
        assert(r.isEmpty())
    }

    /**
     * `discriminateWithOptionalProperty4`'s workaround local: a written union of two object
     * types is refused, because narrowing it by the truthiness of an optional `?: undefined`
     * member is a false TS18048 in this checker — on a PARAMETER of the same type too (a
     * pre-existing residue, (P18.239) note). tsgo is silent.
     */
    @Test
    fun `negative control - a written union of object types on a body local is not installed`() {
        val r = rows("""
            type U = { a: string[]; b?: undefined } | { b: string[]; a?: undefined };
            declare const g: U;
            function l() { const w: U = g; w.a ? w.a.toString() : w.b.toString(); }
            function l2() { const w = g as U; if (!w.a) { w.b.toString(); } }
        """)
        assert(r.isEmpty())
    }

    /**
     * The written type lives for ONE access: installed for the frame's lifetime, the `this` a
     * preceding `this.p` typed would reach the destructuring-assignment private check and
     * report a false TS2341 on `({ x } = this)` inside the class (the measured cost of a frame
     * install, `noUnusedLocals_destructuringAssignment`). tsgo is silent.
     */
    @Test
    fun `negative control - an access on this does not leak its type into a later destructuring assignment`() {
        val r = rows("""
            class C {
                private x = 0;
                p = 1;
                m(): number {
                    const a = this.p;
                    let x: number;
                    ({ x } = this);
                    return x + a;
                }
            }
        """)
        assert(r.isEmpty())
    }

    /**
     * A read inside an object-literal METHOD: tsgo narrows through it, this checker's flow graph
     * does not. Structurally silent here — the property-access walk does not descend into an
     * object-literal method body — so this control cannot discriminate the crossing guard.
     */
    @Test
    fun `negative control - a narrowed local read inside an object-literal method stays silent`() {
        val r = rows("""
            interface D { p: number; opt?: D }
            function crosses(u: D | undefined) { const x: D | undefined = u; if (x) { const o = { m() { return x.p; } }; } }
        """)
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - a nullable annotated const read unguarded still reports once`() {
        val r = rows("""
            interface D { p: number; opt?: D }
            declare function mk2(): D | undefined;
            function nl() { const x: D | undefined = mk2(); const v = x.p; }
        """)
        assert(r == listOf("3,59: TS18048 'x' is possibly 'undefined'."))
    }

    /** A static member's `this` is `typeof S`: the dot row is enclosingClassType's; the key row is a residue. */
    @Test
    fun `static this keeps its dot row and gains no key row`() {
        val r = rows("""
            class S { static p = 0; static m(k: string) { const w = this.nope; } }
        """)
        assert(r == listOf("1,62: TS2339 Property 'nope' does not exist on type 'typeof S'."))
    }

    /**
     * RESIDUE, tsgo reports `2,52: TS7053 … index type 'D'` here: a class that DECLARES a
     * constructor carries a construct signature on its INSTANCE type ((CHK.73)), so
     * `ElementAccessMissingMember.plainIndexless` refuses it — for `this` in the constructor
     * and equally for a plain parameter of that class type. Not a `this`-typing gap.
     */
    @Test
    fun `residue - a receiver whose class declares a constructor stays silent on a string key`() {
        val r = rows("""
            class D { p = 0; opt?: D; constructor(k: string) { const v = this[k]; } }
            function f(d: D, k: string) { const v = d[k]; }
        """)
        assert(r.isEmpty())
    }
}
