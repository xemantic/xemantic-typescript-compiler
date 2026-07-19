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

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.concurrent.TimeUnit

internal actual fun fileEvents(root: String): Flow<String> = callbackFlow {
    val watcher = FileSystems.getDefault().newWatchService()
    fun register(dir: Path) {
        if (!Files.isDirectory(dir)) return
        Files.walk(dir).use { paths ->
            paths.filter { Files.isDirectory(it) }.forEach {
                it.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            }
        }
    }
    register(Paths.get(root))
    val poller = launch {
        try {
        while (isActive) {
            val key = watcher.poll(250, TimeUnit.MILLISECONDS) ?: continue
            val dir = key.watchable() as Path
            for (event in key.pollEvents()) {
                val rel = event.context() as? Path ?: continue
                val abs = dir.resolve(rel)
                // A newly-created directory joins the watch (WatchService is
                // non-recursive per registration).
                if (event.kind() == ENTRY_CREATE && Files.isDirectory(abs)) register(abs)
                trySend(abs.toString())
            }
            key.reset()
        }
        } catch (_: java.nio.file.ClosedWatchServiceException) {
            // Normal teardown: awaitClose closes the watcher while poll() is
            // blocked — the exception IS the designed wakeup, not an error.
        }
    }
    awaitClose {
        poller.cancel()
        watcher.close()
    }
}.flowOn(pipelineIoDispatcher)
