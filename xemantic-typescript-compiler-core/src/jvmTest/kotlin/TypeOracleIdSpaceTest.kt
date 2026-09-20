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
import kotlin.test.assertNotNull

/**
 * (INV.2b) An oracle belongs to ONE ID SPACE, and the guard that says so.
 *
 * ## The hazard, measured before the guard was written
 *
 * `Type.id` and `Symbol.id` come from THREAD-LOCAL counters (INV.6(6c0)), and
 * almost every oracle row can MINT: `typeOfSymbol` resolving a symbol the walk
 * never needed, `propertiesOfType` resolving a member table, `isAssignableTo`
 * instantiating a generic's members. A mint draws from the ASKING thread's
 * counter, and a thread that has never compiled starts at 1.
 *
 * Measured on a program whose build minted **612** types, through the shipped
 * `ProjectCompiler` oracle path:
 *
 *  * seven representative rows (`typeAt`, `typeOfSymbol`, `declaredTypeOfSymbol`,
 *    `propertiesOfType`, `apparentType`, `isAssignableTo`, `typeToString`) minted
 *    **nothing at all** — on the building thread and on a fresh one alike, because
 *    everything they asked about was already interned. **That reassuring zero is a
 *    property of the FIXTURE, not of the design**, which is why the measurement
 *    needed a positive control;
 *  * resolving a LIB type the program never mentions minted 7-82 types per row. On
 *    the building thread those landed at ids **612-806**, above the build. On a
 *    FRESH thread the same class of query minted ids **1-70** — and `anyType.id` is
 *    **10**. A freshly minted type carrying the intrinsic `any`'s id is round 825's
 *    `--workers` race reached through a retained oracle: `Relation`'s cache is keyed
 *    by a packed `(source.id, target.id)`, unions intern by member-id list, and the
 *    symbol-type memo is `Symbol.id`-keyed, so two distinct objects sharing an id
 *    make the checker answer another pair's question. Silently.
 *
 * ## Why the guard is a COUNTER comparison and not a thread identity
 *
 * The thread that RAN the checker is `runWithDeepStack`'s `xtsc-deep-stack` thread,
 * and it is dead before a caller can ask anything — a thread-identity guard would
 * refuse every query of every oracle. What that handoff does is write the ADVANCED
 * counters back to the CALLER, so the caller's thread is exactly the one whose
 * sequences dominate the build's. Domination is the soundness condition itself, it
 * is monotone, and it admits every safe case: a second build on the same thread, a
 * query after it, and a worker-rebased thread all pass.
 *
 * The third pin below is what makes that a decision rather than an accident: a
 * fresh thread HANDED the build's counters is answered. A thread-identity guard
 * would refuse it.
 */
class TypeOracleIdSpaceTest {

    private val source = """
        export interface Shape { p: string }
        export function probe(s: Shape): string { return s.p; }
    """.trimIndent()

    private fun build(): TypeOracleBuild =
        typeOracleOf(mapOf("/proj/main.ts" to source), CompilerOptions())

    private fun firstIdentifier(file: SourceFile): Identifier {
        val stack = ArrayList<Node>()
        stack.add(file)
        var found: Identifier? = null
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            forEachChild(node) { child -> stack.add(child) }
            if (node is Identifier) found = node
        }
        // `assertNotNull`, not `assert`: a power-assert diagram would toString the
        // node and render its whole subtree (CLAUDE.md's AST-subexpression trap).
        return assertNotNull(found)
    }

    private fun <T> onFreshThread(block: () -> T): Result<T> {
        var outcome: Result<T>? = null
        val thread = Thread({ outcome = runCatching(block) }, "inv2b-foreign")
        thread.start()
        thread.join()
        return outcome!!
    }

    /**
     * The POSITIVE CONTROL for every pin below: the thread `typeOracleOf` returned
     * to answers, so a refusal elsewhere is about the id space and not about a
     * query that never worked.
     */
    @Test
    fun `the thread the build returned to is answered`() {
        val built = build()
        val name = firstIdentifier(built.oracle.files.first())
        val answered = built.oracle.symbolAt(name) != null || built.oracle.typeAt(name) != null
        assert(answered)
        built.oracle.close()
    }

    /**
     * And the fact that makes the guard load-bearing rather than defensive: a fresh
     * thread's sequence really does start BELOW the build's, i.e. its next mint
     * would land inside the build's own ids. A value pin, so it cannot go quiet.
     */
    @Test
    fun `a fresh thread's id sequence starts below the build's high water mark`() {
        val built = build()
        val afterBuild = Type.captureThreadId()
        // Non-vacuity: a build that minted nothing could not express the hazard.
        val buildMinted = afterBuild > 1
        assert(buildMinted)
        val freshThreadStart = onFreshThread { Type.captureThreadId() }.getOrThrow()
        val wouldMintInsideTheBuild = freshThreadStart < afterBuild
        assert(wouldMintInsideTheBuild)
        // …and low enough to collide with the INTRINSICS themselves.
        val belowIntrinsics = freshThreadStart <= anyType.id
        assert(belowIntrinsics)
        built.oracle.close()
    }

    @Test
    fun `a query from a thread whose sequences do not dominate the build is refused`() {
        val built = build()
        val name = firstIdentifier(built.oracle.files.first())
        val outcome = onFreshThread { built.oracle.typeAt(name) }
        val refusal = assertNotNull(outcome.exceptionOrNull() as? OracleRefusal)
        // The message must name the mechanism, or a host meets a refusal it cannot act on.
        val namesTheMechanism = refusal.message?.contains("id space") == true &&
            refusal.message?.contains("INV.6(6c0)") == true
        assert(namesTheMechanism)
        built.oracle.close()
    }

    /**
     * The pin that makes this a COUNTER guard and not a thread guard — and the one
     * a thread-identity implementation fails. A fresh thread handed the building
     * thread's sequences is exactly as safe as the building thread (its next mint
     * lands above the build), and it is answered.
     */
    @Test
    fun `a fresh thread carrying the build's sequences is answered`() {
        val built = build()
        val name = firstIdentifier(built.oracle.files.first())
        val typeIds = Type.captureThreadId()
        val symbolIds = Symbol.captureThreadIds()
        val outcome = onFreshThread {
            Type.restoreThreadId(typeIds)
            Symbol.restoreThreadIds(symbolIds)
            built.oracle.symbolAt(name) != null || built.oracle.typeAt(name) != null
        }
        assert(outcome.isSuccess)
        assert(outcome.getOrThrow())
        built.oracle.close()
    }

    /**
     * A thread that has compiled SOMETHING ELSE since is also safe — its counters
     * are further advanced still, so its mints land above BOTH builds. Pinned
     * because the obvious implementation of "one thread" (an identity check) would
     * also refuse the ordinary two-project host.
     */
    @Test
    fun `a second build on the same thread does not lock out the first oracle`() {
        val first = build()
        val name = firstIdentifier(first.oracle.files.first())
        val second = typeOracleOf(
            mapOf("/other/main.ts" to "export const zzzOther: boolean = true;"),
            CompilerOptions(),
        )
        val advanced = Type.captureThreadId()
        val secondBuildMinted = advanced > 1
        assert(secondBuildMinted)
        val stillAnswers = first.oracle.symbolAt(name) != null || first.oracle.typeAt(name) != null
        assert(stillAnswers)
        first.oracle.close()
        second.oracle.close()
    }
}
