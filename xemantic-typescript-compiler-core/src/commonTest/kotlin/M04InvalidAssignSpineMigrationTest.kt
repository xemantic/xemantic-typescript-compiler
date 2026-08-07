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
 * (M0.4, round 642): pins for the checkInvalidAssignmentTargets (TS2364
 * invalid assignment/compound-assignment targets + the destructuring
 * private-identifier check) spine migration. The legacy expression
 * recursion is guarded by the SHARED `checkDepth` counter
 * (checkInvalidAssignInExpr bails past maxCheckDepth = 200), so deep
 * expression chains PRUNE — the INT-depth classifier shape. Frozen reach
 * quirks pinned both directions: for-heads walk initializer-as-Expression
 * AND condition AND incrementor while a for-head DECLARATION-LIST
 * initializer is never walked; switch-case EXPRESSIONS (and the switch
 * SUBJECT) are not walked, clause statements are; objlit
 * methods/accessors + class-EXPRESSION members ARE walked; enum member
 * initializers, class heritage, computed property names, and decorators
 * are unreached; `<<=`/`>>=`/`>>>=`/`**=` are NOT classified as
 * assignment operators (frozen isAssignmentOperator gap → silent). All
 * expectations verified against the pre-migration walker.
 */
class M04InvalidAssignSpineMigrationTest {

    // ── fires: emission shapes ─────────────────────────────────────────────

    @Test
    fun `TS2364 - literal assignment target at top level`() {
        diagnose(
            """
            1 = 2;
            """
        ) should {
            have(any { it.code == 2364 })
        }
    }

    @Test
    fun `TS2364 - compound assignment with literal target`() {
        diagnose(
            """
            0 ^= 1;
            """
        ) should {
            have(any { it.code == 2364 })
        }
    }

    @Test
    fun `TS2364 - compound assignment with destructuring array target`() {
        diagnose(
            """
            let a: any;
            [a] ^= 1;
            """
        ) should {
            have(any { it.code == 2364 })
        }
    }

    @Test
    fun `negative control - plain destructuring assignment is valid`() {
        diagnose(
            """
            let a: any;
            [a] = [1];
            """
        ) should {
            have(none { it.code == 2364 })
        }
    }

    @Test
    fun `negative control - compound assignment with valid targets`() {
        diagnose(
            """
            let x: any = 0;
            const o: any = {};
            x += 1;
            o.y ^= 1;
            (x as any) |= 1;
            """
        ) should {
            have(none { it.code == 2364 })
        }
    }

    @Test
    fun `TS2364 - invalid target through as-wrapper`() {
        diagnose(
            """
            (1 as any) = 2;
            """
        ) should {
            have(any { it.code == 2364 })
        }
    }

    @Test
    fun `TS2364 - shift-compound assignment draws nothing - frozen operator gap`() {
        diagnose(
            """
            1 <<= 2;
            1 >>= 2;
            1 **= 2;
            """
        ) should {
            have(none { it.code == 2364 })
        }
    }

    // ── destructuring private identifiers ──────────────────────────────────

    @Test
    fun `TS2364 - private identifier in array destructuring`() {
        diagnose(
            """
            let x: any;
            [#abc] = x;
            """
        ) should {
            have(any { it.code == 2364 })
        }
    }

    @Test
    fun `TS2364 - private identifier under a destructuring default`() {
        diagnose(
            """
            let x: any;
            [#a = 1] = x;
            """
        ) should {
            have(any { it.code == 2364 })
        }
    }

    @Test
    fun `TS2364 - private identifier as object destructuring value`() {
        diagnose(
            """
            let v: any;
            ({ x: #abc } = v);
            """
        ) should {
            have(any { it.code == 2364 })
        }
    }

    // ── reach: statement positions ─────────────────────────────────────────

    @Test
    fun `TS2364 - in a variable initializer`() {
        diagnose(
            """
            const v = (1 = 2);
            """
        ) should {
            have(any { it.code == 2364 })
        }
    }

    @Test
    fun `TS2364 - in if for while and do conditions`() {
        val d = diagnose(
            """
            declare const x: any;
            if ((1 = 2)) {}
            while ((1 = 2)) {}
            do {} while ((1 = 2));
            """
        )
        val count = d.count { it.code == 2364 }
        assert(count == 3)
    }

    @Test
    fun `TS2364 - all three for-head expression positions`() {
        val d = diagnose(
            """
            for (1 = 2; 1 = 2; 1 = 2) {}
            """
        )
        val count = d.count { it.code == 2364 }
        assert(count == 3)
    }

    @Test
    fun `negative control - for-head declaration-list initializer is never walked`() {
        diagnose(
            """
            for (let x = (1 = 2);;) { break; }
            """
        ) should {
            have(none { it.code == 2364 })
        }
    }

    @Test
    fun `TS2364 - for-in and for-of iterated expressions`() {
        val d = diagnose(
            """
            for (const k in (1 = 2)) {}
            for (const v of (1 = 2)) {}
            """
        )
        val count = d.count { it.code == 2364 }
        assert(count == 2)
    }

    @Test
    fun `negative control - switch subject and case expressions are not walked`() {
        diagnose(
            """
            declare const x: any;
            switch (1 = 2) {}
            switch (x) { case (1 = 2): break; }
            """
        ) should {
            have(none { it.code == 2364 })
        }
    }

    @Test
    fun `TS2364 - switch clause statements are walked`() {
        diagnose(
            """
            declare const x: any;
            switch (x) { case 1: 1 = 2; break; default: 3 = 4; }
            """
        ) should {
            have(any { it.code == 2364 })
        }
    }

    @Test
    fun `TS2364 - try catch finally throw return and labeled statements`() {
        val d = diagnose(
            """
            function f() {
                try { 1 = 2; } catch (e) { 1 = 2; } finally { 1 = 2; }
                L: 1 = 2;
                if (Math.random()) return (1 = 2);
                throw (1 = 2);
            }
            """
        )
        val count = d.count { it.code == 2364 }
        assert(count == 6)
    }

    @Test
    fun `TS2364 - namespace body`() {
        diagnose(
            """
            namespace N { 1 = 2; }
            """
        ) should {
            have(any { it.code == 2364 })
        }
    }

    @Test
    fun `negative control - enum member initializers are never walked`() {
        diagnose(
            """
            enum E { A = (1 = 2) }
            """
        ) should {
            have(none { it.code == 2364 })
        }
    }

    @Test
    fun `negative control - class heritage is never walked`() {
        diagnose(
            """
            class C extends (1 = 2) {}
            """
        ) should {
            have(none { it.code == 2364 })
        }
    }

    // ── reach: class and object-literal members ────────────────────────────

    @Test
    fun `TS2364 - class declaration member bodies and property initializer`() {
        val d = diagnose(
            """
            class C {
                p = (1 = 2);
                m() { 1 = 2; }
                get g(): any { 1 = 2; return 0; }
                set s(v: any) { 1 = 2; }
                constructor() { 1 = 2; }
            }
            """
        )
        val count = d.count { it.code == 2364 }
        assert(count == 5)
    }

    @Test
    fun `TS2364 - class expression member body and property initializer`() {
        val d = diagnose(
            """
            const c = class {
                p = (1 = 2);
                m() { 1 = 2; }
            };
            """
        )
        val count = d.count { it.code == 2364 }
        assert(count == 2)
    }

    @Test
    fun `TS2364 - object literal member positions`() {
        val d = diagnose(
            """
            declare let z: any;
            ({
                a: (1 = 2),
                m() { 1 = 2; },
                get g(): any { 1 = 2; return 0; },
                set s(v: any) { 1 = 2; },
                ...(1 = 2),
            });
            ({ b = (1 = 2) } = z);
            """
        )
        val count = d.count { it.code == 2364 }
        assert(count == 6)
    }

    @Test
    fun `negative control - computed property names are never walked`() {
        diagnose(
            """
            ({ [(1 = 2)]: 3 });
            """
        ) should {
            have(none { it.code == 2364 })
        }
    }

    // ── reach: expression positions ────────────────────────────────────────

    @Test
    fun `TS2364 - nested function-like bodies`() {
        val d = diagnose(
            """
            const a1 = () => (1 = 2);
            const a2 = () => { 1 = 2; };
            const fe = function() { 1 = 2; };
            """
        )
        val count = d.count { it.code == 2364 }
        assert(count == 3)
    }

    @Test
    fun `TS2364 - call callee arguments and new expression`() {
        val d = diagnose(
            """
            declare const f: any;
            f(1 = 2);
            (1 = 2)();
            new (1 = 2)();
            new f(1 = 2);
            """
        )
        val count = d.count { it.code == 2364 }
        assert(count == 4)
    }

    @Test
    fun `TS2364 - template spans and tagged templates`() {
        val d = diagnose(
            """
            declare const tag: any;
            `${'$'}{1 = 2}`;
            tag`${'$'}{1 = 2}`;
            (1 = 2)`x`;
            """
        )
        val count = d.count { it.code == 2364 }
        assert(count == 3)
    }

    @Test
    fun `TS2364 - unary spread access and comma positions`() {
        val d = diagnose(
            """
            declare const x: any;
            !(1 = 2);
            [...(1 = 2)];
            x[1 = 2];
            (1 = 2).y;
            void (1 = 2);
            typeof (1 = 2);
            ((1 = 2), 3);
            """
        )
        val count = d.count { it.code == 2364 }
        assert(count == 7)
    }

    @Test
    fun `TS2364 - await and yield operands`() {
        val d = diagnose(
            """
            async function fa() { await (1 = 2); }
            function* fg() { yield (1 = 2); }
            """
        )
        val count = d.count { it.code == 2364 }
        assert(count == 2)
    }

    @Test
    fun `TS2364 - inner assignment nested in a valid outer assignment`() {
        diagnose(
            """
            let a: any;
            a = (1 = 2);
            """
        ) should {
            have(any { it.code == 2364 })
        }
    }

    @Test
    fun `TS2364 - ternary branches and condition`() {
        val d = diagnose(
            """
            ((1 = 2) ? (3 = 4) : (5 = 6));
            """
        )
        val count = d.count { it.code == 2364 }
        assert(count == 3)
    }

    // ── the depth cap (shared checkDepth counter, maxCheckDepth = 200) ─────

    @Test
    fun `TS2364 - fires at exactly the 200-frame depth boundary`() {
        val src = "(".repeat(200) + "1 = 2" + ")".repeat(200) + ";"
        val d = diagnose(src)
        val count = d.count { it.code == 2364 }
        assert(count == 1)
    }

    @Test
    fun `negative control - pruned past the 200-frame depth boundary`() {
        val src = "(".repeat(201) + "1 = 2" + ")".repeat(201) + ";"
        diagnose(src) should {
            have(none { it.code == 2364 })
        }
    }

    // ── file gates ─────────────────────────────────────────────────────────

    @Test
    fun `negative control - dts files are skipped`() {
        diagnose(
            """
            1 = 2;
            """,
            fileName = "t.d.ts",
        ) should {
            have(none { it.code == 2364 })
        }
    }
}
