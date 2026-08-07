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
import kotlin.test.fail

/**
 * INV.4(c)(iii) batch 1: the spine-maintained unresolved-names NameScope.
 *
 * Every test compiles under the family's AUDIT mode: the LEGACY walk records
 * a scope fingerprint (has / hasLocalShadow / isTypeParam / hasType /
 * constraint-presence / inFunction / hasArguments / classContext) at every
 * [checkIdentifierResolved] / [checkTypeNameResolved] position, and the SPINE
 * maintenance records the same fingerprint at every Identifier enter. The
 * assertion: every legacy record has an IDENTICAL spine record — pinning that
 * the spine's scope chain reproduces the legacy walk's scope at every
 * emission position (the enabling contract for the emission-swap batches).
 */
class Inv4UnresolvedSpineScopeTest {

    /** Compile with the audit on; fail on any fingerprint divergence; return
     *  the number of compared positions. */
    private fun auditClean(
        source: String,
        directives: String = "// @strict: true",
        fileName: String = "t.ts",
        minCompared: Int = 1,
    ): Int {
        Checker.unresolvedAuditEnabled = true
        Checker.unresolvedAuditOld.clear()
        Checker.unresolvedAuditSpine.clear()
        try {
            diagnose(source, directives, fileName)
            val mismatches = ArrayList<String>()
            for ((key, oldFp) in Checker.unresolvedAuditOld) {
                val spineFp = Checker.unresolvedAuditSpine[key]
                if (spineFp == null) mismatches.add("$key legacy=$oldFp spine=MISSING")
                else if (spineFp != oldFp) mismatches.add("$key legacy=$oldFp spine=$spineFp")
            }
            if (mismatches.isNotEmpty()) {
                fail(
                    "spine unresolved-names scope diverged from the legacy walk at " +
                        "${mismatches.size} position(s):\n" + mismatches.sorted().joinToString("\n")
                )
            }
            val compared = Checker.unresolvedAuditOld.size
            if (compared < minCompared) {
                fail("audit compared only $compared positions (expected >= $minCompared) — hook inert")
            }
            return compared
        } finally {
            Checker.unresolvedAuditEnabled = false
            Checker.unresolvedAuditOld.clear()
            Checker.unresolvedAuditSpine.clear()
        }
    }

    @Test
    fun `kitchen sink audits with zero fingerprint mismatches`() {
        auditClean(
            """
            const fileConst = 1
            var hoistedTop = 2
            function fn<T extends object, U extends T>(p: T, q: U = p as U): T {
                let local: T = p
                for (let i = 0; i < 10; i++) { local = p; i }
                for (const el of [1, 2]) { el }
                for (const key in { a: 1 }) { key }
                try { throw local } catch (err) { err }
                while (fileConst > 1) { break }
                do { var hoisted = 1 } while (false)
                hoisted
                switch (fileConst) {
                    case hoistedTop: { let c = 2; c; break }
                    default: break
                }
                class Inner<V> extends Object { m(w: V): V { return w } }
                const fe = function self(): number { return fe ? 0 : 1 }
                const arrow = <A,>(a: A): A => a
                enum E { A = 1, B = A }
                interface I<W> { v: W; m(x: W): W; [k: string]: unknown }
                type Alias<X> = X | null
                const anon = class Named<Y> { n(z: Y): Y { return z } }
                const obj = {
                    meth(mp: number): number { return mp + hoisted },
                    get g() { return fileConst },
                    set s(sv: number) { sv },
                }
                return p
            }
            namespace Outer.Inner2 {
                export const nsv = 1
                export function nsf(): number { return nsv }
                const hidden = 2
                export namespace Deeper { export const dv = hidden }
            }
            declare namespace Amb { const av: number; function af(x: typeof av): void }
            const useNs = Outer.Inner2.nsf() + Outer.Inner2.Deeper.dv
            type Mapped<T> = { [K in keyof T]: K }
            type Cond<S> = S extends Array<infer El> ? El : S
            type FnT = <FT extends string>(a: FT, b: number) => FT
            type CtorT = new (a: number) => object
            type Lit = { m<LT>(a: LT): LT; (call: number): void; new (ctor: string): object }
            class WithMembers {
                static sProp = fileConst
                iProp: number = WithMembers.sProp
                constructor(public cp: number, ro: string) { this.iProp = cp; ro }
                meth<MT>(mp: MT): MT { return mp }
                get acc(): number { return this.iProp }
                set acc(v: number) { this.iProp = v }
                static { hoistedTop = 3 }
            }
            interface Ext extends I2 { more: number }
            interface I2 { base: string }
            label: { let inBlock = 1; inBlock }
            """,
            minCompared = 40,
        )
    }

    @Test
    fun `computed method name does not see the method's own params or TPs`() {
        // B98.r111: `[p]` / `[T2]` evaluate at class-definition time — the
        // method's own params/TPs are NOT in scope; the legacy walk checks the
        // computed name against the pre-population member scope and fires
        // TS2304. The lazy spine population must reproduce that.
        val n = auditClean(
            """
            class C {
                [p]<T2>(p: number): void { p; }
            }
            """,
        )
        if (n < 1) fail("expected computed-name comparisons")
        diagnose(
            """
            class C {
                [p]<T2>(p: number): void { p; }
            }
            """,
        ) should {
            have(any { it.code == 2304 && "'p'" in it.message })
        }
    }

    @Test
    fun `type parameter constraints do not see function parameters`() {
        // tsc evaluates TP constraints without params in scope: `x` in the
        // constraint is TS2304 even though a param `x` exists.
        auditClean(
            """
            function f<T extends typeof x>(x: number): T { return x as T }
            """,
        )
    }

    @Test
    fun `switch expression resolves in the outer scope not the clause scope`() {
        // No braces around the clause body — the `let` binds in the switch's
        // SHARED clause scope, which must NOT be visible to the subject.
        auditClean(
            """
            switch (w) { case 1: let w = 1; break; default: w; break }
            """,
        )
        diagnose("switch (w) { case 1: let w = 1; break; default: w; break }") should {
            have(any { it.code == 2304 && "'w'" in it.message })
        }
    }

    @Test
    fun `conditional type infer names scope only into the true branch`() {
        auditClean(
            """
            type Cond<S> = S extends Array<infer El> ? El : El
            """,
        )
        diagnose("type Cond<S> = S extends Array<infer El> ? El : El") should {
            have(any { it.code == 2304 && "'El'" in it.message })
        }
    }

    @Test
    fun `mapped type constraint is checked in the outer scope`() {
        // The TP being introduced is not in scope inside its own constraint
        // (CLAUDE.md MappedType gotcha) — `K in K` fires TS2304 on the
        // constraint K while the value position resolves.
        auditClean(
            """
            type M = { [K in K]: K }
            """,
        )
    }

    @Test
    fun `class and method decorators are checked in their legacy scopes`() {
        // Class decorators: OUTER scope (forEachChild visits them LAST — the
        // spine deactivates the class level). Method decorators: the member
        // scope BEFORE TPs/params register (the decoratorScope view) — `marg`
        // is a method param and must NOT resolve inside the decorator.
        auditClean(
            """
            declare function dec(x?: unknown): any
            @dec(fileLevel)
            class C {
                @dec(marg)
                m<T>(marg: T): T { return marg }
            }
            const fileLevel = 1
            """,
            directives = "// @strict: true\n// @experimentalDecorators: true",
        )
    }

    @Test
    fun `sub-ES2015 target hoists body vars into parameter defaults`() {
        auditClean(
            """
            function f(a = b) { var b = 1; return a + b }
            """,
            directives = "// @target: es5",
        )
    }

    @Test
    fun `checkJs file with typedef types audits clean`() {
        auditClean(
            """
            /** @typedef {number} Num */
            /** @param {Num} n */
            function f(n) { return n + missing }
            """,
            directives = "// @allowJs: true\n// @checkJs: true",
            fileName = "t.js",
        )
    }

    @Test
    fun `enum member initializers see sibling members`() {
        auditClean(
            """
            enum E { A = 1, B = A, C = B + outer }
            const outer = 1
            """,
        )
    }

    @Test
    fun `namespace bodies see exported members of merged blocks`() {
        auditClean(
            """
            namespace N { export const a = 1 }
            namespace N { export function f(): number { return a } }
            namespace M.Nested { export const x = 1 }
            namespace M { export namespace Nested { export const y = x } }
            """,
        )
    }

    @Test
    fun `object literal accessors and methods use their legacy scopes`() {
        auditClean(
            """
            const o = {
                m(mp: number) { return mp + missingInMethod },
                get g() { return missingInGet },
                set s(v: number) { v; missingInSet },
            }
            """,
        )
    }

    @Test
    fun `function expression self-name resolves inside its own body`() {
        auditClean(
            """
            const f = function self(n: number): number { return n > 0 ? self(n - 1) : 0 }
            """,
        )
    }

    @Test
    fun `NaN comparison shadow detection fingerprints agree`() {
        auditClean(
            """
            function t(NaN: number, x: number) { if (x === NaN) { x } }
            function u(y: number) { if (y === NaN) { y } }
            """,
        )
    }

    @Test
    fun `catch variable scopes over the catch block only`() {
        auditClean(
            """
            try { throw 1 } catch (err) { err } finally { }
            """,
        )
        diagnose("try { throw 1 } catch (err) { } err") should {
            have(any { it.code == 2304 && "'err'" in it.message })
        }
    }
}
