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

package com.xemantic.typescript.tsgo.go.time

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.posix.localtime_r
import platform.posix.nanosleep
import platform.posix.timespec
import platform.posix.time_tVar
import platform.posix.tm

internal actual fun localZoneOffsetSeconds(unixSeconds: Long): Int = memScoped {
    val t = alloc<time_tVar>()
    t.value = unixSeconds
    val out = alloc<tm>()
    localtime_r(t.ptr, out.ptr)
    out.tm_gmtoff.toInt()
}

internal actual fun platformSleep(nanos: Long) {
    memScoped {
        val ts = alloc<timespec>()
        ts.tv_sec = nanos / 1_000_000_000
        ts.tv_nsec = nanos % 1_000_000_000
        nanosleep(ts.ptr, null)
    }
}
