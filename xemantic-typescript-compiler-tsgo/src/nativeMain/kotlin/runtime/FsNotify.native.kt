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

@file:OptIn(ExperimentalForeignApi::class)

package com.xemantic.typescript.tsgo.runtime

import com.xemantic.typescript.tsgo.fswatch.XtscNotifier
import com.xemantic.typescript.tsgo.fswatch.XtscNotifyEvent
import com.xemantic.typescript.tsgo.go.sync.blockingWait
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.linux.IN_CLOEXEC
import platform.linux.IN_CREATE
import platform.linux.IN_DELETE
import platform.linux.IN_DELETE_SELF
import platform.linux.IN_DONT_FOLLOW
import platform.linux.IN_EXCL_UNLINK
import platform.linux.IN_MODIFY
import platform.linux.IN_MOVED_FROM
import platform.linux.IN_MOVED_TO
import platform.linux.IN_MOVE_SELF
import platform.linux.IN_NONBLOCK
import platform.linux.IN_ONLYDIR
import platform.linux.inotify_add_watch
import platform.linux.inotify_init1
import platform.linux.inotify_rm_watch
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.POLLIN
import platform.posix.errno
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.strerror

/** inotify_linux.go's inotifyMask. */
private val INOTIFY_MASK: UInt = (IN_CREATE or IN_DELETE or IN_DELETE_SELF or IN_MODIFY or IN_MOVE_SELF or IN_MOVED_FROM or
    IN_MOVED_TO or IN_DONT_FOLLOW or IN_ONLYDIR or IN_EXCL_UNLINK).toUInt()

private fun errnoError(what: String): GoError = GoPlainError("$what: ${strerror(errno)?.toKString() ?: "errno $errno"}")

internal actual fun platformNewNotifier(): Tuple2<XtscNotifier?, GoError?> {
    val fd = inotify_init1(IN_NONBLOCK or IN_CLOEXEC)
    if (fd < 0) return Tuple2(null, errnoError("inotify_init1"))
    return Tuple2(InotifyNotifier(fd), null)
}

/** An inotify(7) instance: [read] polls it (its run token given back meanwhile) and decodes the queued `inotify_event`s. */
private class InotifyNotifier(private val fd: Int) : XtscNotifier {
    private val bufSize = 8192
    private val buf = nativeHeap.allocArray<ByteVar>(bufSize)

    override fun addWatch(p0: String): Tuple2<Int, GoError?> {
        val wd = inotify_add_watch(fd, GoString.toUtf16(p0), INOTIFY_MASK)
        return if (wd < 0) Tuple2(0, errnoError("inotify_add_watch")) else Tuple2(wd, null)
    }

    override fun rmWatch(p0: Int): GoError? = if (inotify_rm_watch(fd, p0) < 0) errnoError("inotify_rm_watch") else null

    override fun read(p0: Int): Tuple2<GoSlice<XtscNotifyEvent>, GoError?> {
        val ready = blockingWait {
            memScoped {
                val p = alloc<pollfd>()
                p.fd = fd
                p.events = POLLIN.toShort()
                val n = poll(p.ptr, 1u, p0)
                if (n < 0 && errno != EINTR) return@blockingWait -1
                if (n > 0) 1 else 0
            }
        }
        if (ready < 0) return Tuple2(GoSlice.of(XtscNotifyEvent.ELEM), errnoError("poll"))
        val out = ArrayList<XtscNotifyEvent>()
        if (ready > 0) {
            while (true) {
                val n = platform.posix.read(fd, buf, bufSize.toULong())
                if (n <= 0L) {
                    if (n < 0L && errno != EAGAIN && errno != EINTR) return Tuple2(GoSlice.of(XtscNotifyEvent.ELEM), errnoError("read"))
                    break
                }
                var off = 0
                while (off < n) {
                    val wd = int32At(off)
                    val mask = int32At(off + 4).toUInt()
                    val len = int32At(off + 12)
                    val nameStart = off + 16
                    var end = nameStart
                    while (end < nameStart + len && buf[end] != 0.toByte()) end++
                    val name = ByteArray(end - nameStart) { buf[nameStart + it] }
                    // one Char per byte: a Go byte string (docs/goport-design.md § 3)
                    out += XtscNotifyEvent(wd = wd, mask = mask, name = name.joinToString("") { (it.toInt() and 0xFF).toChar().toString() })
                    off = nameStart + len
                }
            }
        }
        return Tuple2(GoSlice.of(XtscNotifyEvent.ELEM, *out.toTypedArray()), null)
    }

    private fun int32At(off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or ((buf[off + 3].toInt() and 0xFF) shl 24)

    override fun close(): GoError? {
        platform.posix.close(fd)
        nativeHeap.free(buf.rawValue)
        return null
    }
}
