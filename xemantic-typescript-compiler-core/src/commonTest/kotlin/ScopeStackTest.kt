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
 * (WARM.18)/(WARM.18b) rounds 891-892 — pins [MapScopeStack] and [SetScopeStack],
 * the undo-log replacements for the `CtaFrame.varTypes` and `CtaFrame`
 * localTypes/declNodes/shadowedNames per-push whole-map copies.
 *
 * **Why the pins are HERE and not through a compile.** The property being
 * replaced is "a scope push behaves like a copy", and its failure mode is the
 * one CLAUDE.md names as the reason round 869 stopped short of this family: a
 * wrong scope does not crash, it silently resolves a name to an OUTER binding.
 * The `varTypes` map is a legacy STRING side-channel consulted by the
 * assignment/return string checks, so a leak surfaces (if at all) as a missing
 * or spurious TS2322 in a shape no fixture here contains. The mechanism is
 * therefore pinned directly, exactly as [AnnScopeStackTest] pins its twin.
 *
 * Every value is a bare word so the power-assert diagram stays readable.
 */
class ScopeStackTest {

    private fun at(s: MapScopeStack<String>, k: String): String = s.view[k] ?: "absent"

    @Test
    fun `an inner scope sees the outer scope's entries`() {
        val s = MapScopeStack<String>()
        s.push()
        s.view["a"] = "string"
        s.push()
        assert(at(s, "a") == "string")
        assert(s.depth == 2)
    }

    @Test
    fun `a write in an inner scope is gone after the scope closes`() {
        val s = MapScopeStack<String>()
        s.push()
        s.push()
        s.view["b"] = "number"
        assert(at(s, "b") == "number")
        s.pop()
        assert(at(s, "b") == "absent")
        assert(s.depth == 1)
    }

    @Test
    fun `a shadowed key comes back with the OUTER value - not absent`() {
        val s = MapScopeStack<String>()
        s.push()
        s.view["c"] = "string"
        s.push()
        s.view["c"] = "number"
        assert(at(s, "c") == "number")
        s.pop()
        assert(at(s, "c") == "string")
    }

    @Test
    fun `repeated writes to one key in one scope still restore the inherited value`() {
        val s = MapScopeStack<String>()
        s.push()
        s.view["d"] = "string"
        s.push()
        s.view["d"] = "number"
        s.view["d"] = "boolean"
        s.view["d"] = "any"
        assert(at(s, "d") == "any")
        s.pop()
        assert(at(s, "d") == "string")
    }

    @Test
    fun `nesting three deep unwinds one level at a time`() {
        val s = MapScopeStack<String>()
        s.push(); s.view["e"] = "string"
        s.push(); s.view["e"] = "number"
        s.push(); s.view["e"] = "boolean"
        assert(at(s, "e") == "boolean")
        s.pop()
        assert(at(s, "e") == "number")
        s.pop()
        assert(at(s, "e") == "string")
        assert(s.depth == 1)
    }

    /**
     * The `extraVarTypes` path — a method/ctor/set-accessor frame seeds its
     * scope with the class's `this.X` entries through `putAll`, so `putAll` has
     * to be recorded exactly as `put` is or those seeds outlive the member.
     */
    @Test
    fun `putAll is recorded and is undone at the pop`() {
        val s = MapScopeStack<String>()
        s.push()
        s.view["f"] = "string"
        s.push()
        s.view.putAll(mapOf("f" to "number", "g" to "boolean"))
        assert(at(s, "f") == "number")
        assert(at(s, "g") == "boolean")
        s.pop()
        assert(at(s, "f") == "string")
        assert(at(s, "g") == "absent")
    }

    @Test
    fun `a remove inside a scope is undone at the pop`() {
        val s = MapScopeStack<String>()
        s.push()
        s.view["h"] = "string"
        s.push()
        s.view.remove("h")
        assert(at(s, "h") == "absent")
        s.pop()
        assert(at(s, "h") == "string")
    }

    /**
     * The SHARE case. A bare then-statement narrowing frame opens no scope, so
     * a declaration inside it persists into the enclosing scope — which is what
     * the map it replaced did, because the legacy dispatch passed the map
     * straight through instead of copying it.
     */
    @Test
    fun `a write made with no scope open persists - the shared frame`() {
        val s = MapScopeStack<String>()
        s.push()
        s.view["i"] = "string"
        // no push here: this is the SHARED frame
        s.view["i"] = "number"
        s.view["j"] = "boolean"
        s.pop()
        assert(at(s, "i") == "absent")
        assert(at(s, "j") == "absent")
    }

    @Test
    fun `the file root's writes survive every pop and are dropped only by reset`() {
        val s = MapScopeStack<String>()
        s.view["k"] = "string"
        s.push()
        s.view["k"] = "number"
        s.pop()
        assert(at(s, "k") == "string")
        assert(s.depth == 0)
        assert(s.undoSize == 0)
        s.reset()
        assert(at(s, "k") == "absent")
        assert(s.liveSize == 0)
    }

    @Test
    fun `reset drops every scope and every entry`() {
        val s = MapScopeStack<String>()
        s.push(); s.view["l"] = "string"
        s.push(); s.view["m"] = "number"
        s.reset()
        assert(s.depth == 0)
        assert(s.undoSize == 0)
        assert(s.liveSize == 0)
        assert(at(s, "l") == "absent")
    }

    /**
     * The legacy nested walk does `varTypes.toMutableMap()` and then writes into
     * the result. That must still be a DETACHED copy — the whole scheme rests on
     * the view being the live map, so the one place a genuine snapshot is taken
     * is the place most likely to be broken by a delegation mistake.
     */
    @Test
    fun `toMutableMap on the view is a detached snapshot`() {
        val s = MapScopeStack<String>()
        s.push()
        s.view["n"] = "string"
        val snapshot = s.view.toMutableMap()
        snapshot["n"] = "number"
        snapshot["o"] = "boolean"
        assert(at(s, "n") == "string")
        assert(at(s, "o") == "absent")
        assert(snapshot["n"] == "number")
        assert(s.undoSize == 1)
    }

    @Test
    fun `the undo log costs one record per write and not one per entry`() {
        val s = MapScopeStack<String>()
        s.push()
        for (i in 0 until 40) s.view["v$i"] = "string"
        s.push()
        s.view["v0"] = "number"
        assert(s.liveSize == 40)
        assert(s.undoSize == 41)
        s.pop()
        assert(at(s, "v0") == "string")
        assert(s.undoSize == 40)
    }

    /** A pop with nothing open is the guarded no-op the spine's leave relies on. */
    @Test
    fun `popping with no scope open is a no-op`() {
        val s = MapScopeStack<String>()
        s.view["p"] = "string"
        s.pop()
        assert(at(s, "p") == "string")
        assert(s.depth == 0)
    }

    // ---- (WARM.18b) round 892 — the value type is a PARAMETER, and the epoch
    // hook. The cta LOCAL family instantiates the same class at `Type` and at
    // `TypeNode`, so nothing about the mechanism may depend on `String`.

    @Test
    fun `the map stack shadows and restores at a non-String value type`() {
        val s = MapScopeStack<Int>()
        s.push()
        s.view["k"] = 1
        s.push()
        s.view["k"] = 2
        assert(s.view["k"] == 2)
        s.pop()
        assert(s.view["k"] == 1)
    }

    /** The bump the replaced `EpochMap` performed — every RECORDED mutation,
     *  and the root writes too (a superset, which is the safe direction). */
    @Test
    fun `onMutate fires once per mutation including at the file root`() {
        var bumps = 0
        val s = MapScopeStack<Int>(onMutate = { bumps++ })
        s.view["root"] = 0
        assert(bumps == 1)
        s.push()
        s.view["a"] = 1
        s.view["a"] = 2
        s.view.remove("a")
        assert(bumps == 4)
        s.pop()
        assert(bumps == 4)
    }

    /** A clear cannot be recorded in O(1) and would drop the ENCLOSING scopes'
     *  entries with no way to restore them at the pop. */
    @Test
    fun `clear on the map view throws instead of silently dropping outer scopes`() {
        val s = MapScopeStack<String>()
        s.push()
        s.view["a"] = "string"
        var threw = false
        try { s.view.clear() } catch (_: UnsupportedOperationException) { threw = true }
        assert(threw)
        assert(at(s, "a") == "string")
    }

    // ---- (WARM.18b) round 892 — [SetScopeStack], the `shadowedNames` twin.
    // A set records one BIT per touched element (present-before / absent-before)
    // where the map records a value, so the restore is a different statement and
    // needs its own pins.

    @Test
    fun `an inner set scope sees the outer scope's elements`() {
        val s = SetScopeStack()
        s.push()
        s.view.add("outer")
        s.push()
        assert("outer" in s.view)
        assert(s.depth == 2)
    }

    @Test
    fun `an add in an inner set scope is gone after the scope closes`() {
        val s = SetScopeStack()
        s.push()
        s.push()
        s.view.add("inner")
        assert("inner" in s.view)
        s.pop()
        assert("inner" !in s.view)
    }

    /** The mirror of the map stack's shadowing pin: a REMOVE of an inherited
     *  element must put it BACK, not leave it absent. */
    @Test
    fun `removing an inherited element is undone at the pop`() {
        val s = SetScopeStack()
        s.push()
        s.view.add("kept")
        s.push()
        s.view.remove("kept")
        assert("kept" !in s.view)
        s.pop()
        assert("kept" in s.view)
    }

    /** The pin the REVERSE replay exists for: with a forward replay the last
     *  restore applied would be this scope's own state, not the inherited one. */
    @Test
    fun `repeated adds and removes in one set scope restore the inherited state`() {
        val s = SetScopeStack()
        s.push()
        s.view.add("k")
        s.push()
        s.view.remove("k")
        s.view.add("k")
        s.view.remove("k")
        s.pop()
        assert("k" in s.view)
        assert(s.undoSize == 1)
    }

    @Test
    fun `addAll is recorded per element and is undone at the pop`() {
        val s = SetScopeStack()
        s.push()
        s.view.add("a")
        s.push()
        s.view.addAll(listOf("a", "b", "c"))
        assert(s.liveSize == 3)
        s.pop()
        assert(s.liveSize == 1)
        assert("a" in s.view)
    }

    @Test
    fun `a set add made with no scope open persists - the shared frame`() {
        val s = SetScopeStack()
        s.view.add("root")
        s.push()
        s.pop()
        assert("root" in s.view)
        assert(s.undoSize == 0)
    }

    @Test
    fun `reset drops every set scope and every element`() {
        val s = SetScopeStack()
        s.view.add("root")
        s.push()
        s.view.add("inner")
        s.reset()
        assert(s.liveSize == 0)
        assert(s.depth == 0)
        assert(s.undoSize == 0)
    }

    @Test
    fun `popping the set stack with no scope open is a no-op`() {
        val s = SetScopeStack()
        s.view.add("p")
        s.pop()
        assert("p" in s.view)
        assert(s.depth == 0)
    }

    @Test
    fun `clear on the set view throws instead of silently dropping outer scopes`() {
        val s = SetScopeStack()
        s.push()
        s.view.add("a")
        var threw = false
        try { s.view.clear() } catch (_: UnsupportedOperationException) { threw = true }
        assert(threw)
        assert("a" in s.view)
    }
}
