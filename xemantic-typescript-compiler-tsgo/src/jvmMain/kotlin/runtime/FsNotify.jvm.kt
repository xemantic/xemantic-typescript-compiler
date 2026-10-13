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

package com.xemantic.typescript.tsgo.runtime

import com.xemantic.typescript.tsgo.fswatch.XtscInCreate
import com.xemantic.typescript.tsgo.fswatch.XtscInDelete
import com.xemantic.typescript.tsgo.fswatch.XtscInDeleteSelf
import com.xemantic.typescript.tsgo.fswatch.XtscInIsDir
import com.xemantic.typescript.tsgo.fswatch.XtscInModify
import com.xemantic.typescript.tsgo.fswatch.XtscInQOverflow
import com.xemantic.typescript.tsgo.fswatch.XtscNotifier
import com.xemantic.typescript.tsgo.fswatch.XtscNotifyEvent
import com.xemantic.typescript.tsgo.go.sync.blockingWait
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit

internal actual fun platformNewNotifier(): Tuple2<XtscNotifier?, GoError?> = try {
    Tuple2(WatchServiceNotifier(FileSystems.getDefault().newWatchService()), null)
} catch (e: java.io.IOException) {
    Tuple2(null, GoPlainError(e.message ?: "newWatchService failed"))
}

/**
 * inotify's contract over a [WatchService] (on Linux the JDK's own is inotify underneath): a watch descriptor per
 * registered directory (one [WatchKey]); ENTRY_CREATE / ENTRY_MODIFY / ENTRY_DELETE as IN_CREATE / IN_MODIFY /
 * IN_DELETE (a rename's two halves are a delete and a create there too), OVERFLOW as IN_Q_OVERFLOW, and a key the
 * JDK invalidates (its directory is gone) as IN_DELETE_SELF. A WatchService does not say whether an entry is a
 * directory: a created one is asked, a deleted one is a directory exactly when it is itself watched.
 */
private class WatchServiceNotifier(private val ws: WatchService) : XtscNotifier {
    private val lock = Any()
    private val wdOf = HashMap<WatchKey, Int>()
    private val keyOf = HashMap<Int, WatchKey>()
    private val dirOf = HashMap<Int, Path>()
    private val wdOfDir = HashMap<Path, Int>()
    private var nextWd = 1

    override fun addWatch(p0: String): Tuple2<Int, GoError?> {
        val dir = Path.of(GoString.toUtf16(p0))
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            val why = if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) "not a directory" else "no such file or directory"
            return Tuple2(0, GoPlainError(why))
        }
        val key = try {
            dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY)
        } catch (e: java.io.IOException) {
            return Tuple2(0, GoPlainError(e.message ?: "register failed"))
        }
        val wd = synchronized(lock) {
            wdOf.getOrPut(key) {
                val wd = nextWd++
                keyOf[wd] = key
                dirOf[wd] = dir
                wdOfDir[dir] = wd
                wd
            }
        }
        return Tuple2(wd, null)
    }

    override fun rmWatch(p0: Int): GoError? {
        val key = synchronized(lock) {
            val key = keyOf.remove(p0) ?: return GoPlainError("invalid argument")
            wdOf.remove(key)
            dirOf.remove(p0)?.let { wdOfDir.remove(it) }
            key
        }
        key.cancel()
        return null
    }

    override fun read(p0: Int): Tuple2<GoSlice<XtscNotifyEvent>, GoError?> {
        val out = ArrayList<XtscNotifyEvent>()
        try {
            var key: WatchKey? = blockingWait { ws.poll(p0.toLong(), TimeUnit.MILLISECONDS) }
            while (key != null) {
                drain(key, out)
                key = ws.poll()
            }
        } catch (_: ClosedWatchServiceException) {
        } catch (_: InterruptedException) {
        }
        return Tuple2(GoSlice.of(XtscNotifyEvent.ELEM, *out.toTypedArray()), null)
    }

    private fun drain(key: WatchKey, out: MutableList<XtscNotifyEvent>) {
        val (wd, dir) = synchronized(lock) {
            val wd = wdOf[key] ?: -1
            wd to dirOf[wd]
        }
        for (e in key.pollEvents()) {
            if (e.kind() == StandardWatchEventKinds.OVERFLOW) {
                out += XtscNotifyEvent(wd = -1, mask = XtscInQOverflow)
                continue
            }
            if (wd < 0 || dir == null) continue
            val name = e.context() as Path
            val child = dir.resolve(name)
            val mask = when (e.kind()) {
                StandardWatchEventKinds.ENTRY_CREATE -> XtscInCreate or (if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) XtscInIsDir else 0u)
                StandardWatchEventKinds.ENTRY_DELETE -> XtscInDelete or (if (synchronized(lock) { child in wdOfDir }) XtscInIsDir else 0u)
                else -> XtscInModify
            }
            out += XtscNotifyEvent(wd = wd, mask = mask, name = GoString.fromUtf16(name.toString()))
        }
        if (!key.reset() && wd >= 0) {
            synchronized(lock) {
                keyOf.remove(wd)
                wdOf.remove(key)
                dirOf.remove(wd)?.let { wdOfDir.remove(it) }
            }
            out += XtscNotifyEvent(wd = wd, mask = XtscInDeleteSelf)
        }
    }

    override fun close(): GoError? {
        ws.close()
        return null
    }
}
