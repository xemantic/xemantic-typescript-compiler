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
 * INV.4(c)(iii) batch 4 (round 527): the checkUnresolvedNames family's
 * EXPRESSION emissions migrated onto the check spine — identifiers resolve at
 * their own enters when `spineUResExprChecked` classifies their ancestor chain
 * as expression territory (DESCEND edges up to a dispatch ROOT), reproducing
 * the deleted recursive expression walk's exact reach; NaN comparisons,
 * shorthand-property resolution (TS18004/TS1312), embedded type positions
 * (as/satisfies/assertion targets, call/new type args), class-expression
 * heritage, and JSX tag/factory checks dispatch per node kind. The recursive
 * walker is retained ONLY for the type walker's TypeLiteral computed-name
 * positions (batch 5 deletes both). All pins pre-verified against the OLD
 * walker — a pure reach-preserving migration.
 */
class Inv4SpineBatch18Test {

    @Test
    fun `unresolved identifier in expression statement fires TS2304`() {
        diagnose("unknownA;") should {
            have(any { it.code == 2304 && it.message.contains("'unknownA'") })
        }
    }

    @Test
    fun `binary operands are all checked`() {
        diagnose(
            """
            declare var known: number;
            var r = known + unknownB * unknownC;
            """,
        ) should {
            have(any { it.code == 2304 && it.message.contains("'unknownB'") })
            have(any { it.code == 2304 && it.message.contains("'unknownC'") })
        }
    }

    @Test
    fun `NaN equality comparison fires TS2845`() {
        diagnose(
            """
            declare var x: number;
            if (x === NaN) {}
            """,
        ) should {
            have(any { it.code == 2845 })
        }
    }

    @Test
    fun `negative control - shadowed NaN parameter does not fire TS2845`() {
        diagnose(
            """
            function f(NaN: number) {
                if (NaN === 1) {}
            }
            """,
        ) should {
            have(none { it.code == 2845 })
        }
    }

    @Test
    fun `shorthand property without a value in scope fires TS18004`() {
        diagnose("var o = { missingSh };") should {
            have(any { it.code == 18004 && it.message.contains("'missingSh'") })
        }
    }

    @Test
    fun `shorthand with initializer outside destructuring fires TS1312`() {
        diagnose(
            """
            declare var a: number;
            var o = { a = 5 };
            """,
        ) should {
            have(any { it.code == 1312 })
        }
    }

    @Test
    fun `negative control - shorthand default inside destructuring assignment has no TS1312`() {
        diagnose(
            """
            declare var a: number;
            declare var o: any;
            ({ a = 5 } = o);
            """,
        ) should {
            have(none { it.code == 1312 })
        }
    }

    @Test
    fun `arrow parameter default is checked`() {
        diagnose("var f = (p = missingD) => p;") should {
            have(any { it.code == 2304 && it.message.contains("'missingD'") })
        }
    }

    @Test
    fun `arrow parameter type and TP constraint are checked`() {
        diagnose("var f = <T extends MissingC>(x: MissingT) => x;") should {
            have(any { it.code == 2304 && it.message.contains("'MissingC'") })
            have(any { it.code == 2304 && it.message.contains("'MissingT'") })
        }
    }

    @Test
    fun `arrow expression body is checked`() {
        diagnose("var f = () => missingBody;") should {
            have(any { it.code == 2304 && it.message.contains("'missingBody'") })
        }
    }

    @Test
    fun `function expression param type and default are checked`() {
        diagnose("var f = function (p: MissingFT = missingFD) { return p; };") should {
            have(any { it.code == 2304 && it.message.contains("'MissingFT'") })
            have(any { it.code == 2304 && it.message.contains("'missingFD'") })
        }
    }

    @Test
    fun `object literal method param type and default are checked`() {
        diagnose("var o = { m(p: MissingMT = missingMD) { return p; } };") should {
            have(any { it.code == 2304 && it.message.contains("'MissingMT'") })
            have(any { it.code == 2304 && it.message.contains("'missingMD'") })
        }
    }

    @Test
    fun `object literal computed property name is checked`() {
        diagnose("var o = { [missingKey]: 1 };") should {
            have(any { it.code == 2304 && it.message.contains("'missingKey'") })
        }
    }

    @Test
    fun `class expression heritage base is checked`() {
        diagnose("var C = class extends MissingBase {};") should {
            have(any { (it.code == 2304 || it.code == 2552) && it.message.contains("'MissingBase'") })
        }
    }

    @Test
    fun `as-expression checks both expression and type`() {
        diagnose("var y = missingE as MissingAsT;") should {
            have(any { it.code == 2304 && it.message.contains("'missingE'") })
            have(any { it.code == 2304 && it.message.contains("'MissingAsT'") })
        }
    }

    @Test
    fun `call type arguments are checked`() {
        diagnose(
            """
            declare function g<T>(): void;
            g<MissingTA>();
            """,
        ) should {
            have(any { it.code == 2304 && it.message.contains("'MissingTA'") })
        }
    }

    @Test
    fun `implements bare type param fires TS2422 without a name error`() {
        diagnose("class C2<T> implements T {}") should {
            have(any { it.code == 2422 })
            have(none { it.code == 2304 || it.code == 2693 })
        }
    }

    @Test
    fun `template span expression is checked`() {
        diagnose("var t = `a${'$'}{missingT3}b`;") should {
            have(any { it.code == 2304 && it.message.contains("'missingT3'") })
        }
    }

    @Test
    fun `with statement expression is checked but body is not`() {
        diagnose(
            """
            declare var obj: any;
            with (missingWE) {}
            with (obj) { missingW; }
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 2304 && it.message.contains("'missingWE'") })
            have(none { it.code == 2304 && it.message.contains("'missingW'") })
        }
    }

    @Test
    fun `enum member initializer is checked`() {
        diagnose("enum E { A = missingEM }") should {
            have(any { it.code == 2304 && it.message.contains("'missingEM'") })
        }
    }

    @Test
    fun `negative control - new dot target has no name error`() {
        diagnose(
            """
            function f2() { var n = new.target; }
            """,
            directives = "// @target: es2015",
        ) should {
            have(none { it.code == 2304 && it.message.contains("'target'") })
        }
    }

    @Test
    fun `negative control - jump labels are not name-checked`() {
        diagnose("lbl: for (;;) { break lbl; }") should {
            have(none { it.code == 2304 && it.message.contains("'lbl'") })
        }
    }

    @Test
    fun `negative control - property access NAME is not checked`() {
        diagnose(
            """
            declare var oo: any;
            oo.zzMissing;
            """,
        ) should {
            have(none { it.code == 2304 && it.message.contains("'zzMissing'") })
        }
    }

    @Test
    fun `type literal computed property name keeps the retained walker`() {
        diagnose("type TT = { [missingTL]: string };") should {
            have(any { (it.code == 2304 || it.code == 2693) && it.message.contains("'missingTL'") })
        }
    }

    @Test
    fun `negative control - declare function signature is not checked`() {
        diagnose("declare function df(p: MissingPT): void;") should {
            have(none { it.code == 2304 && it.message.contains("'MissingPT'") })
        }
    }

    @Test
    fun `variable annotation type is still checked`() {
        diagnose("var v2: MissingVT;") should {
            have(any { it.code == 2304 && it.message.contains("'MissingVT'") })
        }
    }

    @Test
    fun `class heritage type arguments are checked`() {
        diagnose(
            """
            declare class Base<T> {}
            class C5 extends Base<MissingArg> {}
            """,
        ) should {
            have(any { it.code == 2304 && it.message.contains("'MissingArg'") })
        }
    }

    @Test
    fun `for header positions are checked`() {
        diagnose("for (var i2 = missingFor1; missingFor2; missingFor3) {}") should {
            have(any { it.code == 2304 && it.message.contains("'missingFor1'") })
            have(any { it.code == 2304 && it.message.contains("'missingFor2'") })
            have(any { it.code == 2304 && it.message.contains("'missingFor3'") })
        }
    }

    @Test
    fun `switch subject and case expressions are checked`() {
        diagnose("switch (missingSw) { case missingCs: break; }") should {
            have(any { it.code == 2304 && it.message.contains("'missingSw'") })
            have(any { it.code == 2304 && it.message.contains("'missingCs'") })
        }
    }

    @Test
    fun `conditional expression branches are checked`() {
        diagnose("var c = missingC1 ? missingC2 : missingC3;") should {
            have(any { it.code == 2304 && it.message.contains("'missingC1'") })
            have(any { it.code == 2304 && it.message.contains("'missingC2'") })
            have(any { it.code == 2304 && it.message.contains("'missingC3'") })
        }
    }

    @Test
    fun `await and spread operands are checked`() {
        diagnose(
            """
            async function fa() { await missingAw; }
            var arr = [...missingSp];
            """,
            directives = "// @target: es2017",
        ) should {
            have(any { it.code == 2304 && it.message.contains("'missingAw'") })
            have(any { it.code == 2304 && it.message.contains("'missingSp'") })
        }
    }

    @Test
    fun `deep binary chain still resolves its operands`() {
        val terms = (1..2000).joinToString(" + ")
        diagnose(
            "var sum = $terms + missingDeep;",
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 2304 && it.message.contains("'missingDeep'") })
        }
    }

    @Test
    fun `class member param default and property initializer are checked`() {
        diagnose(
            """
            class C6 {
                p = missingPI;
                m(q = missingMQ) {}
            }
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 2304 && it.message.contains("'missingPI'") })
            have(any { it.code == 2304 && it.message.contains("'missingMQ'") })
        }
    }

    @Test
    fun `tagged template tag and spans are checked`() {
        diagnose("var r2 = missingTag`x${'$'}{missingSpan}`;") should {
            have(any { it.code == 2304 && it.message.contains("'missingTag'") })
            have(any { it.code == 2304 && it.message.contains("'missingSpan'") })
        }
    }

    @Test
    fun `negative control - for-in loop variable is not expression-checked`() {
        diagnose(
            """
            declare var target2: any;
            for (var kx in target2) {}
            """,
        ) should {
            have(none { it.code == 2304 && it.message.contains("'kx'") })
        }
    }

    @Test
    fun `jsx attribute expression and uppercase tag are checked`() {
        diagnose(
            """
            var e = <MissingComp a={missingAttr} />;
            """,
            directives = "// @jsx: preserve",
            fileName = "t.tsx",
        ) should {
            have(any { it.code == 2304 && it.message.contains("'MissingComp'") })
            have(any { it.code == 2304 && it.message.contains("'missingAttr'") })
        }
    }

    @Test
    fun `negative control - lowercase jsx intrinsic tag is not name-checked`() {
        diagnose(
            """
            var e = <div />;
            """,
            directives = "// @jsx: preserve",
            fileName = "t.tsx",
        ) should {
            have(none { it.code == 2304 && it.message.contains("'div'") })
        }
    }
}
