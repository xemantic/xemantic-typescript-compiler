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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * INV.3(c)(i) (round 504): `lookupPerFileForNode` — the NODE-keyed per-file
 * consult for names read from AST nodes. The round-503 measurement showed ~82%
 * of conflated `globals` traffic resolves names from FOREIGN nodes (another
 * module file's member annotations) while checking a different file; tsc
 * resolves such an annotation in its OWNING file's scope. The primitive must:
 *
 *  - resolve a name read from a foreign file's node under THAT file's
 *    visibility (returning the same merged-globals instance the legacy
 *    consult returned — what makes the (c)(ii) flips byte-identical);
 *  - return null for a node owned by a file where the name has NO meaning
 *    (the conflation leak, killed);
 *  - keep resolving for a node owned by a file that IMPORTS the name;
 *  - degrade to the LEGACY merged consult for an unindexed node (data-class
 *    `copy()` — parent chain never stamped).
 *
 * Built by direct `Checker(options, binderResults)` construction (the
 * Inv3PerFileLookupTest pattern) so symbol IDENTITY is assertable; fixture
 * files are path-shaped per the round-501 lesson.
 */
class Inv3NodeKeyedLookupTest {

    private fun buildChecker(vararg files: Pair<String, String>): Pair<Checker, Map<String, BinderResult>> {
        val options = CompilerOptions()
        val results = files.map { (name, src) -> Binder(options).bind(Parser(src.trimIndent(), name).parse()) }
        val byName = results.associateBy { it.sourceFile.fileName }
        return Checker(options, results, isMultiFileSource = true) to byName
    }

    /** a.ts declares the enum + an interface whose `.kind` annotation references
     *  it — the exact foreign-node shape the kind-domain machinery reads;
     *  b.ts is a module file that neither declares nor imports `KindEnum`. */
    private val declaringFile = "/proj/a.ts" to """
        export enum KindEnum { A, B }
        export interface Iface {
            kind: KindEnum;
        }
    """
    private val foreignFile = "/proj/b.ts" to """
        export const marker = 1;
    """

    /** The `KindEnum` TypeReference node on `Iface.kind` — a node OWNED by a.ts. */
    private fun kindAnnotationNode(results: Map<String, BinderResult>): TypeNode {
        val iface = results.getValue("/proj/a.ts").sourceFile.statements
            .filterIsInstance<InterfaceDeclaration>().single()
        val member = iface.members.filterIsInstance<PropertyDeclaration>().single()
        val annotation = member.type
        assertNotNull(annotation)
        return annotation
    }

    @Test
    fun `a foreign file's annotation node resolves the name under its OWNING file's visibility`() {
        val (checker, results) = buildChecker(declaringFile, foreignFile)
        val declared = results.getValue("/proj/a.ts").locals["KindEnum"]
        assertNotNull(declared)
        val resolved = checker.lookupPerFileForNode(kindAnnotationNode(results), "KindEnum")
        assertSame(declared, resolved)
    }

    @Test
    fun `a node owned by a file where the name has no meaning yields null - the leak killed`() {
        val (checker, results) = buildChecker(declaringFile, foreignFile)
        val bNode = results.getValue("/proj/b.ts").sourceFile.statements.first()
        assertNull(checker.lookupPerFileForNode(bNode, "KindEnum"))
    }

    @Test
    fun `a node owned by a file that IMPORTS the name keeps resolving`() {
        val (checker, results) = buildChecker(
            declaringFile,
            "/proj/c.ts" to """
                import { KindEnum } from "./a";
                export const use = KindEnum.A;
            """,
        )
        val declared = results.getValue("/proj/a.ts").locals["KindEnum"]
        assertNotNull(declared)
        val cNode = results.getValue("/proj/c.ts").sourceFile.statements.first()
        assertSame(declared, checker.lookupPerFileForNode(cNode, "KindEnum"))
    }

    @Test
    fun `an unindexed copy degrades to the legacy merged consult`() {
        val (checker, results) = buildChecker(declaringFile, foreignFile)
        val declared = results.getValue("/proj/a.ts").locals["KindEnum"]
        assertNotNull(declared)
        // A data-class copy has nodeId -1 / parent null — no owner.
        val iface = results.getValue("/proj/a.ts").sourceFile.statements
            .filterIsInstance<InterfaceDeclaration>().single()
        val detached = iface.copy()
        assertNull(owningSourceFile(detached))
        // Legacy degradation: the merged-globals consult still answers.
        assertSame(declared, checker.lookupPerFileForNode(detached, "KindEnum"))
    }

    @Test
    fun `owningSourceFile walks a deep node to its file and identity-matches`() {
        val (_, results) = buildChecker(declaringFile, foreignFile)
        val aFile = results.getValue("/proj/a.ts").sourceFile
        val owner = owningSourceFile(kindAnnotationNode(results))
        assertNotNull(owner)
        assertSame(aFile, owner)
    }

    @Test
    fun `negative control - a lib name resolves regardless of the owning file`() {
        val (checker, results) = buildChecker(declaringFile, foreignFile)
        val bNode = results.getValue("/proj/b.ts").sourceFile.statements.first()
        // `Array` is lib-visible everywhere — never module-only, never nulled.
        assertNotNull(checker.lookupPerFileForNode(bNode, "Array"))
    }
}
