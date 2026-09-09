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
 * (INV.0) step 8 — the invariants of the [CaptureRecorder] seam, one pin per
 * mechanism, each with a stated one-line ablation.
 *
 * ## Why every assertion here is a VALUE
 *
 * A capture that is ABSENT renders nothing and reports no error anywhere — the
 * (INC.2b) law, *"an answer that was never asked for is ABSENT, and an absent
 * capture renders nothing with no error"*. So "there is a capture at this span"
 * and "the build did not throw" both pass against a badly broken recorder. Every
 * pin below therefore names the exact member list, node kind, declaration
 * location or overload index it expects, and every fixture additionally asserts
 * that the population it measures is non-empty before it measures it.
 *
 * ## The ablation table — one line each, and each reddens exactly one pin here
 *
 * | pin | ablation inside `CaptureRecorder.kt` |
 * |---|---|
 * | `the member table applies deepest-wins at a colliding span` | in `typeCaptureRecordMembers`, `if (previous != null && !typeCaptureIsDescendantOf(node, previous)) return` -> `if (previous != null) return` |
 * | `the type table stays first-wins at the same colliding span` | in `typeCaptureRecord`, delete `if (span in typeCaptureResults) return` |
 * | `a this receiver inside a braced arrow answers the enclosing method's class` | in `typeCaptureVisit`, `checker.currentClassForThis = typeCaptureThisClass(node)` -> `= frame.classForThis` |
 * | `a private member is hidden from a receiver outside its class` | in `typeCaptureRecordMembers`, delete `if (typeCaptureMemberInaccessible(item, symbols, enclosingClass)) continue` |
 * | `a union receiver offers only the members every constituent has` | in `typeCaptureMembersOfType`'s Union arm, `if (alsoHere == null) entries.remove() else entry.value.addAll(alsoHere)` -> `if (alsoHere != null) entry.value.addAll(alsoHere)` |
 * | `an imported name's definition is the declaration and not the import` | make `typeCaptureFollowImportAlias`'s body `return symbol` |
 * | `the scope enumeration reads the binder table each level aliases` | in `typeCaptureScopeNames`, delete the `scope.existing?.let { … }` block |
 * | `signature help selects the overload with room for the caret's argument` | replace `typeCaptureActiveSignature`'s body with `return 0` |
 *
 * ## Where the expected values come from
 *
 * Every one of them was READ OFF the built compiler through its own LSP server
 * (`com.xemantic.typescript.compiler.lsp.XtscLspMainKt`, driven over stdio on this
 * fixture) and then CROSS-CHECKED against tsc 7.0.2's language server
 * (`tools/tsgo-7.0.2/lib/tsc --lsp -stdio`, the instrument round 924 introduced).
 * The two agree on every answer asserted here — the member lists, the hidden
 * `private`, the union intersection, the definition location and the active
 * overload — which is what makes these pins statements about TypeScript rather
 * than about this implementation.
 *
 * ONE measured divergence, deliberately NOT asserted as if it were correct: at
 * the dangling-`.` span, tsc hovers the RECEIVER (`const holder: { alpha: number;
 * beta: string; }`) where first-wins makes us answer the property access's `any`.
 * Pinning `"any"` would be a countdown of the kind CLAUDE.md forbids, so the
 * first-wins pin asserts WHICH NODE won ([CapturedType.kind]) and says nothing
 * about whether that node's type is the answer a hover should show.
 */
class CaptureRecorderSeamTest {

    private val libFile = "/work/lib.ts"
    private val mainFile = "/work/main.ts"
    private val dangleFile = "/work/dangle.ts"

    private val lib = """
        export interface Shape { readonly kind: string; area(): number }
        export function makeShape(k: string): Shape { return { kind: k, area: () => 1 } }
        export const libConst: number = 7;
    """.trimIndent() + "\n"

    /**
     * The main fixture. Every shape in it is here for one pin:
     *
     *  - `Alpha | Beta` — a union whose `shared` member is on both constituents and
     *    whose `onlyA` / `onlyB` are on one each, which is what separates the
     *    completion rule (intersect) from the definition rule (collect);
     *  - `over` — two overloads differing only in ARITY, so the caret's argument
     *    index alone decides which one has room;
     *  - `Widget` — a `private` member plus an ARROW body WITH BRACES, because an
     *    expression-bodied arrow pushes no cta frame at all (round 923) and would
     *    make the `this` pin measure the method's own frame instead;
     *  - `usesLib` — a function body holding a parameter, a local, and uses of three
     *    IMPORTED names, which is the scope enumeration's subject.
     */
    private val main = """
        import { Shape, makeShape, libConst } from "./lib.js";

        interface Alpha { shared: string; onlyA: number }
        interface Beta { shared: string; onlyB: boolean }
        declare const either: Alpha | Beta;
        const eitherShared = either.shared;

        declare function over(a: string): void;
        declare function over(a: string, b: number): void;
        declare function rest(head: string, ...tail: number[]): void;

        class Widget {
            private secret: number = 1;
            label: string = "w";
            run(): void {
                const cb = () => { const inArrow = this.label; return inArrow; };
                cb();
            }
        }

        function usesLib(param: number): void {
            const shp = makeShape("s");
            const n = libConst;
            over("a", 1);
            rest("h", 1, 2, 3);
            const w = new Widget();
            const lbl = w.label;
        }
    """.trimIndent() + "\n"

    /**
     * The SPAN-COLLISION fixture, and its exact bytes are the fixture.
     *
     * The file must END immediately after the `.`: a dangling dot at end of file
     * makes the parser synthesize a zero-width name, and the property access's
     * `end` — read after the one-token lookahead, which sees only end-of-file —
     * then equals its RECEIVER's, so `holder` and `holder.<nothing>` carry the same
     * raw `(pos, end)` pair. Round 917's entry records that `holder. ` / `holder.;`
     * / `holder.\n` all HIDE it, which is why this is built by concatenation rather
     * than by `trimIndent`, and why [collidingSpan] asserts the collision instead of
     * assuming it.
     */
    private val dangle = "export const holder = { alpha: 1, beta: \"b\" };\nholder."

    private fun vfs() = InMemoryVfs(
        mapOf(
            "/work/tsconfig.json" to
                """{"compilerOptions":{"strict":true,"target":"es2020","module":"esnext"}}""",
            libFile to lib,
            mainFile to main,
            dangleFile to dangle,
        ),
    )

    /**
     * The raw `(pos, end)` span of the ONE node of [kind] starting at [offset].
     *
     * Iterative, as every full-tree walk in this repo must be, and it asserts the
     * match count rather than the node — power-assert renders every subexpression,
     * and an AST node's `toString` is its whole subtree.
     *
     * `Node.end` overshoots by a token (round 910) and is used here purely as the
     * IDENTITY the capture matches on, exactly as the caller does.
     */
    private fun spanOfNodeAt(
        text: String,
        fileName: String,
        offset: Int,
        kind: String,
    ): TypeCaptureSpan {
        val file = Parser(text, fileName).parse()
        var pos = -1
        var end = -1
        var matches = 0
        val stack = ArrayList<Node>()
        stack.add(file)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            if (node.pos == offset && node.kind.name == kind) {
                pos = node.pos
                end = node.end
                matches++
            }
            forEachChild(node) { child -> stack.add(child) }
        }
        // A span the fixture cannot produce would make every assertion about it
        // vacuous, and a second node of the same kind at one offset would make the
        // answer depend on the walk order.
        assert(matches == 1)
        return TypeCaptureSpan(fileName, pos, end)
    }

    /** The offset of the `n`-th occurrence - 0-based - of [needle] in [text]. */
    private fun offsetOf(text: String, needle: String, occurrence: Int = 0): Int {
        var at = -1
        repeat(occurrence + 1) { at = text.indexOf(needle, at + 1) }
        assert(at >= 0)
        return at
    }

    /**
     * The span BOTH the receiver `holder` and the property access `holder.` carry.
     *
     * The equality is asserted rather than assumed: if the parser ever stops
     * colliding here, the two pins built on this fixture must fail loudly instead
     * of measuring two different spans and agreeing.
     */
    private fun collidingSpan(): TypeCaptureSpan {
        val at = offsetOf(dangle, "holder", occurrence = 1)
        val receiver = spanOfNodeAt(dangle, dangleFile, at, "Identifier")
        val access = spanOfNodeAt(dangle, dangleFile, at, "PropertyAccessExpression")
        assert(receiver == access)
        return receiver
    }

    private fun spanIn(text: String, fileName: String, needle: String, kind: String) =
        spanOfNodeAt(text, fileName, offsetOf(text, needle), kind)

    /** Every span this class asks about, resolved once. */
    private class Spans(
        val collision: TypeCaptureSpan,
        val unionReceiver: TypeCaptureSpan,
        val thisInArrow: TypeCaptureSpan,
        val widgetOutside: TypeCaptureSpan,
        val importedUse: TypeCaptureSpan,
        val bodyAnchor: TypeCaptureSpan,
        val overCall: TypeCaptureSpan,
    )

    private fun spans() = Spans(
        collision = collidingSpan(),
        // The RECEIVER of `either.shared`, which is what a completion names.
        unionReceiver = spanIn(main, mainFile, "either.shared", "Identifier"),
        // `this` is an ordinary Identifier in this parser - there is no
        // ThisExpression node - so the receiver span is the `this` token's.
        thisInArrow = spanIn(main, mainFile, "this.label", "Identifier"),
        widgetOutside = spanIn(main, mainFile, "w.label", "Identifier"),
        importedUse = spanIn(main, mainFile, "makeShape(\"s\")", "Identifier"),
        // An ordinary node inside `usesLib`'s body: the scope in force there is the
        // function's, which is all a free-name enumeration needs.
        bodyAnchor = spanOfNodeAt(
            main, mainFile, offsetOf(main, "= libConst") + 2, "Identifier",
        ),
        overCall = spanIn(main, mainFile, "over(\"a\", 1)", "CallExpression"),
    )

    /**
     * ONE build carrying every channel, so the pins measure the same program.
     *
     * The `activeArgument` is 1 — the caret sits in the SECOND argument of
     * `over("a", 1)`, which is the index that separates the two overloads.
     */
    private fun capture(): ProjectCompiler.Result {
        val s = spans()
        return ProjectCompiler(vfs()).build(
            "/work",
            noEmit = true,
            typeCapture = TypeCaptureRequest(
                spans = listOf(s.collision, s.importedUse),
                memberSpans = listOf(
                    s.collision, s.unionReceiver, s.thisInArrow, s.widgetOutside,
                ),
                scopeSpans = listOf(s.bodyAnchor),
                signatureSpans = listOf(
                    SignatureCaptureSpan(
                        mainFile, s.overCall.start, s.overCall.end, activeArgument = 1,
                    ),
                ),
            ),
        )
    }

    /** The member NAMES recorded at [span], which must have been recorded at all. */
    private fun membersAt(result: ProjectCompiler.Result, span: TypeCaptureSpan): List<String> {
        val recorded = result.capturedMembers.filter {
            it.fileName == span.fileName && it.start == span.start && it.end == span.end
        }
        // A member entry is written even when nothing was found, so exactly one
        // entry is the statement "this receiver was reached"; zero would make every
        // assertion below vacuous in the (INC.2b) direction.
        assert(recorded.size == 1)
        return recorded[0].members.map { it.name }
    }

    @Test
    fun `the member table applies deepest-wins at a colliding span`() {
        val span = collidingSpan()
        val names = membersAt(capture(), span)
        // Preorder reaches the property access FIRST, and its own type is the
        // member `""` of an object that has none. Plain first-wins would answer
        // with the members of `any`; the descendant rule lets `holder` overwrite it.
        assert(names == listOf("alpha", "beta"))
    }

    @Test
    fun `the type table stays first-wins at the same colliding span`() {
        val span = collidingSpan()
        val recorded = capture().capturedTypes.filter {
            it.fileName == span.fileName && it.start == span.start && it.end == span.end
        }
        assert(recorded.size == 1)
        val kind = recorded[0].kind
        // WHICH NODE won, not what its type is: the ancestor wrote first and keeps
        // the entry, where the member table one line up let the descendant take it.
        // (tsc 7.0.2 hovers the RECEIVER here — measured, and recorded in this
        // class's KDoc rather than asserted, because a pin on today's answer to a
        // question we get wrong is a countdown and not a guard.)
        assert(kind == "PropertyAccessExpression")
    }

    @Test
    fun `a this receiver inside a braced arrow answers the enclosing method's class`() {
        val names = membersAt(capture(), spans().thisInArrow)
        // A cta frame does not thread `this` — the frame an ARROW pushes carries
        // null in `classForThis` — so this list exists only because
        // `typeCaptureVisit` reconstructs the class by an ASCENT out of the node.
        // Reading it off the frame answers no members at all here.
        assert(names == listOf("label", "run", "secret"))
    }

    @Test
    fun `a private member is hidden from a receiver outside its class`() {
        val names = membersAt(capture(), spans().widgetOutside)
        // The same three members as the pin above, minus the one `usesLib` may not
        // write. The pair is the whole test: an accessibility filter that hid
        // nothing and one that hid everything would each pass one of them.
        assert(names == listOf("label", "run"))
    }

    @Test
    fun `a union receiver offers only the members every constituent has`() {
        val names = membersAt(capture(), spans().unionReceiver)
        // `onlyA` and `onlyB` are real declarations and are still not writable
        // through `Alpha | Beta`. This is deliberately NOT the rule the definition
        // channel uses for the same receiver.
        assert(names == listOf("shared"))
    }

    @Test
    fun `an imported name's definition is the declaration and not the import`() {
        val s = spans()
        val recorded = capture().capturedDefinitions.filter {
            it.fileName == mainFile && it.start == s.importedUse.start
        }
        assert(recorded.size == 1)
        val definition = recorded[0]
        val locations = definition.locations
        assert(locations.size == 1)
        val target = locations[0]
        val expectedStart = offsetOf(lib, "makeShape")
        // The DECLARATION in the other file, at its NAME — not the import specifier
        // that brought the name in, which is what an unfollowed alias answers.
        assert(definition.name == "makeShape")
        assert(target.fileName == libFile)
        assert(target.start == expectedStart)
        assert(target.length == "makeShape".length)
        assert(target.kind == "Identifier")
    }

    @Test
    fun `the scope enumeration reads the binder table each level aliases`() {
        val s = spans()
        val recorded = capture().capturedScopes.filter {
            it.fileName == mainFile && it.start == s.bodyAnchor.start
        }
        // The count is asserted through a local: this list holds every lib global,
        // and power-assert renders every subexpression of a failing assertion.
        val recordedCount = recorded.size
        assert(recordedCount == 1)
        val offered = recorded[0].names.map { it.name }
        // The whole list is ~2,250 lib globals, so the assertion is over a
        // PROJECTION whose every member is decided: two IMPORTED names and two
        // bindings of the enclosing function must be offered, and two locals of a
        // DIFFERENT function's body must not. Every one of the six was checked
        // against tsc 7.0.2's own completion list and agrees.
        val probes = listOf("cb", "inArrow", "libConst", "makeShape", "param", "shp")
        val visible = probes.filter { it in offered }
        // An import is bound by the MAIN binder, so it lives in the table each
        // lexical level ALIASES and in the scope-space leftovers of no level at
        // all: a `symbols`-only sweep offers no import and no file-level
        // declaration, and the merged globals do not carry a module's names
        // either — measured, `libConst` is offered in no OTHER file of this
        // program.
        assert(visible == listOf("libConst", "makeShape", "param", "shp"))
    }

    @Test
    fun `signature help selects the overload with room for the caret's argument`() {
        val s = spans()
        val recorded = capture().capturedSignatures.filter {
            it.fileName == mainFile && it.start == s.overCall.start
        }
        assert(recorded.size == 1)
        val captured = recorded[0]
        val labels = captured.signatures.map { it.label }
        // Every overload comes back in declaration order, and the caret is in
        // argument 1 — where only the second has room. Answering 0 unconditionally
        // is what a host shows when the selection rule is not consulted.
        assert(
            labels == listOf(
                "over(a: string): void",
                "over(a: string, b: number): void",
            ),
        )
        assert(captured.activeSignature == 1)
    }
}
