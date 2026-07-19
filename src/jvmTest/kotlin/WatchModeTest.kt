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

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** INV.7(c1): the watch-mode building blocks. */
class WatchModeTest {

    @Test
    fun `watchRelevant accepts source and config files, rejects the rest`() {
        assertTrue(watchRelevant("/p/src/a.ts"))
        assertTrue(watchRelevant("/p/src/a.tsx"))
        assertTrue(watchRelevant("/p/src/a.mts"))
        assertTrue(watchRelevant("/p/tsconfig.json"))
        assertTrue(watchRelevant("/p/package.json"))
        assertFalse(watchRelevant("/p/out/a.js.map"))
        assertFalse(watchRelevant("/p/notes.md"))
        assertFalse(watchRelevant("/p/node_modules/x/index.ts"))
        assertFalse(watchRelevant("/p/other.json"))
    }

    @Test
    fun `awaitChangeBatch debounces a burst into one deduplicated batch`() = runBlocking {
        val changes = Channel<String>(Channel.UNLIMITED)
        changes.send("/p/a.ts")
        changes.send("/p/b.ts")
        changes.send("/p/a.ts")
        val batch = awaitChangeBatch(changes, quietMs = 100)
        assertEquals(setOf("/p/a.ts", "/p/b.ts"), batch)
    }

    @Test
    fun `awaitChangeBatch blocks until the first event arrives`() = runBlocking {
        val changes = Channel<String>(Channel.UNLIMITED)
        launch {
            kotlinx.coroutines.delay(50)
            changes.send("/p/late.ts")
        }
        val batch = withTimeout(2000) { awaitChangeBatch(changes, quietMs = 50) }
        assertEquals(setOf("/p/late.ts"), batch)
    }

    @Test
    fun `fileEvents reports a write under the watched tree`() = runBlocking {
        val dir = Files.createTempDirectory("xtsc-watch-test")
        val sub = Files.createDirectory(dir.resolve("src"))
        try {
            val writer = launch {
                // Keep writing until the collector sees an event — WatchService
                // registration races the first write on some filesystems.
                repeat(50) {
                    Files.writeString(sub.resolve("a.ts"), "const x = $it;")
                    kotlinx.coroutines.delay(100)
                }
            }
            val event = withTimeout(10_000) {
                fileEvents(dir.toString()).first { it.endsWith("a.ts") }
            }
            assertTrue(event.endsWith("a.ts"), event)
            writer.cancel()
        } finally {
            sub.toFile().deleteRecursively()
            dir.toFile().deleteRecursively()
        }
    }
}
