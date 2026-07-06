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

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Round 423 — the four TS2366 shapes left by round 422's `requiredUnionDiscriminantKeys`:
 * exhaustiveness needs the switch RECEIVER guard-narrowed (and typed) BEFORE the union
 * walk, mirroring tsc (which computes exhaustiveness over the discriminant's NARROWED
 * type). The four mechanisms, each pinned with a negative control (`.errors.txt` corpus
 * tests are disabled — the controls ARE the FP-safety gate):
 *
 * 1. a local `const target = getAssignmentTarget(node)` receiver types from the callee's
 *    declared return annotation, and `if (!target) return;` drops the `undefined` member
 *    via flow narrowing (tsc's `getAssignmentTargetKind`);
 * 2. a `node: Node` param narrowed DOWN to a union by an early-return type guard
 *    (`if (!isNamedEvaluationSource(node)) return false;` — tsc's `isNamedEvaluation`);
 * 3. an OPTIONAL enum property (`newLine?: NewLineKind`) contributes a required
 *    `@undefined` key instead of bailing — `case undefined:` then completes the cover
 *    (tsc's `getNewLineCharacter`);
 * 4. an indexed-access param annotation `LiteralToken["kind"]` unions the alias's
 *    members' `kind` keys (tsc's `createLiteralLikeNode`).
 */
class GuardNarrowedSwitchReceiverTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile(
            "// @strict: true\n" + source.trimIndent(), "t.ts",
        ).diagnostics

    private fun assertNoImplicitReturnCodes(d: List<Diagnostic>, what: String) {
        assertTrue(
            d.none { it.code == 2366 || it.code == 7030 || it.code == 2355 },
            "$what must count as terminating; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    private fun assertFires2366(d: List<Diagnostic>, what: String) {
        assertTrue(
            d.any { it.code == 2366 },
            "$what must keep TS2366; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    private val nodeDecls = """
        const enum K { Binary, Prefix, Postfix, ForIn, ForOf, PropAssign, ExportAssign }
        interface Node { readonly kind: K; readonly flags: number; }
        interface Binary extends Node { readonly kind: K.Binary; op: number; }
        interface Prefix extends Node { readonly kind: K.Prefix; }
        interface Postfix extends Node { readonly kind: K.Postfix; }
        interface ForIn extends Node { readonly kind: K.ForIn; }
        interface ForOf extends Node { readonly kind: K.ForOf; }
        type ForInOrOf = ForIn | ForOf;
        type AssignmentTarget = Binary | Prefix | Postfix | ForInOrOf;
        function getTarget(node: Node): AssignmentTarget | undefined {
            return node.flags > 0 ? (node as Binary) : undefined;
        }
    """

    @Test
    fun `local const from call return annotation plus nullish guard is exhaustive`() {
        val d = diags(
            """
            $nodeDecls
            export function f(node: Node): number {
                const target = getTarget(node);
                if (!target) {
                    return 0;
                }
                switch (target.kind) {
                    case K.Binary: return 1;
                    case K.Prefix:
                    case K.Postfix: return 2;
                    case K.ForIn:
                    case K.ForOf: return 3;
                }
            }
            """,
        )
        assertNoImplicitReturnCodes(d, "guarded const-from-call exhaustive switch")
    }

    @Test
    fun `missing member keeps TS2366 for the guarded const receiver`() {
        val d = diags(
            """
            $nodeDecls
            export function f(node: Node): number {
                const target = getTarget(node);
                if (!target) {
                    return 0;
                }
                switch (target.kind) {
                    case K.Binary: return 1;
                    case K.Prefix:
                    case K.Postfix: return 2;
                    case K.ForIn: return 3;
                }
            }
            """,
        )
        assertFires2366(d, "guarded const receiver with an uncovered ForOf member")
    }

    @Test
    fun `unguarded const receiver keeps TS2366`() {
        // Without the `if (!target) return` guard the undefined member survives the
        // narrowing walk → the union contains a non-object → conservative bail.
        val d = diags(
            """
            $nodeDecls
            export function f(node: Node): number {
                const target = getTarget(node);
                switch (target!.kind) {
                    case K.Binary: return 1;
                    case K.Prefix:
                    case K.Postfix: return 2;
                    case K.ForIn:
                    case K.ForOf: return 3;
                }
            }
            """,
        )
        assertFires2366(d, "unguarded (only `!`-asserted) const receiver")
    }

    @Test
    fun `early-return type guard narrows a param receiver down to the union`() {
        val d = diags(
            """
            $nodeDecls
            interface PropAssign extends Node { readonly kind: K.PropAssign; }
            interface ExportAssign extends Node { readonly kind: K.ExportAssign; }
            type Source =
                | PropAssign & { readonly extra: number; }
                | Binary & { readonly leftName: string; }
                | ExportAssign;
            function isSource(node: Node): node is Source {
                return node.flags === 1;
            }
            export function f(node: Node): boolean {
                if (!isSource(node)) return false;
                switch (node.kind) {
                    case K.PropAssign: return true;
                    case K.Binary: return false;
                    case K.ExportAssign: return true;
                }
            }
            """,
        )
        assertNoImplicitReturnCodes(d, "type-guard-narrowed param exhaustive switch")
    }

    @Test
    fun `type-guard-narrowed union with an uncovered member keeps TS2366`() {
        val d = diags(
            """
            $nodeDecls
            interface PropAssign extends Node { readonly kind: K.PropAssign; }
            interface ExportAssign extends Node { readonly kind: K.ExportAssign; }
            type Source =
                | PropAssign & { readonly extra: number; }
                | Binary & { readonly leftName: string; }
                | ExportAssign;
            function isSource(node: Node): node is Source {
                return node.flags === 1;
            }
            export function f(node: Node): boolean {
                if (!isSource(node)) return false;
                switch (node.kind) {
                    case K.PropAssign: return true;
                    case K.Binary: return false;
                }
            }
            """,
        )
        assertFires2366(d, "type-guard-narrowed union with uncovered ExportAssign")
    }

    @Test
    fun `optional enum property with case undefined is exhaustive`() {
        val d = diags(
            """
            const enum NewLineKind { CarriageReturnLineFeed = 0, LineFeed = 1 }
            interface CompilerOptions { newLine?: NewLineKind; strict?: boolean; }
            interface PrinterOptions { newLine?: NewLineKind; }
            export function f(options: CompilerOptions | PrinterOptions): string {
                switch (options.newLine) {
                    case NewLineKind.CarriageReturnLineFeed: return "\r\n";
                    case NewLineKind.LineFeed:
                    case undefined: return "\n";
                }
            }
            """,
        )
        assertNoImplicitReturnCodes(d, "optional enum property covered incl. `case undefined`")
    }

    @Test
    fun `optional enum property without case undefined keeps TS2366`() {
        val d = diags(
            """
            const enum NewLineKind { CarriageReturnLineFeed = 0, LineFeed = 1 }
            interface CompilerOptions { newLine?: NewLineKind; strict?: boolean; }
            interface PrinterOptions { newLine?: NewLineKind; }
            export function f(options: CompilerOptions | PrinterOptions): string {
                switch (options.newLine) {
                    case NewLineKind.CarriageReturnLineFeed: return "\r\n";
                    case NewLineKind.LineFeed: return "\n";
                }
            }
            """,
        )
        assertFires2366(d, "optional enum property with undefined uncovered")
    }

    @Test
    fun `indexed-access param annotation unions the alias members kind keys`() {
        val d = diags(
            """
            const enum K { Numeric, BigIntLit, Str, JsxText, JsxTextAll }
            interface Node { readonly kind: K; }
            interface NumericLiteral extends Node { readonly kind: K.Numeric; }
            interface BigIntLiteral extends Node { readonly kind: K.BigIntLit; }
            interface StringLiteral extends Node { readonly kind: K.Str; }
            interface JsxText extends Node { readonly kind: K.JsxText; }
            type LiteralToken = NumericLiteral | BigIntLiteral | StringLiteral | JsxText;
            function mk(kind: K): number { return kind; }
            export function f(kind: LiteralToken["kind"] | K.JsxTextAll): number {
                switch (kind) {
                    case K.Numeric: return mk(kind);
                    case K.BigIntLit: return mk(kind);
                    case K.Str: return mk(kind);
                    case K.JsxText: return mk(kind);
                    case K.JsxTextAll: return mk(kind);
                }
            }
            """,
        )
        assertNoImplicitReturnCodes(d, "indexed-access `LiteralToken[\"kind\"]` exhaustive switch")
    }

    @Test
    fun `indexed-access annotation with an uncovered case keeps TS2366`() {
        val d = diags(
            """
            const enum K { Numeric, BigIntLit, Str, JsxText, JsxTextAll }
            interface Node { readonly kind: K; }
            interface NumericLiteral extends Node { readonly kind: K.Numeric; }
            interface BigIntLiteral extends Node { readonly kind: K.BigIntLit; }
            interface StringLiteral extends Node { readonly kind: K.Str; }
            interface JsxText extends Node { readonly kind: K.JsxText; }
            type LiteralToken = NumericLiteral | BigIntLiteral | StringLiteral | JsxText;
            function mk(kind: K): number { return kind; }
            export function f(kind: LiteralToken["kind"] | K.JsxTextAll): number {
                switch (kind) {
                    case K.Numeric: return mk(kind);
                    case K.BigIntLit: return mk(kind);
                    case K.Str: return mk(kind);
                    case K.JsxText: return mk(kind);
                }
            }
            """,
        )
        assertFires2366(d, "indexed-access annotation with uncovered JsxTextAll")
    }

    @Test
    fun `reassigned let receiver after the guard still suppresses TS2366 like tsc`() {
        // tsc computes switch exhaustiveness over the NON-NULLISH part of the
        // discriminant and flags the possibly-undefined ACCESS separately (TS18048)
        // — `switch (target.kind)` over a re-widened `AssignmentTarget | undefined`
        // covering every kind draws no TS2366 in tsc. Pin that we agree on the
        // TS2366 verdict. (The TS18048 on the access is a separate, known M3.4
        // emitter gap — the round-422 assignment-washing note.)
        val d = diags(
            """
            $nodeDecls
            export function f(node: Node): number {
                let target = getTarget(node);
                if (!target) {
                    return 0;
                }
                target = getTarget(node);
                switch (target.kind) {
                    case K.Binary: return 1;
                    case K.Prefix:
                    case K.Postfix: return 2;
                    case K.ForIn:
                    case K.ForOf: return 3;
                }
            }
            """,
        )
        assertNoImplicitReturnCodes(d, "kind-exhaustive switch over a reassigned receiver")
    }
}
