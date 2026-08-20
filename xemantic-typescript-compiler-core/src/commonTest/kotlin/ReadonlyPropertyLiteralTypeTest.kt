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
 * (WIDEN.1)(b) — a `readonly` class PROPERTY with a literal initializer and no type
 * annotation keeps that LITERAL type; a mutable property widens it.
 *
 * This is the PROPERTY half of the same tsc gate round 781 landed for `const`
 * (`getWidenedLiteralTypeForInitializer`, checker.ts:41455):
 *
 * ```ts
 * return getCombinedNodeFlagsCached(declaration) & NodeFlags.Constant ||
 *     isDeclarationReadonly(declaration) ? type : getWidenedLiteralType(type);
 * ```
 *
 * `NodeFlags.Constant` was the `const` half; `isDeclarationReadonly` is this one.
 * Both halves read a DECLARATION FLAG, never type freshness.
 *
 * WHY IT MATTERED ENOUGH TO LAND: the `static readonly X = 'X'` enum-like constant is
 * how a hand-rolled TypeScript library spells an enum, and every read of one was
 * answering the base primitive. On the `yaml` package it was ~37 of the 66 excess
 * diagnostics xtsc reported over tsc.
 *
 * The oracle for every expectation below is `tools/tsgo-7.0.2/lib/tsc` run over the
 * same source — the boundary was MEASURED, not reasoned. Two of its answers are
 * counter-intuitive and are pinned here as their own cases: `static` is NOT part of
 * the gate (a static readonly keeps its literal for the same reason an instance one
 * does), and a PARAMETER PROPERTY (`constructor(readonly p = 'P')`) WIDENS, because
 * tsc's `isDeclarationReadonly` excludes one explicitly.
 *
 * SEVEN of these fourteen pins DISCRIMINATE, measured by ablation rather than
 * assumed, against TWO separate injected mistakes (one at a time, per the standing
 * protocol):
 *
 *  * arm 1 — `WIDEN1_READONLY_PROP_KEEPS_LITERAL` flipped to `false`, i.e. the rule
 *    itself removed: SIX pins red, the six "keeps its literal type" ones;
 *  * arm 2 — the `widen1ImmutableLiteralTypeIds` widen-back at the assignment target
 *    dropped: exactly ONE pin red, `a write to a readonly literal property emits only
 *    the readonly error`, which is that guard's own uniquely-its-own failure.
 *
 * The other seven hold on both arms on purpose. Four are NEGATIVE CONTROLS pinning
 * that the mutable / static-mutable / annotated / parameter-property directions still
 * widen — a fix that made every literal initializer literal would be worse than the
 * bug it fixes, and these are the pins that say so. Two pin the deliberate exclusions
 * (an enum-member initializer, a computed one). The last, `read through this`, was
 * WRITTEN expecting to discriminate and measured GREEN on both arms: a
 * `return this.tag` against a literal return annotation is decided before the
 * property's own inferred type is consulted, so no input of that shape can see this
 * rule. It is recorded here as an undiscriminated shape rather than claimed as
 * coverage.
 */
class ReadonlyPropertyLiteralTypeTest {

    private val prelude = """
        class C {
            static readonly A = 'A';
            static mutableStatic = 'S';
            readonly b = 'B';
            mutable = 'M';
            readonly n = 42;
            readonly t = true;
            readonly annotated: string = 'A';
            constructor(readonly param = 'P') {}
        }
        declare const c: C;
    """.trimIndent() + "\n"

    // --- the literal survives a readonly property ---------------------------

    /** DISCRIMINATES — `Type 'string' is not assignable to type '"A"'` without the fix. */
    @Test
    fun `a static readonly string literal property keeps its literal type`() {
        val diagnostics = diagnose(prelude + "let x: 'A' = C.A;")
        assert(diagnostics.none { it.code == 2322 })
    }

    /** DISCRIMINATES — the instance half of the same rule; `static` is not the gate. */
    @Test
    fun `an instance readonly string literal property keeps its literal type`() {
        val diagnostics = diagnose(prelude + "let x: 'B' = c.b;")
        assert(diagnostics.none { it.code == 2322 })
    }

    /** DISCRIMINATES — a numeric literal, same rule. */
    @Test
    fun `a readonly numeric literal property keeps its literal type`() {
        val diagnostics = diagnose(prelude + "let x: 42 = c.n;")
        assert(diagnostics.none { it.code == 2322 })
    }

    /** DISCRIMINATES — a boolean literal, same rule. */
    @Test
    fun `a readonly true literal property keeps its literal type`() {
        val diagnostics = diagnose(prelude + "let x: true = c.t;")
        assert(diagnostics.none { it.code == 2322 })
    }

    /**
     * UNDISCRIMINATED — green with the rule ON and OFF alike. Kept as a documented
     * shape (a library's own methods read their constants through `this`) and as a
     * guard that the rule does not BREAK it, but it is not evidence the rule works:
     * a `return this.tag` against a literal return annotation is decided without
     * consulting the property's inferred type at all.
     */
    @Test
    fun `a readonly literal property keeps its literal type when read through this`() {
        val diagnostics = diagnose(
            """
            class K {
                readonly tag = 'k';
                describe(): 'k' { return this.tag; }
            }
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /**
     * DISCRIMINATES — the `yaml` shape that motivated the round: a family of
     * `static readonly` constants used as a discriminated-union tag.
     */
    @Test
    fun `readonly literal properties serve as union tags`() {
        val diagnostics = diagnose(
            """
            class Scalar { static readonly PLAIN = 'PLAIN'; static readonly QUOTE = 'QUOTE'; }
            declare function wantsType(t: 'PLAIN' | 'QUOTE'): void;
            wantsType(Scalar.PLAIN);
            wantsType(Scalar.QUOTE);
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2345 || it.code == 2322 })
    }

    /** DISCRIMINATES — `as const` on a readonly property is literal either way, but the
     *  plain form beside it is what moves; both are pinned in one shape. */
    @Test
    fun `a readonly property initialized with an as const expression keeps its literal`() {
        val diagnostics = diagnose(
            """
            class K { readonly a = 'X' as const; readonly b = 'X'; }
            declare const k: K;
            let p: 'X' = k.a;
            let q: 'X' = k.b;
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    // --- the other direction: a mutable property still widens ----------------

    /**
     * NEGATIVE CONTROL — a MUTABLE instance property widens, so pinning it to its
     * literal must FAIL. A fix that made every literal initializer literal would be
     * worse than the bug it fixes; this is the pin that says so.
     */
    @Test
    fun `negative control - a mutable property still widens its literal`() {
        val diagnostics = diagnose(prelude + "let x: 'M' = c.mutable;")
        assert(diagnostics.any { it.code == 2322 })
    }

    /** NEGATIVE CONTROL — the static mirror of the case above. */
    @Test
    fun `negative control - a mutable static property still widens its literal`() {
        val diagnostics = diagnose(prelude + "let x: 'S' = C.mutableStatic;")
        assert(diagnostics.any { it.code == 2322 })
    }

    /**
     * NEGATIVE CONTROL — an ANNOTATED readonly property takes its type from the
     * ANNOTATION, so `readonly annotated: string = 'A'` is `string` and stays so.
     * The gate must be reached only on the un-annotated branch.
     */
    @Test
    fun `negative control - an annotated readonly property keeps its annotation`() {
        val diagnostics = diagnose(prelude + "let x: 'A' = c.annotated;")
        assert(diagnostics.any { it.code == 2322 })
    }

    /**
     * NEGATIVE CONTROL — a PARAMETER PROPERTY widens, measured against tsgo 7.0.2:
     * tsc's `isDeclarationReadonly` ends `&& !isParameterPropertyDeclaration(...)`.
     * In this parser such a declaration is a `Parameter` node and so cannot reach the
     * property branch at all, which is a structural reason and not a checked one —
     * hence the pin.
     */
    @Test
    fun `negative control - a readonly parameter property still widens its literal`() {
        val diagnostics = diagnose(prelude + "let x: 'P' = c.param;")
        assert(diagnostics.any { it.code == 2322 })
    }

    // --- the deliberate exclusions ------------------------------------------

    /**
     * The rule must NOT add an assignability error at a WRITE to a readonly property:
     * that write is already TS2540 and tsc adds nothing further there
     * (`checkReferenceExpression` returns false). Without the widen-back through
     * `widen1ImmutableLiteralTypeIds`, `c.b = 'B'` — assigning a property its own
     * value — co-emits `Type 'string' is not assignable to type '"B"'`.
     */
    @Test
    fun `a write to a readonly literal property emits only the readonly error`() {
        val diagnostics = diagnose(
            """
            class K { readonly s = 'X'; }
            declare const k: K;
            k.s = 'X';
            k.s = 'Y';
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
        assert(diagnostics.count { it.code == 2540 } == 2)
    }

    /**
     * An ENUM MEMBER initializer is out of scope by construction —
     * `literalTypeOfExpression` answers null for a property access, so
     * `readonly e = E.A` keeps whatever it did before and the rule cannot reach the
     * enum-relation ecology. Pinned so a later widening of the literal reader is
     * forced to re-decide this deliberately rather than by accident.
     */
    @Test
    fun `a readonly property initialized from an enum member is out of scope`() {
        val diagnostics = diagnose(
            """
            enum E { A = 'a', B = 'b' }
            class K { readonly e = E.A; }
            declare const k: K;
            let x: E = k.e;
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /**
     * A readonly property whose initializer is a non-literal EXPRESSION is out of
     * scope too — the literal reader answers null for a computed value, so the
     * property keeps its inferred base type and a literal target is still an error.
     */
    @Test
    fun `negative control - a readonly property computed from an expression still widens`() {
        val diagnostics = diagnose(
            """
            declare const src: string;
            class K { readonly s = src; }
            declare const k: K;
            let x: 'X' = k.s;
            """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2322 })
    }
}
