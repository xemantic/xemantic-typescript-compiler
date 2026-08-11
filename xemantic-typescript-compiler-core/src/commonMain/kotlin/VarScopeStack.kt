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
 * (WARM.18) round 891 — a LIFO stack of `name -> declared-type STRING` scopes
 * that keeps ONE live map and an UNDO LOG, in place of the whole-map copy every
 * `CtaFrame` push used to perform.
 *
 * This is [AnnScopeStack]'s shape (round 869) applied to the family round 869
 * measured and deliberately did not take. Two things make it a different piece
 * of code rather than a second instance of the same class:
 *
 * - the map is handed OUT, by reference, to ~15 legacy helper call sites
 *   (`checkVarDeclAssignability`, `checkAssignmentExpression`,
 *   `checkReturnAssignability`, `walkFunctionBodiesInExpr`,
 *   `ctaTypeParamsIntoLocals`), which WRITE into it. So the scope cannot be a
 *   private map with a `put` method — it has to be a `MutableMap` FACADE whose
 *   `put`/`putAll`/`remove` route through the undo log. That is [view];
 *   every frame holds the same instance.
 * - a `CtaFrame` does not always copy. A BARE (non-`Block`) then-statement
 *   narrowing frame deliberately SHARES its parent's map, because the legacy
 *   dispatch it reproduces passed the map straight through. Sharing is exactly
 *   "open no scope", so it is expressed by the frame's `varScoped` flag rather
 *   than by a different map object — which is what makes the two cases one
 *   mechanism instead of two.
 *
 * ## Why
 *
 * The census (`--frontEnd`, compiler profile, warm; identical on every run
 * since round 869):
 *
 * | | pushes | entries copied | mean | max | writes |
 * | --- | ---: | ---: | ---: | ---: | ---: |
 * | `CtaFrame.varTypes` | 30,433 | 1,145,523 | 37.6 | 100 | 2,564+ |
 *
 * i.e. **1.15 M entries copied to serve a few thousand writes**. A copy costs
 * O(size) and an undo log costs O(writes); the two are EXACTLY equivalent for a
 * stack that is strictly LIFO and whose readers neither retain the map nor
 * depend on its iteration ORDER. All of those hold here and each is pinned in
 * `VarScopeStackTest`. (The `writes` column above is the PRE-change census and
 * is an under-count: it hooked two of the three write paths. The undo column
 * this class reports counts all of them, because the facade is the only way in.)
 *
 * ## The invariant
 *
 * At every moment [view] holds exactly what the copy chain would have held: the
 * entries of the enclosing scopes, overridden by this scope's own writes.
 * [push] records only a MARK; a write appends the key's pre-write state
 * (`null` = the key was ABSENT, unambiguous because a value is a non-null
 * `String`); [pop] replays this frame's slice of the log in REVERSE, which is
 * what makes a repeated write to one key correct without any per-frame "already
 * shadowed" set — the last restore applied to a key is its first record, i.e.
 * the value the frame inherited.
 *
 * ## What a caller must not do
 *
 * - Retain [view] past the frame that produced it. It is the LIVE map, not a
 *   snapshot. Every consumer reads it synchronously inside one spine node's
 *   dispatch; `toMutableMap()` on it (which the legacy nested walk does) still
 *   produces a genuine detached copy, so that path is unaffected.
 * - Iterate it and depend on the order. The replaced map was a `LinkedHashMap`
 *   (`toMutableMap()`), so order WAS observable in principle; a grep of all 217
 *   `varTypes` references finds no `.keys`/`.values`/`.entries`/`.forEach`/
 *   `.iterator`/`.sorted` reader, which is what licenses the plain `HashMap`
 *   here (round 483's rule, applied in the direction it is usually applied).
 * - Leave a frame unpopped across a file. [reset] at the file boundary is what
 *   makes that harmless — and it is the only thing that does, because a frame
 *   abandoned by an exception between `push` and the spine's leave would
 *   otherwise persist its writes into the enclosing scope.
 */
internal class VarScopeStack {

    private val live = HashMap<String, String>()

    /** [undoKeys] size at each open frame's push — the start of its slice. */
    private val marks = ArrayList<Int>()

    private val undoKeys = ArrayList<String>()

    /** The pre-write value at [undoKeys]`[i]`; `null` = the key was ABSENT. */
    private val undoVals = ArrayList<String?>()

    /**
     * The live scope, as a `MutableMap` the legacy helpers can write through.
     * Reads go straight to the backing map; every mutation is recorded first.
     */
    val view: MutableMap<String, String> = View()

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
        // A write made while no scope is open belongs to the file-root frame,
        // which is dropped by [reset] rather than by a pop — recording it would
        // grow a log nothing ever replays.
        if (marks.isEmpty()) return
        undoKeys.add(key)
        undoVals.add(live[key])
        FrontEnd.noteMut(FrontEnd.CP_CTA_VAR)
        FrontEnd.addUndo(FrontEnd.CP_CTA_VAR, 1)
    }

    private inner class View : MutableMap<String, String> by live {

        override fun put(key: String, value: String): String? {
            record(key)
            return live.put(key, value)
        }

        override fun putAll(from: Map<out String, String>) {
            for ((k, v) in from) {
                record(k)
                live[k] = v
            }
        }

        override fun remove(key: String): String? {
            record(key)
            return live.remove(key)
        }

        /**
         * Unsupported on purpose. No `varTypes` reader clears the map today, and
         * a `clear` cannot be recorded in O(1) — a silent one would drop the
         * enclosing scopes' entries with no way to restore them at the pop.
         */
        override fun clear(): Unit = throw UnsupportedOperationException(
            "VarScopeStack.view.clear(): a scope's map is dropped by pop(), never cleared"
        )
    }
}
