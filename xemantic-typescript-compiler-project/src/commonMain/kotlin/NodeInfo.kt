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

package com.xemantic.typescript.compiler.project

/**
 * What the compiler's syntax tree says is at one position: a VALUE, carrying no
 * reference to the tree it was read from.
 *
 * ## Why a value and not the node
 *
 * `Project.nodeAt` — the internal half of this — hands back the `Node` itself, and
 * this class exists precisely so the PUBLIC half does not. Three reasons, in
 * increasing order of how expensive the mistake would be to undo:
 *
 * 1. **A node goes stale silently.** It belongs to one parse of one buffer; the
 *    next `Project.updateFile` replaces that parse and nothing on the node says so.
 *    A host would cache it beside its own editor state and describe the previous
 *    keystroke. A value cannot go stale — it is already only a claim about the text
 *    at the moment it was asked for, and its [start]/[end] are offsets a caller can
 *    re-validate against its own buffer.
 * 2. **`Node` is a large, sealed, mutable-in-places hierarchy** whose members are
 *    the compiler's own working state (`NodeBase.nodeId`/`parent`/`kindId` are
 *    `var`s stamped by the indexer, and a `data class` node's `hashCode` recurses
 *    its whole subtree — CLAUDE.md, round 471). Publishing it would make every
 *    parser refactor an API break, and would hand a host an object it is unsafe to
 *    use as a map key.
 * 3. **Whether the embedding API publishes `Node` / `Symbol` / `Type` at all is a
 *    DELIBERATELY OPEN question** — the queue item after this one is where it gets
 *    decided, together with what a quick-info answer should look like. Publishing
 *    the node here would decide it by accident, in the direction that cannot be
 *    walked back.
 *
 * ## The span
 *
 * [start] and [end] are 0-based character offsets into the file's text, half-open:
 * the node covers `start until end`. That is the same convention `Diagnostic.start`
 * and this compiler's `Node.pos` use, so an offset from either is directly
 * comparable — but note that [end] is NOT the node's `Node.end`, which in this
 * compiler runs past the node (see `SourceIndex`); it is the end of the node's own
 * last token.
 *
 * ## The chain
 *
 * [ancestorKinds] runs OUTWARDS from the node: the immediate parent first, the
 * source file last. It is the descent path the lookup took, not a re-derivation
 * from `NodeBase.parent`, so it is correct even for a tree whose `parent` stamps
 * are absent. For the source file itself it is empty, which is the only way a
 * caller can recognise "your offset is in no node but the file".
 */
public data class NodeInfo(
    /**
     * The node's syntax kind, as `SyntaxKind`'s own name — `"Identifier"`,
     * `"VariableDeclaration"`, `"JsxOpeningElement"`.
     *
     * A STRING rather than the `SyntaxKind` enum, for the same reason the node
     * itself is not published: the enum is the parser's internal vocabulary, it has
     * hundreds of entries, and pinning this API to it would make adding a syntax
     * kind a breaking change for every consumer's exhaustive `when`. The names are
     * tsc's own AST vocabulary, so a host bridging to `tsserver`/LSP recognises
     * them without a table.
     */
    public val kind: String,
    /** 0-based offset of the node's first character. */
    public val start: Int,
    /** 0-based offset one past the node's last character. */
    public val end: Int,
    /** Enclosing kinds, immediate parent first, source file last. */
    public val ancestorKinds: List<String>,
)
