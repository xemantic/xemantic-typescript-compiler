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
 * (LEGACY.1)(j1) — TS1250/TS1251 (`Function declarations are not allowed inside blocks in strict
 * mode when targeting 'ES5'.`) and TS18028 (`Private identifiers are only available when targeting
 * ECMAScript 2015 and higher.`) are diagnostics TypeScript 7 never emits, at ANY target.
 *
 * **Every expected value is tsgo 7.0.2's**, measured 2026-09-15 over four scratch projects
 * (`target` ∈ {es5, es2015} × `strict` ∈ {unset, true}), each holding a script file, a
 * `"use strict"`-prologue script and an `export {}` module with a block-scoped function in an `if`
 * body, a loop body and a class method, plus a class with `#p`, `#m()`, `static #s`, `get #g` and a
 * class-expression `#q` — through the LSP's `textDocument/diagnostic` pull, because tsgo's CLI stops
 * at `TS5108 Option 'target=ES5' has been removed` (the value position of the tsconfig). In every
 * cell tsgo's checker rows at es5 are byte-identical to es2015's: unused-declaration hints only,
 * **0 × TS1250/1251/1252, 0 × TS18028**. Ours printed eight rows per file at es5 before this round.
 *
 * Why tsgo cannot emit them: `binder.go:1379` `getStrictModeBlockScopeFunctionDeclarationMessage`
 * carries all three TS1250-family references in `internal/` and has NO caller — tsc 6's
 * `checkStrictModeFunctionDeclaration` (`languageVersion < ES2015` → report) was not ported; and
 * `Private_identifiers_are_only_available_when_targeting_ECMAScript_2015_and_higher` is referenced
 * by nothing but the generated table. Zero rows of either code in tsgo's own baselines (7 and 6 in
 * tsc 6's). Both emitters (`checkBlockScopedFunctionDeclarations` and `checkPrivateIdentifiersTarget`,
 * with their single-caller helpers) are deleted whole, together with the tsc-6 countdown class
 * `PrivateIdentifierTargetGateTest` and `DownlevelGateDefaultTargetTest`'s TS1250 pin.
 *
 * The `@target: es5` directive is where a written es5 target is still expressible on the harness
 * path (`usesUnsupportedOption` drops every es3/es5 directive from the corpus — 0 active subtests
 * at either target, 12 carry an EMBEDDED nested tsconfig the harness never applies — so these pins
 * are the only gate). (P18.133) At the shipped `"7.0"` default the harness prints tsgo's own
 * TS5108 row for the target itself, file-less because the harness has no tsconfig position for it,
 * and NOTHING silences it; [DOWNLEVEL_ES5]'s `@typeScriptVersion: 6.0` is what keeps it out of the
 * exact-list pins here, and the three pins below own the row itself at both versions.
 * Pins named `control -` were GREEN on the pre-change binary too and state the tsgo-shaped answer;
 * every other pin was RED on it (stash-ablated 2026-09-15).
 */
class TargetGatedDiagnosticsRemovedTest {

    private val es5 = DOWNLEVEL_ES5
    private val es5NoStrict = "// @target: es5\n// @typeScriptVersion: 6.0\n// @ignoreDeprecations: 6.0"
    private val es3 = "// @strict: true\n// @target: es3\n// @ignoreDeprecations: 6.0"
    private val es2015 = "// @strict: true\n// @target: es2015"

    private val cond = "declare const cond: boolean;"

    /** The whole matrix body, one file: every shape the two deleted emitters reached. */
    private val everyShape = """
        $cond
        if (cond) { function blockFn() { } }
        for (;;) { function loopFn() { } break; }
        { function bareBlockFn() { } }
        class WithPrivate {
            #p = 1;
            #m() { return this.#p; }
            static #s: string;
            get #g() { return 2; }
            inMethod() { if (cond) { function classFn() { } } }
        }
        const ce = class { #q = 3; };
        function outer() { class Nested { #n = 4; } }
    """

    // ── TS1250 / TS1251: the block-scoped function family is gone ────────────────────────────

    @Test
    fun `an es5 target no longer reports TS1250 for a function declared in an if body`() {
        diagnose("$cond if (cond) { function blockFn() { } }", es5) should {
            have(none { it.code == 1250 })
        }
    }

    @Test
    fun `an es5 target no longer reports TS1250 for a function declared in a loop body`() {
        diagnose("for (;;) { function loopFn() { } break; }", es5) should {
            have(none { it.code == 1250 })
        }
    }

    /** The shape `DownlevelGateDefaultTargetTest` used to pin the OTHER way. */
    @Test
    fun `an es5 target no longer reports TS1250 for a function declared in a bare block`() {
        diagnose("{ function bareBlockFn() { } }", es5) should {
            have(none { it.code == 1250 })
        }
    }

    /** tsc 6's class-body wording (TS1251) shared the deleted emitter. */
    @Test
    fun `an es5 target no longer reports TS1251 for a function declared in a block inside a class method`() {
        diagnose("$cond class C { m() { if (cond) { function classFn() { } } } }", es5) should {
            have(none { it.code == 1251 })
            have(none { it.code == 1250 })
        }
    }

    /** tsgo: a `"use strict"` prologue changes nothing at es5 — no row. */
    @Test
    fun `an es5 target with a use strict prologue no longer reports TS1250`() {
        diagnose("\"use strict\";\n$cond if (cond) { function strictBlockFn() { } }", es5) should {
            have(none { it.code == 1250 })
        }
    }

    /** tsgo: a module file (tsc 6's TS1252 wording) reports nothing either. */
    @Test
    fun `an es5 target in a module file no longer reports the block-scoped function family`() {
        diagnose("export {};\n$cond if (cond) { function modBlockFn() { } }", es5) should {
            have(none { it.code == 1250 })
            have(none { it.code == 1252 })
        }
    }

    /** The old emitter fired regardless of `strict` — tsgo is silent regardless of `strict`. */
    @Test
    fun `an es5 target without strict no longer reports TS1250`() {
        diagnose("$cond if (cond) { function blockFn() { } }", es5NoStrict) should {
            have(none { it.code == 1250 })
        }
    }

    // ── TS18028: the private-identifier target gate is gone ──────────────────────────────────

    @Test
    fun `an es5 target no longer reports TS18028 for a private property`() {
        diagnose("class C { #p = 1; }", es5) should {
            have(none { it.code == 18028 })
        }
    }

    @Test
    fun `an es5 target no longer reports TS18028 for a private method static member and accessor`() {
        diagnose("class C { #m() { } static #s: string; get #g() { return 1; } }", es5) should {
            have(none { it.code == 18028 })
        }
    }

    @Test
    fun `an es5 target no longer reports TS18028 for a class-expression private member`() {
        diagnose("const ce = class { #q = 3; };", es5) should {
            have(none { it.code == 18028 })
        }
    }

    @Test
    fun `an es5 target no longer reports TS18028 for a class nested in a function body`() {
        diagnose("function outer() { class Nested { #n = 4; } }", es5) should {
            have(none { it.code == 18028 })
        }
    }

    /** The old gate read the RAW target and `es3` opened it too. */
    @Test
    fun `an es3 target no longer reports either code`() {
        diagnose(everyShape, es3) should {
            have(none { it.code == 1250 })
            have(none { it.code == 1251 })
            have(none { it.code == 18028 })
        }
    }

    // ── the whole answer, byte-exact ─────────────────────────────────────────────────────────

    /** tsgo at a written es5: zero error rows over every shape (its LSP shows unused hints only). */
    @Test
    fun `an es5 target reports nothing at all over every shape the deleted emitters reached`() {
        val d = diagnose(everyShape, es5)
        assert(d.isEmpty())
    }

    /**
     * (P18.133) At the shipped default the target's own option row is the ONLY thing left —
     * before (LEGACY.1) it sat beside four checker rows — and it is tsgo's, byte for byte.
     * Measured on `tools/tsgo-7.0.2/lib/tsc` over `{ "compilerOptions": { "target": "ES5" } }`:
     *
     *     tsconfig.json(1,34): error TS5108: Option 'target=ES5' has been removed. Please remove it from your configuration.
     */
    @Test
    fun `an es5 target reports exactly tsgo's removed-option row and nothing else`() {
        val d = diagnose(everyShape, "// @strict: true\n// @target: es5")
        val codes = d.map { it.code }
        assert(codes == listOf(5108))
        val message = d.single().message
        assert(message == "Option 'target=ES5' has been removed. Please remove it from your configuration.")
    }

    /**
     * (P18.133) `ignoreDeprecations` cannot reach that row: TypeScript 7 parses the option and
     * consults it NOWHERE (`tsoptions/parsinghelpers.go:287` is its only write; no reader), so
     * our removed branch returns above `isDeprecationSuppressed`. Measured — tsgo answers the
     * SAME row for `{ "target": "ES5", "ignoreDeprecations": "5.0" }`, and for `"6.0"`/`"7.0"`
     * and an invalid value alike.
     */
    @Test
    fun `ignoreDeprecations does not silence the removed-option row at the shipped default`() {
        val d = diagnose(everyShape, "// @strict: true\n// @target: es5\n// @ignoreDeprecations: 6.0")
        assert(d.map { it.code } == listOf(5108))
    }

    /**
     * Control, and the reason [DOWNLEVEL_ES5] carries a version directive: an EXPLICIT
     * `@typeScriptVersion` below `7.0` still selects the TypeScript 6 deprecation ladder,
     * where `ignoreDeprecations` silences as it always did. Without this the new default is
     * pinned only by the pins above, and an ablation restoring `?: "6.0"` would redden them
     * while nothing said the 6.0 ladder still works.
     */
    @Test
    fun `control - an explicit typeScriptVersion 6 0 keeps the deprecated-option row`() {
        val d = diagnose(everyShape, "// @strict: true\n// @target: es5\n// @typeScriptVersion: 6.0")
        assert(d.map { it.code } == listOf(5107))
        assert(
            d.single().message ==
                "Option 'target=ES5' is deprecated and will stop functioning in TypeScript 7.0. " +
                "Specify compilerOption '\"ignoreDeprecations\": \"6.0\"' to silence this error."
        )
        assert(diagnose(everyShape, es5).isEmpty())
    }

    // ── controls ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `control - an es2015 target never reported either code`() {
        diagnose(everyShape, es2015) should {
            have(none { it.code == 1250 })
            have(none { it.code == 1251 })
            have(none { it.code == 18028 })
        }
    }

    @Test
    fun `control - an unset target never reported either code`() {
        diagnose(everyShape, "// @strict: true") should {
            have(none { it.code == 1250 })
            have(none { it.code == 1251 })
            have(none { it.code == 18028 })
        }
    }

    /** The written es5 is still a real target notion: its LIB is the es5 one ((LEGACY.1)(j4)'s). */
    @Test
    fun `control - an es5 target still answers the es5 lib`() {
        diagnose("""const s: string = "a".padStart(3);""", "// @useRealLibs: true\n// @target: es5") should {
            have(any { it.code == 2550 })
        }
    }
}
