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

package com.xemantic.typescript.compiler.client

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.O_RDWR
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix._exit
import platform.posix.close
import platform.posix.dup2
import platform.posix.execvp
import platform.posix.fflush
import platform.posix.fork
import platform.posix.getenv
import platform.posix.open
import platform.posix.setsid
import platform.posix.stderr
import platform.posix.waitpid
import platform.posix.write

public actual fun readEnv(name: String): String? = getenv(name)?.toKString()

public actual fun writeStderr(text: String) {
    val bytes = text.encodeToByteArray()
    memScoped {
        val buffer = allocArray<ByteVar>(bytes.size)
        for (i in bytes.indices) buffer[i] = bytes[i]
        write(STDERR_FILENO, buffer, bytes.size.toULong())
    }
    fflush(stderr)
}

/**
 * Starts [command] fully detached, with the DOUBLE FORK that actually achieves
 * detachment on POSIX.
 *
 * Three things here are load-bearing and none is obvious:
 *
 *  * **Two forks, not one.** The intermediate child calls `setsid` to leave this
 *    process's session and then forks again, so the daemon is orphaned to init.
 *    With a single fork the daemon stays this process's child, and `xtsc` exiting
 *    would leave it re-parented but still sharing the controlling terminal — a
 *    Ctrl-C in the shell would then kill the daemon along with the client.
 *  * **The intermediate child is REAPED.** `waitpid` on it returns immediately —
 *    it exits as soon as it has forked — and without it every `xtsc` invocation
 *    that starts a daemon leaves a zombie.
 *  * **`_exit`, never `exit`.** After `fork` the child shares the parent's stdio
 *    buffers; `exit` would run atexit handlers and flush them, duplicating
 *    whatever this process had buffered.
 *
 * Returns false only when the first `fork` fails. A failure of `execvp` happens
 * in the grandchild, after this function has returned — which is why the client
 * confirms the daemon by POLLING the socket rather than by trusting this result.
 */
public actual fun spawnDetached(command: List<String>): Boolean {
    if (command.isEmpty()) return false
    val first = fork()
    if (first < 0) return false
    if (first == 0) {
        // Intermediate child: leave the session, fork the real daemon, exit.
        setsid()
        val second = fork()
        if (second == 0) {
            detachStandardStreams()
            memScoped {
                val argv = allocArray<CPointerVar<ByteVar>>(command.size + 1)
                command.forEachIndexed { i, arg -> argv[i] = arg.cstr.ptr }
                argv[command.size] = null
                execvp(command[0], argv)
            }
            // Only reached if execvp failed; the client discovers this by the
            // socket never appearing.
            _exit(127)
        }
        _exit(0)
    }
    // Reap the intermediate child so no zombie is left behind.
    waitpid(first, null, 0)
    return true
}

/**
 * Points the daemon's stdin/stdout/stderr at /dev/null before it execs.
 *
 * MEASURED, not theoretical: without this the daemon inherits fds 0/1/2 and so
 * holds the client's stdout open. `xtsc … | tail` then never terminates — the
 * client exits, the pipe stays open because the daemon still owns the write end,
 * and the shell waits forever on a compile that finished seconds ago. The JVM
 * spawner avoids the same trap with `Redirect.DISCARD`; on POSIX it has to be
 * done by hand, and it is the one part of detaching that a passing `--serve`
 * test cannot reveal.
 */
private fun detachStandardStreams() {
    val devNull = open("/dev/null", O_RDWR)
    if (devNull < 0) return
    dup2(devNull, STDIN_FILENO)
    dup2(devNull, STDOUT_FILENO)
    dup2(devNull, STDERR_FILENO)
    if (devNull > STDERR_FILENO) close(devNull)
}
