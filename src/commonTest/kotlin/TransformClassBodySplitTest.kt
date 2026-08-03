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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * (JIT.1)(e) round 818 — pins for the split of `Transformer.transformClassBody`
 * (**16,233 bytecodes**, over HotSpot's 8,000-byte `HugeMethodLimit`, so never
 * JIT-compiled) into an entry plus nine `tcb*` helpers.
 *
 * Like round 817's target this sits on the EMIT path, so every `--noEmit` A/B in
 * this arc is structurally blind to it: the behavioural gate is the corpus
 * suite's emit baselines plus this class. Nothing here is a performance claim —
 * `transformClassBody` runs once per CLASS, and the split lands for the
 * threshold and for the (JIT.1)(f) ratchet.
 *
 * `HugeMethodLimitTest` guards the SIZE. This class pins what a size check
 * cannot see: one ARM pin per helper (an observable only that stage produces, so
 * a dropped call is visible AND attributable), plus the four seams a compiler
 * cannot catch:
 *
 *  * the **ORDER seam** — `tcbCaptureClassAlias` decides the class-alias temp
 *    that the static-block IIFE builder (a local `fun` left in the entry, passed
 *    to `tcbEmitStaticFieldTrailing` as a function-typed argument) closes over.
 *    Running the capture LAST type-checks: all three vars are declared above it.
 *    Then the block's `this` is never rewritten to the alias and the arrow
 *    captures the OUTER `this`;
 *  * the **LIST-IDENTITY seam** — `emittedStaticBlocks` is the caller's list, and
 *    the caller's later loop skips the blocks recorded in it. A helper handed a
 *    fresh list compiles and emits every static block TWICE;
 *  * the **RETURN-SIGNAL seams** — `tcbBuildOutputMembers` reports whether it
 *    placed the constructor at its source position (losing the signal prepends a
 *    SECOND copy), and `tcbAllocatePrivateState` returns the WeakSet brand
 *    variable (losing it drops both the `_C_instances.add(this)` prologue and the
 *    trailing `_C_instances = new WeakSet()`).
 *
 * Every expected string here was READ OFF the real compiler in a scratch project
 * before the pin was written, then re-validated against the UNSPLIT binary —
 * round 817's cheapest step, which caught two blind probes there.
 */
class TransformClassBodySplitTest {

    private fun js(@Language("typescript") source: String): String =
        TypeScriptCompiler().compile(source.trimIndent(), "input.ts")
            .jsOutputs.joinToString("\n") { it.second }

    private fun count(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    // ── tcbLowerAutoAccessors ───────────────────────────────────────────────

    @Test
    fun `auto-accessor downlevel - a public instance accessor field becomes WeakMap storage plus a getter pair`() {
        val out = js(
            """
            // @module: esnext
            // @target: es2015
            export class Acc { accessor v = 1; }
            """
        )
        assert(out.contains("var _Acc_v_accessor_storage;"))
        assert(out.contains("get v() { return __classPrivateFieldGet(this, _Acc_v_accessor_storage, \"f\"); }"))
        assert(out.contains("set v(value) { __classPrivateFieldSet(this, _Acc_v_accessor_storage, value, \"f\"); }"))
        assert(out.contains("_Acc_v_accessor_storage = new WeakMap();"))
    }

    // ── tcbExtractComputedPropertyKeys ──────────────────────────────────────

    @Test
    fun `computed-key extraction - a non-literal computed field name is evaluated once into a temp`() {
        val out = js(
            """
            // @module: esnext
            // @target: es2015
            declare const k: string;
            export class Comp { [k] = 10; }
            """
        )
        assert(out.contains("var _a;"))
        assert(out.contains("this[_a] = 10;"))
        assert(out.contains("_a = k;"))
    }

    // ── tcbAllocatePrivateState ─────────────────────────────────────────────

    /**
     * ALSO THE BRAND RETURN-SIGNAL SEAM: `_Priv_instances` is the WeakSet brand
     * this helper allocates and RETURNS. The `.add(this)` prologue is emitted by
     * the entry and the `= new WeakSet()` by `tcbEmitAliasAndPrivateState`, so
     * both are gated on the returned value having reached the caller.
     */
    @Test
    fun `private state allocation - a private field and method get their WeakMap and WeakSet brand`() {
        val out = js(
            """
            // @module: esnext
            // @target: es2015
            export class Priv { #x = 1; #m() { return this.#x; } use() { return this.#m(); } }
            """
        )
        assert(out.contains("var _Priv_instances, _Priv_m;"))
        assert(out.contains("var _Priv_x;"))
        assert(out.contains("_Priv_instances.add(this);"))
        assert(out.contains("_Priv_x.set(this, 1);"))
        assert(out.contains("_Priv_x = new WeakMap();"))
        assert(out.contains("_Priv_instances = new WeakSet(), _Priv_m = function _Priv_m()"))
    }

    // ── tcbBuildInstanceInitializers ────────────────────────────────────────

    @Test
    fun `instance initializers - a field initializer becomes a this-assignment in the constructor`() {
        val out = js(
            """
            // @module: esnext
            // @target: es2015
            export class Init { y = 2; }
            """
        )
        assert(out.contains("constructor() {"))
        assert(out.contains("this.y = 2;"))
    }

    @Test
    fun `instance initializers - useDefineForClassFields lowers a field to Object defineProperty`() {
        val out = js(
            """
            // @module: esnext
            // @target: es2015
            // @useDefineForClassFields: true
            export class Def { p = 1; q; }
            """
        )
        assert(out.contains("Object.defineProperty(this, \"p\", {"))
        assert(out.contains("value: 1"))
        // a field with NO initializer still gets a defineProperty with void 0
        assert(out.contains("Object.defineProperty(this, \"q\", {"))
        assert(out.contains("value: void 0"))
    }

    // ── tcbBuildTransformedConstructor ──────────────────────────────────────

    @Test
    fun `constructor build - field initializers are spliced in after the super call`() {
        val out = js(
            """
            // @module: esnext
            // @target: es2015
            class Base { constructor(public a: number) {} }
            export class Der extends Base { y = 2; constructor() { super(1); } }
            """
        )
        // the parameter property of the base class
        assert(out.contains("this.a = a;"))
        val superCall = out.indexOf("super(1);")
        val fieldInit = out.indexOf("this.y = 2;")
        assert(superCall >= 0)
        assert(fieldInit > superCall)
    }

    // ── tcbBuildOutputMembers ───────────────────────────────────────────────

    /**
     * THE CONSTRUCTOR RETURN-SIGNAL SEAM. The helper places the transformed
     * constructor at its SOURCE position and reports that it did; the caller
     * prepends it only when it did not. A lost signal emits it twice — once in
     * member order and once at index 0 — which still compiles as Kotlin and is
     * invisible to any size check.
     */
    @Test
    fun `output members - the constructor stays at its source position and is emitted exactly once`() {
        val out = js(
            """
            // @module: esnext
            // @target: es2015
            export class Ord { m() { return 1; } constructor(public a: number) {} }
            """
        )
        assert(count(out, "constructor(") == 1)
        val method = out.indexOf("m() { return 1; }")
        val ctor = out.indexOf("constructor(")
        assert(method >= 0)
        assert(ctor > method)
    }

    // ── tcbCaptureClassAlias + tcbEmitAliasAndPrivateState +
    //    tcbEmitStaticFieldTrailing ────────────────────────────────────────

    /**
     * THE ORDER SEAM and the LIST-IDENTITY SEAM in one shape.
     *
     *  * `_a = Stat` exists only if the capture stage ran AND its answer reached
     *    the caller, and `_a.v = 2` inside the IIFE only if it ran BEFORE the
     *    static-trailing stage built the block (the builder reads the caller's
     *    var through a closure). With the order reversed the arrow keeps `this`,
     *    which at that position is the OUTER `this` — a real semantic change;
     *  * the block must appear EXACTLY ONCE: the member-order loop records it in
     *    `emittedStaticBlocks` and the entry's later loop skips what is recorded.
     */
    @Test
    fun `static trailing - a static block becomes one IIFE with this routed through the class alias`() {
        val out = js(
            """
            // @module: esnext
            // @target: es2015
            export class Stat { static v = 1; static { this.v = 2; } }
            """
        )
        assert(out.contains("var _a;"))
        assert(out.contains("_a = Stat;"))
        assert(out.contains("Stat.v = 1;"))
        assert(out.contains("_a.v = 2;"))
        assert(!out.contains("this.v = 2;"))
        assert(count(out, "(() => {") == 1)
    }

    @Test
    fun `static trailing - a plain static field becomes a trailing assignment with no alias`() {
        val out = js(
            """
            // @module: esnext
            // @target: es2015
            export class Plain { static s = 1; }
            """
        )
        assert(out.contains("Plain.s = 1;"))
        assert(!out.contains("var _a;"))
    }

    // ── what STAYED in the entry ────────────────────────────────────────────

    /**
     * The single whole-function `return` was deliberately left in the entry (the
     * round-813 property: no helper needs a return signal for CONTROL flow, only
     * for values). This is its control — a class with none of the shapes above
     * still round-trips through every kept stage.
     */
    @Test
    fun `the entry still returns a plain class untouched`() {
        val out = js(
            """
            // @module: esnext
            // @target: es2015
            export class Plainest { m(): number { return 1; } }
            """
        )
        assert(out.contains("export class Plainest {"))
        assert(out.contains("m() { return 1; }"))
        assert(!out.contains("constructor"))
    }
}
