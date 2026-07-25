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
 * INV.4(c)(iv) (round 529): checkTypeUsedAsValue (TS2693/TS2708/TS2689/TS2585/
 * TS18042) migrated onto the check spine — the recursive
 * checkTypeAsValueInStatement(s)/checkTypeAsValueInExpr walkers are DELETED.
 * Reach is reproduced by a memoized ancestor-chain classifier
 * (`spineTavStatus` over `spineTavEdge` — the deleted walker's exact dispatch
 * arms, including the corpus-tuned NON-descent into for/while/do/switch/try
 * bodies, class accessors/expressions, shorthand properties, and object-literal
 * method parameter defaults), the three ScopeNameSet chains by pull-based
 * memoized levels (`tavLevelAt` — the family's surveys are position-
 * independent), and the plain-`=`-LHS TS2708 suppression as a REACHED_NONS
 * status. All pins verified against the OLD walker (pre-migration checker) —
 * a pure reach-preserving migration.
 */
class Inv4SpineBatch20Test {

    // ── TS2693: type-only names in value positions ──────────────────────────

    @Test
    fun `interface called as value fires TS2693`() {
        diagnose("""
            interface I1 {}
            I1();
        """) should {
            have(any { it.code == 2693 && it.message.contains("'I1'") })
        }
    }

    @Test
    fun `type alias in bare expression statement fires TS2693`() {
        diagnose("""
            type T1 = number;
            T1;
        """) should {
            have(any { it.code == 2693 && it.message.contains("'T1'") })
        }
    }

    @Test
    fun `type keyword used as value fires TS2693`() {
        diagnose("number;") should {
            have(any { it.code == 2693 && it.message.contains("'number'") })
        }
    }

    @Test
    fun `new on interface fires TS2693`() {
        diagnose("""
            interface I2 {}
            new I2();
        """) should {
            have(any { it.code == 2693 && it.message.contains("'I2'") })
        }
    }

    @Test
    fun `typeof interface in value position fires TS2693`() {
        diagnose("""
            interface I3 {}
            let x = typeof I3;
        """) should {
            have(any { it.code == 2693 && it.message.contains("'I3'") })
        }
    }

    @Test
    fun `negative control - typeof type keyword does not fire TS2693`() {
        diagnose("let x = typeof number;") should {
            have(none { it.code == 2693 })
        }
    }

    @Test
    fun `assignment target interface still fires TS2693`() {
        diagnose("""
            interface I4 {}
            I4 = 5;
        """) should {
            have(any { it.code == 2693 && it.message.contains("'I4'") })
        }
    }

    // ── TS2708: namespace-only names in value positions ─────────────────────

    @Test
    fun `type-only namespace used as value fires TS2708`() {
        diagnose("""
            namespace N1 { export interface I {} }
            N1;
        """) should {
            have(any { it.code == 2708 && it.message.contains("'N1'") })
        }
    }

    @Test
    fun `negative control - namespace self-reference inside own body does not fire TS2708`() {
        diagnose("""
            namespace N2 {
                N2;
            }
        """) should {
            have(none { it.code == 2708 })
        }
    }

    @Test
    fun `negative control - plain assignment LHS namespace fires TS2708 exactly once`() {
        // checkConstAssignment owns the assignment-target TS2708; the family
        // suppresses its own emission on a plain `=` LHS (no double-emit).
        val d = diagnose("""
            namespace N3 { export interface I {} }
            N3 = 5;
        """)
        assert(d.count { it.code == 2708 } == 1)
    }

    @Test
    fun `compound assignment LHS namespace fires TS2708`() {
        diagnose("""
            namespace N4 { export interface I {} }
            N4 += 1;
        """) should {
            have(any { it.code == 2708 && it.message.contains("'N4'") })
        }
    }

    @Test
    fun `negative control - merged namespace with value declarations does not fire TS2708`() {
        diagnose("""
            namespace M1 { export interface T {} }
            namespace M1 { export const x = 1; }
            let z = M1;
        """) should {
            have(none { it.code == 2708 })
        }
    }

    @Test
    fun `negative control - type plus instantiated namespace clodule is a value`() {
        diagnose("""
            type B1 = number;
            namespace B1 { export const enter = 1; }
            let w = B1.enter;
        """) should {
            have(none { it.code == 2708 || it.code == 2693 })
        }
    }

    // ── reach: the corpus-tuned non-descent ─────────────────────────────────

    @Test
    fun `negative control - for body is not walked`() {
        diagnose("""
            interface I5 {}
            for (;;) { I5(); }
        """) should {
            have(none { it.code == 2693 })
        }
    }

    @Test
    fun `negative control - while body is not walked`() {
        diagnose("""
            interface I6 {}
            while (1) { I6(); }
        """) should {
            have(none { it.code == 2693 })
        }
    }

    @Test
    fun `negative control - switch case body is not walked`() {
        diagnose("""
            interface I7 {}
            switch (1) { case 1: I7(); }
        """) should {
            have(none { it.code == 2693 })
        }
    }

    @Test
    fun `negative control - try block is not walked`() {
        diagnose("""
            interface I8 {}
            try { I8(); } catch (e) {}
        """) should {
            have(none { it.code == 2693 })
        }
    }

    @Test
    fun `negative control - for-header declaration initializer is not walked`() {
        diagnose("""
            interface I9 {}
            for (let a = I9; ;) {}
        """) should {
            have(none { it.code == 2693 })
        }
    }

    @Test
    fun `if branches are walked`() {
        diagnose("""
            interface IA {}
            if (1) { IA(); }
        """) should {
            have(any { it.code == 2693 && it.message.contains("'IA'") })
        }
    }

    @Test
    fun `nested block and return expression are walked`() {
        diagnose("""
            interface IB {}
            function f() {
                {
                    return IB();
                }
            }
        """) should {
            have(any { it.code == 2693 && it.message.contains("'IB'") })
        }
    }

    // ── scope levels: shadowing ─────────────────────────────────────────────

    @Test
    fun `type parameter used as value fires TS2693`() {
        diagnose("""
            function f<TP1>() { return new TP1(); }
        """) should {
            have(any { it.code == 2693 && it.message.contains("'TP1'") })
        }
    }

    @Test
    fun `negative control - parameter shadows type-only name`() {
        diagnose("""
            interface IC {}
            function f(IC: any) { IC(); }
        """) should {
            have(none { it.code == 2693 })
        }
    }

    @Test
    fun `negative control - hoisted var in nested block shadows type-only name`() {
        diagnose("""
            interface ID {}
            function f() {
                { var ID = 1; }
                ID;
            }
        """) should {
            have(none { it.code == 2693 })
        }
    }

    @Test
    fun `negative control - destructured const element shadows type-only name`() {
        diagnose("""
            interface IE {}
            declare const o: any;
            const { IE } = o;
            IE;
        """) should {
            have(none { it.code == 2693 })
        }
    }

    // ── classes ─────────────────────────────────────────────────────────────

    @Test
    fun `class extends interface fires TS2689`() {
        diagnose("""
            interface IF {}
            class C1 extends IF {}
        """) should {
            have(any { it.code == 2689 && it.message.contains("'IF'") })
        }
    }

    @Test
    fun `class extends type alias fires TS2693`() {
        diagnose("""
            type TA1 = {};
            class C2 extends TA1 {}
        """) should {
            have(any { it.code == 2693 && it.message.contains("'TA1'") })
        }
    }

    @Test
    fun `class method body and property initializer are walked`() {
        val d = diagnose("""
            interface IG {}
            class C3 {
                p = IG;
                m() { IG(); }
            }
        """)
        assert(d.count { it.code == 2693 } == 2)
    }

    @Test
    fun `negative control - class accessor bodies are not walked`() {
        diagnose("""
            interface IH {}
            class C4 {
                get g() { return IH(); }
                set s(v: any) { IH(); }
            }
        """) should {
            have(none { it.code == 2693 })
        }
    }

    @Test
    fun `negative control - class expression members are not walked`() {
        diagnose("""
            interface II {}
            let c = class { m() { II(); } };
        """) should {
            have(none { it.code == 2693 })
        }
    }

    @Test
    fun `class method parameter default is walked`() {
        diagnose("""
            interface IJ {}
            class C5 { m(x = IJ) {} }
        """) should {
            have(any { it.code == 2693 && it.message.contains("'IJ'") })
        }
    }

    // ── object literals ─────────────────────────────────────────────────────

    @Test
    fun `object literal method body and computed name are walked`() {
        val d = diagnose("""
            interface IK {}
            let o = {
                [IK]: 1,
                m() { return IK; },
            };
        """)
        assert(d.count { it.code == 2693 } == 2)
    }

    @Test
    fun `negative control - object literal accessor body is not walked`() {
        diagnose("""
            interface IL {}
            let o = { get g() { return IL(); } };
        """) should {
            have(none { it.code == 2693 })
        }
    }

    @Test
    fun `negative control - shorthand property is not walked`() {
        diagnose("""
            interface IM {}
            let o = { IM };
        """) should {
            have(none { it.code == 2693 })
        }
    }

    @Test
    fun `negative control - object literal method parameter default is not walked`() {
        diagnose("""
            interface IN {}
            let o = { m(x = IN) {} };
        """) should {
            have(none { it.code == 2693 })
        }
    }

    // ── namespace bodies ────────────────────────────────────────────────────

    @Test
    fun `namespace-local interface used as value fires TS2693`() {
        diagnose("""
            namespace M2 {
                interface J1 {}
                let x = new J1();
            }
        """) should {
            have(any { it.code == 2693 && it.message.contains("'J1'") })
        }
    }

    @Test
    fun `nested type-only namespace used as value fires TS2708`() {
        diagnose("""
            namespace M3 {
                namespace K1 { export interface Q {} }
                let y = K1;
            }
        """) should {
            have(any { it.code == 2708 && it.message.contains("'K1'") })
        }
    }

    @Test
    fun `outer type-only name propagates into namespace body`() {
        diagnose("""
            interface IO {}
            namespace M4 {
                let a = new IO();
            }
        """) should {
            have(any { it.code == 2693 && it.message.contains("'IO'") })
        }
    }

    // ── function expressions / arrows ───────────────────────────────────────

    @Test
    fun `function expression parameter default is walked`() {
        diagnose("""
            interface IP {}
            let f = function (x = IP) {};
        """) should {
            have(any { it.code == 2693 && it.message.contains("'IP'") })
        }
    }

    @Test
    fun `arrow expression body is walked`() {
        diagnose("""
            interface IQ {}
            let f = () => IQ;
        """) should {
            have(any { it.code == 2693 && it.message.contains("'IQ'") })
        }
    }

    // ── TS2585: forward-declarable lib types under restrictive lib ──────────

    @Test
    fun `Promise value use under es5 lib fires TS2585`() {
        diagnose(
            """
            const p = Promise.resolve(1);
            """,
            directives = "// @lib: es5",
        ) should {
            have(any { it.code == 2585 && it.message.contains("'Promise'") })
        }
    }
}
