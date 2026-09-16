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
 * so a NO-SUBSTITUTION TEMPLATE spelling and every LATE-BOUND key reach them. Round 938 then
 * gated TS2300/TS2687 out for a late-bound key, reading `dynamicNamesErrors`' PRISTINE
 * baseline, where `interface T0 { [c0]: number; 1: number }` gets NOTHING. **(LEGACY.0b) step
 * 20 retired that gate for an INTERFACE** under the tsgo-only directive — tsgo reports TS2300
 * at every member of the group — and kept it for a CLASS, whose tsgo answer is order-dependent
 * in a way no rule over the walker's group reproduces. The two pins below carry tsgo's
 * measured rows; `TsgoStep20Test` is the family's own class.
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
    fun `a late-bound duplicate in an interface is TS2300 at both members and TS2717`() {
        // RE-POINTED at (LEGACY.0b) step 20. This pin asserted PRISTINE's answer — no TS2300
        // for a duplicate by late-bound NAME — which was right for round 938 and is not the
        // compatibility target. tsgo 7.0.2 on this exact fixture, measured:
        //   t.ts(2,17): TS2300: Duplicate identifier 'p'.
        //   t.ts(2,28): TS2300: Duplicate identifier 'p'.
        //   t.ts(2,28): TS2717: … Property '[K]' must be of type 'number', …
        // The ORDINARY member supplies the TS2300 name and the computed member squiggles its
        // own three characters; TS2717 keeps naming the offending member as written.
        val d = check(k + "interface Dup { p: number; [K]: string }")
        val dup = d.filter { it.code == 2300 }
        assert(dup.size == 2)
        assert(dup.all { it.message == "Duplicate identifier 'p'." })
        assert(dup[0].character == 17)
        assert(dup[0].length == 1)
        assert(dup[1].character == 28)
        assert(dup[1].length == 3)
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
        // RE-POINTED at (LEGACY.0b) step 20 — tsgo 7.0.2 on this exact fixture:
        //   t.ts(3,15): TS2300: Duplicate identifier '[K]'.
        //   t.ts(3,28): TS2300: Duplicate identifier '[K]'.
        // With no ordinary member in the group the FIRST key's written spelling names it.
        val dup = d.filter { it.code == 2300 }
        assert(dup.size == 2)
        assert(dup.all { it.message == "Duplicate identifier '[K]'." })
        assert(dup[0].character == 15)
        assert(dup[1].character == 28)
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
    fun `a class duplicate flags BOTH declarations, and the two messages spell the key differently`() {
        // RE-POINTED at (LEGACY.0b) step 8. This pin used to assert ONE TS2300 "as pristine
        // tsc does", with a comment calling the second row "a tsgo divergence this compiler
        // does not chase" — written before the 2026-09-12 owner directive made TypeScript 7
        // the only compatibility target. Measured on tsgo 7.0.2, our output for this exact
        // source is now byte-identical to it, three rows including the spellings:
        //   c.ts(1,11): TS2300: Duplicate identifier '["p"]'.
        //   c.ts(1,30): TS2300: Duplicate identifier '["p"]'.
        //   c.ts(1,30): TS2717: … Property '[`p`]' must be of type 'number', …
        // The two codes deliberately disagree about the name: TS2300 carries the group's
        // FIRST key as written and TS2717 the offending member's own.
        val d = check("class C { [\"p\"]: number = 1; [`p`]: string = \"s\"; }")
        assert(d.count { it.code == 2300 } == 2)
        assert(d.filter { it.code == 2300 }.all { it.message == "Duplicate identifier '[\"p\"]'." })
        assert(d.count { it.code == 2717 } == 1)
        assert(d.first { it.code == 2717 }.message.contains("Property '[`p`]'"))
    }
}
