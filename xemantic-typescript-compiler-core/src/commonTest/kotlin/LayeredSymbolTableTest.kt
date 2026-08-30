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
 * (INC.61) The overlay answers, and ITERATES, exactly what the copy it replaces did.
 *
 * `Checker.buildPerFileScopes` used to build one flat table per file by copying every
 * lib global into it — `files x libGlobals` insertions. On the SAME 2,401-file program,
 * adding `dom` to `lib` (which is what an ordinary project gets by default) took that
 * pass from 13.5 ms to **175.6 ms**, 70% of the floor pass table and ~46% of the
 * per-keystroke floor. The overlay makes the shared half cost O(globals) once.
 *
 * **ORDER is the load-bearing half and it is not obvious.** Three consumers iterate a
 * per-file scope, and round 776's law is that a name order which stops being a function
 * of the program turns a cost counter into a property of the box. The copy inserted the
 * base first and then the file's own locals, and a `LinkedHashMap` keeps a key's
 * ORIGINAL position when a later `put` merely overwrites its value — so a shadowing
 * local must appear at the BASE's position carrying the OWN value, not at the end. That
 * is the one thing an implementation gets wrong by accident, so it is pinned against the
 * reference construction itself rather than against a hand-written expectation.
 */
class LayeredSymbolTableTest {

    private fun sym(name: String) = Symbol(name = name, flags = SymbolFlags.None)

    private val base = symbolTable().also {
        it["Array"] = sym("Array")
        it["shared"] = sym("base-shared")
        it["Promise"] = sym("Promise")
    }

    private val own = symbolTable().also {
        it["shared"] = sym("own-shared")
        it["local"] = sym("local")
    }

    /** Exactly what `buildPerFileScopes` used to construct, for the same inputs. */
    private fun theCopy(): SymbolTable = symbolTable().also {
        for ((k, v) in base) it[k] = v
        for ((k, v) in own) it[k] = v
    }

    @Test
    fun `every lookup answers what the copy answers`() {
        val layered = LayeredSymbolTable(base, own)
        val copy = theCopy()
        for (name in copy.keys + own.keys + setOf("absent")) {
            assert(layered[name] === copy[name])
            assert(layered.containsKey(name) == copy.containsKey(name))
        }
        assert(layered.size == copy.size)
        assert(!layered.isEmpty())
    }

    @Test
    fun `iteration order is entry-for-entry the copy's`() {
        val layered = LayeredSymbolTable(base, own)
        val copy = theCopy()
        assert(layered.keys.toList() == copy.keys.toList())
        assert(layered.entries.map { it.key to it.value }.toList() == copy.entries.map { it.key to it.value }.toList())
        assert(layered.values.toList() == copy.values.toList())
        // The discriminating element: a shadowed name keeps the BASE's position and
        // carries the OWN value. Appending it instead would pass every lookup pin.
        assert(layered.keys.toList()[1] == "shared")
        assert(layered["shared"]!!.name == "own-shared")
    }

    @Test
    fun `an empty overlay is the base, and an empty base is the overlay`() {
        assert(LayeredSymbolTable(base, symbolTable()).entries.map { it.key }.toList() == base.keys.toList())
        assert(LayeredSymbolTable(symbolTable(), own).entries.map { it.key }.toList() == own.keys.toList())
        assert(LayeredSymbolTable(symbolTable(), symbolTable()).isEmpty())
    }

    @Test
    fun `negative control - a write is refused loudly rather than dropped`() {
        // A view that silently accepted a write would DROP it, and the symptom would be
        // a name resolving to a foreign module's local — silent, and far from here.
        val layered = LayeredSymbolTable(base, own)
        var threw = false
        try {
            layered["new"] = sym("new")
        } catch (_: UnsupportedOperationException) {
            threw = true
        }
        assert(threw)
    }
}
