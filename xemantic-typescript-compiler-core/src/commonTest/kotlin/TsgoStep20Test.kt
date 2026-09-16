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
import org.intellij.lang.annotations.Language

/**
 * (LEGACY.0b) step 20 — the last three F2 duplicate-identifier rows, three mechanisms.
 *
 * Every expectation here was READ from `tools/tsgo-7.0.2/lib/tsc -p <scratch>` before any
 * code was written; tsgo 7.0.2 is the only compatibility target (CLAUDE.md, owner directive
 * 2026-09-12), so where PRISTINE `typescript@6.0.3` disagrees — and for all three of these
 * it does — tsgo is the answer implemented.
 *
 * **M1 — `dynamicNamesErrors`: a LATE-BOUND computed member name.** The pending reason named
 * tsgo's `lateBindMember` (checker.go:15962) as the missing emitter. It survived contact only
 * as one HALF of the mechanism: the rows this fixture needs come from tsgo's OTHER duplicate
 * emitter, `checkObjectTypeForDuplicateDeclarations` + `reportMergeSymbolError`, which is why
 * the group's NAME is a written spelling (`'[c0]'`) in one shape and the resolved member name
 * (`'1'`) in the other. Measured over twenty shapes, the rule that reproduces every one for an
 * INTERFACE or a TYPE LITERAL is: report at every member of the duplicate group, named by the
 * FIRST BINDER-VISIBLE member's written spelling if the group has one, else by the first
 * member's. A CLASS is measurably NOT that rule — tsgo's class answer is order-dependent (see
 * the residue pins below) — so the class walker keeps round 938's conservative gate.
 *
 * **M2 — `methodSignatureHandledDeclarationKindForSymbol`: a cross-declaration MERGE.** The
 * reason's hypothesis survived exactly: method-vs-property is a binder merge CONFLICT, so
 * tsgo reports TS2300 at EVERY declaration and neither TS2717 nor TS2687. The KIND difference
 * is load-bearing (property-beside-property across two declarations keeps TS2717 alone) and
 * the ORDER is not (property-then-method reports identically), which the reason did not say.
 *
 * **M3 — `parameterPropertyInConstructor2`: a constructor PARAMETER PROPERTY.** Both halves of
 * the reason are true of tsgo, and the narrow row it names is closed by reporting at the
 * OVERLOAD's parameter as well as the implementation's. The general half — parameter
 * properties joining the class member table, so `class { p: number; constructor(public p:
 * string) {} }` is TS2300 at both plus TS2403 — is measured, stated and NOT landed: it needs
 * the TS2403-vs-TS2717 split beside it.
 */
class TsgoStep20Test {

    private fun check(@Language("typescript") source: String): List<Diagnostic> =
        diagnose(source, directives = "// @strict: true")

    private fun ts2300(d: List<Diagnostic>): List<Diagnostic> =
        d.filter { it.code == 2300 }.sortedWith(compareBy({ it.line }, { it.character }))

    private fun row(
        d: Diagnostic,
        name: String,
        line: Int,
        character: Int,
        length: Int,
    ) {
        assert(d.message == "Duplicate identifier '$name'.")
        assert(d.code == 2300)
        assert(d.category == DiagnosticCategory.Error)
        assert(d.fileName == "t.ts")
        assert(d.line == line)
        assert(d.character == character)
        assert(d.length == length)
    }

    // ── M1 — a late-bound computed key is a duplicate in an INTERFACE ────────────

    /**
     * tsgo: `t.ts(3,5)` and `t.ts(4,5)`, both `Duplicate identifier '1'.`, squiggles of 4 and
     * 1 — `dynamicNamesErrors`' T0 group. The ORDINARY member supplies the name even though
     * the computed one is written first.
     */
    @Test
    fun `a late-bound key duplicating an ordinary member reports at both under the ordinary name`() {
        val rows = ts2300(check(
            """
            const c0 = "1";
            interface T0 {
                [c0]: number;
                1: number;
            }
            """
        ))
        assert(rows.size == 2)
        row(rows[0], "1", line = 3, character = 5, length = 4)
        row(rows[1], "1", line = 4, character = 5, length = 1)
    }

    /**
     * tsgo: `t.ts(4,5)` and `t.ts(5,5)`, both `Duplicate identifier '[c0]'.` with 4-character
     * squiggles, beside the TS2717 this compiler already had — `dynamicNamesErrors`' T3 group.
     * Two late-bound keys naming one member take the FIRST one's written spelling.
     */
    @Test
    fun `two late-bound keys naming one member report at both under the first written spelling`() {
        val d = check(
            """
            const c0 = "1";
            const c1 = 1;
            interface T3 {
                [c0]: number;
                [c1]: string;
            }
            """
        )
        val rows = ts2300(d)
        assert(rows.size == 2)
        row(rows[0], "[c0]", line = 4, character = 5, length = 4)
        row(rows[1], "[c0]", line = 5, character = 5, length = 4)
        assert(d.count { it.code == 2717 } == 1)
    }

    /** tsgo names the group `'p'` at both members although the computed key is written first. */
    @Test
    fun `an ordinary member supplies the group name from any position`() {
        val rows = ts2300(check(
            """
            const K = "p";
            interface D2 {
                [K]: number;
                p: string;
            }
            """
        ))
        assert(rows.size == 2)
        row(rows[0], "p", line = 3, character = 5, length = 3)
        row(rows[1], "p", line = 4, character = 5, length = 1)
    }

    /**
     * A LITERAL computed key is binder-visible, so it can be the namer — and its SQUIGGLE is
     * its own five characters while the name is the ordinary member's one. tsgo prints exactly
     * that; before this round the computed member's span was `name.length`, i.e. 1.
     */
    @Test
    fun `a computed member squiggles its own written spelling and not the group name`() {
        val rows = ts2300(check(
            """
            interface L1 {
                a: number;
                ["a"]: string;
            }
            """
        ))
        assert(rows.size == 2)
        row(rows[0], "a", line = 2, character = 5, length = 1)
        row(rows[1], "a", line = 3, character = 5, length = 5)
    }

    /** tsgo: `Duplicate identifier '["ab"]'.` at both — the first BINDER-VISIBLE member wins. */
    @Test
    fun `a literal computed key supplies the group name ahead of a late-bound one`() {
        val rows = ts2300(check(
            """
            const k1 = "ab";
            interface D5 {
                ["ab"]: number;
                [k1]: string;
            }
            """
        ))
        assert(rows.size == 2)
        row(rows[0], "[\"ab\"]", line = 3, character = 5, length = 6)
        row(rows[1], "[\"ab\"]", line = 4, character = 5, length = 4)
    }

    /** Control: two late-bound keys that resolve to DIFFERENT names are not a group at all. */
    @Test
    fun `control - two distinct late-bound keys are not duplicates`() {
        val d = check(
            """
            const ka = "aa";
            const kb = "bb";
            interface C1 {
                [ka]: number;
                [kb]: string;
            }
            """
        )
        assert(d.none { it.code == 2300 })
        assert(d.none { it.code == 2717 })
    }

    /**
     * Control: an all-early group is untouched — `duplicateStringNamedProperty1`'s rule, where
     * the FIRST member's written spelling carries its quotes into every row of the group.
     */
    @Test
    fun `control - an all-early group still takes the first member's written spelling`() {
        val rows = ts2300(check(
            """
            interface S1 {
                "artist": string;
                artist: string;
            }
            """
        ))
        assert(rows.size == 2)
        row(rows[0], "\"artist\"", line = 2, character = 5, length = 8)
        row(rows[1], "\"artist\"", line = 3, character = 5, length = 6)
    }

    /**
     * Control, and the reason the CLASS walker keeps round 938's gate: with an ordinary member
     * written BEFORE a late-bound one, tsgo reports TS2717 alone in a class — measured on four
     * spellings of the ordinary member (initialized, un-initialized, `declare`d, `!`-asserted),
     * all four silent — while the same pair in an interface is TS2300 at both. Un-gating the
     * class walker makes this fixture a two-row false positive.
     */
    @Test
    fun `control - a class with an ordinary member before a late-bound one is TS2717 alone`() {
        val d = check(
            """
            const K = "p";
            class C2 {
                p: number = 1;
                [K]: string = "x";
            }
            """
        )
        assert(d.none { it.code == 2300 })
        assert(d.count { it.code == 2717 } == 1)
    }

    /**
     * residue - tsgo reports `Duplicate identifier 'p'.` at BOTH members here, because in a
     * class its answer is ORDER-dependent: late-then-early reports and early-then-late does
     * not. No rule over this walker's group reproduces that split without a model of which
     * symbol tsgo's merge clones, so the class side stays silent and this records it.
     */
    @Test
    fun `residue - a class with a late-bound key before an ordinary member stays silent`() {
        val d = check(
            """
            const K = "p";
            class C3 {
                [K]: string = "x";
                p: number = 1;
            }
            """
        )
        assert(d.none { it.code == 2300 })
    }

    // ── M2 — a method in one declaration beside a property in another ────────────

    /**
     * `methodSignatureHandledDeclarationKindForSymbol`'s whole baseline: tsgo reports TS2300 at
     * `bold(): string` and at `bold: string`, and NO TS2717. This compiler emitted the TS2717
     * and neither TS2300 before this round.
     */
    @Test
    fun `a method and a property across merged interfaces are TS2300 at both`() {
        val d = check(
            """
            interface Foo {
                bold(): string;
            }

            interface Foo {
                bold: string;
            }
            """
        )
        val rows = ts2300(d)
        assert(rows.size == 2)
        row(rows[0], "bold", line = 2, character = 5, length = 4)
        row(rows[1], "bold", line = 6, character = 5, length = 4)
        assert(d.none { it.code == 2717 })
        assert(d.none { it.code == 2687 })
    }

    /** The ORDER is not load-bearing — tsgo reports the property-first spelling identically. */
    @Test
    fun `a property and a method across merged interfaces are TS2300 at both`() {
        val d = check(
            """
            interface G2 { b: string; }
            interface G2 { b(): string; }
            """
        )
        val rows = ts2300(d)
        assert(rows.size == 2)
        row(rows[0], "b", line = 1, character = 16, length = 1)
        row(rows[1], "b", line = 2, character = 16, length = 1)
        assert(d.none { it.code == 2717 })
    }

    /** Every declaration of the name reports, methods included — tsgo gives three rows here. */
    @Test
    fun `three declarations of a conflicting name all report`() {
        val rows = ts2300(check(
            """
            interface K4 { d(): string; }
            interface K4 { d(): string; }
            interface K4 { d: string; }
            """
        ))
        assert(rows.size == 3)
        row(rows[0], "d", line = 1, character = 16, length = 1)
        row(rows[1], "d", line = 2, character = 16, length = 1)
        row(rows[2], "d", line = 3, character = 16, length = 1)
    }

    /** A function-TYPED property is still a property, so the conflict is the same one. */
    @Test
    fun `a function-typed property beside a method is TS2300 at both`() {
        val rows = ts2300(check(
            """
            interface G7 { b: () => string; }
            interface G7 { b(): string; }
            """
        ))
        assert(rows.size == 2)
        row(rows[0], "b", line = 1, character = 16, length = 1)
        row(rows[1], "b", line = 2, character = 16, length = 1)
    }

    /**
     * The merged TS2687 is suppressed for a conflicting name: there is no merged symbol whose
     * modifiers could disagree. tsgo gives TS2300 x2 and nothing else; before this round this
     * fixture was TS2687 x2 plus a TS2717.
     */
    @Test
    fun `an optional property beside a method reports TS2300 and not TS2687`() {
        val d = check(
            """
            interface G6 { b(): string; }
            interface G6 { b?: string; }
            """
        )
        assert(d.count { it.code == 2300 } == 2)
        assert(d.none { it.code == 2687 })
        assert(d.none { it.code == 2717 })
    }

    /** Control: same KIND across two declarations is NOT this rule — tsgo keeps TS2717 alone. */
    @Test
    fun `control - two properties across merged interfaces are not TS2300`() {
        val d = check(
            """
            interface G3 { b: string; }
            interface G3 { b: number; }
            """
        )
        assert(d.none { it.code == 2300 })
    }

    /** Control: two methods across two declarations are an OVERLOAD SET and are silent. */
    @Test
    fun `control - two methods across merged interfaces are not TS2300`() {
        val d = check(
            """
            interface G4 { b(): string; }
            interface G4 { b(): number; }
            """
        )
        assert(d.none { it.code == 2300 })
        assert(d.none { it.code == 2717 })
    }

    /** Control: a modifier difference between two PROPERTIES still reaches TS2687. */
    @Test
    fun `control - an optional property across merged interfaces still reports TS2687`() {
        val d = check(
            """
            interface G8 { b: string; }
            interface G8 { b?: string; }
            """
        )
        assert(d.count { it.code == 2687 } == 2)
        assert(d.none { it.code == 2300 })
    }

    // ── M3 — a parameter property declared by two constructor signatures ─────────

    /**
     * `parameterPropertyInConstructor2`'s missing row is the OVERLOAD's own parameter at
     * (3,24); the implementation's at (4,24) was already emitted. tsgo reports both.
     */
    @Test
    fun `a parameter property declared by an overload and its implementation reports at both`() {
        val rows = ts2300(check(
            """
            namespace mod {
              class Customers {
                constructor(public names: string);
                constructor(public names: string, public ages: number) {
                }
              }
            }
            """
        ))
        assert(rows.size == 2)
        row(rows[0], "names", line = 3, character = 24, length = 5)
        row(rows[1], "names", line = 4, character = 24, length = 5)
    }

    /**
     * THREE signatures give THREE rows and not four: the implementation's parameter is reached
     * once per overload, and de-duplicating it is what the old shape lacked — it emitted the
     * implementation's row twice, which tsgo's own diagnostic de-duplication hid from its
     * baselines.
     */
    @Test
    fun `three constructor signatures declaring one parameter property give three rows`() {
        val rows = ts2300(check(
            """
            class H2 {
              constructor(public n: string);
              constructor(public n: number, x?: string);
              constructor(public n: any, public a?: any) {}
            }
            """
        ))
        assert(rows.size == 3)
        row(rows[0], "n", line = 2, character = 22, length = 1)
        row(rows[1], "n", line = 3, character = 22, length = 1)
        row(rows[2], "n", line = 4, character = 22, length = 1)
    }

    /** Control: one declaration of the field is not a duplicate, whatever the overload says. */
    @Test
    fun `control - an overload parameter property whose implementation partner is plain is silent`() {
        val d = check(
            """
            class H3 {
              constructor(public n: string);
              constructor(n: any, a?: any) {}
            }
            """
        )
        assert(d.none { it.code == 2300 })
    }

    /** Control: two parameter properties in ONE signature are a duplicate PARAMETER name, and
     *  that emitter is untouched — exactly two rows, not four. */
    @Test
    fun `control - two parameter properties in one constructor give exactly two rows`() {
        val rows = ts2300(check(
            """
            class J5 {
              constructor(public a: number, public a: string) {}
            }
            """
        ))
        assert(rows.size == 2)
        row(rows[0], "a", line = 2, character = 22, length = 1)
        row(rows[1], "a", line = 2, character = 40, length = 1)
    }

    /**
     * residue - tsgo groups a parameter property with an ordinary member of the same class and
     * reports `Duplicate identifier 'p'.` at both plus a TS2403; this compiler is silent. The
     * fix is parameter properties in `checkDuplicateClassMembers`' table, which needs the
     * TS2403-vs-TS2717 split beside it.
     */
    @Test
    fun `residue - a parameter property beside a same-named field stays silent`() {
        val d = check(
            """
            class H5 {
              p: number = 1;
              constructor(public p: string) {}
            }
            """
        )
        assert(d.none { it.code == 2300 })
    }
}
