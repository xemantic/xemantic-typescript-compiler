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
 * INV.4(c)(ii) (round 523): the checkUnresolvedNames STATE swap — the walk's
 * NameScope content queries (`has` / `isTypeParam` / `hasType` /
 * `typeParamConstraintOf` / `hasLocalShadow` / the TS2552 candidate pool) are
 * served by the INV.2(c) `lexicalScopes` tables wherever a TRUSTED scope owner
 * links its binder scope into the threaded chain, with the walk's own
 * population SKIPPED at linked sites. Untrusted levels stay threaded:
 * namespaces (buildNamespaceScope is export-filtered; the binder table carries
 * ALL merged members), enums (EnumMember-filtered), type-level scopes the
 * binder doesn't model (mapped-type TPs, `infer` names, fn-TYPE params), and
 * function SIGNATURE positions (params/TPs stay threaded — the binder's flat
 * function table would leak body declarations into parameter defaults, which
 * only the sub-ES2015 downlevel path may see).
 *
 * These pins hold on BOTH paths (linked and the unindexed-tree fallback);
 * the equivalence gate proper is the corpus suite + the 8-profile listAll
 * byte-diff.
 */
class Inv4c2LexicalStateSwapTest {

    // ── value-position resolution through linked lexical levels ────────────

    @Test
    fun `genuinely unresolved name in a nested block still fires TS2304`() {
        diagnose(
            """
            function f() { { totallyMissing; } }
            """,
        ) should {
            have(any { it.code == 2304 && it.message.contains("'totallyMissing'") })
        }
    }

    @Test
    fun `block-scoped let is visible inside its own block`() {
        diagnose(
            """
            { let bl = 1; bl; }
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `block-scoped let is NOT visible after its block`() {
        diagnose(
            """
            { let ol = 1; }
            ol;
            """,
        ) should {
            have(any { it.code == 2304 && it.message.contains("'ol'") })
        }
    }

    @Test
    fun `var hoists out of a nested block at function level`() {
        diagnose(
            """
            function f() { g; { var g = 1; } }
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `var hoists out of a nested block at file level`() {
        // The main binder never binds block-nested vars (B83.5) — the lexical
        // pass binds them into the SourceFile root's scope-space symbols.
        diagnose(
            """
            h;
            { var h = 1; }
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `catch variable is visible in the catch block and not after`() {
        diagnose(
            """
            try {} catch (e) { e; }
            e;
            """,
        ) should {
            have(any { it.code == 2304 && it.message.contains("'e'") })
            have(count { it.code == 2304 } == 1)
        }
    }

    @Test
    fun `switch clauses share one block scope across fall-through`() {
        diagnose(
            """
            switch (1 as number) {
                case 0: let sx = 1; break;
                case 1: sx; break;
            }
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `for-of loop variable is visible in the loop body`() {
        diagnose(
            """
            for (const item of [1, 2]) { item; }
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `named function expression self-name resolves in its own body`() {
        diagnose(
            """
            const f = function g() { return g; };
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `named class expression self-name resolves in its own body`() {
        diagnose(
            """
            const c = class K { m() { return K; } };
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    // ── parameter-position vs body-declaration visibility (the flat-table trap) ──

    @Test
    fun `param default referencing a body local fires TS2304 at ES2015`() {
        // The binder's flat function table holds params AND body declarations;
        // the walk links it at the BODY only, so a parameter default must not
        // see body locals (the legacy walk's sequencing).
        diagnose(
            """
            function f(a = bodyLet) { let bodyLet = 1; return a; }
            """,
            directives = "// @strict: true\n// @target: es2015",
        ) should {
            have(any { it.code == 2304 && it.message.contains("'bodyLet'") })
        }
    }

    @Test
    fun `param default referencing a body local is suppressed below ES2015`() {
        // let/const downlevel to hoisted var — the legacy ES5 pre-collect stays.
        diagnose(
            """
            function f(a = bodyLet) { let bodyLet = 1; return a; }
            """,
            directives = "// @strict: true\n// @target: es5",
        ) should {
            have(none { it.code == 2304 })
        }
    }

    // ── type-position state: TPs via lexical symbols, type-level stays threaded ──

    @Test
    fun `class type parameter resolves in member signatures and bodies`() {
        diagnose(
            """
            class C<T> { m(x: T): T { return x; } }
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `implements bare class type parameter fires TS2422`() {
        // classScope.isTypeParam served by the linked class scope's TP symbol.
        diagnose(
            """
            class C<T> implements T {}
            """,
        ) should {
            have(any { it.code == 2422 })
        }
    }

    @Test
    fun `interface and type-alias type parameters resolve`() {
        diagnose(
            """
            interface I<T> { m(x: T): T; }
            type A<U> = U[];
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `infer names in conditional types stay resolvable`() {
        diagnose(
            """
            type F<T> = T extends Array<infer U> ? U : never;
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `mapped type parameter stays resolvable in the mapped body`() {
        diagnose(
            """
            type M2<T> = { [K in keyof T]: T[K] };
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    // ── legacy file-root exclusions survive on the lexical root level ───────

    @Test
    fun `ambient external module name is not a bare identifier`() {
        diagnose(
            """
            declare module "amb" { export const x: number; }
            amb;
            """,
        ) should {
            have(any { it.code == 2304 && it.message.contains("'amb'") })
        }
    }

    @Test
    fun `declare global augmentation does not bind a bare global name`() {
        diagnose(
            """
            export {};
            declare global { interface GFoo { a: number; } }
            global;
            """,
        ) should {
            have(any { (it.code == 2304 || it.code == 2552) && it.message.contains("'global'") })
        }
    }

    // ── untrusted lexical levels: namespace export filtering preserved ──────

    @Test
    fun `non-exported namespace member is invisible in a sibling block`() {
        diagnose(
            """
            namespace M { var a = 1; }
            namespace M { export var b = 2; var c = a; }
            """,
        ) should {
            have(any { it.code == 2304 && it.message.contains("'a'") })
        }
    }

    @Test
    fun `exported namespace member is visible in a sibling block`() {
        diagnose(
            """
            namespace M { export var a = 1; }
            namespace M { var c = a; }
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `enum members resolve bare in sibling member initializers`() {
        diagnose(
            """
            enum E { A = 1, B = A }
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    // ── TS2552 candidate pool draws from linked lexical levels ──────────────

    @Test
    fun `spelling suggestion finds a block-scoped local through the lexical level`() {
        diagnose(
            """
            function f() { let localName = 1; return localNmae; }
            """,
        ) should {
            have(any { it.code == 2552 && it.message.contains("'localName'") })
        }
    }

    // ── binder gap fixes surfaced by the first suite run ────────────────────

    @Test
    fun `block-nested namespace name resolves within its block`() {
        // TS1235 fires for the placement, but tsc still BINDS the name —
        // `export = M` in the same block resolves M (moduleElementsInWrongContext).
        diagnose(
            """
            {
                namespace M { }
                var m = M;
            }
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `body local is invisible in a nested function inside a param default at ES2015`() {
        // The fn-intermediate skip: the nested fn-expr's body links its OWN lex
        // scope, whose binder parent chain crosses the OUTER fn's flat table
        // (params + body decls) — that level was never activated on this walk
        // path (we are in the outer SIGNATURE), so it must be skipped.
        diagnose(
            """
            function f(cb = function () { return foo }) { let foo = 1; }
            """,
            directives = "// @strict: true\n// @target: es2015",
        ) should {
            have(any { it.code == 2304 && it.message.contains("'foo'") })
        }
    }

    @Test
    fun `import alias overwriting an ambient module name keeps the bare name resolvable`() {
        // The alias-overwrite gotcha: `import m2 = require("m2")` replaces the
        // ambient module symbol in file locals — the exclusion set must not
        // strip the name once a non-module declaration binds it.
        diagnose(
            """
            declare module "m2" { export class C { } }
            import m2 = require("m2");
            var q = m2;
            """,
        ) should {
            have(none { it.code == 2304 || it.code == 2552 })
        }
    }

    // ── hasLocalShadow through the lexical chain (TS2845 NaN gate) ───────────

    @Test
    fun `comparing against the global NaN fires TS2845`() {
        diagnose(
            """
            let v = 1;
            if (v === NaN) {}
            """,
        ) should {
            have(any { it.code == 2845 })
        }
    }

    @Test
    fun `negative control - a NaN parameter shadow suppresses TS2845`() {
        diagnose(
            """
            function f(NaN: number, v: number) { return v === NaN; }
            """,
        ) should {
            have(none { it.code == 2845 })
        }
    }
}
