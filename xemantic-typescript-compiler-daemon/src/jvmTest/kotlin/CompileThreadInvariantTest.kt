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

package com.xemantic.typescript.compiler.server

import com.xemantic.kotlin.test.assert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test

/**
 * Pins invariant 1 of [CompileServer]: every compile runs on the SAME thread.
 *
 * This is not a throughput property. Symbol/Type id sequences are thread-local
 * and `runWithDeepStack` hands them off by capturing the CALLER's counters and
 * writing the advanced values back on join, so a request served from a
 * different carrier thread would hand off from counters still at zero, restart
 * ids at 1, and collide with the singleton intrinsics.
 *
 * **The reason this needs a dedicated pin at all is that the failure is
 * invisible**: it leaves every byte of every diagnostic identical, so neither
 * the corpus, nor a `--listAll` diff, nor the cost gate can see it. The only
 * observable is which thread the work ran on.
 */
class CompileThreadInvariantTest {

    // startsWith, not equality: the coroutines debug agent appends the coroutine
    // it is running to the thread name, e.g. "xtsc-compile @coroutine#19".
    @Test
    fun `every compile runs on the dedicated compile thread`() = runBlocking {
        val name = CompileServer.onCompileThread { Thread.currentThread().name }
        assert(name.startsWith(CompileServer.COMPILE_THREAD_NAME))
    }

    @Test
    fun `consecutive compiles run on the very same thread`() = runBlocking {
        val first = CompileServer.onCompileThread { Thread.currentThread().id }
        val second = CompileServer.onCompileThread { Thread.currentThread().id }
        val third = CompileServer.onCompileThread { Thread.currentThread().id }
        assert(second == first)
        assert(third == first)
    }

    // The caller is what varies in production: `serve` accepts on an IO
    // dispatcher whose carrier thread is free to differ per connection. The
    // handoff must be immune to that, which a same-caller test cannot show.
    @Test
    fun `the compile thread does not follow the calling dispatcher`() = runBlocking {
        val fromDefault = withContext(Dispatchers.Default) {
            CompileServer.onCompileThread { Thread.currentThread().id }
        }
        val fromIo = withContext(Dispatchers.IO) {
            CompileServer.onCompileThread { Thread.currentThread().id }
        }
        val fromUnconfined = withContext(Dispatchers.Unconfined) {
            CompileServer.onCompileThread { Thread.currentThread().id }
        }
        assert(fromIo == fromDefault)
        assert(fromUnconfined == fromDefault)
    }

    // Concurrent callers must still be SERIALIZED onto the one thread — the
    // single-thread executor is what provides that, and a swap to any pooled
    // dispatcher would fail here while passing every test above.
    @Test
    fun `concurrent callers are serialized onto one thread`() = runBlocking {
        val ids = (1..8).map {
            async(Dispatchers.IO) {
                CompileServer.onCompileThread { Thread.currentThread().id }
            }
        }.awaitAll()
        assert(ids.distinct().size == 1)
    }

}
