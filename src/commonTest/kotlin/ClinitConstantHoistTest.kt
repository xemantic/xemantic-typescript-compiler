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
 * (JIT.1)(e) round 820 — pins for the hoist of the seven largest companion
 * constants out of `Checker.<clinit>` (**10,339 bytecodes**, over HotSpot's
 * 8,000-byte `HugeMethodLimit`, so never JIT-compiled) into top-level
 * `ckConst*` builder functions.
 *
 * **This target is unlike every other one in the arc, in two ways a reader must
 * know before judging these pins.**
 *
 *  * There is **no frequency argument and no performance claim**: a static
 *    initializer runs ONCE, at class load, so nothing here is priceable by any
 *    A/B in this repo. It lands for the (JIT.1)(f) ratchet;
 *  * the moved text has **no control flow at all** — it is seven collection
 *    literals — so the equivalence risk is not a lost `return` or a mis-ordered
 *    call, it is a **WRONG-SET SUBSTITUTION**. Five of the seven builders return
 *    `Set<String>`, so handing a property the wrong one TYPE-CHECKS, compiles
 *    clean, and changes only which names the checker believes exist. Nothing in
 *    the type system can catch it and no size gate can see it.
 *
 * So each pin below names a diagnostic that fires **because of exactly one of
 * those sets**, chosen so that substituting any other of the seven changes it:
 *
 * | constant | observable |
 * |---|---|
 * | `KNOWN_GLOBALS` | a lib global with no embedded-lib declaration resolves |
 * | `DOM_GLOBAL_NAMES` | TS2552's related TS2728 points at `lib.dom.d.ts` |
 * | `VALUE_ONLY_GLOBALS` | TS2749 for a value used as a type |
 * | `KEYWORD_IDENTIFIERS` | TS2503 is SUPPRESSED for a contextual keyword |
 * | `NODE_BUILTIN_MODULES` | TS2591's `@types/node` hint, not a bare TS2307 |
 * | `KNOWN_GENERIC_BUILTINS` | TS2314's arity and display name |
 * | `LIB_MIN_TARGET` | TS2550 for a later-lib member — and, separately, for a
 *   TYPED-ARRAY member, which comes from the `+ TYPED_ARRAY_NAMES.flatMap { … }`
 *   TAIL that stayed in the companion because it reads a `private` member a
 *   top-level function cannot see |
 *
 * Every pin carries its NEGATIVE control in the same test, so a pin cannot pass
 * by the checker having gone silent altogether.
 */
class ClinitConstantHoistTest {

    private val target = "// @strict: true\n// @target: es2020"

    // ── ckConstKnownGlobals ─────────────────────────────────────────────────

    @Test
    fun `known globals - a lib name with no embedded declaration still resolves`() {
        val d = diagnose(
            """
            const a = AggregateError;
            const b = ByteLengthQueuingStrategy;
            const c = definitelyNotAGlobalXyz;
            """,
            target,
        )
        // The seeded names resolve; the unseeded one is the control that proves
        // name resolution ran at all.
        assert(d.none { it.code == 2304 && "AggregateError" in it.message })
        assert(d.none { it.code == 2304 && "ByteLengthQueuingStrategy" in it.message })
        assert(d.any { it.code == 2304 && "definitelyNotAGlobalXyz" in it.message })
    }

    // ── ckConstDomGlobalNames ───────────────────────────────────────────────

    @Test
    fun `dom global names - the spelling suggestion carries a lib dom declared-here`() {
        val d = diagnose("const g = documnt;", target)
        val suggestion = d.filter { it.code == 2552 }
        assert(suggestion.size == 1)
        assert("Did you mean 'document'?" in suggestion[0].message)
        val related = suggestion[0].relatedInformation
        assert(related.size == 1)
        assert(related[0].code == 2728)
        assert(related[0].fileName == "lib.dom.d.ts")
    }

    @Test
    fun `negative control - a name resembling nothing gets no declared-here`() {
        val d = diagnose("const g = zqxwvutsrq;", target)
        assert(d.any { it.code == 2304 })
        assert(d.none { it.code == 2728 })
    }

    // ── ckConstValueOnlyGlobals ─────────────────────────────────────────────

    @Test
    fun `value-only globals - a value global used as a type is TS2749`() {
        val d = diagnose(
            """
            let v: parseInt;
            let w: Date;
            """,
            target,
        )
        val v = d.filter { it.code == 2749 }
        assert(v.size == 1)
        assert("'parseInt' refers to a value, but is being used as a type here." in v[0].message)
        // `Date` is a KNOWN_GLOBALS name that is NOT value-only — the control
        // that separates "the set was consulted" from "TS2749 fires for anything".
        assert(d.none { it.code == 2749 && "Date" in it.message })
    }

    // ── ckConstKeywordIdentifiers ───────────────────────────────────────────

    @Test
    fun `keyword identifiers - an import-equals onto a contextual keyword is not TS2503`() {
        val d = diagnose(
            """
            import ka = type;
            import kb = notdefinedxyz;
            export { ka, kb };
            """,
            target,
        )
        val ns = d.filter { it.code == 2503 }
        assert(ns.size == 1)
        assert("Cannot find namespace 'notdefinedxyz'." in ns[0].message)
    }

    // ── ckConstNodeBuiltinModules ───────────────────────────────────────────

    @Test
    fun `node builtin modules - an unresolved builtin gets the types-node hint`() {
        val d = diagnose(
            """
            import * as fs from "fs";
            import * as zz from "notabuiltinxyz";
            export { fs, zz };
            """,
            target,
        )
        val hint = d.filter { it.code == 2591 }
        assert(hint.size == 1)
        assert("npm i --save-dev @types/node" in hint[0].message)
        // The control: a module that is NOT a node builtin gets the plain TS2307,
        // so the hint is attributable to the set and not to "any missing module".
        val plain = d.filter { it.code == 2307 }
        assert(plain.size == 1)
        assert("Cannot find module 'notabuiltinxyz'" in plain[0].message)
    }

    // ── ckConstKnownGenericBuiltins ─────────────────────────────────────────

    @Test
    fun `known generic builtins - a bare Array names its arity and display form`() {
        val d = diagnose(
            """
            let a: Array;
            let b: NotAGenericBuiltinXyz;
            """,
            target,
        )
        val arity = d.filter { it.code == 2314 }
        assert(arity.size == 1)
        assert(arity[0].message == "Generic type 'Array<T>' requires 1 type argument(s).")
        assert(d.any { it.code == 2304 && "NotAGenericBuiltinXyz" in it.message })
    }

    // ── ckConstLibMinTargetBase, and the tail that did NOT move ─────────────

    @Test
    fun `lib min target - a later-lib array member is TS2550 below its target`() {
        val d = diagnose("const x = [1, 2].at(0);", target)
        val late = d.filter { it.code == 2550 }
        assert(late.size == 1)
        assert("Property 'at' does not exist on type 'number[]'." in late[0].message)
        assert("'es2022' or later" in late[0].message)
    }

    @Test
    fun `lib min target - the typed-array tail stayed in the companion and still fires`() {
        val d = diagnose("const x = new Int8Array(1).at(0);", target)
        val late = d.filter { it.code == 2550 }
        assert(late.size == 1)
        assert("Property 'at' does not exist on type 'Int8Array<ArrayBuffer>'." in late[0].message)
    }

    @Test
    fun `negative control - an array member available at the target is not TS2550`() {
        val d = diagnose("const x = [1, 2].indexOf(1);", target)
        assert(d.none { it.code == 2550 })
        assert(d.isEmpty())
    }
}
