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

package com.xemantic.typescript.tsgo

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.tsgo.fswatch.EventDelete
import com.xemantic.typescript.tsgo.fswatch.EventUpdate
import com.xemantic.typescript.tsgo.go.io.fs.FileMode
import com.xemantic.typescript.tsgo.go.os.O_CREATE
import com.xemantic.typescript.tsgo.go.os.O_TRUNC
import com.xemantic.typescript.tsgo.go.os.O_WRONLY
import com.xemantic.typescript.tsgo.go.os.mkdirAll
import com.xemantic.typescript.tsgo.go.os.openFile
import com.xemantic.typescript.tsgo.go.os.removeAll
import com.xemantic.typescript.tsgo.go.os.tempDir
import com.xemantic.typescript.tsgo.go.sync.Mutex
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.installFsNotify
import kotlin.random.Random
import kotlin.test.Test

/**
 * (TSGO.5-c) fswatch's ported core over the platform notifier (`runtime/FsNotify`: the JVM's WatchService,
 * Kotlin/Native's inotify): a recursive watch reports a created file, a created directory, a file created inside
 * that new directory (watched once it appears) and a deleted file, each under its full path.
 */
class FsWatchTest {

    private fun write(path: String, text: String) {
        val (f, err) = openFile(path, O_WRONLY or O_CREATE or O_TRUNC, FileMode(420u))
        assert(err == null)
        f!!.writeString(text)
        f.close()
    }

    @Test
    fun `a recursive watch reports creations - in new directories too - and deletions`() {
        installFsNotify()
        val root = tempDir() + "/xtsc-fswatch-" + Random.nextLong(1L shl 40)
        assert(mkdirAll(root, FileMode(493u)) == null)
        val mu = Mutex()
        val seen = ArrayList<String>()
        fun has(e: String): Boolean {
            mu.lock()
            val r = e in seen
            mu.unlock()
            return r
        }
        fun await(e: String): Boolean {
            repeat(500) {
                if (has(e)) return true
                com.xemantic.typescript.tsgo.runtime.goRecv(com.xemantic.typescript.tsgo.go.time.after(com.xemantic.typescript.tsgo.go.time.Duration(20_000_000)))
            }
            return false
        }
        val opts = GoSlice.of(GoElem.ref<com.xemantic.typescript.tsgo.fswatch.WatchOption?>(), com.xemantic.typescript.tsgo.fswatch.withRecursive())
        val (watch, err) = com.xemantic.typescript.tsgo.fswatch.default()!!.watchDirectory(root, { events, _ ->
            mu.lock()
            for (i in 0 until events.len) {
                val e = events[i]
                val kind = if (e.kind == EventUpdate) "update" else if (e.kind == EventDelete) "delete" else "?"
                seen += kind + " " + e.path.removePrefix(root)
            }
            mu.unlock()
        }, opts)
        assert(err == null)
        try {
            write("$root/x.ts", "x")
            val created = await("update /x.ts")
            assert(mkdirAll("$root/sub", FileMode(493u)) == null)
            val dir = await("update /sub")
            write("$root/sub/f.ts", "f")
            val nested = await("update /sub/f.ts")
            removeAll("$root/x.ts")
            val deleted = await("delete /x.ts")
            assert(created && dir && nested && deleted)
        } finally {
            watch!!.close()
            removeAll(root)
        }
    }
}
