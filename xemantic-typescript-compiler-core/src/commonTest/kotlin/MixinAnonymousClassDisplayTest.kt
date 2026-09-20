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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (P18.139) AN ANONYMOUS CLASS IS NAMED BY ITS DECLARATION CHAIN AND CARRIES NO TYPE
 * ARGUMENTS — `mixB.(Anonymous class)`, never `mixB<typeof A>.(Anonymous class)`.
 *
 * tsgo builds this name with `symbolToString`, which walks `Symbol.parent`, so an
 * anonymous class declared inside a generic mixin function prints the FUNCTION's name and
 * stops. TypeScript 6 rendered the INSTANTIATED anonymous-class type instead, which is
 * where the `<typeof A>` — and, one level deeper, the whole
 * `mixC<{ new (...args: any[]): mixB<typeof A>.(Anonymous class); prototype:
 * mixB<any>.(Anonymous class); } & typeof A>` — came from.
 *
 * The site is the hardcoded pin walker `checkMixinPrivateConflictReducedToNever`, whose
 * own KDoc says it recomputes these display strings from the mixin call chain because the
 * types are not modelled. So closing `mixinPrivateAndProtected.errors.txt` is a
 * RE-TRANSCRIPTION of one string, not an engine change — (P18.101)'s rule, and the
 * measurement that says so is that the fixture's entire recorded divergence
 * (`submoduleAccepted/compiler/mixinPrivateAndProtected.errors.txt.diff`) is these lines
 * and nothing else. `typeofDisp`, which filled the `<…>`, had no other reader and is gone.
 *
 * MEASURED against `tools/tsgo-7.0.2/lib/tsc` on this file's own shapes.
 */
class MixinAnonymousClassDisplayTest {

    private val prelude = """
        type Constructor<T> = new(...args: any[]) => T;

        class A {
            public pb: number = 2;
            private pvt: number = 0;
        }

        function mixG<T extends Constructor<{}>>(Cls: T) {
            return class extends Cls {
                private pvt: number = 0;
            };
        }
    """.trimIndent()

    @Test
    fun `a generic mixin owner is named without type arguments`() {
        diagnose(
            prelude + "\n" + """
            const AG = mixG(A);
            const ag = new AG();
            ag.pb.toFixed();
            """.trimIndent(),
        ) should {
            have(any {
                it.code == 2339 &&
                    it.message == "Property 'pb' does not exist on type 'never'." &&
                    it.messageChain == listOf(
                        "  The intersection 'mixG.(Anonymous class) & A' was reduced to 'never' " +
                            "because property 'pvt' exists in multiple constituents and is private in some.",
                    )
            })
            have(none { it.code == 2339 && "<typeof A>" in it.messageChain.joinToString("") })
        }
    }

    @Test
    fun `a two-level mixin chain names each owner bare and keeps the base last`() {
        diagnose(
            prelude + "\n" + """
            function mixC<T extends Constructor<{}>>(Cls: T) {
                return class extends Cls {
                    private pvt: number = 0;
                };
            }
            const AG = mixG(A);
            const AGC = mixC(AG);
            const agc = new AGC();
            agc.pb.toFixed();
            """.trimIndent(),
        ) should {
            have(any {
                it.code == 2339 &&
                    it.messageChain == listOf(
                        "  The intersection 'mixC.(Anonymous class) & mixG.(Anonymous class) & A' " +
                            "was reduced to 'never' because property 'pvt' exists in multiple " +
                            "constituents and is private in some.",
                    )
            })
            // the TypeScript 6 spelling built a `{ new (...args: any[]): … }` constructor type
            // into the chain; tsgo writes no type argument at any level.
            have(none { it.code == 2339 && "new (...args: any[])" in it.messageChain.joinToString("") })
        }
    }

    @Test
    fun `residue - a NON-generic mixin owner reports an ours-only row that tsgo does not have`() {
        // `function mixN(Cls: typeof A) { return class extends Cls { private pvt = 0 } }` is not
        // an intersection in tsgo at all — the base is a concrete constructor, so tsgo reports
        //   t.ts(<class>,12): error TS2415: Class '(Anonymous class)' incorrectly extends base
        //                     class 'A'.  Types have separate declarations of a private property 'pvt'.
        // at the CLASS and is silent at the member access. This walker's `extendsParam` test is
        // by NAME only, so it fires here too and prints `mixN.(Anonymous class) & A`. The DISPLAY
        // now matches tsgo's shape; the ROW is a pre-existing false positive, unchanged by
        // (P18.139), and closing it is a question about the walker's population, not its display.
        diagnose(
            """
            class A {
                public pb: number = 2;
                private pvt: number = 0;
            }

            function mixN(Cls: typeof A) {
                return class extends Cls {
                    private pvt: number = 0;
                };
            }

            const AN = mixN(A);
            const an = new AN();
            an.pb.toFixed();
            """.trimIndent(),
        ) should {
            have(any {
                it.code == 2339 &&
                    it.messageChain == listOf(
                        "  The intersection 'mixN.(Anonymous class) & A' was reduced to 'never' " +
                            "because property 'pvt' exists in multiple constituents and is private in some.",
                    )
            })
            have(none { it.code == 2415 })
        }
    }

    @Test
    fun `negative control - no private conflict means no reduced-to-never row at all`() {
        diagnose(
            """
            type Constructor<T> = new(...args: any[]) => T;

            class A {
                public pb: number = 2;
                private pvt: number = 0;
            }

            function mixOk<T extends Constructor<{}>>(Cls: T) {
                return class extends Cls {
                    protected ptd: number = 10;
                };
            }

            const AOK = mixOk(A);
            const aok = new AOK();
            aok.pb.toFixed();
            """.trimIndent(),
        ) should {
            have(none { it.code == 2339 && "reduced to 'never'" in it.messageChain.joinToString("") })
        }
    }
}
