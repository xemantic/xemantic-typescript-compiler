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
 * (REL.3) round 761: a type argument survived exactly ONE heritage hop.
 *
 * `findInheritedBaseRef` walks a receiver's base chain looking for the
 * `Type.Reference` that instantiates the property's DECLARING type, then hands it
 * to `resolveGenericPropertyType`. When it stepped from a `Type.Reference` up to
 * its target's own base types it enqueued them RAW — but a target's `baseTypes`
 * are written in the TARGET's type-parameter scope, so for
 * `interface Fwd<TFWD> extends Box<TFWD>` the enqueued base is `Box<TFWD>`,
 * carrying Fwd's own parameter rather than this reference's argument.
 * `interface S2 extends Fwd<number>` therefore resolved `.v` to the bare type
 * PARAMETER `TFWD` instead of `number` — an unconstrained type parameter, which
 * relates leniently in the read direction and so mostly read as silence.
 *
 * tsc's own source has exactly this shape —
 * `AbstractKeyword extends KeywordToken<SyntaxKind.AbstractKeyword>`,
 * `KeywordToken<TKind> extends Token<TKind>`, `Token<TKind> extends Node`
 * declaring `kind: TKind` — so every keyword node's `kind` degraded.
 *
 * The observable used throughout is an ARGUMENT position: an unconstrained type
 * parameter is assignable to `string`, so the defect shows up as a MISSING or
 * mis-typed TS2345 rather than as an extra one. Each target asserts the
 * substituted type NAME appears in the message, which is what fails on the
 * pre-fix binary.
 */
class HeritageTypeArgumentSecondHopTest {

    private val prelude = """
        interface Box<TBOX> { v: TBOX; }
        interface Fwd<TFWD> extends Box<TFWD> {}
        declare function wantStr(s: string): void;
    """.trimIndent()

    @Test
    fun `a type argument survives a second heritage hop through a forwarding intermediate`() {
        diagnose(
            prelude + "\n" + """
            interface S2 extends Fwd<number> {}
            declare const s2: S2;
            wantStr(s2.v);
            """.trimIndent()
        ) should {
            have(any { it.code == 2345 && it.message.contains("'number'") })
        }
    }

    @Test
    fun `a type argument survives three heritage hops`() {
        diagnose(
            prelude + "\n" + """
            interface Two<TTWO> extends Fwd<TTWO> {}
            interface S12 extends Two<number> {}
            declare const s12: S12;
            wantStr(s12.v);
            """.trimIndent()
        ) should {
            have(any { it.code == 2345 && it.message.contains("'number'") })
        }
    }

    @Test
    fun `an intermediate that REORDERS its type parameters substitutes the right one`() {
        // Swap's base uses its SECOND parameter, so a fix that merely passed the
        // outer arguments down positionally would answer 'boolean' here.
        diagnose(
            prelude + "\n" + """
            interface Swap<A, B> extends Box<B> {}
            interface S6 extends Swap<boolean, number> {}
            declare const s6: S6;
            wantStr(s6.v);
            """.trimIndent()
        ) should {
            have(any { it.code == 2345 && it.message.contains("'number'") })
        }
    }

    @Test
    fun `an intermediate that WRAPS its type parameter substitutes inside the wrapper`() {
        diagnose(
            prelude + "\n" + """
            interface Wrap<TWR> extends Box<TWR[]> {}
            interface S4 extends Wrap<number> {}
            declare const s4: S4;
            wantStr(s4.v);
            """.trimIndent()
        ) should {
            have(any { it.code == 2345 && it.message.contains("number[]") })
        }
    }

    @Test
    fun `a class chain substitutes through its second heritage hop`() {
        diagnose(
            """
            declare class CBox<TCB> { v: TCB; }
            declare class CFwd<TCF> extends CBox<TCF> {}
            declare class C8 extends CFwd<number> {}
            declare function wantStr(s: string): void;
            declare const c8: C8;
            wantStr(c8.v);
            """.trimIndent()
        ) should {
            have(any { it.code == 2345 && it.message.contains("'number'") })
        }
    }

    @Test
    fun `negative control - a single heritage hop was never broken`() {
        diagnose(
            prelude + "\n" + """
            interface S1 extends Box<number> {}
            declare const s1: S1;
            wantStr(s1.v);
            """.trimIndent()
        ) should {
            have(any { it.code == 2345 && it.message.contains("'number'") })
        }
    }

    @Test
    fun `negative control - a SPECIALIZED intermediate is not re-substituted`() {
        // Spec fixes its base's argument to `string` and never forwards TSPEC, so
        // S5.v must stay `string`. A fix that pushed the outer argument through
        // regardless of the base's own type arguments would answer 'number'.
        diagnose(
            """
            interface Box<TBOX> { v: TBOX; }
            interface Spec<TSPEC> extends Box<string> { s: TSPEC; }
            interface S5 extends Spec<number> {}
            declare function wantNum(n: number): void;
            declare const s5: S5;
            wantNum(s5.v);
            """.trimIndent()
        ) should {
            have(any { it.code == 2345 && it.message.contains("'string'") })
        }
    }

    @Test
    fun `negative control - an intermediate's OWN member still substitutes`() {
        diagnose(
            """
            interface Box<TBOX> { v: TBOX; }
            interface Own<TOWN> extends Box<TOWN> { w: TOWN; }
            interface S7 extends Own<number> {}
            declare function wantStr(s: string): void;
            declare const s7: S7;
            wantStr(s7.w);
            """.trimIndent()
        ) should {
            have(any { it.code == 2345 && it.message.contains("'number'") })
        }
    }
}
