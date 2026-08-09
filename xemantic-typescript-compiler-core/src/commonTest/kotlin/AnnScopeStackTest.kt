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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (WARM.16) round 869 — pins [AnnScopeStack], the undo-log replacement for the
 * two annotation-scope families' per-push whole-map copy.
 *
 * **Why the pins are HERE and not through a compile.** The property being
 * replaced is "a scope push behaves like a copy": a write inside a scope must
 * be invisible after the scope closes, and a key the scope shadowed must come
 * back with its OUTER value, not be dropped. Both failures are silent
 * downstream — the two consumers (`spread2698CheckOperand` / `rest2700Check`
 * and the `pddu*` family) read the map to decide whether to emit TS2698 /
 * TS2700 / one TS2345 shape, so a leaked annotation shows up (if at all) as a
 * missing or spurious diagnostic in a file no fixture here contains. Round 868
 * made `buildStarExportIndex` a directly-callable function for exactly this
 * reason; this is the same move.
 *
 * **Every assertion is over strings and ints**, never over a `TypeNode` — the
 * power-assert diagram renders every subexpression, and an AST node renders its
 * whole subtree (CLAUDE.md). [tag] is what keeps the diagram readable.
 */
class AnnScopeStackTest {

    /** Distinguishable annotation values whose rendering is one word. */
    private val str = KeywordTypeNode(SyntaxKind.StringKeyword)
    private val num = KeywordTypeNode(SyntaxKind.NumberKeyword)
    private val bool = KeywordTypeNode(SyntaxKind.BooleanKeyword)
    private val any = KeywordTypeNode(SyntaxKind.AnyKeyword)

    /** Owners are compared by IDENTITY only, so any distinct node will do. */
    private fun owner() = KeywordTypeNode(SyntaxKind.VoidKeyword)

    /** The value under [k], as a bare word — see the class doc. */
    private fun tag(s: AnnScopeStack, k: String): String =
        (s.view[k] as? KeywordTypeNode)?.kind?.toString() ?: "absent"

    @Test
    fun `an inner scope sees the outer scope's entries`() {
        val s = AnnScopeStack()
        s.push(owner())
        s.put("a", str)
        s.push(owner())
        assert(tag(s, "a") == "StringKeyword")
        assert(s.depth == 2)
    }

    @Test
    fun `a write in an inner scope is gone after the scope closes`() {
        val s = AnnScopeStack()
        val root = owner()
        s.push(root)
        val inner = owner()
        s.push(inner)
        s.put("b", num)
        assert(tag(s, "b") == "NumberKeyword")
        s.pop()
        assert(tag(s, "b") == "absent")
        assert(s.topOwner() === root)
    }

    @Test
    fun `a shadowed key comes back with the OUTER value, not absent`() {
        val s = AnnScopeStack()
        s.push(owner())
        s.put("c", str)
        s.push(owner())
        s.put("c", num)
        assert(tag(s, "c") == "NumberKeyword")
        s.pop()
        assert(tag(s, "c") == "StringKeyword")
    }

    @Test
    fun `repeated writes to one key in one scope still restore the inherited value`() {
        val s = AnnScopeStack()
        s.push(owner())
        s.put("d", str)
        s.push(owner())
        s.put("d", num)
        s.put("d", bool)
        s.put("d", any)
        assert(tag(s, "d") == "AnyKeyword")
        s.pop()
        assert(tag(s, "d") == "StringKeyword")
    }

    @Test
    fun `nesting three deep unwinds one level at a time`() {
        val s = AnnScopeStack()
        s.push(owner()); s.put("e", str)
        s.push(owner()); s.put("e", num)
        s.push(owner()); s.put("e", bool)
        assert(tag(s, "e") == "BooleanKeyword")
        s.pop()
        assert(tag(s, "e") == "NumberKeyword")
        s.pop()
        assert(tag(s, "e") == "StringKeyword")
        s.pop()
        assert(tag(s, "e") == "absent")
        assert(s.depth == 0)
    }

    @Test
    fun `the undo log records one entry per write and releases it at the pop`() {
        val s = AnnScopeStack()
        s.push(owner())
        s.put("f", str)
        assert(s.undoSize == 1)
        s.push(owner())
        assert(s.undoSize == 1)
        s.put("g", num)
        s.put("h", bool)
        assert(s.undoSize == 3)
        s.pop()
        assert(s.undoSize == 1)
    }

    @Test
    fun `a sibling scope does not inherit its predecessor's writes`() {
        val s = AnnScopeStack()
        s.push(owner())
        s.push(owner())
        s.put("i", str)
        s.pop()
        s.push(owner())
        assert(tag(s, "i") == "absent")
    }

    @Test
    fun `negative control - a write with no open scope is dropped`() {
        val s = AnnScopeStack()
        s.put("j", str)
        assert(tag(s, "j") == "absent")
        assert(s.depth == 0)
        assert(s.undoSize == 0)
    }

    @Test
    fun `negative control - popping with no open scope is a no-op`() {
        val s = AnnScopeStack()
        s.pop()
        assert(s.depth == 0)
        s.push(owner())
        s.put("k", str)
        s.pop()
        s.pop()
        assert(s.depth == 0)
        assert(tag(s, "k") == "absent")
    }

    @Test
    fun `reset drops every scope and every entry`() {
        val s = AnnScopeStack()
        s.push(owner()); s.put("l", str)
        s.push(owner()); s.put("l", num)
        s.reset()
        assert(s.depth == 0)
        assert(s.undoSize == 0)
        assert(tag(s, "l") == "absent")
        assert(s.topOwner() == null)
    }

    @Test
    fun `topOwner identifies the innermost open scope so a pop loop terminates`() {
        val s = AnnScopeStack()
        val a = owner()
        val b = owner()
        s.push(a)
        s.push(b)
        s.push(b)
        var popped = 0
        while (s.topOwner() === b) { s.pop(); popped++ }
        assert(popped == 2)
        assert(s.topOwner() === a)
    }
}
