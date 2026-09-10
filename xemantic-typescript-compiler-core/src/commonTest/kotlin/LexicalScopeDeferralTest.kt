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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * (INC.16) The INV.2(c) lexical-scope tables are built ON FIRST ASK, and the one
 * reader that used to ask for EVERY file's is served by a projection instead.
 *
 * (INC.15) measured `bindLexicalScopes` at 93% of everything binding costs and
 * ~16% of the incremental floor, and (INC.9)'s template — `BinderResult.flowGraph`
 * on first ask, 136 ms banked — applies verbatim EXCEPT for one line:
 * `init:computeAllEnumValues`' scope-space census is a program-wide COLLECTOR, so
 * round 609 forbids gating it onto the partition, and a plain `lazy` would simply
 * be forced by it for every file. Measured with the `forcedBy` census in
 * `scripts/lex-defer.sh`, that pass was the SOLE forcer of all 78 program files.
 *
 * The projection is `SourceFile.nestedEnumOrTypeAliasCount`: `declareLexical` mints
 * a `TypeAlias`- or `Enum`-flagged scope symbol at exactly two sites, both gated on
 * `scope.existing == null`, and a declaration whose parent is the SourceFile itself
 * lands in the root scope, which always aliases file locals. So a file with no OTHER
 * `enum`/`type` declaration can never contribute a row to the census — and skipping
 * it is what leaves its tables unbuilt.
 *
 * The counters this class drives are process-global, so every test here SAVES AND
 * RESTORES them (CLAUDE.md's PassLab rule).
 */
class LexicalScopeDeferralTest {

    private fun parse(@Language("typescript") source: String) =
        Parser(source.trimIndent(), "t.ts").parse()

    /** Structure of a scope table, id-free: `declareLexical` mints from the descending
     *  NEGATIVE `Symbol.scopeSymbol` sequence, which any deferral reorders among itself. */
    private fun shape(scopes: Map<Int, LexicalScope>): List<String> =
        scopes.keys.sorted().map { id ->
            val s = scopes.getValue(id)
            val own = s.symbols.keys.sorted().joinToString(",") { "$it:${s.symbols.getValue(it).flags.value}" }
            "$id|${(s.owner as NodeBase).nodeId}|${s.existing == null}|$own"
        }

    private fun <T> withDeferral(deferred: Boolean, block: () -> T): T {
        val saved = LexDefer.deferred
        LexDefer.deferred = deferred
        try {
            return block()
        } finally {
            LexDefer.deferred = saved
        }
    }

    private val source = """
        type Top = string;
        enum TopEnum { A, B }
        namespace N {
            type Inner = number;
        }
        function f() {
            enum Body { X = 1, Y = 2 }
            type Alias = Body;
            let v: Alias = Body.X;
            return v;
        }
    """

    @Test
    fun `a deferred bind leaves the scope tables unbuilt until they are asked for`() {
        withDeferral(deferred = true) {
            val result = Binder(CompilerOptions()).bind(parse(source))
            assert(!result.lexicalScopesBuilt)
            assert(result.lexicalScopes.isNotEmpty())
            assert(result.lexicalScopesBuilt)
        }
    }

    @Test
    fun `the shipped configuration is the deferred one`() {
        // Pins the DEFAULT, not the mechanism: every other test here installs the mode
        // it wants and restores it, so flipping `LexDefer.deferred` back would leave
        // them all green while every query paid the whole scope walk again.
        val result = Binder(CompilerOptions()).bind(parse(source))
        assert(!result.lexicalScopesBuilt)
    }

    @Test
    fun `a namespace scope aliases its OWN file's exports when two files collide on a node key`() {
        // Hazard (a). `nodeToSymbol` is shared by every `BinderResult` from one `Binder`
        // and its `(pos, end)` keys COLLIDE ACROSS FILES, last-wins in bind order — so a
        // scope built at FIRST ASK, i.e. after every file is bound, would alias whichever
        // file wrote that key last. The two sources below are the same LENGTH and declare
        // their namespace at the same offsets, so their ModuleDeclarations share a key.
        val binder = Binder(CompilerOptions())
        val a = binder.bind(Parser("namespace N { export type Alpha = number; }", "a.ts").parse())
        val b = binder.bind(Parser("namespace N { export type Bravo = number; }", "b.ts").parse())
        // Asked AFTER both binds — which is exactly what the deferral does.
        fun aliasedNames(r: BinderResult): Set<String> {
            val decl = r.sourceFile.statements.first() as ModuleDeclaration
            val scope = r.lexicalScopes[(decl as NodeBase).nodeId]
            return scope?.existing?.keys?.toSet() ?: emptySet()
        }
        assert(aliasedNames(a) == setOf("Alpha"))
        assert(aliasedNames(b) == setOf("Bravo"))
    }

    @Test
    fun `the eager bind builds them inside bind itself`() {
        withDeferral(deferred = false) {
            val result = Binder(CompilerOptions()).bind(parse(source))
            assert(result.lexicalScopesBuilt)
        }
    }

    @Test
    fun `a deferred build produces the tables the eager build produced`() {
        val eager = withDeferral(deferred = false) {
            shape(Binder(CompilerOptions()).bind(parse(source)).lexicalScopes)
        }
        val deferred = withDeferral(deferred = true) {
            shape(Binder(CompilerOptions()).bind(parse(source)).lexicalScopes)
        }
        assert(deferred == eager)
        assert(eager.isNotEmpty())
    }

    private fun bindOf(@Language("typescript") src: String): BinderResult =
        Binder(CompilerOptions()).bind(parse(src))

    @Test
    fun `a file whose enum and type declarations are all file level declares no scope type names`() {
        val sf = parse(
            """
            type Top = string;
            enum TopEnum { A, B }
            interface I { p: number }
            function f() { return 1; }
            """,
        )
        assert(sf.nestedScopeDecls.isEmpty())
        val result = Binder(CompilerOptions()).bind(sf)
        assert(!result.declaresScopeEnum)
        assert(result.scopeTypeNames.isEmpty())
    }

    @Test
    fun `an enum in a function body is the only thing that forces a scope build`() {
        assert(parse("function f() { enum E { A } return E.A; }").nestedScopeDecls.size == 1)
        assert(bindOf("function f() { enum E { A } return E.A; }").declaresScopeEnum)
    }

    @Test
    fun `a type alias in a block is handed over by name and forces nothing`() {
        val result = bindOf("function f() { type T = number; let x: T = 1; return x; }")
        assert(!result.declaresScopeEnum)
        assert(result.scopeTypeNames == setOf("T"))
    }

    /**
     * (INV.0) step 10a widened the stamp and the projection from `type`/`enum` to the
     * whole TYPE space; **step 10b widened the STAMP again to the union of the TYPE and
     * VALUE spaces and SPLIT the projection in two**, and this pin is where that is
     * stated. The stamp's six kinds are exactly those `Binder.bindLexicalScopes` mints a
     * scope symbol for under its `scope.existing == null` gate; the two projections then
     * say which SPACE each name is in, and `class` / `enum` are in BOTH.
     *
     * Its previous form asserted the stamp carried TWO declarations and that a nested
     * `function` was "deliberately NOT carried" — true of the TYPE consult and, once the
     * value consult existed, false of the stamp. It is updated on purpose, which is what
     * a countdown pin is for.
     */
    @Test
    fun `a class an interface and a function in a function body are carried by the name projections`() {
        val src = "function f() { class ZzzC {} interface ZzzI { p: number } function g() {} return 1; }"
        assert(parse(src).nestedScopeDecls.size == 3)
        val result = bindOf(src)
        assert(!result.declaresScopeEnum)
        assert(result.scopeTypeNames == setOf("ZzzC", "ZzzI"))
        assert(result.scopeValueNames == setOf("ZzzC", "g"))
    }

    /**
     * (INV.0) step 10b — the VALUE projection's own shapes, and the two the item's
     * mirror of `bindLexicalScopes` has to get right or the gate lies in one of two
     * silent directions: a `declare global` block declares NOTHING (GH#42209, so
     * claiming `global` would be a hit that costs an ascent and answers nothing), and a
     * DOTTED namespace name declares its LEFTMOST segment only.
     *
     * An `enum` is in both projections because it declares a name in both spaces, and a
     * nested `namespace` is a TS1235 grammar error that tsc still BINDS — which is why
     * the projection carries it rather than refusing it.
     */
    @Test
    fun `the value projection mirrors what the lexical binder declares`() {
        val result = bindOf(
            """
            function f() {
                function zzzFn() { return 1; }
                class ZzzC {}
                enum ZzzE { A }
                namespace ZzzNs.Inner { export const v = 1; }
                declare global { interface ZzzG { p: number } }
                const zzzLocal = 1;
                return zzzLocal;
            }
            """,
        )
        assert(result.scopeValueNames == setOf("zzzFn", "ZzzC", "ZzzE", "ZzzNs"))
        // `ZzzG` is the interface INSIDE the `declare global` block, and it belongs there:
        // the CARRIER declares nothing (which is what `global` being absent above says),
        // while the block's own body reaches a fresh scope like any other module block.
        // This pin caught the round's own wrong expectation, which is what it is for.
        assert(result.scopeTypeNames == setOf("ZzzC", "ZzzE", "ZzzG"))
    }

    @Test
    fun `a namespace level type alias does not - the namespace scope aliases its exports`() {
        // It IS a syntactic candidate, and the binder is what refuses it: the namespace
        // scope aliases the merged `exports`, so `declareLexical` skips the name. That
        // refusal is a fact about the bind, not about the tree, which is why the decision
        // lives in `Binder.scopeDeclarations` and not in `indexSourceFile`.
        assert(parse("namespace N { type Inner = number; }").nestedScopeDecls.size == 1)
        val result = bindOf("namespace N { type Inner = number; }")
        assert(!result.declaresScopeEnum)
        assert(result.scopeTypeNames.isEmpty())
    }

    @Test
    fun `a type alias inside a function inside a namespace does reach a fresh scope`() {
        val result =
            bindOf("namespace N { export function f() { type T = number; let x: T = 1; return x; } }")
        assert(result.scopeTypeNames == setOf("T"))
    }

    @Test
    fun `the census skip fires and censuses nothing it passed over`() {
        // The positive control for the projection: `verifySkip` keeps walking every
        // file's scopes and counts every scope-space Enum/TypeAlias symbol found in a
        // file the skip would have passed over. A zero is evidence only beside a
        // non-zero skipped count — a skip that never fires reports zero for the wrong
        // reason (round 790).
        val savedVerify = LexDefer.verifySkip
        LexDefer.verifySkip = true
        try {
            diagnose(
                """
                // @Filename: plain.ts
                export type Top = string;
                export function g(): number { return 1; }
                // @Filename: nested.ts
                export function f(): number {
                    enum Body { X = 1 }
                    return Body.X;
                }
                """,
            )
            assert(LexDefer.skippedFiles > 0)
            assert(LexDefer.skipViolations == 0)
        } finally {
            LexDefer.verifySkip = savedVerify
        }
    }

    /**
     * (INC.52) The SECOND loop's projection — `computeAllEnumValues` walked every file's
     * whole symbol table, recursing through every namespace's `exports`, to find the
     * program's enums. `BinderResult.bindsEnum` answers that from the bind that already
     * happened, and on tsc's own 78 sources it skips 45 of them.
     *
     * The same positive control as the pin above, for the same reason: a zero violation
     * count is evidence only beside a non-zero skipped count.
     */
    @Test
    fun `the file-level enum skip fires and passes over no enum symbol`() {
        val savedVerify = LexDefer.verifySkip
        LexDefer.verifySkip = true
        try {
            diagnose(
                """
                // @Filename: noenum.ts
                export type Top = string;
                export function g(): number { return 1; }
                // @Filename: withenum.ts
                export const enum Direction { Up = 1, Down = 2 }
                export namespace Nested { export const enum Inner { A = 7 } }
                """,
            )
            assert(LexDefer.localsSkippedFiles > 0)
            assert(LexDefer.localsSkipViolations == 0)
        } finally {
            LexDefer.verifySkip = savedVerify
        }
    }

    /**
     * (INC.52) …and the VALUES still arrive, which is what the skip could break and the
     * counters above cannot see. A `const enum` member's value reaching a literal-typed
     * slot is the sharp signal: without it the member is not the literal `1` and the
     * assignment is an error.
     *
     * The NESTED case is the one a wrong predicate gets wrong — a projection that looked
     * only at a file's top-level statements would skip a file whose only enum lives in a
     * namespace, and the walk it replaces recurses into `exports` precisely for that.
     */
    @Test
    fun `enum values survive the file-level skip - top-level and nested`() {
        val diagnostics = diagnose(
            """
            // @Filename: noenum.ts
            export const unrelated: string = "x";
            // @Filename: e.ts
            export const enum Direction { Up = 1 }
            export namespace Nested { export const enum Inner { A = 7 } }
            // @Filename: use.ts
            import { Direction, Nested } from "./e";
            export const top: 1 = Direction.Up;
            export const nested: 7 = Nested.Inner.A;
            """,
        )
        assert(diagnostics.none { it.code == 2322 })
    }
}
