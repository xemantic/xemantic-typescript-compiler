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
 * (LEGACY.0b) step 24, 2026-09-17 — the property-access check family runs in a CHECKED
 * JavaScript file, for a receiver whose member table cannot carry a JavaScript expando.
 *
 * **What was measured first.** Four spine handlers opened `if (spineIsDts || spineIsJsLike)
 * return`, so the whole call-expression (`ccet*`) and property-access (`cpa*`) families were
 * off for every `.js` file. Flipping all four to `checkJs` moves FIVE corpus baselines: three
 * belong to the CALL family (a duplicated TS2349, three false TS2351 on `new B()` where `B`
 * extends a `.d.ts` class, a false TS2345 against a JSDoc `@overload` set) and it delivers
 * none of the rows the two pending JavaScript baselines need — so the call half stays shut.
 * The two cpa ones are a duplicated TS2339 and a false TS2339 on the legal expando static
 * `C.blah2 = 456`.
 *
 * **The firewall is what makes the cpa half safe.** A JavaScript declaration's members
 * include everything an assignment puts on it, and this checker's member tables do not know
 * that: measured on a 19-line file whose class carries five ordinary expando fields, the
 * ungated family invents TEN rows tsgo does not report. So an access is admitted only when
 * its receiver's type is declared entirely in `.ts`/`.d.ts` files, which no assignment can
 * extend — tsgo reports `c.expando = 1` on an imported `.ts` class as TS2339 even from
 * JavaScript. Every row this compiler now emits in a `.js` file is a row tsgo 7.0.2 emits, at
 * tsgo's position and with tsgo's message, over eight adversarial probes.
 *
 * **Stated residues** (tsgo reports, we stay silent — each needs the expando member model):
 * a JavaScript class instance, the static side of a JavaScript class or function, and a
 * JavaScript object literal. The ELEMENT-ACCESS funnel stays closed for a different reason:
 * `recv['missing']` is TS7053 with a chain in tsgo and TS2339 here, and the same fixture in a
 * `.ts` file diverges identically — a pre-existing general gap that opening the funnel would
 * only propagate into a second file kind.
 */
class TsgoStep24Test {

    private val js =
        "// @strict: true\n// @allowJs: true\n// @checkJs: true\n// @target: esnext\n// @noEmit: true"
    private val jsNoCheck =
        "// @strict: true\n// @allowJs: true\n// @target: esnext\n// @noEmit: true"

    // NOTE: the `.ts` declarations below are spelled out in every fixture rather than
    // interpolated from one property. A MULTI-LINE interpolation defeats `trimIndent`: its
    // continuation lines carry no indentation of their own, so the common prefix of the raw
    // string becomes 0 and NOTHING is stripped — every column in every pin then reads 12 too
    // high, which looks exactly like a span defect in the compiler.

    @Test
    fun `a missing member of a ts class read through a JSDoc param in a js file reports`() {
        // tsgo: a.js(2,26): error TS2339: Property 'nope' does not exist on type 'TsCls'.
        val d = diagnose(
            """
            // @Filename: /d.ts
            class TsCls { known: number = 1 }
            interface Iface { alsoKnown: string }
            declare const cfg: { deep: { leaf: number } };
            declare const iface: Iface;
            // @Filename: /a.js
            /** @param {TsCls} c */
            function f(c) { return c.nope; }
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/a.js" && it.line == 2 && it.character == 26 &&
                it.length == 4 &&
                it.message == "Property 'nope' does not exist on type 'TsCls'."
        })
    }

    @Test
    fun `a missing member of a ts class read through a JSDoc type const reports`() {
        // tsgo: a.js(3,14): error TS2339: Property 'nopeVar' does not exist on type 'TsCls'.
        val d = diagnose(
            """
            // @Filename: /d.ts
            class TsCls { known: number = 1 }
            interface Iface { alsoKnown: string }
            declare const cfg: { deep: { leaf: number } };
            declare const iface: Iface;
            // @Filename: /a.js
            /** @type {TsCls} */
            const v = new TsCls();
            const r = v.nopeVar;
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/a.js" && it.line == 3 && it.character == 13 &&
                it.length == 7 &&
                it.message == "Property 'nopeVar' does not exist on type 'TsCls'."
        })
    }

    @Test
    fun `a missing member of a nested anonymous object type declared in ts reports`() {
        // tsgo: a.js(1,21): TS2339 Property 'nopeDeep' does not exist on type '{ leaf: number; }'.
        val d = diagnose(
            """
            // @Filename: /d.ts
            class TsCls { known: number = 1 }
            interface Iface { alsoKnown: string }
            declare const cfg: { deep: { leaf: number } };
            declare const iface: Iface;
            // @Filename: /a.js
            const r = cfg.deep.nopeDeep;
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/a.js" && it.line == 1 && it.character == 20 &&
                it.length == 8 &&
                it.message == "Property 'nopeDeep' does not exist on type '{ leaf: number; }'."
        })
    }

    @Test
    fun `a missing member of a ts interface reports`() {
        val d = diagnose(
            """
            // @Filename: /d.ts
            class TsCls { known: number = 1 }
            interface Iface { alsoKnown: string }
            declare const cfg: { deep: { leaf: number } };
            declare const iface: Iface;
            // @Filename: /a.js
            const r = iface.nopeIface;
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/a.js" && it.line == 1 && it.character == 17 &&
                it.length == 9 &&
                it.message == "Property 'nopeIface' does not exist on type 'Iface'."
        })
    }

    @Test
    fun `an expando WRITE onto a ts class reports - a ts declaration gains nothing from an assignment`() {
        // tsgo: a.js(2,3): TS2339 — measured, the assignment does NOT declare, because the
        // container is TypeScript. That is the whole reason the firewall keys on the
        // DECLARATION's file rather than on the file being checked.
        val d = diagnose(
            """
            // @Filename: /d.ts
            class TsCls { known: number = 1 }
            interface Iface { alsoKnown: string }
            declare const cfg: { deep: { leaf: number } };
            declare const iface: Iface;
            // @Filename: /a.js
            /** @type {TsCls} */
            const v = new TsCls();
            v.expandoWrite = 1;
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/a.js" && it.line == 3 && it.character == 3 &&
                it.length == 12 &&
                it.message == "Property 'expandoWrite' does not exist on type 'TsCls'."
        })
    }

    @Test
    fun `a compound assignment to a missing member of a ts class reports`() {
        val d = diagnose(
            """
            // @Filename: /d.ts
            class TsCls { known: number = 1 }
            interface Iface { alsoKnown: string }
            declare const cfg: { deep: { leaf: number } };
            declare const iface: Iface;
            // @Filename: /a.js
            /** @type {TsCls} */
            const v = new TsCls();
            v.compoundMissing += 1;
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/a.js" && it.line == 3 && it.character == 3 &&
                it.message == "Property 'compoundMissing' does not exist on type 'TsCls'."
        })
    }

    @Test
    fun `a parenthesized assignment target that is a missing member of a ts class reports`() {
        val d = diagnose(
            """
            // @Filename: /d.ts
            class TsCls { known: number = 1 }
            interface Iface { alsoKnown: string }
            declare const cfg: { deep: { leaf: number } };
            declare const iface: Iface;
            // @Filename: /a.js
            /** @type {TsCls} */
            const v = new TsCls();
            (v.parenMissing) = 1;
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/a.js" && it.line == 3 &&
                it.message == "Property 'parenMissing' does not exist on type 'TsCls'."
        })
    }

    @Test
    fun `negative control - allowJs without checkJs reports nothing for the same file`() {
        // (P18.92): a `.js` file is in the program under allowJs and CHECKED only under
        // checkJs. tsgo is silent for this whole fixture without the flag.
        val d = diagnose(
            """
            // @Filename: /d.ts
            class TsCls { known: number = 1 }
            interface Iface { alsoKnown: string }
            declare const cfg: { deep: { leaf: number } };
            declare const iface: Iface;
            // @Filename: /a.js
            /** @param {TsCls} c */
            function f(c) { return c.nope; }
            """,
            jsNoCheck,
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `negative control - a JavaScript class instance receiver stays silent - the stated expando residue`() {
        // tsgo DOES report `Property 'nopeJs' does not exist on type 'JsCls'.` here. We do
        // not, and that is the firewall being conservative rather than a rule about tsgo:
        // `JsCls`'s members include every `this.x = …` assignment and this checker's tables
        // hold none of them, so an ALL-MISSING verdict on it carries no witness (CHK.45).
        val d = diagnose(
            """
            // @Filename: /a.js
            class JsCls { constructor() { this.a = 1; } }
            const j = new JsCls();
            const r = j.nopeJs;
            """,
            js,
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `negative control - a static expando on a JavaScript class stays silent`() {
        // tsgo is SILENT here too: `K.s1 = 1` DECLARES a static member on a JavaScript class,
        // so neither the write nor a later read is an error. It is the one shape the naive
        // gate flip moved a green corpus baseline on (classFieldSuperAccessibleJs1).
        val d = diagnose(
            """
            // @Filename: /a.js
            class K { }
            K.s1 = 1;
            const r = K.s1;
            """,
            js,
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `negative control - an element access on a ts receiver in a js file stays silent`() {
        // tsgo answers TS7053 with a two-line chain anchored at the RECEIVER; this checker
        // answers TS2339 anchored at the index, in a `.ts` file just the same. The funnel is
        // kept closed rather than made to emit the wrong code at the wrong span.
        val d = diagnose(
            """
            // @Filename: /d.ts
            class TsCls { known: number = 1 }
            interface Iface { alsoKnown: string }
            declare const cfg: { deep: { leaf: number } };
            declare const iface: Iface;
            // @Filename: /a.js
            /** @type {TsCls} */
            const v = new TsCls();
            const r = v['nopeElem'];
            """,
            js,
        )
        assert(d.none { it.code == 2339 || it.code == 7053 })
    }

    @Test
    fun `negative control - the Object defineProperty walker's row is emitted exactly once`() {
        // B428's dedicated walker existed BECAUSE the family was off for `.js`. Opening the
        // cpa half without the firewall made the general path emit the same row a second
        // time — the corpus caught it as `jsCheckObjectDefineThisNoCrash`. RE-POINTED at
        // (LEGACY.0b) step 25: the JavaScript class expando model now decides this access,
        // reproduces the row byte-for-byte, and the walker is RETIRED (measured with the
        // PassLab — disabling it on the landed binary leaves the errors screen 3,102 / 0), so
        // the ASSERTION is unchanged and only its owner moved.
        val d = diagnose(
            """
            // @Filename: /a.js
            class C {
                constructor() {
                    Object.defineProperty(this, "_prop", { value: 0 });
                    Object.defineProperty(this._prop, "num", { value: 12 });
                }
            }
            """,
            js,
        )
        assert(d.count { it.code == 2339 && it.message.contains("'_prop'") } == 1)
    }

    @Test
    fun `negative control - a ts file in the same program is unaffected`() {
        val d = diagnose(
            """
            // @Filename: /d.ts
            class TsCls { known: number = 1 }
            interface Iface { alsoKnown: string }
            declare const cfg: { deep: { leaf: number } };
            declare const iface: Iface;
            // @Filename: /b.ts
            declare const t: TsCls;
            export const q = t.nopeTs;
            // @Filename: /a.js
            const r = cfg.deep.leaf;
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/b.ts" &&
                it.message == "Property 'nopeTs' does not exist on type 'TsCls'."
        })
        assert(d.none { it.fileName == "/a.js" })
    }
}
