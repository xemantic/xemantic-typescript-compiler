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
 * (P18.138) (CHK.124) step 3 — THE JAVASCRIPT OBJECT-LITERAL EXPANDO HOST, and the
 * `Object.defineProperty` member.
 *
 * Step 1 gave a `FunctionDeclaration` host real members and step 2 a `const`-bound
 * function expression; both are TypeScript-only, because a JavaScript host's member set
 * is B419/B432/B433's territory. This step adds the JavaScript half that
 * `jsExpandoObjectDefineProperty.errors.txt` needs — an `{}`-initialized `var`/`let`/
 * `const` — and the declaration form that goes with it.
 *
 * **THE HOST PREDICATE IS tsgo's `IsExpandoInitializer` + `getInitializerSymbol`**, and
 * every boundary below was measured against `tools/tsgo-7.0.2/lib/tsc` rather than read
 * off the Go source: in a JavaScript file an object literal is a host when it is EMPTY
 * and initializes a declaration carrying no type annotation, under `var`, `let` AND
 * `const` alike (the `const` requirement the TypeScript arm has is dropped for a JS
 * declaration). A NON-empty literal is not a host — tsgo reports TS2339 at the write
 * itself — and neither is an annotated one.
 *
 * **THE MEMBER SET IS THE CONTAINER SCAN, WHICH IS ALSO tsgo's RULE.** A write inside an
 * `if` block declares; a write inside a nested `function` body or an IIFE does NOT, and
 * tsgo reports TS2339 at that write. `collectExpandoDecls` walks exactly that set, so
 * the two cannot disagree — which is what licenses the member-access firewall to TRUST
 * the table ((CHK.45): ALL-MISSING carries no witness of its own, and a complete table
 * is the witness).
 *
 * **`Object.defineProperty` IS A DECLARATION IN JAVASCRIPT ONLY.** The member's type is
 * tsgo's `getTypeFromPropertyDescriptor` — `value`, else the `get` function's single
 * signature's RETURN type, else the `set` function's single signature's FIRST PARAMETER
 * type, else `any` — and its `readonly`-ness is `isReadonlyAssignmentDeclaration`: with a
 * `value` present, readonly unless `writable` is the boolean literal `false`; with no
 * `value`, readonly exactly when there is no `set`.
 *
 * **RESIDUES, EACH MEASURED AND EACH A *MISSING* ROW RATHER THAN A WRONG ONE** — tsgo
 * reports and this compiler stays silent:
 *  * a JSDoc-`@type`-ANNOTATED host (a `@type {{}}` JSDoc comment above `var a = {}`),
 *    where tsgo still
 *    reports the write and the read against `'{}'`;
 *  * the expando CHAIN (`r.inner = {}` then `r.inner.deep = 1`), which tsgo treats as a
 *    nested host — refused here because a one-level model's table would be a strict
 *    SUBSET of tsgo's;
 *  * a JavaScript FUNCTION-EXPRESSION or CLASS-EXPRESSION host, and a host declared
 *    inside a function BODY (B83.5 — never bound, so there is no symbol to attach to);
 *  * a PRIMITIVE receiver (`p.expandoOk = 1; p.expandoOk.nope`, tsgo TS2339 on
 *    `'number'`), refused with its own measurement — see the `residue -` pins below;
 *  * and, under `noImplicitAny: false` ONLY, the ledger row itself, because our
 *    contextual typing does not reach a nested object literal inside a contextually
 *    typed one, so the `jsLiteral` flag is over-set for the descriptor's `value: {}`.
 */
class JsObjectLiteralExpandoTest {

    private val js =
        "// @strict: true\n// @allowJs: true\n// @checkJs: true\n// @target: es2015\n// @noEmit: true"
    private val jsLoose =
        "// @strict: false\n// @allowJs: true\n// @checkJs: true\n// @target: es2015\n// @noEmit: true"

    // NOTE: every fixture is spelled out in full rather than interpolated from a shared
    // prelude property. A MULTI-LINE interpolation defeats `trimIndent` — its continuation
    // lines carry no indentation of their own, so the common prefix becomes 0 and NOTHING
    // is stripped, and every column below would then read too high.

    // -------------------------------------------------- the ledger row, row for row

    @Test
    fun `a member absent on an object-literal expando member type reports`() {
        // tsgo: index.js(3,17): error TS2339: Property 'inspectedWindow' does not exist
        //       on type '{}'.
        val d = diagnose(
            """
            // @Filename: /index.js
            var chrome = {}
            Object.defineProperty(chrome, 'devtools', { value: {}, enumerable: true })
            chrome.devtools.inspectedWindow = {}
            chrome.missingDirect
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/index.js" && it.line == 3 &&
                it.character == 17 && it.length == 15 &&
                it.message == "Property 'inspectedWindow' does not exist on type '{}'."
        })
    }

    @Test
    fun `a member absent on the object-literal host itself reports with the readonly display`() {
        // tsgo: index.js(4,8): error TS2339: Property 'missingDirect' does not exist on
        //       type '{ readonly devtools: {}; }'.
        val d = diagnose(
            """
            // @Filename: /index.js
            var chrome = {}
            Object.defineProperty(chrome, 'devtools', { value: {}, enumerable: true })
            chrome.devtools.inspectedWindow = {}
            chrome.missingDirect
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/index.js" && it.line == 4 &&
                it.character == 8 && it.length == 13 &&
                it.message ==
                "Property 'missingDirect' does not exist on type '{ readonly devtools: {}; }'."
        })
    }

    @Test
    fun `the ledger fixture reports exactly two rows and each exactly once`() {
        val d = diagnose(
            """
            // @Filename: /index.js
            var chrome = {}
            Object.defineProperty(chrome, 'devtools', { value: {}, enumerable: true })
            chrome.devtools.inspectedWindow = {}
            chrome.missingDirect
            """,
            js,
        )
        assert(d.size == 2)
        assert(d.count { it.code == 2339 && it.line == 3 && it.character == 17 } == 1)
        assert(d.count { it.code == 2339 && it.line == 4 && it.character == 8 } == 1)
    }

    @Test
    fun `an absent member on an object-literal expando host is reported exactly once`() {
        // The double-emission guard this arc keeps finding: a VARIABLE host is in no
        // B431 candidate set (`spineExSetup` puts every `VariableStatement` name in its
        // `merged` exclusion) and is NOT marked in `expandoAttachedTypeIds`, so route (B)
        // must stay OPEN for it — and exactly one emitter may speak.
        val d = diagnose(
            """
            // @Filename: /index.js
            var host = {}
            host.known = 1
            host.zzzOnce
            host.zzzOnce
            """,
            js,
        )
        assert(d.count { it.code == 2339 && it.line == 3 } == 1)
        assert(d.count { it.code == 2339 && it.line == 4 } == 1)
        assert(d.size == 2)
    }

    // -------------------------------------------------- the descriptor, all six cells

    @Test
    fun `every Object defineProperty descriptor shape contributes tsgo's member and readonly-ness`() {
        // tsgo: index.js(8,6): error TS2339: Property 'zzzMissing' does not exist on type
        //       '{ readonly valProp: number; readonly getProp: string; setProp: any;
        //          gsProp: number; writableProp: number; readonly noValue: any; }'.
        val d = diagnose(
            """
            // @Filename: /index.js
            var host = {}
            Object.defineProperty(host, 'valProp', { value: 1 })
            Object.defineProperty(host, 'getProp', { get: function () { return "s" } })
            Object.defineProperty(host, 'setProp', { set: function (v) { } })
            Object.defineProperty(host, 'gsProp', { get: function () { return 1 }, set: function (v) { } })
            Object.defineProperty(host, 'writableProp', { value: 2, writable: true })
            Object.defineProperty(host, 'noValue', { enumerable: true })
            host.zzzMissing
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.line == 8 && it.character == 6 &&
                it.message ==
                "Property 'zzzMissing' does not exist on type '{ readonly valProp: number; " +
                "readonly getProp: string; setProp: any; gsProp: number; " +
                "writableProp: number; readonly noValue: any; }'."
        })
    }

    @Test
    fun `a readonly Object defineProperty member refuses a write and a writable one does not`() {
        // tsgo: index.js(3,4): error TS2540: Cannot assign to 'fixed' because it is a
        //       read-only property.  — and NOTHING for `wr.loose = 8`.
        val d = diagnose(
            """
            // @Filename: /index.js
            var ro = {}
            Object.defineProperty(ro, 'fixed', { value: 5 })
            ro.fixed = 6
            var wr = {}
            Object.defineProperty(wr, 'loose', { value: 7, writable: true })
            wr.loose = 8
            """,
            js,
        )
        assert(d.size == 1)
        assert(d.any {
            it.code == 2540 && it.line == 3 && it.character == 4 &&
                it.message == "Cannot assign to 'fixed' because it is a read-only property."
        })
    }

    @Test
    fun `an explicit writable false is readonly and writable true is not`() {
        // tsgo: (4,6) TS2540 for `host.ro = 9` and NOTHING for `host.rw = 9`, then
        // (6,6) against '{ readonly ro: number; rw: number; }'. The `writable` value is
        // read through its PROPERTY ASSIGNMENT's initializer, as tsgo does — its member
        // TYPE is the widened `boolean` by the time the descriptor has been typed, so
        // asking the symbol would make BOTH cells readonly.
        val d = diagnose(
            """
            // @Filename: /index.js
            var host = {}
            Object.defineProperty(host, 'ro', { value: 3, writable: false })
            Object.defineProperty(host, 'rw', { value: 4, writable: true })
            host.ro = 9
            host.rw = 9
            host.zzzW
            """,
            js,
        )
        assert(d.count { it.code == 2540 } == 1)
        assert(d.any {
            it.code == 2540 && it.line == 4 && it.character == 6 &&
                it.message == "Cannot assign to 'ro' because it is a read-only property."
        })
        assert(d.any {
            it.code == 2339 && it.line == 6 && it.character == 6 &&
                it.message ==
                "Property 'zzzW' does not exist on type '{ readonly ro: number; rw: number; }'."
        })
    }

    @Test
    fun `residue - a NUMERIC Object defineProperty name refuses the whole host`() {
        // tsgo: index.js(4,9): error TS2339: Property 'zzzNum' does not exist on type
        //       '{ readonly 0: number; readonly named: number; }'. tsgo names such a
        // member by the numeric value's CANONICAL string where this parser holds the
        // source text (round 934), so the collected set would be a strict SUBSET of
        // tsgo's — and an incomplete table is exactly what the firewall must not trust.
        // The host is marked UNDECIDABLE and the whole read stays silent.
        val d = diagnose(
            """
            // @Filename: /index.js
            var numName = {}
            Object.defineProperty(numName, 0, { value: 1 })
            Object.defineProperty(numName, 'named', { value: 2 })
            numName.zzzNum
            """,
            js,
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `a non-literal Object defineProperty name declares nothing`() {
        // tsgo declares NOTHING for a computed or variable name — measured, the later
        // read is reported against the members the LITERAL-named calls contributed.
        val d = diagnose(
            """
            // @Filename: /index.js
            var host = {}
            var nm = 'dyn'
            Object.defineProperty(host, nm, { value: 1 })
            Object.defineProperty(host, 'known', { value: 2 })
            host.dyn
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.line == 5 && it.character == 6 &&
                it.message == "Property 'dyn' does not exist on type '{ readonly known: number; }'."
        })
    }

    // -------------------------------------------------- the host predicate

    @Test
    fun `var let and const are all object-literal expando hosts in a javascript file`() {
        // tsgo: (3,7) '{ vp: number; }', (6,7) '{ lp: string; }', (9,7) '{ cp: boolean; }'
        val d = diagnose(
            """
            // @Filename: /index.js
            var vHost = {}
            vHost.vp = 1
            vHost.zzzV
            let lHost = {}
            lHost.lp = "s"
            lHost.zzzL
            const cHost = {}
            cHost.cp = true
            cHost.zzzC
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.line == 3 && it.character == 7 &&
                it.message == "Property 'zzzV' does not exist on type '{ vp: number; }'."
        })
        assert(d.any {
            it.code == 2339 && it.line == 6 && it.character == 7 &&
                it.message == "Property 'zzzL' does not exist on type '{ lp: string; }'."
        })
        assert(d.any {
            it.code == 2339 && it.line == 9 && it.character == 7 &&
                it.message == "Property 'zzzC' does not exist on type '{ cp: boolean; }'."
        })
    }

    @Test
    fun `a NON-empty object-literal initializer is not a host and its write is reported`() {
        // tsgo: (2,10) 'p' and (3,10) 'zzzNe', both on '{ k: number; }' — the write is an
        // error there precisely BECAUSE the literal is not an expando host.
        val d = diagnose(
            """
            // @Filename: /index.js
            var nonEmpty = { k: 1 }
            nonEmpty.p = 1
            nonEmpty.zzzNe
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.line == 2 && it.character == 10 &&
                it.message == "Property 'p' does not exist on type '{ k: number; }'."
        })
        assert(d.any {
            it.code == 2339 && it.line == 3 && it.character == 10 &&
                it.message == "Property 'zzzNe' does not exist on type '{ k: number; }'."
        })
    }

    @Test
    fun `an object-literal host with no collected write still has a complete empty table`() {
        // tsgo: (2,26) 'fromIife' and (3,10) 'zzzIife', both on '{}' — an IIFE's write
        // declares NOTHING, so the host's table is complete and EMPTY.
        val d = diagnose(
            """
            // @Filename: /index.js
            var iifeHost = {}
            ;(function () { iifeHost.fromIife = 1 })()
            iifeHost.zzzIife
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.line == 2 && it.character == 26 &&
                it.message == "Property 'fromIife' does not exist on type '{}'."
        })
        assert(d.any {
            it.code == 2339 && it.line == 3 && it.character == 10 &&
                it.message == "Property 'zzzIife' does not exist on type '{}'."
        })
    }

    @Test
    fun `a write inside a nested function declares nothing and a write inside an if block declares`() {
        // tsgo: (3,33) 'fromFn' on '{}' — a nested `function` body declares nothing —
        // while `if (1) { ifHost.fromIf = 1 }` DOES declare, so (6,8) names it.
        val d = diagnose(
            """
            // @Filename: /index.js
            var fnWriteHost = {}
            function writer() { fnWriteHost.fromFn = 1 }
            fnWriteHost.zzzFnWrite
            var ifHost = {}
            if (1) { ifHost.fromIf = 1 }
            ifHost.zzzIf
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.line == 2 && it.character == 33 &&
                it.message == "Property 'fromFn' does not exist on type '{}'."
        })
        assert(d.any {
            it.code == 2339 && it.line == 3 && it.character == 13 &&
                it.message == "Property 'zzzFnWrite' does not exist on type '{}'."
        })
        assert(d.any {
            it.code == 2339 && it.line == 6 && it.character == 8 &&
                it.message == "Property 'zzzIf' does not exist on type '{ fromIf: number; }'."
        })
    }

    @Test
    fun `an element-access write declares an object-literal expando member`() {
        // tsgo: index.js(3,7): error TS2339: Property 'zzzE' does not exist on type
        //       '{ q: number; }'.
        val d = diagnose(
            """
            // @Filename: /index.js
            var host = {}
            host["q"] = 4
            host.zzzE
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.line == 3 && it.character == 6 &&
                it.message == "Property 'zzzE' does not exist on type '{ q: number; }'."
        })
    }

    // -------------------------------------------------- noImplicitAny, tsgo's isJSLiteralType

    @Test
    fun `an object-literal host is silent under noImplicitAny false`() {
        // tsgo's `isJSLiteralType` is MEANINGLESS under `noImplicitAny` and suppresses a
        // missing member otherwise. Measured: with `@strict: false` tsgo reports NOTHING
        // for this fixture, and with `@strict: true` it reports the read.
        val loose = diagnose(
            """
            // @Filename: /index.js
            var h = {}
            h.p = 1
            h.zzzSuppressed
            """,
            jsLoose,
        )
        assert(loose.none { it.code == 2339 })
        val strict = diagnose(
            """
            // @Filename: /index.js
            var h = {}
            h.p = 1
            h.zzzSuppressed
            """,
            js,
        )
        assert(strict.any {
            it.code == 2339 && it.line == 3 && it.character == 3 &&
                it.message == "Property 'zzzSuppressed' does not exist on type '{ p: number; }'."
        })
    }

    // -------------------------------------------------- TypeScript is untouched

    @Test
    fun `negative control - Object defineProperty declares nothing in a typescript file`() {
        // tsgo: index.ts(4,17): error TS2339: Property 'dp' does not exist on type
        //       '{ (): void; plain: number; }'. Its `GetAssignmentDeclarationKind` gates
        // the whole `IsBindableObjectDefinePropertyCall` arm on `IsInJSFile`, so in a
        // `.ts` file that call declares NOTHING while the ordinary `f.plain = 2` write
        // still does — and the host here is a REAL (P18.137) TypeScript expando host, so
        // the member would attach if the JavaScript gate were dropped. An annotated host
        // makes this arm DEAD: `getTypeOfVariableOrProperty` returns at `decl.type`
        // several statements above the attachment.
        val d = diagnose(
            """
            // @Filename: /index.ts
            const f = () => {}
            Object.defineProperty(f, 'dp', { value: 1 })
            f.plain = 2
            const probe = f.dp
            """,
            "// @strict: true\n// @target: es2015\n// @noEmit: true",
        )
        assert(d.any {
            it.code == 2339 && it.line == 4 && it.character == 17 &&
                it.message ==
                "Property 'dp' does not exist on type '{ (): void; plain: number; }'."
        })
    }

    @Test
    fun `negative control - an empty object literal host in a typescript file declares nothing`() {
        // The JS-only host predicate: in TypeScript an `{}`-initialized `const` gains no
        // member from a later write, and tsgo agrees (it reports the write).
        val d = diagnose(
            """
            // @Filename: /index.ts
            const host = {}
            const probe = host
            """,
            "// @strict: true\n// @target: es2015\n// @noEmit: true",
        )
        assert(d.none { it.code == 2339 })
    }

    // -------------------------------------------------- residues, each measured

    @Test
    fun `residue - a JSDoc type-annotated object-literal host is not checked`() {
        // tsgo reports BOTH rows against '{}' — the annotation makes the declaration a
        // non-host, so `annotated.q = 1` is TS2339 at (3,11) and the read at (4,11).
        // Refused here: the annotated type is a TYPE LITERAL, which carries none of the
        // syntactic evidence `jsExpandoObjectAccessAdmitted` demands.
        val d = diagnose(
            """
            // @Filename: /index.js
            /** @type {{}} */
            var annotated = {}
            annotated.q = 1
            annotated.zzzAn
            """,
            js,
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `residue - the expando chain is refused rather than half-modelled`() {
        // tsgo: index.js(4,17): error TS2339: Property 'zzzChain' does not exist on type
        //       '{ deep: number; }'. The right-hand `{}` of `r.inner = {}` is itself an
        // expando host there; this round models one level, so its table would be a
        // strict SUBSET of tsgo's and (CHK.45) forbids trusting it.
        val d = diagnose(
            """
            // @Filename: /index.js
            var chainRoot = {}
            chainRoot.inner = {}
            chainRoot.inner.deep = 1
            chainRoot.inner.zzzChain
            """,
            js,
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `residue - a primitive receiver in a javascript file is still refused`() {
        // tsgo: index.js(3,17): error TS2339: Property 'nope' does not exist on type
        //       'number'. BUILT AND REVERTED: admitting a primitive receiver also emits
        // three shapes whose DISPLAY tsgo does not print — `const cs = "hi"; cs.zzzCs`
        // renders 'string' against tsgo's '"hi"', `const cn = 1` renders 'number'
        // against '1', and `var b = true` renders 'boolean' against 'true' — and all
        // three reproduce IDENTICALLY in a `.ts` file on this binary and on its parent,
        // so the blocker is a standing literal-display gap, not a JavaScript one.
        val d = diagnose(
            """
            // @Filename: /index.js
            var plain = {}
            plain.expandoOk = 1
            plain.expandoOk.nope
            """,
            js,
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `residue - a javascript function-expression host is B433's and is refused here`() {
        // tsgo: index.js(3,4): error TS2339: Property 'zzzMissC' does not exist on type
        //       '{ (): void; t: number; }'. `checkJsObjectDefinePropertyLocalFnReads`
        // (B433) owns a `const X = function(){}` + `Object.defineProperty(X, …)` host,
        // including the BODY-LOCAL one this model cannot reach at all (B83.5 leaves such
        // a declaration unbound, so there is no symbol to attach to), and admitting the
        // shape here would DOUBLE-EMIT with it.
        val d = diagnose(
            """
            // @Filename: /index.js
            var c3 = function () {}
            c3.t = 1
            c3.zzzMissC
            """,
            js,
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `residue - an object-literal host declared inside a function body is refused`() {
        // tsgo: index.js(4,8): error TS2339: Property 'zzzMissF' does not exist on type
        //       '{ p: number; }'. B83.5: a declaration nested in a body is never BOUND,
        // so `getTypeOfVariableOrProperty` never runs for it.
        val d = diagnose(
            """
            // @Filename: /index.js
            function blk() {
              var c6 = {}
              c6.p = 1
              c6.zzzMissF
            }
            """,
            js,
        )
        assert(d.none { it.code == 2339 })
    }
}
