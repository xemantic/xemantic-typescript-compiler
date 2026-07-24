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

/**
 * (M0.4, round 651): pins for the checkAbstractMemberContext (TS1253 —
 * abstract property in a non-abstract class; TS1244 — abstract method /
 * accessor in a non-abstract class; TS7008 — abstract property without a type
 * annotation, implicit `any`) spine migration. The legacy pass reached class
 * declarations/expressions via a mutually-recursive statement + expression
 * walk that threaded a downward `inAmbient` flag, and called the emission leaf
 * processClassForAbstractContext per reached class. TS1253 and TS1244 are
 * UNIQUE to this pass (the only `to 1253`/`to 1244` mapping in Checker.kt), so
 * their counts are a clean reach signal; the abstract-property TS7008 is
 * disjoint from the general class-property TS7008 (which requires `static`)
 * and the ambient-class TS7008 (which requires an ambient class), so an
 * `abstract prop;` in a non-ambient class produces exactly one TS7008 from
 * this pass.
 *
 * Frozen REACH quirks pinned in BOTH directions: statement bodies
 * (fn/namespace/block/if-then/loop-body/switch-clause/try/catch) are reached
 * but loop/switch/if HEADS and for-INITIALIZERS are not; class property
 * INITIALIZERS are NOT reached (the walk recurses member BODIES only). The
 * differences from round 650's checkConstructorParamInInitializers are pinned
 * explicitly: arrow/fn-expr Block bodies are the FULL statement walk (a class
 * DECLARATION directly in an arrow body IS reached), the switch SUBJECT and
 * the ternary CONDITION ARE walked, and there is NO declare-skip — declare
 * classes/modules are walked, threading the monotone `inAmbient` flag which
 * suppresses TS1253/TS1244/TS7008 inside a `declare namespace`. All
 * expectations verified against the legacy pass FIRST.
 */
class M04AbstractContextSpineMigrationTest {

    private fun ts1253(ds: List<Diagnostic>) = ds.count { it.code == 1253 }
    private fun ts1244(ds: List<Diagnostic>) = ds.count { it.code == 1244 }
    private fun ts7008(ds: List<Diagnostic>) = ds.count { it.code == 7008 }

    // ── Core emissions ────────────────────────────────────────────────────

    @Test
    fun `abstract property in non-abstract class draws TS1253`() {
        val ds = diagnose(
            """
            class C {
              abstract prop: number;
            }
            """
        )
        assertEquals(1, ts1253(ds))
        assertEquals(0, ts7008(ds)) // has a type annotation
        val d = ds.single { it.code == 1253 }
        assertEquals("abstract".length, d.length)
    }

    @Test
    fun `abstract method in non-abstract class draws TS1244`() {
        val ds = diagnose(
            """
            class C {
              abstract m(): void;
            }
            """
        )
        assertEquals(1, ts1244(ds))
        assertEquals(0, ts1253(ds))
    }

    @Test
    fun `abstract accessor in non-abstract class draws TS1244`() {
        val ds = diagnose(
            """
            class C {
              abstract get g(): number;
            }
            """
        )
        assertEquals(1, ts1244(ds))
    }

    @Test
    fun `abstract untyped property draws BOTH TS1253 and TS7008`() {
        val ds = diagnose(
            """
            class C {
              abstract prop;
            }
            """
        )
        assertEquals(1, ts1253(ds))
        assertEquals(1, ts7008(ds))
    }

    @Test
    fun `TS7008 fires for an untyped abstract property even in an abstract class`() {
        // TS7008 is gated on !isAmbient (NOT on class abstractness), so an
        // abstract class suppresses TS1253 but NOT the implicit-any TS7008.
        val ds = diagnose(
            """
            abstract class C {
              abstract prop;
            }
            """
        )
        assertEquals(0, ts1253(ds))
        assertEquals(1, ts7008(ds))
    }

    @Test
    fun `negative control - abstract members in an abstract class draw nothing`() {
        val ds = diagnose(
            """
            abstract class C {
              abstract prop: number;
              abstract m(): void;
            }
            """
        )
        assertEquals(0, ts1253(ds))
        assertEquals(0, ts1244(ds))
        assertEquals(0, ts7008(ds))
    }

    @Test
    fun `two abstract members in one non-abstract class are each processed`() {
        val ds = diagnose(
            """
            class C {
              abstract a: number;
              abstract b(): void;
            }
            """
        )
        assertEquals(1, ts1253(ds))
        assertEquals(1, ts1244(ds))
    }

    @Test
    fun `negative control - non-abstract members draw nothing`() {
        val ds = diagnose(
            """
            class C {
              m(): void {}
              prop: number = 0;
            }
            """
        )
        assertEquals(0, ts1253(ds))
        assertEquals(0, ts1244(ds))
    }

    @Test
    fun `negative control - interface members are never abstract-checked`() {
        val ds = diagnose(
            """
            interface I {
              m(): void;
              prop: number;
            }
            """
        )
        assertEquals(0, ts1253(ds))
        assertEquals(0, ts1244(ds))
    }

    // ── Ambient gating (the downward inAmbient flag) ───────────────────────

    @Test
    fun `declare namespace suppresses TS1253 for an inner non-declare class`() {
        val ds = diagnose(
            """
            declare namespace N {
              class C {
                abstract prop: number;
              }
            }
            """
        )
        assertEquals(0, ts1253(ds))
    }

    @Test
    fun `non-declare namespace does NOT suppress - TS1253 fires`() {
        val ds = diagnose(
            """
            namespace N {
              class C {
                abstract prop: number;
              }
            }
            """
        )
        assertEquals(1, ts1253(ds))
    }

    @Test
    fun `inAmbient is monotone - a declare namespace ancestor suppresses a deeply nested class`() {
        val ds = diagnose(
            """
            declare namespace N {
              namespace M {
                class C {
                  abstract prop: number;
                }
              }
            }
            """
        )
        assertEquals(0, ts1253(ds))
    }

    @Test
    fun `declare namespace suppresses both TS1253 and TS1244`() {
        // The abstract-context codes (unique to this pass) are suppressed
        // inside a declare namespace. (The general ambient-class implicit-any
        // TS7008 is a SEPARATE pass and fires independently, so it is not
        // asserted here.)
        val ds = diagnose(
            """
            declare namespace N {
              class C {
                abstract prop: number;
                abstract m(): void;
              }
            }
            """
        )
        assertEquals(0, ts1253(ds))
        assertEquals(0, ts1244(ds))
    }

    // ── Reach — nested classes REACHED (emission fires) ────────────────────

    @Test
    fun `class nested in a method body is reached`() {
        val ds = diagnose(
            """
            class Outer {
              m() {
                class C {
                  abstract prop: number;
                }
              }
            }
            """
        )
        assertEquals(1, ts1253(ds))
    }

    @Test
    fun `class nested in a function body is reached`() {
        val ds = diagnose(
            """
            function f() {
              class C {
                abstract prop: number;
              }
            }
            """
        )
        assertEquals(1, ts1253(ds))
    }

    @Test
    fun `class in an if-then block is reached`() {
        val ds = diagnose(
            """
            if (true) {
              class C {
                abstract prop: number;
              }
            }
            """
        )
        assertEquals(1, ts1253(ds))
    }

    @Test
    fun `class in a loop body is reached`() {
        val ds = diagnose(
            """
            while (false) {
              class C {
                abstract prop: number;
              }
            }
            """
        )
        assertEquals(1, ts1253(ds))
    }

    @Test
    fun `class in a switch clause is reached`() {
        val ds = diagnose(
            """
            switch (0) {
              case 0: {
                class C {
                  abstract prop: number;
                }
              }
            }
            """
        )
        assertEquals(1, ts1253(ds))
    }

    @Test
    fun `class in a try block is reached`() {
        val ds = diagnose(
            """
            try {
              class C {
                abstract prop: number;
              }
            } catch (e) {}
            """
        )
        assertEquals(1, ts1253(ds))
    }

    @Test
    fun `deeply nested - non-declare namespace then function body is reached`() {
        val ds = diagnose(
            """
            namespace N {
              function f() {
                class C {
                  abstract prop: number;
                }
              }
            }
            """
        )
        assertEquals(1, ts1253(ds))
    }

    // ── Reach — the Ab-vs-CP differences (full arrow body, subject, cond) ──

    @Test
    fun `class DECLARATION directly in an arrow block body IS reached`() {
        // KEY difference from round 650 (checkConstructorParamInInitializers):
        // CP restricts arrow bodies to Expression/Return/Variable statements
        // so a class declaration there is NOT reached; Ab walks the FULL
        // statement list, so it IS.
        val ds = diagnose(
            """
            const f = () => {
              class C {
                abstract prop: number;
              }
            };
            """
        )
        assertEquals(1, ts1253(ds))
    }

    @Test
    fun `class declaration in a function-expression body IS reached`() {
        val ds = diagnose(
            """
            const f = function () {
              class C {
                abstract prop: number;
              }
            };
            """
        )
        assertEquals(1, ts1253(ds))
    }

    @Test
    fun `class expression in the switch SUBJECT IS reached`() {
        // Ab walks the switch subject expression; CP does not.
        val ds = diagnose(
            """
            switch (class Z { abstract m(): void; }) {
            }
            """
        )
        assertEquals(1, ts1244(ds))
    }

    @Test
    fun `class expression in a ternary CONDITION IS reached`() {
        // Ab walks the ternary condition; CP does not.
        val ds = diagnose(
            """
            const r = (class Z { abstract m(): void; }) ? 1 : 2;
            """
        )
        assertEquals(1, ts1244(ds))
    }

    // ── Reach — class expressions in ordinary expression positions ─────────

    @Test
    fun `class expression in a variable initializer is reached`() {
        val ds = diagnose(
            """
            const X = class { abstract m(): void; };
            """
        )
        assertEquals(1, ts1244(ds))
    }

    @Test
    fun `class expression in an array literal is reached`() {
        val ds = diagnose(
            """
            [class Z { abstract m(): void; }];
            """
        )
        assertEquals(1, ts1244(ds))
    }

    // ── Reach — NOT reached (no emission) ──────────────────────────────────

    @Test
    fun `class expression in a class property INITIALIZER is NOT reached`() {
        // The walk recurses member BODIES only, never property initializers,
        // so the inner class expression's abstract method is never checked.
        val ds = diagnose(
            """
            class Outer {
              p = class { abstract m(): void; };
            }
            """
        )
        assertEquals(0, ts1244(ds))
    }

    @Test
    fun `class expression in an if CONDITION is NOT reached`() {
        val ds = diagnose(
            """
            if (class Z { abstract m(): void; }) {
            }
            """
        )
        assertEquals(0, ts1244(ds))
    }

    @Test
    fun `class expression in a for-head initializer is NOT reached`() {
        val ds = diagnose(
            """
            for (let z = class { abstract m(): void; }; false; ) {
            }
            """
        )
        assertEquals(0, ts1244(ds))
    }

    // ── File-kind gate ─────────────────────────────────────────────────────

    @Test
    fun `dts file is skipped entirely`() {
        // A non-abstract class with an abstract method draws TS1244 in a .ts
        // (see the `abstract method` pin above); in a .d.ts the whole pass is
        // skipped, so the unique codes vanish.
        val ds = diagnose(
            """
            class C {
              abstract m(): void;
            }
            """,
            fileName = "t.d.ts",
        )
        assertEquals(0, ts1253(ds))
        assertEquals(0, ts1244(ds))
    }
}
