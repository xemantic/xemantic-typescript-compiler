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
import kotlin.test.assertSame
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * M2.1(c) (round 390): [RealLibSnapshots] — real lib files parsed ONCE
 * process-wide (immutable, shared ASTs), bound FRESH per consumer (the
 * checker's `mergeSymbolTable` mutates merged-in symbols, so bound tables must
 * never be shared across programs — the merge-pollution gotcha).
 */
class RealLibSnapshotTest {

    @Test
    fun `parses each lib file once - identical AST instance across calls and selections`() {
        val first = RealLibSnapshots.parsedLibFile("es5")
        val again = RealLibSnapshots.parsedLibFile("es5")
        assertSame(first, again, "es5 must be parsed once and shared")
        // The same instance serves any selection that includes es5.
        val viaSelection = RealLibSnapshots.parsedLibFiles(listOf("es2015"), ScriptTarget.ES5)
        assertSame(first, viaSelection.first { it.fileName == "lib.es5.d.ts" })
    }

    @Test
    fun `parsed lib files carry distributed file names and real declarations`() {
        val es5 = RealLibSnapshots.parsedLibFile("es5")
        assertEquals("lib.es5.d.ts", es5.fileName)
        val interfaceNames = es5.statements
            .filterIsInstance<InterfaceDeclaration>().map { it.name.text }.toSet()
        for (name in listOf("Array", "Object", "String", "RegExp", "Promise", "PromiseLike")) {
            assertTrue(name in interfaceNames, "es5 must declare interface $name")
        }
        // The target-default selection uses the dist alias names.
        val defaults = RealLibSnapshots.parsedLibFiles(null, ScriptTarget.ES5)
        assertEquals("lib.d.ts", defaults.first().fileName)
    }

    @Test
    fun `binding is fresh per consumer and produces the lib globals`() {
        val options = CompilerOptions(target = ScriptTarget.ES5)
        val a = RealLibSnapshots.bindLibFiles(listOf("es5"), ScriptTarget.ES5, options)
        val b = RealLibSnapshots.bindLibFiles(listOf("es5"), ScriptTarget.ES5, options)
        val es5A = a.first { it.sourceFile.fileName == "lib.es5.d.ts" }
        val es5B = b.first { it.sourceFile.fileName == "lib.es5.d.ts" }
        // Same shared AST underneath...
        assertSame(es5A.sourceFile, es5B.sourceFile)
        // ...but fresh symbols per bind — a checker merging user declarations into
        // one program's lib symbols must never pollute the next program's.
        assertNotSame(es5A.locals["Array"], es5B.locals["Array"], "bound symbols must not be shared")
        for (name in listOf("Array", "Object", "Math", "JSON", "parseInt")) {
            assertTrue(es5A.locals.containsKey(name), "lib locals must bind '$name'")
        }
    }

    @Test
    fun `useRealLibs flag exists and defaults off`() {
        assertEquals(false, CompilerOptions().useRealLibs)
        assertEquals(true, CompilerOptions(useRealLibs = true).useRealLibs)
    }
}
