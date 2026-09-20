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
 * ## Why a value, when `Project.nodeAt` hands back the node
 *
 * **(INV.2b) decided the open question this class used to be waiting on, and
 * decided it the other way**: `Project.nodeAt` is PUBLIC, because `TypeOracle`'s
 * whole surface is addressed by node and a host that cannot obtain one cannot ask
 * the oracle anything. This class is no longer "the public half that withholds the
 * node" — it is the answer for a host that wants to know what the text IS without
 * taking on an AST, and both reasons for preferring it survive the change intact:
 *
 * 1. **A node goes stale silently.** It belongs to one parse of one buffer; the
 *    next `Project.updateFile` replaces that parse and nothing on the node says so.
 *    A host would cache it beside its own editor state and describe the previous
 *    keystroke. A value cannot go stale — it is already only a claim about the text
 *    at the moment it was asked for, and its [start]/[end] are offsets a caller can
 *    re-validate against its own buffer. (An oracle query is safe from this for a
 *    different reason: the edit CLOSES the oracle, so the stale node is refused
 *    rather than answered.)
 * 2. **`Node` is a large, sealed, mutable-in-places hierarchy** whose members are
 *    the compiler's own working state (`NodeBase.nodeId`/`parent`/`kindId` are
 *    `var`s stamped by the indexer, and a `data class` node's `hashCode` recurses
 *    its whole subtree — CLAUDE.md, round 471). A host that holds nodes takes on a
 *    dependency on the parser's shape and an object that is unsafe as a map key; a
 *    host that holds [NodeInfo] takes on neither.
 *
 * So: reach for the node when you are going to ASK THE ORACLE about it, and for
 * this when you are going to render, compare or store it.
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
     * A STRING rather than the `SyntaxKind` enum, for reason 2 above applied to the
     * enum: it is the parser's internal vocabulary, it has
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
