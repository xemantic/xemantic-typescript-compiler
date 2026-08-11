/*
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xemantic.typescript.compiler

/**
 * (WARM.18b) round 892 — the GENERIC form of round 869's `AnnScopeStack` and
 * round 891's `VarScopeStack`: a LIFO stack of `name -> V` scopes kept as ONE
 * live map plus an UNDO LOG, in place of a whole-map copy per push.
 *
 * Round 891 wrote the type-specific version because that family's map is handed
 * OUT by reference to legacy helpers that write into it, which forces a
 * `MutableMap` FACADE rather than a private map with a `put` method. The cta
 * LOCAL family (`currentLocalTypes` / `currentLocalDeclTypeNodes`) has exactly
 * the same shape at two different value types, so this class is that facade with
 * the value type as a parameter; round 891's specialisation is RETIRED onto it,
 * so the reverse-replay mechanism exists once rather than twice.
 *
 * ## The invariant
 *
 * At every moment [view] holds exactly what the copy chain would have held: the
 * entries of the enclosing scopes, overridden by this scope's own writes.
 * [push] records only a MARK; a write appends the key's pre-write state
 * (`null` = the key was ABSENT — unambiguous because `V` is non-null); [pop]
 * replays this frame's slice of the log in REVERSE, which is what makes a
 * repeated write to one key correct without any per-frame "already shadowed"
 * set: the last restore applied to a key is its FIRST record, i.e. the value
 * the frame inherited.
 *
 * ## What a caller must not do (round 869's condition, verbatim)
 *
 * Replaceable EXACTLY when the stack is strictly LIFO, no reader RETAINS the
 * map past its frame, and no reader depends on ITERATION ORDER. A write and a
 * removal are both fine — they are recorded. A [MutableMap.clear] is not, and
 * throws: it cannot be recorded in O(1), and a silent one would drop the
 * ENCLOSING scopes' entries with no way to restore them at the pop.
 *
 * @param onMutate run before every recorded mutation — the cta `localTypes`
 *   family passes the expression-memo epoch bump the `EpochMap` it replaces
 *   performed. Bumping is the SAFE direction: an extra bump can only make the
 *   probe memo MISS, where a missing one could make it serve a stale entry.
 * @param census the `FrontEnd.CP_*` family this stack reports its undo records
 *   under, or `-1` for none.
 */
internal class MapScopeStack<V : Any>(
    private val census: Int = -1,
    private val onMutate: (() -> Unit)? = null,
) {

    private val live = HashMap<String, V>()

    /** [undoKeys] size at each open scope's push — the start of its slice. */
    private val marks = ArrayList<Int>()

    private val undoKeys = ArrayList<String>()

    /** The pre-write value at [undoKeys]`[i]`; `null` = the key was ABSENT. */
    private val undoVals = ArrayList<V?>()

    /**
     * The live scope, as a `MutableMap` the legacy helpers can write through.
     * Reads go straight to the backing map; every mutation is recorded first.
     */
    val view: MutableMap<String, V> = View()

    /** Open scopes. */
    val depth: Int get() = marks.size

    /** Entries currently recorded by the undo log — the census's `undo` column. */
    val undoSize: Int get() = undoKeys.size

    /** Live entries — what the replaced map's `size` would have been. */
    val liveSize: Int get() = live.size

    /** Drop every scope and every entry. The per-file boundary. */
    fun reset() {
        live.clear()
        marks.clear()
        undoKeys.clear()
        undoVals.clear()
    }

    /** Open a scope. Costs one mark, never a copy. */
    fun push() {
        marks.add(undoKeys.size)
    }

    /**
     * Close the innermost scope, restoring every key it shadowed or introduced.
     * A no-op when no scope is open, matching the guarded pop it replaces.
     */
    fun pop() {
        if (marks.isEmpty()) return
        val mark = marks.removeAt(marks.size - 1)
        var i = undoKeys.size - 1
        while (i >= mark) {
            val k = undoKeys.removeAt(i)
            val v = undoVals.removeAt(i)
            if (v == null) live.remove(k) else live[k] = v
            i--
        }
    }

    private fun record(key: String) {
        onMutate?.invoke()
        // A write made while no scope is open belongs to the outermost (file
        // root) frame, which is dropped by [reset] rather than by a pop —
        // recording it would grow a log nothing ever replays.
        if (marks.isEmpty()) return
        undoKeys.add(key)
        undoVals.add(live[key])
        if (census >= 0) {
            FrontEnd.noteMut(census)
            FrontEnd.addUndo(census, 1)
        }
    }

    private inner class View : MutableMap<String, V> by live {

        override fun put(key: String, value: V): V? {
            record(key)
            return live.put(key, value)
        }

        override fun putAll(from: Map<out String, V>) {
            for ((k, v) in from) {
                record(k)
                live[k] = v
            }
        }

        override fun remove(key: String): V? {
            record(key)
            return live.remove(key)
        }

        override fun clear(): Unit = throw UnsupportedOperationException(
            "MapScopeStack.view.clear(): a scope's map is dropped by pop(), never cleared"
        )
    }
}

/**
 * (WARM.18b) round 892 — [MapScopeStack]'s set twin, for the cta LOCAL family's
 * `shadowedNames` component.
 *
 * A set needs one bit per record where a map needs a value: `true` = the element
 * was PRESENT before this scope touched it. The reverse replay and the
 * no-scope-open rule are [MapScopeStack]'s, unchanged.
 */
internal class SetScopeStack(
    private val census: Int = -1,
) {

    private val live = HashSet<String>()
    private val marks = ArrayList<Int>()
    private val undoKeys = ArrayList<String>()
    private val undoHad = ArrayList<Boolean>()

    val view: MutableSet<String> = View()

    val depth: Int get() = marks.size
    val undoSize: Int get() = undoKeys.size
    val liveSize: Int get() = live.size

    fun reset() {
        live.clear()
        marks.clear()
        undoKeys.clear()
        undoHad.clear()
    }

    fun push() {
        marks.add(undoKeys.size)
    }

    fun pop() {
        if (marks.isEmpty()) return
        val mark = marks.removeAt(marks.size - 1)
        var i = undoKeys.size - 1
        while (i >= mark) {
            val k = undoKeys.removeAt(i)
            val had = undoHad.removeAt(i)
            if (had) live.add(k) else live.remove(k)
            i--
        }
    }

    private fun record(key: String) {
        if (marks.isEmpty()) return
        undoKeys.add(key)
        undoHad.add(key in live)
        if (census >= 0) {
            FrontEnd.noteMut(census)
            FrontEnd.addUndo(census, 1)
        }
    }

    private inner class View : MutableSet<String> by live {

        override fun add(element: String): Boolean {
            record(element)
            return live.add(element)
        }

        override fun addAll(elements: Collection<String>): Boolean {
            var changed = false
            for (e in elements) {
                record(e)
                if (live.add(e)) changed = true
            }
            return changed
        }

        override fun remove(element: String): Boolean {
            record(element)
            return live.remove(element)
        }

        override fun clear(): Unit = throw UnsupportedOperationException(
            "SetScopeStack.view.clear(): a scope's set is dropped by pop(), never cleared"
        )
    }
}
