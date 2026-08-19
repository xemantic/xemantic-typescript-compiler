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
 * Round 938 — (CHK.5)(b): A DUPLICATE MEMBER DECLARATION. Two separable defects, both
 * measured on 32 scratch projects against `tools/tsgo-7.0.2/lib/tsc --noEmit -p .` and,
 * where the two references disagree, against the PRISTINE tsc baselines this repo pins.
 *
 * **(i) THE SURVIVING TYPE.** `interface I { p: number; p: string }` is an error program in
 * both compilers, but the type it leaves behind is observable independently of the
 * diagnostic — and this member map was LAST-WINS for every duplicate spelling, so `i.p` read
 * `string` where tsc reads `number`. The same in a class, in a type literal, across two
 * MERGED `interface I` blocks, for a numeric member name, and — the row round 937 recorded
 * and this round closes — for a LATE-BOUND computed key, where round 937 had just turned a
 * silent program into one spurious TS2322 of the wrong code. tsc reaches first-wins in the
 * binder (`setValueDeclaration` replaces an existing `valueDeclaration` only across an
 * ambient / assignment-declaration / module-kind boundary), and pristine tsc's own TS2717
 * text is the statement of the rule: `classWithDuplicateIdentifier`'s baseline says
 * "Property 'c' must be of type 'number', but here has type 'string'".
 *
 * **(ii) THE DIAGNOSTIC, AND WHERE THE TWO REFERENCES PART.** The duplicate SCANS are AST
 * scans beside the member-BUILDING sites round 937 levelled, and they carried an older,
 * narrower copy of the same `when` — B451's law one site further on. They now ask one namer,
 * so a NO-SUBSTITUTION TEMPLATE spelling and every LATE-BOUND key reach them. But TS2300 and
 * TS2687 are the BINDER's duplicate checks and a late-bound key never reaches those:
 * `dynamicNamesErrors`' PRISTINE baseline is the measurement — `interface T0 { [c0]: number;
 * 1: number }` with `const c0 = "1"` is a duplicate by name and gets NOTHING, while its
 * late-bound sibling `T3` gets TS2717 alone. tsc 7.0.2 emits TS2300 for both; that is a tsgo
 * divergence and this compiler follows pristine tsc (CLAUDE.md's standing directive).
 *
 * NOT pinned, deliberately (round 765 — a known-open gap is a countdown, not a guard), each
 * measured this round with tsc's answer and recorded in (CHK.5)(b): a MERGED-interface
 * TS2717 (`interface I { p: number }` + `interface I { p: string }` — tsc reports, we do
 * not, and the scans are per-declaration by construction); an INTERFACE property-vs-METHOD
 * TS2300 pair; TS1117 for a late-bound object-literal key; the TS2717 for a required-vs-
 * OPTIONAL redeclaration (`number` vs `number | undefined`); and `C.p` reading the INSTANCE
 * member's type when a static and an instance member share a name, which is the unfinished
 * [staticMembers] dual-population and not this round's rule.
 */
class DuplicateMemberDeclarationTest {

    private fun check(@Language("typescript") source: String): List<Diagnostic> =
        diagnose(source.trimIndent(), directives = "// @strict: true")

    private val k = "const K = \"p\";\n"

    /** The one TS2322 names [expected] as the type the duplicated member RESOLVED to. */
    private fun resolvesTo(expected: String, d: List<Diagnostic>) {
        val ts2322 = d.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].message == "Type '$expected' is not assignable to type '0'.")
    }

    // ── (i) the surviving type: FIRST-WINS ───────────────────────────────────

    @Test
    fun `an interface duplicate property keeps the FIRST declaration's type`() {
        resolvesTo("number", check(
            "interface I { p: number; p: string }\ndeclare const i: I;\nconst probe: 0 = i.p;"
        ))
    }

    @Test
    fun `a class duplicate property keeps the FIRST declaration's type`() {
        resolvesTo("number", check(
            "class C { p: number = 1; p: string = \"s\"; }\ndeclare const c: C;\nconst probe: 0 = c.p;"
        ))
    }

    @Test
    fun `a type literal duplicate property keeps the FIRST declaration's type`() {
        resolvesTo("number", check(
            "type T = { p: number; p: string };\ndeclare const t: T;\nconst probe: 0 = t.p;"
        ))
    }

    @Test
    fun `a duplicate across two MERGED interface blocks keeps the FIRST type`() {
        resolvesTo("number", check(
            "interface I { p: number }\ninterface I { p: string }\ndeclare const i: I;\nconst probe: 0 = i.p;"
        ))
    }

    @Test
    fun `a THREE-way duplicate keeps the first of the three`() {
        resolvesTo("number", check(
            "interface I { p: number; p: string; p: boolean }\ndeclare const i: I;\nconst probe: 0 = i.p;"
        ))
    }

    @Test
    fun `a NUMERIC member name duplicate keeps the FIRST type`() {
        resolvesTo("number", check(
            "interface I { 1: number; 1: string }\ndeclare const i: I;\nconst probe: 0 = i[1];"
        ))
    }

    @Test
    fun `a LATE-BOUND computed key duplicating a plain member keeps the FIRST type`() {
        // Round 937's own recorded divergence: with `[K]` bound the program moved from 0
        // diagnostics to one spurious TS2322 naming `string`. The map, not the key, was wrong.
        resolvesTo("number", check(
            k + "interface Dup { p: number; [K]: string }\ndeclare const d: Dup;\nconst probe: 0 = d.p;"
        ))
    }

    @Test
    fun `two LITERAL computed spellings of one key keep the FIRST type`() {
        resolvesTo("number", check(
            "interface Dup { [\"p\"]: number; [`p`]: string }\ndeclare const d: Dup;\nconst probe: 0 = d.p;"
        ))
    }

    // ── (i) the negative controls the first-wins guard must not break ─────────

    @Test
    fun `negative control - a derived class property still OVERRIDES the inherited one`() {
        // The guard's first clause. [members] is PRE-POPULATED with the base types' members
        // before the own-member loop runs, so a guard testing `members[name] != null` would
        // silently delete every override in the program — this reads `string` (the override)
        // and not `string | number` (the base).
        resolvesTo("string", check(
            "class B { p: string | number = 1; }\nclass D extends B { p: string = \"x\"; }\n" +
                "declare const d: D;\nconst probe: 0 = d.p;"
        ))
    }

    @Test
    fun `negative control - a static and an instance member of one name do not collide`() {
        // The guard's third clause. Both live in one map until the [staticMembers]
        // dual-population is consumed, so first-wins ACROSS that boundary would make the
        // instance read answer the static's `string`.
        resolvesTo("number", check(
            "class C { static p: string = \"s\"; p: number = 1; }\ndeclare const c: C;\nconst probe: 0 = c.p;"
        ))
    }

    @Test
    fun `negative control - a property beside a METHOD of the same name is unchanged`() {
        // The guard's second clause: property-vs-property only. tsc reads `number` here too.
        resolvesTo("number", check(
            "class C { p: number = 1; p(): void {} }\ndeclare const c: C;\nconst probe: 0 = c.p;"
        ))
    }

    @Test
    fun `negative control - an interface method OVERLOAD set is not a duplicate`() {
        val d = check("interface I { m(a: number): void; m(a: string): void }\ndeclare const i: I;\ni.m(1); i.m(\"s\");")
        assert(d.none { it.code == 2300 })
        assert(d.none { it.code == 2717 })
        assert(d.none { it.code == 2345 })
    }

    @Test
    fun `negative control - a get set accessor pair reads the getter's type`() {
        resolvesTo("number", check(
            "class C { get p(): number { return 1 } set p(v: number) {} }\n" +
                "declare const c: C;\nconst probe: 0 = c.p;"
        ))
    }

    @Test
    fun `negative control - a named member beside a string index signature is unchanged`() {
        resolvesTo("number", check(
            "interface I { [k: string]: number; p: number }\ndeclare const i: I;\nconst probe: 0 = i.p;"
        ))
    }

    @Test
    fun `negative control - two IDENTICAL merged interface blocks stay silent`() {
        val d = check("interface I { p: number }\ninterface I { p: number }\ndeclare const i: I;\nconst probe: 0 = i.p;")
        assert(d.none { it.code == 2300 })
        assert(d.none { it.code == 2717 })
        resolvesTo("number", d)
    }

    // ── (ii) the duplicate DIAGNOSTIC ────────────────────────────────────────

    @Test
    fun `a NO-SUBSTITUTION TEMPLATE spelling duplicating a quoted one is a duplicate`() {
        // The spelling round 933 identified as the one fixed name nothing could see; the
        // duplicate scans were the last site still refusing it.
        val d = check("interface Dup { [\"p\"]: number; [`p`]: string }")
        assert(d.count { it.code == 2300 } == 2)
        assert(d.count { it.code == 2717 } == 1)
    }

    @Test
    fun `a late-bound duplicate is TS2717 and deliberately NOT TS2300`() {
        // `dynamicNamesErrors`' pristine baseline: a duplicate by late-bound NAME is invisible
        // to the binder's duplicate check and reaches only the re-declaration check.
        val d = check(k + "interface Dup { p: number; [K]: string }")
        assert(d.none { it.code == 2300 })
        assert(d.count { it.code == 2717 } == 1)
        assert(d.first { it.code == 2717 }.message ==
            "Subsequent property declarations must have the same type.  Property '[K]' must be of type 'number', but here has type 'string'.")
    }

    @Test
    fun `a late-bound duplicate in a CLASS is TS2717 and not TS2300`() {
        val d = check(k + "class C { p: number = 1; [K]: string = \"s\"; }")
        assert(d.none { it.code == 2300 })
        assert(d.count { it.code == 2717 } == 1)
    }

    @Test
    fun `two late-bound keys naming one member emit exactly ONE TS2717`() {
        // The B357 walker reaches the same verdict at the same span for this sub-population,
        // so it RETRACTS before it emits — without that this line is reported twice.
        val d = check(
            "const K = \"p\";\nconst K2 = \"p\";\ninterface I { [K]: number; [K2]: string }"
        )
        assert(d.count { it.code == 2717 } == 1)
        assert(d.none { it.code == 2300 })
    }

    @Test
    fun `negative control - a WELL-KNOWN symbol key twice is not a duplicate here`() {
        // Deliberately refused: [getMemberName]'s `[Symbol.X]` arm is not asked by the
        // duplicate scans, so the eight tsc profiles' 57 `[Symbol.iterator](` members are
        // exactly as invisible to them as they were before this round.
        val d = check("interface I { [Symbol.iterator](): void; [Symbol.iterator](): void }")
        assert(d.none { it.code == 2300 })
        assert(d.none { it.code == 2717 })
    }

    @Test
    fun `negative control - a genuinely DYNAMIC key twice is not a duplicate`() {
        // `declare const L: string` names no member in either compiler; tsc gives the
        // interface a string INDEX SIGNATURE instead, which is (CHK.5)(e) and not this round.
        val d = check("declare const L: string;\ninterface I { [L]: number; [L]: string }")
        assert(d.none { it.code == 2300 })
        assert(d.none { it.code == 2717 })
    }

    @Test
    fun `a class duplicate flags only the SECOND declaration, as pristine tsc does`() {
        // `classWithDuplicateIdentifier` / `duplicateIdentifierComputedName`: pristine tsc
        // reports ONE TS2300 for a class property-vs-property duplicate (tsc 7.0.2 reports
        // two — a tsgo divergence this compiler does not chase).
        val d = check("class C { [\"p\"]: number = 1; [`p`]: string = \"s\"; }")
        assert(d.count { it.code == 2300 } == 1)
        assert(d.count { it.code == 2717 } == 1)
    }
}
