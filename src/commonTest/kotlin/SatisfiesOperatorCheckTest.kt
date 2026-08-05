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
 * (M3.0, round 833) The general `satisfies` check.
 *
 * Before this round a `SatisfiesExpression` was type-transparent and verified
 * NOWHERE: the operator's entire purpose — check the operand against a type
 * without changing the operand's type — was unimplemented, and the only TS1360
 * in the tree was a corpus-unique walker keyed on two identifier names.
 *
 * The check is deliberately partial, and the negative controls below are the
 * reason. A `satisfies` operand is FRESH, so a property VALUE verdict depends on
 * contextual typing this site does not install; taking such a verdict now would
 * REJECT legal code. Only two verdicts survive that objection, because contextual
 * typing changes property values and never changes which property NAMES a literal
 * carries, nor the type of a non-fresh primitive operand:
 *   - excess / missing property NAMES on an object literal, and
 *   - a primitive-intrinsic operand against a primitive-intrinsic target.
 *
 * Every positive here fails on the pre-833 binary (which emitted nothing at all
 * for `satisfies`), and every negative fails on the naive "just call the relation"
 * implementation — which is the mistake this design exists to avoid.
 */
class SatisfiesOperatorCheckTest {

    private val prelude = """
        interface I1 {
            a: number;
        }
        type Exact = {
            a: "a" | "b";
        }
        type Facts = { [key: string]: boolean };
    """.trimIndent() + "\n"

    @Test
    fun `an object literal missing a required property does not satisfy the type`() {
        val diagnostics = diagnose(prelude + "const t = {} satisfies I1;")
        diagnostics should {
            have(any { it.code == 1360 })
            have(any {
                it.code == 1360 &&
                    it.message == "Type '{}' does not satisfy the expected type 'I1'."
            })
        }
        val d = diagnostics.first { it.code == 1360 }
        assert(d.messageChain == listOf("  Property 'a' is missing in type '{}' but required in type 'I1'."))
        assert(d.length == 9)
    }

    @Test
    fun `the TS1360 squiggle is the satisfies keyword itself`() {
        val source = prelude + "const t = {} satisfies I1;"
        val diagnostics = diagnose(source)
        val d = diagnostics.first { it.code == 1360 }
        // The checker indexes into the DIRECTIVE-STRIPPED text, so derive rather
        // than hardcode — see the pin-position rule in the triage skill.
        val stripped = source
        val expected = stripped.indexOf("satisfies")
        assert(d.start == expected)
    }

    @Test
    fun `an excess property on a satisfies object literal is reported`() {
        diagnose(prelude + "const t = { a: 1, b: 1 } satisfies I1;") should {
            have(any {
                it.code == 2353 &&
                    it.message == "Object literal may only specify known properties, and 'b' does not exist in type 'I1'."
            })
        }
    }

    @Test
    fun `a primitive operand that cannot be the target primitive does not satisfy it`() {
        diagnose(prelude + "const t = 1 satisfies boolean;") should {
            have(any {
                it.code == 1360 &&
                    it.message == "Type 'number' does not satisfy the expected type 'boolean'."
            })
        }
    }

    @Test
    fun `negative control - a satisfied object literal draws nothing`() {
        diagnose(prelude + "const t = { a: 1 } satisfies I1;") should {
            have(none { it.code == 1360 })
            have(none { it.code == 2353 })
        }
    }

    @Test
    fun `negative control - a contextually typed literal property value is not judged`() {
        // LEGAL in tsc: the literal's `a` is contextually typed by the target, so it
        // is `"a"`, not the widened `string`. A naive relation call at this site
        // rejects it — this pin is what makes that mistake visible.
        diagnose(prelude + "const t = { a: \"a\" } satisfies Exact;") should {
            have(none { it.code == 1360 })
        }
    }

    @Test
    fun `negative control - an index signature target has no excess properties`() {
        diagnose(prelude + "const t = { m: true, s: false } satisfies Facts;") should {
            have(none { it.code == 2353 })
            have(none { it.code == 1360 })
        }
    }

    @Test
    fun `negative control - a matching primitive operand draws nothing`() {
        diagnose(prelude + "declare const s: string;\nconst t = s satisfies string;") should {
            have(none { it.code == 1360 })
        }
    }

    @Test
    fun `negative control - a spread makes the literal property set undecidable`() {
        // The spread's own properties are not in the literal's AST, so neither the
        // excess nor the missing-property verdict can be taken from it.
        diagnose(prelude + "declare const src: I1;\nconst t = { ...src } satisfies I1;") should {
            have(none { it.code == 1360 })
            have(none { it.code == 2353 })
        }
    }
}
