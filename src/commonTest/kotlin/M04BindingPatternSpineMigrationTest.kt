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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (M0.4, round 631): pins for the checkBindingPatternComputedIndexSig
 * (B9.4/B98.r40 — TS2537 computed-key destructuring vs the `{}` default;
 * TS2448+TS2728/TS2538 self-referential computed binding keys) spine
 * migration — the three emission families' gates and the deleted
 * walkB94InStmts/-InStmt/-InExpr reach, both directions: fn-EXPRESSION-like
 * parameter lists (arrow / fn-expr / objlit method+setter / class-EXPRESSION
 * method+ctor+setter) emit TS2537 while FunctionDeclaration and
 * class-DECLARATION member parameter lists do NOT; a FunctionDeclaration's
 * parameter DEFAULTS are walked while an arrow's are NOT; the empty-objlit
 * destructure emits at VariableStatements but NOT at for-heads; the self-ref
 * key fires for let/const (incl. for/for-in/for-of heads) but never `var`;
 * a DOTTED namespace's body IS walked (the parser keeps one
 * ModuleDeclaration with a dotted name and a ModuleBlock body). All
 * expectations verified green against the pre-migration legacy pass first.
 */
class M04BindingPatternSpineMigrationTest {

    private val prelude = """
        declare const key: string;
        declare const x: any;
    """.trimIndent()

    // ── emitB94ForFnLikeParams: fn-like parameter lists that DO emit ───────

    @Test
    fun `arrow param computed-key pattern fires TS2537`() {
        val ds = diagnose(
            prelude + "\nconst f = ({[key]: v}) => v;"
        )
        assert(ds.count { it.code == 2537 } == 1)
        ds should {
            have(any { it.code == 2537 &&
                it.message == "Type '{}' has no matching index signature for type 'string'." })
        }
    }

    @Test
    fun `function-expression param computed-key pattern fires TS2537`() {
        diagnose(
            prelude + "\nconst f = function({[key]: v}) { return v; };"
        ) should {
            have(any { it.code == 2537 &&
                it.message == "Type '{}' has no matching index signature for type 'string'." })
        }
    }

    @Test
    fun `object-literal method param fires TS2537`() {
        diagnose(
            prelude + "\nconst o = { m({[key]: v}) {} };"
        ) should {
            have(any { it.code == 2537 })
        }
    }

    @Test
    fun `object-literal setter param fires TS2537`() {
        diagnose(
            prelude + "\nconst o = { set p({[key]: v}) {} };"
        ) should {
            have(any { it.code == 2537 })
        }
    }

    @Test
    fun `class-expression constructor param fires TS2537`() {
        diagnose(
            prelude + "\nconst K = class { constructor({[key]: v}) {} };"
        ) should {
            have(any { it.code == 2537 })
        }
    }

    @Test
    fun `class-expression method param fires TS2537`() {
        diagnose(
            prelude + "\nconst K = class { m({[key]: v}) {} };"
        ) should {
            have(any { it.code == 2537 })
        }
    }

    // ── fn-like parameter lists that do NOT emit (legacy quirks) ───────────

    @Test
    fun `negative control - FunctionDeclaration param pattern draws no TS2537`() {
        diagnose(
            prelude + "\nfunction g({[key]: v}) {}"
        ) should {
            have(none { it.code == 2537 })
        }
    }

    @Test
    fun `negative control - class-DECLARATION method param draws no TS2537`() {
        diagnose(
            prelude + "\nclass C { m({[key]: v}) {} }"
        ) should {
            have(none { it.code == 2537 })
        }
    }

    @Test
    fun `negative control - an annotated param pattern draws no TS2537`() {
        diagnose(
            prelude + "\nconst f = ({[key]: v}: any) => v;"
        ) should {
            have(none { it.code == 2537 })
        }
    }

    @Test
    fun `negative control - a defaulted param pattern draws no TS2537`() {
        diagnose(
            prelude + "\nconst f = ({[key]: v} = x) => v;"
        ) should {
            have(none { it.code == 2537 })
        }
    }

    @Test
    fun `negative control - a literal computed key draws no TS2537`() {
        diagnose(
            prelude + "\nconst f = ({[\"a\"]: v}) => v;"
        ) should {
            have(none { it.code == 2537 })
        }
    }

    // ── emitB94ForComputedKeyPattern: the empty-objlit destructure ─────────

    @Test
    fun `empty-object-literal destructure fires TS2537`() {
        diagnose(
            prelude + "\nlet {[key]: v} = {};"
        ) should {
            have(any { it.code == 2537 &&
                it.message == "Type '{}' has no matching index signature for type 'string'." })
        }
    }

    @Test
    fun `negative control - a NON-empty object-literal destructure draws no TS2537 here`() {
        diagnose(
            prelude + "\nlet {[key]: v} = { a: 1 };"
        ) should {
            have(none { it.code == 2537 })
        }
    }

    @Test
    fun `negative control - an annotated empty-objlit destructure draws no TS2537`() {
        diagnose(
            prelude + "\nlet {[key]: v}: any = {};"
        ) should {
            have(none { it.code == 2537 })
        }
    }

    @Test
    fun `negative control - a for-head empty-objlit destructure draws no TS2537`() {
        diagnose(
            prelude + "\nfor (let {[key]: v} = {}; ;) { break; }"
        ) should {
            have(none { it.code == 2537 })
        }
    }

    // ── checkSelfRefComputedBindingKeyList: TS2448 + TS2728 + TS2538 ───────

    @Test
    fun `let self-referential computed key fires TS2448 with TS2728 related and TS2538`() {
        val ds = diagnose(
            prelude + "\nlet {[a]: a} = x;"
        )
        assert(ds.count { it.code == 2448 } == 1)
        assert(ds.count { it.code == 2538 } == 1)
        ds should {
            have(any { it.code == 2448 &&
                it.message == "Block-scoped variable 'a' used before its declaration." &&
                it.relatedInformation.any { r -> r.code == 2728 && r.message == "'a' is declared here." } })
            have(any { it.code == 2538 &&
                it.message == "Type 'any' cannot be used as an index type." })
        }
    }

    @Test
    fun `const self-referential computed key fires too`() {
        diagnose(
            prelude + "\nconst {[b]: b} = x;"
        ) should {
            have(any { it.code == 2448 })
            have(any { it.code == 2538 })
        }
    }

    @Test
    fun `negative control - a var self-referential computed key draws neither`() {
        diagnose(
            prelude + "\nvar {[c]: c} = x;"
        ) should {
            have(none { it.code == 2448 })
            have(none { it.code == 2538 })
        }
    }

    @Test
    fun `negative control - distinct key and binding names draw neither`() {
        diagnose(
            prelude + "\ndeclare const k2: string;\nlet {[k2]: v} = x;"
        ) should {
            have(none { it.code == 2448 })
            have(none { it.code == 2538 })
        }
    }

    @Test
    fun `for-head self-referential computed key fires`() {
        diagnose(
            prelude + "\nfor (let {[a]: a} = x; ;) { break; }"
        ) should {
            have(any { it.code == 2448 })
            have(any { it.code == 2538 })
        }
    }

    @Test
    fun `for-of-head self-referential computed key fires`() {
        diagnose(
            prelude + "\ndeclare const arr: any[];\nfor (const {[a]: a} of arr) {}"
        ) should {
            have(any { it.code == 2448 })
            have(any { it.code == 2538 })
        }
    }

    @Test
    fun `for-in-head self-referential computed key fires`() {
        diagnose(
            prelude + "\nfor (const {[a]: a} in x) {}"
        ) should {
            have(any { it.code == 2448 })
            have(any { it.code == 2538 })
        }
    }

    // ── reach pins: positions the legacy walker DOES visit ─────────────────

    @Test
    fun `arrow in a call argument fires`() {
        diagnose(
            prelude + "\ndeclare function takes(a: any): void;\ntakes(({[key]: v}) => v);"
        ) should {
            have(any { it.code == 2537 })
        }
    }

    @Test
    fun `arrow in a FunctionDeclaration param default IS walked`() {
        diagnose(
            prelude + "\nfunction g(cb = ({[key]: v}) => v) {}"
        ) should {
            have(any { it.code == 2537 })
        }
    }

    @Test
    fun `self-ref inside a class method body fires`() {
        diagnose(
            prelude + "\nclass C { m() { let {[a]: a} = x; } }"
        ) should {
            have(any { it.code == 2448 })
        }
    }

    @Test
    fun `arrow in a template span fires`() {
        diagnose(
            prelude + "\nconst s = `v\${(({[key]: v}) => v)}w`;"
        ) should {
            have(any { it.code == 2537 })
        }
    }

    @Test
    fun `empty-objlit destructure in a single-name namespace fires`() {
        diagnose(
            prelude + "\nnamespace A { let {[key]: v} = {}; }"
        ) should {
            have(any { it.code == 2537 })
        }
    }

    @Test
    fun `arrow in a switch case-clause expression fires`() {
        diagnose(
            prelude + """

            declare const n: number;
            switch (n) {
                case (({[key]: v}) => 0)(x): break;
            }
            """
        ) should {
            have(any { it.code == 2537 })
        }
    }

    // ── reach pins: positions the legacy walker does NOT visit ─────────────

    @Test
    fun `negative control - an arrow param default is not walked`() {
        diagnose(
            prelude + "\nconst g = (cb = ({[key]: v}) => v) => 0;"
        ) should {
            have(none { it.code == 2537 })
        }
    }

    @Test
    fun `dotted namespace body IS walked - its body is a ModuleBlock in this AST`() {
        // (The parser keeps `namespace A.B` as ONE ModuleDeclaration with a
        // dotted name and a ModuleBlock body, so the legacy `as? ModuleBlock`
        // descends it — verified against the legacy pass.)
        diagnose(
            prelude + "\nnamespace A.B { let {[key]: v} = {}; }"
        ) should {
            have(any { it.code == 2537 })
        }
    }

    @Test
    fun `negative control - a heritage expression is not walked`() {
        diagnose(
            prelude + """

            declare function mix(a: any): new () => object;
            class C extends mix(({[key]: v}) => v) {}
            """
        ) should {
            have(none { it.code == 2537 })
        }
    }
}
