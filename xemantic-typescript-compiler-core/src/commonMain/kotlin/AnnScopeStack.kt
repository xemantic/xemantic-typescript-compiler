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
 * (WARM.16) round 869 — a LIFO stack of `name -> annotation TypeNode` scopes
 * that keeps ONE live map and an UNDO LOG, in place of a whole-map copy per
 * scope push.
 *
 * ## Why
 *
 * Round 868's leaf profile put `HashMap.putMapEntries` at 5.4% of warm
 * compile-thread samples INCLUSIVE, with the check spine's frame bookkeeping as
 * its callers, and left it unpriced. Round 869's census says what the profile
 * could not:
 *
 * | family | pushes | entries copied | mean | writes |
 * | --- | ---: | ---: | ---: | ---: |
 * | `spineOs` annotation frames | 34,155 | 1,841,284 | 53.9 | 17,600 |
 * | `spinePd` annotation frames | 21,674 | 1,247,050 | 57.5 | 16,854 |
 *
 * i.e. **3.09 M entries copied to serve 34,454 writes — 1.1%**. That is round
 * 801's produced-versus-consumed test in the setting where it decides
 * something: a copy costs O(size) and an undo log costs O(writes), and the two
 * are EXACTLY equivalent for a stack that is strictly LIFO, never removes a
 * key, and whose readers neither mutate nor retain the map. All three hold for
 * these two families and each is pinned below.
 *
 * Amplification (round 759, no timestamp pair anywhere — at these sizes one
 * probe boundary would exceed the quantity) prices the two families at
 * **129.7 ms = 1.74% of a warm rebuild** [1.46-2.02%].
 *
 * ## The invariant
 *
 * At every moment the live map holds exactly what the copy chain would have
 * held: the entries of the enclosing scopes, overridden by this scope's own
 * writes. [push] records only a MARK into the undo log; [put] appends the key's
 * pre-write state (`null` = the key was absent, which is unambiguous because a
 * `TypeNode` value is never null); [pop] replays this frame's slice of the log
 * in REVERSE, which is what makes a repeated write to one key correct without
 * any per-frame "already shadowed" set — the last restore applied to a key is
 * its first record, i.e. the value the frame inherited.
 *
 * ## What a caller must not do
 *
 * - Retain [view] past the frame that produced it. It is the LIVE map, not a
 *   snapshot; the whole point is that there is no snapshot. Every consumer in
 *   the checker reads it synchronously inside one spine node's handler.
 * - Write through [view]. It is typed `Map`, so this is compiler-enforced.
 * - Pop a frame that was never pushed, or leave one unpopped across a file.
 *   [reset] at a file boundary is what makes the second harmless.
 *
 * A write made while NO frame is pushed is DROPPED, deliberately: the code this
 * replaces obtained its map as `frames.lastOrNull()?.anns ?: HashMap()`, so
 * such a write went into a throwaway. Keeping the drop keeps that behaviour
 * exact rather than newly persisting an annotation at file scope.
 */
internal class AnnScopeStack {

    private val live = HashMap<String, TypeNode>()

    /** The node that owns each open frame, innermost last. */
    private val owners = ArrayList<Node>()

    /** [undoKeys] size at each frame's push — the start of that frame's slice. */
    private val marks = ArrayList<Int>()

    private val undoKeys = ArrayList<String>()

    /** The pre-write value at [undoKeys]`[i]`; `null` = the key was ABSENT. */
    private val undoVals = ArrayList<TypeNode?>()

    /** The live scope view — reads only, valid until the next [push] or [pop]. */
    val view: Map<String, TypeNode> get() = live

    /** Open frames. */
    val depth: Int get() = owners.size

    /** Entries currently recorded by the undo log — the census's `undo` column. */
    val undoSize: Int get() = undoKeys.size

    /** Drop every frame and every entry. The per-file boundary. */
    fun reset() {
        live.clear()
        owners.clear()
        marks.clear()
        undoKeys.clear()
        undoVals.clear()
    }

    /** Open a scope owned by [owner]. Costs one mark, never a copy. */
    fun push(owner: Node) {
        owners.add(owner)
        marks.add(undoKeys.size)
    }

    /** The innermost open frame's owner, or null when none is open. */
    fun topOwner(): Node? = if (owners.isEmpty()) null else owners[owners.size - 1]

    /**
     * Record [name] -> [type] in the innermost open scope. A no-op when no frame
     * is open (see the class doc — this reproduces the replaced code's
     * throwaway-map write exactly).
     */
    fun put(name: String, type: TypeNode) {
        if (owners.isEmpty()) return
        undoKeys.add(name)
        undoVals.add(live[name])
        live[name] = type
    }

    /**
     * Close the innermost frame, restoring every key it shadowed. A no-op when
     * no frame is open, matching the `while (top.owner === node)` pop loops that
     * called it.
     */
    fun pop() {
        if (owners.isEmpty()) return
        owners.removeAt(owners.size - 1)
        val mark = marks.removeAt(marks.size - 1)
        var i = undoKeys.size - 1
        while (i >= mark) {
            val k = undoKeys.removeAt(i)
            val v = undoVals.removeAt(i)
            if (v == null) live.remove(k) else live[k] = v
            i--
        }
    }
}
