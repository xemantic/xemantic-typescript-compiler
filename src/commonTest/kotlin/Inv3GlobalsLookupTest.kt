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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * INV.3(a) (round 500): the `globals`-lookup instrumentation must be strictly
 * OBSERVATIONAL (enabling it may never change diagnostics; a disabled run
 * records nothing), and the classification must sort lookups into the
 * per-file visibility classes the INV.3 migration is planned from:
 *
 *  - a LIB or SCRIPT-FILE name → TRUE_GLOBAL (survives conflation retirement);
 *  - a name declared by BOTH a module file and a script file → SHARED
 *    (presence legit, merged symbol polluted);
 *  - a name declared ONLY by module files, looked up from a DIFFERENT file →
 *    CONFLATED or UNSCOPED (the migration worklist proper);
 *  - the accounting invariant: total == sum of all classes.
 *
 * Also pins [InstrumentedSymbolTable]'s contract: get/containsKey report to
 * the hook (hit AND miss), everything else — including iteration order —
 * delegates to the LinkedHashMap backing unchanged.
 */
class Inv3GlobalsLookupTest {

    /** Module file a.ts leaks `leakedVar`/`Dual` into the merged globals;
     *  module file b.ts consumes the leak bare (unimported — only the
     *  conflation resolves it); script file c.ts contributes TRUE globals
     *  (`scriptGlobal`) and the script side of the SHARED name `Dual`. */
    private val probeSource = """
        // @filename: c.ts
        const scriptGlobal = 7;
        interface Dual { a: number }

        // @filename: a.ts
        export const leakedVar = { p: 1 };
        export interface Dual { b: string }

        // @filename: b.ts
        export const consume = leakedVar.p;
        const viaAnnotation: number = leakedVar.p;
        export const s = scriptGlobal + 1;
        export const d: Dual = { a: 1 };
        export const nums: Array<number> = [1];
    """

    @Test
    fun `instrumentation on and off produce identical diagnostics and the on-run classifies`() {
        PassTiming.enabled = false
        val off = diagnose(probeSource)
        PassTiming.reset()
        PassTiming.enabled = true
        val on = try {
            diagnose(probeSource)
        } finally {
            PassTiming.enabled = false
        }
        assertEquals(off, on, "diagnostics must be byte-identical with instrumentation on")

        assertTrue(PassTiming.globalsLookups > 0, "the check must consult globals")
        val sum = PassTiming.globalsMisses + PassTiming.globalsTrueGlobalHits +
            PassTiming.globalsSharedHits + PassTiming.globalsOwnLocalHits +
            PassTiming.globalsConflatedHits + PassTiming.globalsUnscopedHits
        assertEquals(PassTiming.globalsLookups, sum, "every lookup lands in exactly one class")

        // Lib names (Array/…) and the script-file `scriptGlobal` classify TRUE_GLOBAL.
        assertTrue(PassTiming.globalsTrueGlobalHits > 0, "lib/script lookups must classify TRUE_GLOBAL")
        // `Dual` is declared by BOTH the module file a.ts and the script file c.ts.
        assertTrue(PassTiming.globalsSharedHits > 0, "a module+script name must classify SHARED")
        // `leakedVar` is declared ONLY by the module file a.ts and consumed from
        // b.ts — a pure module-file leak (conflated when the site carries file
        // context, unscoped when it does not).
        val leak = PassTiming.globalsConflatedHits + PassTiming.globalsUnscopedHits
        assertTrue(leak > 0, "the cross-file bare use of leakedVar must classify as a leak")
        val leakNames = PassTiming.globalsConflatedByName.keys + PassTiming.globalsUnscopedByName.keys
        assertTrue("leakedVar" in leakNames, "leakedVar must appear in the leak worklist, got: $leakNames")
        PassTiming.reset()
    }

    @Test
    fun `negative control - a disabled run records no globals lookups`() {
        PassTiming.enabled = false
        PassTiming.reset()
        diagnose(probeSource)
        assertEquals(0L, PassTiming.globalsLookups)
        assertEquals(0L, PassTiming.globalsConflatedHits)
        assertTrue(PassTiming.globalsConflatedByName.isEmpty())
        assertTrue(PassTiming.globalsUnscopedByName.isEmpty())
    }

    @Test
    fun `instrumented table reports hits and misses to the hook and delegates mutation`() {
        val table = InstrumentedSymbolTable()
        val seen = mutableListOf<Pair<String, Boolean>>()
        table.onLookup = { name, sym -> seen.add(name to (sym != null)) }

        val sym = Symbol(SymbolFlags.Variable, "x")
        table["x"] = sym
        assertEquals(sym, table["x"])
        assertNull(table["absent"])
        assertTrue(table.containsKey("x"))
        assertFalse(table.containsKey("absent"))
        assertEquals(
            listOf("x" to true, "absent" to false, "x" to true, "absent" to false),
            seen,
            "get and containsKey must both report, hit and miss alike",
        )

        seen.clear()
        table.remove("x")
        assertTrue(table.isEmpty())
        assertTrue(seen.isEmpty(), "mutation and size queries must not report")
    }

    @Test
    fun `instrumented table preserves insertion order and stays inert without a hook`() {
        val table = InstrumentedSymbolTable()
        for (n in listOf("zeta", "alpha", "mid")) table[n] = Symbol(SymbolFlags.Variable, n)
        assertEquals(listOf("zeta", "alpha", "mid"), table.keys.toList(), "LinkedHashMap order preserved")
        // No hook installed: lookups must behave plainly.
        assertEquals("alpha", table["alpha"]?.name)
        assertNull(table["nope"])
    }

    @Test
    fun `dump renders the globals section only when lookups were recorded`() {
        PassTiming.reset()
        val before = StringBuilder()
        PassTiming.dump { before.appendLine(it) }
        assertFalse("globals lookups" in before.toString(), "zero-lookup dump omits the section")

        PassTiming.noteGlobalsLookup(GlobalsLookupClass.TRUE_GLOBAL, "Array")
        PassTiming.noteGlobalsLookup(GlobalsLookupClass.CONFLATED, "leakedVar")
        PassTiming.noteGlobalsLookup(GlobalsLookupClass.UNSCOPED, "orphan")
        PassTiming.noteGlobalsLookup(GlobalsLookupClass.MISS, "nope")
        val out = StringBuilder()
        PassTiming.dump { out.appendLine(it) }
        val text = out.toString()
        assertTrue("== globals lookups (INV.3(a)) ==" in text)
        assertTrue("total 4: trueGlobal 1, shared 0, ownLocal 0, CONFLATED 1, unscoped 1, miss 1" in text)
        assertTrue("leakedVar" in text, "conflated names render in the worklist")
        assertTrue("orphan" in text, "unscoped names render in the worklist")
        PassTiming.reset()
    }
}
