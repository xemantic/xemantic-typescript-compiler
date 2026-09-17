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
 * Round (P18.128) — two class-parity gaps that point in OPPOSITE directions and turn out
 * to be three mechanisms, every shape measured against `tools/tsgo-7.0.2/lib/tsc` before
 * it was written.
 *
 * ## (CHK.137) — one modelling quirk, a false positive AND a false negative
 *
 * (CHK.73): **a class VALUE types as its INSTANCE type in this checker**. So the type of
 * `c` in `class Cls {}; const c = Cls` is the instance interface, which has no construct
 * signatures, and `checkSingleNewExpressionTypes`' 17.170 emitter read that as *not
 * constructable* — an ours-only TS2351 on legal code. The SAME artifact hid the mirror
 * defect: a class that DECLARES a constructor puts a construct signature on that instance
 * type, so `const i = new Cls(); new i()` for such a class was SILENT where both
 * references report TS2351.
 *
 * A fixture that varies only whether the class writes `constructor() {}` therefore swaps
 * which of the two you see, which is why they are closed in one place — by asking the
 * DECLARATION what the variable holds ([Checker.newCalleeVarHoldsClassValue]) instead of
 * asking the type, exactly as (KIR.LOWER.6)'s `variableType` had to for the same quirk.
 *
 * **The false positive was SCRIPT-FILE-ONLY and nothing said so.** The emitter reads
 * `globals[...]`, and INV.3(d) keeps a module file's locals out of `globals`, so a bare
 * `export {}` made it vanish. Every pin below that exercises it is deliberately a script.
 *
 * **A third mechanism the fix forced.** `abstract class Cls {}; const c = Cls; new c()`
 * used to be caught by that false positive — with the WRONG code (TS2351 at the callee
 * where tsgo says TS2511 at the whole `new`). Closing the false positive would have left
 * genuinely erroneous code SILENT, so `collectTypeofAbstractVars` learned the INFERRED
 * alias spelling beside the annotated one it already had.
 *
 * ## (CHK.138) — the other four `Function` property names, and an ambient gate
 *
 * tsgo has two TS2699 emitters and `checkStaticPrototypeMembers` is now both:
 * `checker.go:3172` for `prototype` (no `useDefineForClassFields` condition, plus a TS2300
 * companion for the method form) and `checker.go:4389` for `name` / `length` / `caller` /
 * `arguments` (silent whenever `useDefineForClassFields` is in force, i.e. at ES2022 and
 * above — including at an UNSET target, which is why every pin here names one).
 *
 * Both are gated `!nodeInAmbientContext`, which we did not have: `declare class C { static
 * prototype: number }` was an ours-only TS2699 and is now silent, as tsgo is.
 *
 * ## Receipt beyond these pins
 *
 * The conformance fixture `staticPropertyNameConflicts` (not in the active corpus — every
 * variation names a target `usesUnsupportedOption` skips) was reconstructed from its
 * pristine baseline and run: **20 of its 60 TS2699 rows, with ZERO false positives**. The
 * 40 missing are two PRE-EXISTING refusals this round did not widen — 30 are a computed
 * key that is a dotted path through an `as const` object literal, which `MemberNames`
 * refuses by name as (CHK.5) late binding, and 10 are the class-EXPRESSION reach gap
 * recorded below.
 *
 * `Diagnostic.character` is 1-BASED, so every column asserted below is tsgo's own column
 * verbatim — all fifteen were read off `tools/tsgo-7.0.2/lib/tsc` and all fifteen match.
 * (`start` is the 0-based offset beside it; a sibling pin in another class asserts a
 * 0-based column and is measuring a `.js` fixture through a different helper.)
 *
 * Every non-control pin here was proven RED against the pre-change binary.
 */
class TsgoStep23Test {

    // ================================================================ (CHK.137) TS2351

    /**
     * The queue item's own fixture. Ours-only TS2351 before this round; tsgo 7.0.2 reports
     * nothing at all, so the assertion is on the WHOLE list rather than on the code.
     */
    @Test
    fun `a const holding a class is constructable`() {
        val d = diagnose(
            """
            class Cls {}
            const c = Cls;
            new c();
            """,
        )
        assert(d.isEmpty())
    }

    /**
     * The class having MEMBERS changes nothing — it was the absence of a declared
     * `constructor` that decided the old emitter, which is the artifact, not a rule.
     */
    @Test
    fun `a const holding a class with members is constructable`() {
        val d = diagnose(
            """
            class Cls { m(): number { return 1 } }
            const c = Cls;
            new c();
            """,
        )
        assert(d.isEmpty())
    }

    /** A `let` is the same shape; nothing here depends on the binding being immutable. */
    @Test
    fun `a let holding a class is constructable`() {
        val d = diagnose(
            """
            class Cls {}
            let c = Cls;
            new c();
            """,
        )
        assert(d.isEmpty())
    }

    /** A GENERIC class, with explicit type arguments at the construction. */
    @Test
    fun `a const holding a generic class is constructable`() {
        val d = diagnose(
            """
            class Cls<T> { v?: T }
            const c = Cls;
            new c<number>();
            """,
        )
        assert(d.isEmpty())
    }

    /**
     * Two hops — the alias-chain arm of [Checker.newCalleeVarHoldsClassValue]. A single-hop
     * helper reads as working on every one-hop fixture, so this is the pin that tests the
     * hop at all.
     */
    @Test
    fun `an alias chain to a class is constructable`() {
        val d = diagnose(
            """
            class Cls {}
            const a = Cls;
            const c = a;
            new c();
            """,
        )
        assert(d.isEmpty())
    }

    /**
     * A `let` REASSIGNED between two classes. The helper answers from the DECLARATION, and
     * that is correct rather than lucky here: both stores are classes, so every reachable
     * value is constructable.
     */
    @Test
    fun `a let reassigned between two classes is constructable`() {
        val d = diagnose(
            """
            class A {}
            class B {}
            let c = A;
            c = B;
            new c();
            """,
        )
        assert(d.isEmpty())
    }

    /**
     * THE MIRROR HALF: a class that declares a constructor put a construct signature on its
     * INSTANCE type, so this was silent. Both references report TS2351 at the callee.
     */
    @Test
    fun `newing an instance of a class that declares a constructor is TS2351`() {
        val d = diagnose(
            """
            class Cls { constructor() {} }
            const i = new Cls();
            new i();
            """,
        )
        assert(d.size == 1)
        val r = d[0]
        assert(r.code == 2351)
        assert(r.message == "This expression is not constructable.")
        assert(r.messageChain == listOf("  Type 'Cls' has no construct signatures."))
        assert(r.line == 3)
        assert(r.character == 5)
        assert(r.length == 1)
    }

    /**
     * The abstract alias. Before this round it was a TS2351 at the CALLEE — the right
     * position with the wrong code; tsgo reports TS2511 over the whole `new` expression,
     * which is where the direct `new AbstractCls()` spelling has always reported.
     */
    @Test
    fun `newing an inferred alias of an abstract class is TS2511`() {
        val d = diagnose(
            """
            abstract class Cls {}
            const c = Cls;
            new c();
            """,
        )
        assert(d.size == 1)
        val r = d[0]
        assert(r.code == 2511)
        assert(r.message == "Cannot create an instance of an abstract class.")
        assert(r.line == 3)
        assert(r.character == 1)
        assert(r.length == 7)
    }

    // ---------------------------------------------------------------- (CHK.137) controls

    /**
     * NEGATIVE CONTROL — a variable genuinely holding an INSTANCE must stay TS2351. This is
     * the shape the 17.170 emitter exists for, and it is green on both arms by design: what
     * the round changes is which variables reach it, never that it fires.
     */
    @Test
    fun `control - newing an instance is still TS2351`() {
        val d = diagnose(
            """
            class Cls {}
            const i = new Cls();
            new i();
            """,
        )
        assert(d.size == 1)
        val r = d[0]
        assert(r.code == 2351)
        assert(r.messageChain == listOf("  Type 'Cls' has no construct signatures."))
        assert(r.line == 3)
        assert(r.character == 5)
        assert(r.length == 1)
    }

    /**
     * NEGATIVE CONTROL — an ANNOTATED instance. [Checker.newCalleeVarHoldsClassValue]
     * answers null for any annotated declaration, deliberately: the program wrote a type
     * and a syntactic guess must not override it.
     */
    @Test
    fun `control - an annotated instance variable is still TS2351`() {
        val d = diagnose(
            """
            class Cls {}
            declare const i: Cls;
            new i();
            """,
        )
        assert(d.size == 1)
        assert(d[0].code == 2351)
    }

    /**
     * NEGATIVE CONTROL — an interface carrying a REAL construct signature is not a (CHK.73)
     * casualty and the new gate must not reach it. It is excluded by the `Class` flag test
     * on the callee TYPE, not by the declaration walk.
     */
    @Test
    fun `control - a variable typed by a construct-signature interface is constructable`() {
        val d = diagnose(
            """
            class Cls {}
            interface Ctor { new (): Cls }
            declare const c: Ctor;
            new c();
            """,
        )
        assert(d.none { it.code == 2351 })
    }

    /**
     * NEGATIVE CONTROL — the direct `new AbstractCls()` spelling, which has always been
     * TS2511 and is what the widened collector must not disturb.
     */
    @Test
    fun `control - newing an abstract class directly is still TS2511`() {
        val d = diagnose(
            """
            abstract class Cls {}
            new Cls();
            """,
        )
        assert(d.size == 1)
        assert(d[0].code == 2511)
    }

    /**
     * The MODULE spelling of the queue item's fixture, which was already clean because
     * INV.3(d) keeps a module's locals out of `globals` — recorded so the script-only axis
     * is visible rather than rediscovered.
     */
    @Test
    fun `control - the module spelling was already clean`() {
        val d = diagnose(
            """
            export {};
            class Cls {}
            const c = Cls;
            new c();
            """,
        )
        assert(d.isEmpty())
    }

    // ================================================================ (CHK.138) TS2699

    /** Every fixture in this half must name a target below ES2022: at an UNSET target
     *  `useDefineForClassFields` is in force and tsgo reports NOTHING, so a pin written
     *  the usual way would be vacuous. */
    private val es2020 = "// @strict: true\n// @target: es2020"

    @Test
    fun `a static name property conflicts with Function name`() {
        val d = diagnose("""class C { static name = "x" }""", es2020)
        assert(d.size == 1)
        val r = d[0]
        assert(r.code == 2699)
        assert(r.message ==
            "Static property 'name' conflicts with built-in property 'Function.name' of constructor function 'C'.")
        assert(r.line == 1)
        assert(r.character == 18)
        assert(r.length == 4)
    }

    @Test
    fun `a static length property conflicts with Function length`() {
        val d = diagnose("""class C { static length = 1 }""", es2020)
        assert(d.size == 1)
        val r = d[0]
        assert(r.code == 2699)
        assert(r.message ==
            "Static property 'length' conflicts with built-in property 'Function.length' of constructor function 'C'.")
        assert(r.character == 18)
        assert(r.length == 6)
    }

    @Test
    fun `a static caller property conflicts with Function caller`() {
        val d = diagnose("""class C { static caller = 1 }""", es2020)
        assert(d.size == 1)
        assert(d[0].code == 2699)
        assert(d[0].message ==
            "Static property 'caller' conflicts with built-in property 'Function.caller' of constructor function 'C'.")
        assert(d[0].length == 6)
    }

    @Test
    fun `a static arguments property conflicts with Function arguments`() {
        val d = diagnose("""class C { static arguments = 1 }""", es2020)
        assert(d.size == 1)
        assert(d[0].code == 2699)
        assert(d[0].message ==
            "Static property 'arguments' conflicts with built-in property 'Function.arguments' of constructor function 'C'.")
        assert(d[0].length == 9)
    }

    /**
     * All four in one class, which is what says the walk does not stop at the first hit.
     */
    @Test
    fun `all four names in one class are four rows`() {
        val d = diagnose(
            """class C { static name = "x"; static length = 1; static caller = 2; static arguments = 3 }""",
            es2020,
        )
        assert(d.size == 4)
        assert(d.map { it.code } == listOf(2699, 2699, 2699, 2699))
        assert(d.map { it.character } == listOf(18, 37, 56, 75))
    }

    /**
     * A static METHOD conflicts too — and unlike `prototype` it gets NO TS2300 companion,
     * because that companion comes from the class's implicit static `prototype` in the
     * TYPE and no such implicit member exists for these four.
     */
    @Test
    fun `a static name method conflicts and has no TS2300 companion`() {
        val d = diagnose("""class C { static name() { return 1 } }""", es2020)
        assert(d.size == 1)
        assert(d[0].code == 2699)
        assert(d[0].character == 18)
        assert(d[0].length == 4)
    }

    @Test
    fun `a static name getter conflicts`() {
        val d = diagnose("""class C { static get name() { return "x" } }""", es2020)
        assert(d.size == 1)
        assert(d[0].code == 2699)
        assert(d[0].character == 22)
        assert(d[0].length == 4)
    }

    @Test
    fun `a static name setter conflicts`() {
        val d = diagnose("""class C { static set name(v: string) {} }""", es2020)
        assert(d.size == 1)
        assert(d[0].code == 2699)
        assert(d[0].character == 22)
        assert(d[0].length == 4)
    }

    /**
     * A class in a NAMESPACE body — the one nesting the walker reaches. The class-expression
     * and function-body spellings are the recorded reach gap and are NOT pinned as working.
     */
    @Test
    fun `a static name in a namespace-exported class conflicts`() {
        val d = diagnose("""namespace N { export class C { static name = "x" } }""", es2020)
        assert(d.size == 1)
        assert(d[0].code == 2699)
        assert(d[0].message ==
            "Static property 'name' conflicts with built-in property 'Function.name' of constructor function 'C'.")
    }

    // ---------------------------------------------- (CHK.138) the name-SPELLING widening

    /**
     * A STRING-LITERAL member name. The squiggle is the written token including its quotes,
     * which is why the span comes from [MemberNames.writtenMemberNameSpan] and not from the
     * cooked name's length.
     */
    @Test
    fun `a string-literal static prototype conflicts`() {
        val d = diagnose("""class C { static "prototype" = 1 }""", es2020)
        assert(d.size == 1)
        assert(d[0].code == 2699)
        assert(d[0].message ==
            "Static property 'prototype' conflicts with built-in property 'Function.prototype' of constructor function 'C'.")
        assert(d[0].character == 18)
        assert(d[0].length == 11)
    }

    /** A COMPUTED member name that late-binds to `prototype`. */
    @Test
    fun `a computed static prototype conflicts`() {
        val d = diagnose(
            """
            const k = "prototype";
            class C { static [k] = 1 }
            """,
            es2020,
        )
        assert(d.size == 1)
        assert(d[0].code == 2699)
        assert(d[0].line == 2)
        assert(d[0].character == 18)
        assert(d[0].length == 3)
    }

    /**
     * The computed METHOD form, whose TS2300 companion carries the **WRITTEN** name:
     * tsgo prints `Duplicate identifier '[k]'`, not `'prototype'`. A cooked-name message
     * here would be a plausible wrong answer no span assertion could catch.
     */
    @Test
    fun `a computed static prototype method reports the written name in TS2300`() {
        val d = diagnose(
            """
            const k = "prototype";
            class C { static [k]() { return 1 } }
            """,
            es2020,
        ).sortedBy { it.code }
        assert(d.size == 2)
        assert(d[0].code == 2300)
        assert(d[0].message == "Duplicate identifier '[k]'.")
        assert(d[0].length == 3)
        assert(d[1].code == 2699)
        assert(d[1].message ==
            "Static property 'prototype' conflicts with built-in property 'Function.prototype' of constructor function 'C'.")
    }

    /** A `const enum` member as the computed key — the spelling the conformance fixture
     *  `staticPropertyNameConflicts` is built out of. */
    @Test
    fun `a const-enum computed static name conflicts`() {
        val d = diagnose(
            """
            const enum E { N = "name" }
            class C { static [E.N] = 1 }
            """,
            es2020,
        )
        assert(d.size == 1)
        assert(d[0].code == 2699)
        assert(d[0].message ==
            "Static property 'name' conflicts with built-in property 'Function.name' of constructor function 'C'.")
        assert(d[0].line == 2)
        assert(d[0].character == 18)
        assert(d[0].length == 5)
    }

    // ------------------------------------------------------- (CHK.138) the ambient gate

    /**
     * The ours-only FALSE POSITIVE this round closes: an ambient class declares no runtime
     * member, so neither tsgo emitter runs. Both references are silent.
     */
    @Test
    fun `an ambient class with a static prototype is silent`() {
        val d = diagnose("""declare class C { static prototype: number }""", es2020)
        assert(d.isEmpty())
    }

    /** …and the whole body of a `declare namespace` is ambient, one level down. */
    @Test
    fun `a class inside a declare namespace with a static prototype is silent`() {
        val d = diagnose(
            """declare namespace N { class C { static prototype: number } }""",
            es2020,
        )
        assert(d.isEmpty())
    }

    /** An ambient class with one of the OTHER four names is silent for the same reason. */
    @Test
    fun `an ambient class with a static name is silent`() {
        val d = diagnose("""declare class C { static name: string }""", es2020)
        assert(d.isEmpty())
    }

    // ---------------------------------------------------------------- (CHK.138) controls

    /**
     * NEGATIVE CONTROL — the target gate. `useDefineForClassFields` is in force at ES2022,
     * so the four-name family is silent; this is the pin that makes the gate failable in
     * the direction that matters.
     */
    @Test
    fun `control - a static name is silent at ES2022`() {
        val d = diagnose("""class C { static name = "x" }""", "// @strict: true\n// @target: es2022")
        assert(d.isEmpty())
    }

    /** NEGATIVE CONTROL — and silent at an UNSET target, which defaults above ES2022. */
    @Test
    fun `control - a static name is silent at an unset target`() {
        val d = diagnose("""class C { static name = "x" }""")
        assert(d.isEmpty())
    }

    /** NEGATIVE CONTROL — an explicit `useDefineForClassFields` beats the target default. */
    @Test
    fun `control - a static name is silent under useDefineForClassFields`() {
        val d = diagnose(
            """class C { static name = "x" }""",
            "// @strict: true\n// @target: es2020\n// @useDefineForClassFields: true",
        )
        assert(d.isEmpty())
    }

    /**
     * NEGATIVE CONTROL AND THE SPLIT — `prototype` has NO `useDefineForClassFields` gate, so
     * it still fires at ES2022 where the other four do not. Collapsing tsgo's two emitters
     * into one rule fails exactly here.
     */
    @Test
    fun `control - a static prototype still conflicts at ES2022`() {
        val d = diagnose("""class C { static prototype = 1 }""", "// @strict: true\n// @target: es2022")
        assert(d.size == 1)
        assert(d[0].code == 2699)
        assert(d[0].length == 9)
    }

    /** NEGATIVE CONTROL — a static member of any other name. */
    @Test
    fun `control - an unrelated static member name is silent`() {
        val d = diagnose("""class C { static zzz = 1 }""", es2020)
        assert(d.isEmpty())
    }

    /** NEGATIVE CONTROL — an INSTANCE member of a conflicting name is fine; only the
     *  constructor function carries `Function`'s own properties. */
    @Test
    fun `control - an instance member named name is silent`() {
        val d = diagnose("""class C { name = "x" }""", es2020)
        assert(d.isEmpty())
    }

    /** NEGATIVE CONTROL — a PRIVATE static name conflicts with nothing; `#name` is not
     *  `name`, and both references are silent. */
    @Test
    fun `control - a private static name is silent`() {
        val d = diagnose("""class C { static #name = 1 }""", es2020)
        assert(d.none { it.code == 2699 })
    }

    /** NEGATIVE CONTROL — a NUMERIC static member name. */
    @Test
    fun `control - a numeric static member name is silent`() {
        val d = diagnose("""class C { static 1 = 1 }""", es2020)
        assert(d.none { it.code == 2699 })
    }

    /** NEGATIVE CONTROL — a static BLOCK has no name node at all. */
    @Test
    fun `control - a static block is silent`() {
        val d = diagnose("""class C { static { const x = 1; } }""", es2020)
        assert(d.none { it.code == 2699 })
    }

    // ------------------------------------------------------------------------- residues

    /**
     * RESIDUE, measured and recorded rather than pinned as working: this walker reaches a
     * top-level class and a class in a namespace body and nothing else, so a class
     * EXPRESSION is missed by BOTH halves. tsgo 7.0.2 reports TS2699 here, naming the class
     * `'C'` after the variable.
     *
     * It is a PRE-EXISTING reach gap of the `prototype` rule that the new family inherits,
     * not something this round introduced — `const C = class { static prototype = 1 }` was
     * equally silent before it. Widening the reach is a different change with its own blast
     * radius, so the pin says what we do today and names what tsgo does.
     */
    @Test
    fun `residue - a class expression is out of the walker's reach`() {
        val d = diagnose("""const C = class { static name = "x" };""", es2020)
        assert(d.none { it.code == 2699 })
        val p = diagnose("""const C = class { static prototype = 1 };""", es2020)
        assert(p.none { it.code == 2699 })
    }

    /**
     * RESIDUE, same reach gap one nesting over: a class declared in a FUNCTION body. tsgo
     * reports TS2699 for both spellings.
     */
    @Test
    fun `residue - a class in a function body is out of the walker's reach`() {
        val d = diagnose(
            """function f() { class C { static name = "x" } return C; }""",
            es2020,
        )
        assert(d.none { it.code == 2699 })
    }
}
