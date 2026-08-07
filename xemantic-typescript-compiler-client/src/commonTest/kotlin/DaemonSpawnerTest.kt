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

package com.xemantic.typescript.compiler.client

import com.xemantic.kotlin.test.assert
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class DaemonSpawnerTest {

    private fun spawnerOver(
        env: Map<String, String>,
        launched: MutableList<List<String>>,
        succeed: Boolean = true,
    ) = LauncherDaemonSpawner(
        launch = { command -> launched += command; succeed },
        env = { name -> env[name] },
    )

    @Test
    fun `should use XTSC_DAEMON_CMD when it is set`() = runTest {
        val launched = mutableListOf<List<String>>()
        val spawner = spawnerOver(mapOf("XTSC_DAEMON_CMD" to "/opt/xtsc/bin/xtscd"), launched)
        assert(spawner.spawn("/tmp/x.sock"))
        assert(launched.single() == listOf("/opt/xtsc/bin/xtscd", "--serve", "--socket", "/tmp/x.sock"))
    }

    // The daemon must be told the SAME socket the client is about to poll, or
    // the client waits out its whole timeout beside a perfectly healthy daemon
    // listening somewhere else.
    @Test
    fun `should pass the client's own socket path to the daemon`() = runTest {
        val launched = mutableListOf<List<String>>()
        val spawner = spawnerOver(mapOf("XTSC_DAEMON_CMD" to "xtscd"), launched)
        spawner.spawn("/tmp/chosen.sock")
        assert(launched.single().contains("/tmp/chosen.sock"))
    }

    @Test
    fun `should split a multi-word XTSC_DAEMON_CMD`() = runTest {
        val launched = mutableListOf<List<String>>()
        val spawner = spawnerOver(mapOf("XTSC_DAEMON_CMD" to "java -jar xtscd.jar"), launched)
        spawner.spawn("/tmp/x.sock")
        assert(launched.single().take(3) == listOf("java", "-jar", "xtscd.jar"))
    }

    @Test
    fun `should derive the launcher from XTSC_HOME when no command is set`() = runTest {
        val launched = mutableListOf<List<String>>()
        val spawner = spawnerOver(mapOf("XTSC_HOME" to "/opt/xtsc"), launched)
        spawner.spawn("/tmp/x.sock")
        assert(launched.single().first() == "/opt/xtsc/bin/xtsc-daemon")
    }

    @Test
    fun `should tolerate a trailing separator on XTSC_HOME`() = runTest {
        val launched = mutableListOf<List<String>>()
        val spawner = spawnerOver(mapOf("XTSC_HOME" to "/opt/xtsc/"), launched)
        spawner.spawn("/tmp/x.sock")
        assert(launched.single().first() == "/opt/xtsc/bin/xtsc-daemon")
    }

    @Test
    fun `should prefer XTSC_DAEMON_CMD over XTSC_HOME`() = runTest {
        val launched = mutableListOf<List<String>>()
        val spawner = spawnerOver(
            mapOf("XTSC_DAEMON_CMD" to "xtscd", "XTSC_HOME" to "/opt/xtsc"),
            launched
        )
        spawner.spawn("/tmp/x.sock")
        assert(launched.single().first() == "xtscd")
    }

    // Refusing to guess is the point. Searching PATH or inventing a `java -cp`
    // invocation could start a DIFFERENT build's daemon, which answers happily
    // and returns diagnostics from the wrong compiler.
    @Test
    fun `should refuse to guess when the environment names no launcher`() = runTest {
        val launched = mutableListOf<List<String>>()
        val spawner = spawnerOver(emptyMap(), launched)
        assert(!spawner.spawn("/tmp/x.sock"))
        assert(launched.isEmpty())
    }

    @Test
    fun `should ignore an empty XTSC_DAEMON_CMD rather than launch nothing`() = runTest {
        val launched = mutableListOf<List<String>>()
        val spawner = spawnerOver(mapOf("XTSC_DAEMON_CMD" to "   "), launched)
        assert(!spawner.spawn("/tmp/x.sock"))
        assert(launched.isEmpty())
    }

    @Test
    fun `should report a launch failure rather than claim success`() = runTest {
        val launched = mutableListOf<List<String>>()
        val spawner = spawnerOver(
            mapOf("XTSC_DAEMON_CMD" to "xtscd"), launched, succeed = false
        )
        assert(!spawner.spawn("/tmp/x.sock"))
    }

    @Test
    fun `should say how to fix a missing launcher`() {
        val message = LauncherDaemonSpawner().describe()
        assert("XTSC_DAEMON_CMD" in message)
        assert("XTSC_HOME" in message)
    }

}
