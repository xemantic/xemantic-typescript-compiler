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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * Round 934 — the EXCESS-PROPERTY direction of the computed-key family: an object
 * literal's computed key is checked against the target, and the key is named in the
 * message exactly as it is written.
 *
 * THE FALSE NEGATIVE THIS CLOSES, measured against `tsc 7.0.2` on a scratch project
 * before anything was changed: `{ p: 1, ["zz"]: 2 }` and ``{ p: 1, [`zz`]: 2 }``
 * against `interface Opt { p?: number }` are TS2353 in tsc and were SILENT here — a
 * program tsc rejects that this compiler accepted. So was `{ p: 1, 7: 2 }`, whose
 * key is not computed at all, which is the tell for where the omission really was.
 *
 * THE CAUSE IS THE OPPOSITE OF ROUND 933's, in the same >= 5-site family (B451).
 * There, one extraction site had been widened and another had not, so a member
 * resolved for one consumer and FP'd for the other IN ONE COMPILE. Here
 * `getTypeOfObjectLiteral` had named `["zz"]` / `` [`zz`] `` / `7` for a long time —
 * the literal's TYPE carried the member and the member was correctly judged excess —
 * and `checkExcessProperties` then looked for the AST node that declared it with a
 * `when` knowing only `Identifier` and `StringLiteralNode`, found nothing, and
 * emitted nothing. **A diagnostic can be computed in full and then dropped for want
 * of a position, and the failure is silent in the direction no gate looks.** The
 * lookup is now one shared predicate, so the type builder and the excess check
 * cannot disagree about what an object-literal element names.
 *
 * THE MESSAGE FORM IS MATCHED RATHER THAN RECORDED, because it is free: the key
 * node's span is in hand, so tsc's rendering — delimiters KEPT (`'["zz"]'`,
 * `` '[`zz`]' ``, `'"zz"'`, `''zz''`) and the squiggle over the whole written key —
 * is one substring. Every bare-identifier key renders and measures exactly as
 * before, which is why no corpus baseline moves.
 *
 * WHAT STAYS OUT, and it is ONE line rather than a list: a key whose name needs the
 * key's TYPE. `[K]` (a `const`), `[E.P]` (an enum member), `[S]` (a `unique symbol`)
 * and `[Symbol.iterator]` are all reported by tsc, which late-binds a
 * string-literal-TYPED key, and are all silent here — the same open item round 933
 * left in the supply direction. Per round 765 those FNs are NOT pinned; a known-open
 * gap is a countdown, not a guard. What IS pinned is the near miss they produce:
 * `computedSymbolKey` invents the placeholder name `"[E.P]"` for any dotted key, and
 * using it here manufactured a false positive on `const enum E { P = "p" }` +
 * `{ [E.P]: 1 }`, which tsc accepts. That row is a negative control below.
 */
class ComputedKeyExcessPropertyTest {

    private fun check(@Language("typescript") source: String): List<Diagnostic> =
        diagnose(source.trimIndent(), directives = "// @strict: true")

    private val opt = "interface Opt { p?: number }\n"

    private fun excess(d: List<Diagnostic>, key: String, target: String = "Opt") = d.any {
        it.code == 2353 &&
            it.message == "Object literal may only specify known properties, and " +
            "'$key' does not exist in type '$target'."
    }

    // ── the false negative, one row per spelling ───────────────────────────

    @Test
    fun `a backtick-quoted computed key is excess`() {
        val d = check(opt + "const a: Opt = { p: 1, [`zz`]: 2 };")
        assert(excess(d, "[`zz`]"))
    }

    @Test
    fun `a quote-spelled computed key is excess`() {
        val d = check(opt + """const a: Opt = { p: 1, ["zz"]: 2 };""")
        assert(excess(d, """["zz"]"""))
    }

    @Test
    fun `a numeric computed key is excess`() {
        val d = check(opt + "const a: Opt = { p: 1, [7]: 2 };")
        assert(excess(d, "[7]"))
    }

    @Test
    fun `a bare numeric key is excess`() {
        // Not computed at all - the same omission, and the tell for where it was.
        val d = check(opt + "const a: Opt = { p: 1, 7: 2 };")
        assert(excess(d, "7"))
    }

    @Test
    fun `a computed method name is excess`() {
        val d = check(opt + """const a: Opt = { ["mm"]() { return 1 } };""")
        assert(excess(d, """["mm"]"""))
    }

    // ── the message FORM: the key as written, delimiters kept ──────────────

    @Test
    fun `a bare identifier key is named and measured exactly as before - the control`() {
        val d = check(opt + "const a: Opt = { p: 1, zz: 2 };")
        assert(excess(d, "zz"))
        val one = d.single { it.code == 2353 }
        assert(one.length == 2)
    }

    @Test
    fun `a double-quoted key keeps its quotes`() {
        val d = check(opt + """const a: Opt = { p: 1, "zz": 2 };""")
        assert(excess(d, "\"zz\""))
    }

    @Test
    fun `a single-quoted key keeps its quotes`() {
        val d = check(opt + "const a: Opt = { p: 1, 'zz': 2 };")
        assert(excess(d, "'zz'"))
    }

    @Test
    fun `the squiggle covers the whole written key`() {
        // tsc squiggles `["zz"]` whole - `indexSignatures1`'s baseline puts five
        // tildes under `[sym]` and nine under `'someKey'`. The length is read from
        // the source span, never from the cooked name, which is 2 here.
        val d = check(opt + """const a: Opt = { p: 1, ["zz"]: 2 };""")
        val one = d.single { it.code == 2353 }
        assert(one.length == 6)
    }

    @Test
    fun `whitespace inside the brackets is part of the written key`() {
        // The span is found by scanning to the closing bracket, so `[ "zz" ]`
        // renders whole - which is what tsc prints.
        val d = check(opt + """const a: Opt = { p: 1, [ "zz" ]: 2 };""")
        assert(excess(d, """[ "zz" ]"""))
    }

    @Test
    fun `a bracket inside the key text does not truncate the span`() {
        // The closing-bracket scan starts PAST the inner literal, never at the `[`.
        val d = check(opt + """const a: Opt = { p: 1, ["a]b"]: 2 };""")
        assert(excess(d, """["a]b"]"""))
    }

    // ── every position the check is reached from ───────────────────────────

    @Test
    fun `a computed key is excess in ARGUMENT position`() {
        val d = check(opt + """
            declare function take(o: Opt): void;
            take({ p: 1, ["zz"]: 2 });
        """.trimIndent())
        assert(excess(d, """["zz"]"""))
    }

    @Test
    fun `a computed key is excess in RETURN position`() {
        val d = check(opt + """function r(): Opt { return { p: 1, ["zz"]: 2 }; }""")
        assert(excess(d, """["zz"]"""))
    }

    @Test
    fun `a nested literal under a computed key is descended into`() {
        // The nested recursion names its properties with the same predicate, so the
        // INNER excess is found through an outer computed key - tsc's answer too.
        val d = check(opt + """const h: { n?: Opt } = { ["n"]: { p: 1, zz: 2 } };""")
        assert(excess(d, "zz"))
    }

    // ── negative controls: what must stay silent ───────────────────────────

    @Test
    fun `negative control - a computed key naming an EXISTING member is not excess`() {
        val d = check(opt + """const a: Opt = { ["p"]: 1 };""")
        assert(d.none { it.code == 2353 })
    }

    @Test
    fun `negative control - a string index signature absorbs a computed key`() {
        val d = check(
            """
            interface Idx { p?: number; [k: string]: number | undefined }
            const a: Idx = { p: 1, ["zz"]: 2 };
            """
        )
        assert(d.none { it.code == 2353 })
    }

    @Test
    fun `negative control - a numeric index signature absorbs a numeric computed key`() {
        // THE FALSE POSITIVE THIS ROUND MEASURED AND GUARDED. Widening the lookup to
        // numeric keys exposed a target-side gap that could not matter before: a
        // NUMERIC index signature applies to a numerically-named key, and without
        // that guard `{ [7]: 2 }` against `{ [k: number]: T }` was reported - tsc is
        // silent for all four spellings.
        val d = check(
            """
            interface NumIdx { p?: number; [k: number]: number | undefined }
            const a: NumIdx = { p: 1, [7]: 2 };
            const b: NumIdx = { p: 1, 7: 2 };
            const c: NumIdx = { p: 1, "7": 2 };
            const e: NumIdx = { p: 1, ["7"]: 2 };
            """
        )
        assert(d.none { it.code == 2353 })
    }

    @Test
    fun `a numerically-LOOKING string key is still excess against a numeric index signature`() {
        // The guard is not a blanket: tsc's isNumericLiteralName rejects "1e3", so
        // the key is excess there. Asserted in the POSITIVE so a widening of the
        // absorption reddens.
        val d = check(
            """
            interface NumIdx { p?: number; [k: number]: number | undefined }
            const a: NumIdx = { p: 1, "1e3": 2 };
            """
        )
        assert(excess(d, "\"1e3\"", "NumIdx"))
    }

    @Test
    fun `negative control - a SUBSTITUTING template key stays silent`() {
        // It spells no fixed name, so it is not a key the target can be missing -
        // tsc is silent here too.
        val d = check(
            opt + "declare const x: string;\n" +
                "const a: Opt = { p: 1, [`zz${'$'}{x}`]: 2 };"
        )
        assert(d.none { it.code == 2353 })
    }

    @Test
    fun `negative control - a dotted computed key is not named by its placeholder`() {
        // THE DISCRIMINATING PIN FOR THE `computedSymbolKey` EXCLUSION. That helper
        // invents the name "[E.P]" so a well-known-symbol member can match
        // structurally (round 723); used as an excess-check name it reports a key
        // that tsc LATE-BINDS to the existing `p` and accepts. Measured on 7.0.2:
        // silent. A mid-round draft that reused the helper here emitted `'[E.P]'`.
        val d = check(
            """
            interface Opt { p?: number }
            const enum E { P = "p" }
            const a: Opt = { [E.P]: 1 };
            """
        )
        assert(d.none { it.code == 2353 })
    }
}
