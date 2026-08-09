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
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * (WARM.20) The launcher's CLIENT ARM: which binary answers a `--daemon`
 * request, and what happens when it cannot.
 *
 * ## Why an arm exists at all
 *
 * Round 872 measured the fixed per-invocation cost of every client arm against
 * one warm daemon, using a request the server refuses in constant time: JVM
 * dispatcher **287 ms**, the thin JVM client **278 ms**, thin JVM + an AOT cache
 * **105 ms**, and the Kotlin/Native client **7.0 ms** against a **0.9 ms**
 * fork+exec floor. End to end on a 3-file project — the shape an editor
 * generates — that is **376 ms → 98 ms**.
 *
 * ## Why the FALLBACK is the load-bearing half
 *
 * The native client depends on `-api` and nothing else, so it cannot compile;
 * with no daemon reachable and none startable it exits
 * `XTSC_CLIENT_UNAVAILABLE` (3) rather than pretending. The launcher re-runs
 * such a request on the JVM arm, which can. **Round 857 found the launcher had
 * been inoperable for a whole window** because it depended on an artifact that
 * only one task produces; a fresh checkout, a `clean`, or any platform with no
 * native binary must therefore still get a working `xtsc`, and that is what the
 * `jvm` cases below assert.
 *
 * ## How this is tested without a daemon or a compile
 *
 * Two hooks and two fake binaries. `XTSC_CLIENT_DECIDE_ONLY` reports the arm and
 * launches nothing — and it answers for BOTH outcomes, so a green run cannot be
 * a hook that simply never fired. For the fallback, `XTSC_CLIENT` names a script
 * with a chosen exit code and `XTSC_JAVA` names one that prints a marker, so
 * "the JVM arm ran" is an observation rather than an inference.
 */
class LauncherClientArmTest {

    // ---- fixtures ---------------------------------------------------------

    private fun scriptExiting(dir: File, name: String, code: Int, says: String): File {
        val f = File(dir, name)
        f.writeText("#!/bin/sh\necho '$says'\nexit $code\n")
        f.setExecutable(true)
        return f
    }

    private fun run(env: Map<String, String>, vararg args: String): Triple<Int, String, String> {
        val launcher = launcher() ?: fail("no scripts/xtsc found")
        val pb = ProcessBuilder(listOf(launcher.absolutePath) + args)
            .directory(launcher.parentFile.parentFile)
        // A stale value of any of these in the developer's environment would
        // silently pick a different arm than the case is about.
        pb.environment().remove("XTSC_CLIENT")
        pb.environment().remove("XTSC_HOME")
        pb.environment().remove("XTSC_SOCKET")
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.readBytes().decodeToString()
        val err = p.errorStream.readBytes().decodeToString()
        return Triple(p.waitFor(), out, err)
    }

    private fun arm(env: Map<String, String>, vararg args: String): String =
        run(env + ("XTSC_CLIENT_DECIDE_ONLY" to "1"), *args).second.trim()

    private fun tmpDir(): File =
        File.createTempFile("xtsc-arm-", "").let { it.delete(); it.mkdirs(); it }

    // ---- arm selection ----------------------------------------------------

    @Test
    fun `a daemon request with a native client present picks the native arm`() {
        val dir = tmpDir()
        val fake = scriptExiting(dir, "xtsc-client", 0, "unused")
        val chosen = arm(mapOf("XTSC_CLIENT" to fake.absolutePath), "--daemon", "--noEmit", ".")
        assert(chosen == "native ${fake.absolutePath}")
        dir.deleteRecursively()
    }

    // The fresh-checkout case: nothing built, nothing installed, still works.
    @Test
    fun `a daemon request with no native client falls to the jvm arm`() {
        val chosen = arm(
            mapOf("XTSC_CLIENT" to "/nonexistent/xtsc-client"),
            "--daemon", "--noEmit", ".",
        )
        assert(chosen == "jvm")
    }

    // The native client cannot compile, so a non-daemon invocation must never
    // reach it however present it is.
    @Test
    fun `a one-shot compile never uses the client arm`() {
        val dir = tmpDir()
        val fake = scriptExiting(dir, "xtsc-client", 0, "unused")
        val chosen = arm(mapOf("XTSC_CLIENT" to fake.absolutePath), "--noEmit", ".")
        assert(chosen == "jvm")
        dir.deleteRecursively()
    }

    @Test
    fun `serving is the jvm arm too`() {
        val dir = tmpDir()
        val fake = scriptExiting(dir, "xtsc-client", 0, "unused")
        val chosen = arm(mapOf("XTSC_CLIENT" to fake.absolutePath), "--serve", "--socket", "/tmp/x.sock")
        assert(chosen == "jvm")
        dir.deleteRecursively()
    }

    // `off` is a real setting: the A/B harnesses and round 871's own client
    // measurement need to force the JVM arm on a box where the binary exists.
    @Test
    fun `XTSC_CLIENT off forces the jvm arm`() {
        val chosen = arm(mapOf("XTSC_CLIENT" to "off"), "--daemon", "--noEmit", ".")
        assert(chosen == "jvm")
    }

    // The AOT decision is a property of the JVM arm; answering it from the
    // native one would report on something the caller is not asking about.
    @Test
    fun `the AOT decide-only probe still reaches the jvm arm`() {
        val dir = tmpDir()
        val fake = scriptExiting(dir, "xtsc-client", 0, "unused")
        val (code, out, _) = run(
            mapOf("XTSC_CLIENT" to fake.absolutePath, "XTSC_AOT_DECIDE_ONLY" to "1", "XTSC_AOT" to "off"),
            "--daemon", "--noEmit", ".",
        )
        assert(code == 0)
        assert("unused" !in out)
        assert(out.trim().startsWith("SKIP") || out.trim().startsWith("USE"))
        dir.deleteRecursively()
    }

    // ---- the fallback -----------------------------------------------------

    @Test
    fun `a client that cannot reach a daemon falls back to the jvm arm`() {
        val dir = tmpDir()
        val fake = scriptExiting(dir, "xtsc-client", 3, "client gave up")
        val java = scriptExiting(dir, "fake-java", 0, "JVM-ARM-RAN")
        val (code, out, _) = run(
            mapOf(
                "XTSC_CLIENT" to fake.absolutePath,
                "XTSC_JAVA" to java.absolutePath,
                "XTSC_AOT" to "off",
            ),
            "--daemon", "--noEmit", ".",
        )
        assert("JVM-ARM-RAN" in out)
        assert(code == 0)
        dir.deleteRecursively()
    }

    // …and only that code. Any other outcome is the client's answer and re-running
    // it on the JVM would compile the project TWICE and report it twice.
    @Test
    fun `a client that answered is not re-run on the jvm arm`() {
        val dir = tmpDir()
        val fake = scriptExiting(dir, "xtsc-client", 1, "CLIENT-ANSWERED")
        val java = scriptExiting(dir, "fake-java", 0, "JVM-ARM-RAN")
        val (code, out, _) = run(
            mapOf(
                "XTSC_CLIENT" to fake.absolutePath,
                "XTSC_JAVA" to java.absolutePath,
                "XTSC_AOT" to "off",
            ),
            "--daemon", "--noEmit", ".",
        )
        assert("CLIENT-ANSWERED" in out)
        assert("JVM-ARM-RAN" !in out)
        // the compile's own exit code, not the launcher's opinion of it
        assert(code == 1)
        dir.deleteRecursively()
    }

    @Test
    fun `a clean client run exits zero through the launcher`() {
        val dir = tmpDir()
        val fake = scriptExiting(dir, "xtsc-client", 0, "CLIENT-ANSWERED")
        val (code, out, _) = run(mapOf("XTSC_CLIENT" to fake.absolutePath), "--daemon", "--noEmit", ".")
        assert(code == 0)
        assert("CLIENT-ANSWERED" in out)
        dir.deleteRecursively()
    }

    // On a fallback the client has already said it found no daemon; printing
    // that above a compile that then succeeds is worse than saying nothing.
    @Test
    fun `the client's stderr is suppressed when the launcher falls back`() {
        val dir = tmpDir()
        val fake = File(dir, "xtsc-client")
        fake.writeText("#!/bin/sh\necho 'xtsc: no compile daemon' >&2\nexit 3\n")
        fake.setExecutable(true)
        val java = scriptExiting(dir, "fake-java", 0, "JVM-ARM-RAN")
        val (_, out, err) = run(
            mapOf(
                "XTSC_CLIENT" to fake.absolutePath,
                "XTSC_JAVA" to java.absolutePath,
                "XTSC_AOT" to "off",
            ),
            "--daemon", "--noEmit", ".",
        )
        assert("JVM-ARM-RAN" in out)
        assert("no compile daemon" !in err)
        dir.deleteRecursively()
    }

    // …but a client that ANSWERED keeps its stderr: that is the channel its own
    // error messages travel on.
    @Test
    fun `the client's stderr survives when it did not fall back`() {
        val dir = tmpDir()
        val fake = File(dir, "xtsc-client")
        fake.writeText("#!/bin/sh\necho 'xtsc: the daemon stopped answering' >&2\nexit 4\n")
        fake.setExecutable(true)
        val (code, _, err) = run(mapOf("XTSC_CLIENT" to fake.absolutePath), "--daemon", "--noEmit", ".")
        assert(code == 4)
        assert("the daemon stopped answering" in err)
        dir.deleteRecursively()
    }

    // ---- the socket both peers derive ------------------------------------

    // The client honours XTSC_SOCKET and the JVM dispatcher does not, so the
    // launcher must name it explicitly or the two arms would talk to DIFFERENT
    // daemons — which does not fail, it silently starts a second one.
    @Test
    fun `XTSC_SOCKET is passed to the client explicitly`() {
        val dir = tmpDir()
        val fake = File(dir, "xtsc-client")
        fake.writeText("#!/bin/sh\necho \"ARGS: ${'$'}@\"\nexit 0\n")
        fake.setExecutable(true)
        val (_, out, _) = run(
            mapOf("XTSC_CLIENT" to fake.absolutePath, "XTSC_SOCKET" to "/tmp/chosen-r872.sock"),
            "--daemon", "--noEmit", ".",
        )
        assert("--socket /tmp/chosen-r872.sock" in out)
        dir.deleteRecursively()
    }

    // An explicit --socket must win over the environment, and must not be
    // duplicated into the command line.
    @Test
    fun `an explicit socket option is not overridden by XTSC_SOCKET`() {
        val dir = tmpDir()
        val fake = File(dir, "xtsc-client")
        fake.writeText("#!/bin/sh\necho \"ARGS: ${'$'}@\"\nexit 0\n")
        fake.setExecutable(true)
        val (_, out, _) = run(
            mapOf("XTSC_CLIENT" to fake.absolutePath, "XTSC_SOCKET" to "/tmp/env-r872.sock"),
            "--daemon", "--socket", "/tmp/explicit-r872.sock", "--noEmit", ".",
        )
        assert("/tmp/explicit-r872.sock" in out)
        assert("/tmp/env-r872.sock" !in out)
        dir.deleteRecursively()
    }

    // `--daemon` steers the launcher and means nothing to the compiler; leaving
    // it in would reach the CLI as an unknown option.
    @Test
    fun `the daemon flag is not forwarded to the client`() {
        val dir = tmpDir()
        val fake = File(dir, "xtsc-client")
        fake.writeText("#!/bin/sh\necho \"ARGS: ${'$'}@\"\nexit 0\n")
        fake.setExecutable(true)
        val (_, out, _) = run(
            mapOf("XTSC_CLIENT" to fake.absolutePath),
            "--daemon", "--noEmit", "/tmp",
        )
        assert("--daemon" !in out)
        assert("--noEmit /tmp" in out)
        dir.deleteRecursively()
    }

    /**
     * The arm swap must be a LATENCY change and nothing else.
     *
     * The client's own default is to START a daemon when it finds none — right
     * for a binary invoked as `xtsc`, wrong as a side effect of swapping an arm:
     * `--daemon` has always meant "use a server if one is up, else compile here
     * and say so", and auto-spawn would silently replace an in-process compile
     * with a long-lived JVM nobody asked for. Not hypothetical — the first build
     * of this arm spawned one from inside the 14,000-test suite,
     * `AotCacheGuardTest` failed on the missing "no compile server on" message,
     * and a daemon was still running when the run finished.
     */
    @Test
    fun `the client is told not to start a daemon`() {
        val dir = tmpDir()
        val fake = File(dir, "xtsc-client")
        fake.writeText("#!/bin/sh\necho \"ARGS: ${'$'}@\"\nexit 0\n")
        fake.setExecutable(true)
        val (_, out, _) = run(mapOf("XTSC_CLIENT" to fake.absolutePath), "--daemon", "--noEmit", ".")
        assert("--no-spawn" in out)
        dir.deleteRecursively()
    }

    // The same property asserted where it actually bites, with the REAL client:
    // a `--daemon` request with nothing listening must still compile in-process
    // and say so, exactly as it did before there was an arm, and must leave no
    // daemon behind. This is `AotCacheGuardTest`'s dispatcher case seen from the
    // arm's side.
    @Test
    fun `an unreachable daemon still compiles in-process and leaves nothing behind`() {
        val dir = tmpDir()
        val absent = File(dir, "absent.sock")
        val (_, out, err) = run(
            mapOf("XTSC_AOT" to "off"),
            "--daemon", "--socket", absent.absolutePath, "--noEmit", dir.absolutePath,
        )
        assert("no compile server on" in err)
        assert("whole-project build" in out)
        assert(!absent.exists())
        dir.deleteRecursively()
    }

    private companion object {

        /**
         * `scripts/xtsc`, located from a class of THIS module.
         *
         * Same anchor rule as `AotCacheGuardTest`: a class from the compiler
         * module arrives as a JAR, and `File(url.toURI())` on a `jar:` URL
         * throws rather than finding the wrong directory.
         */
        fun launcher(): File? {
            if (!File("/bin/sh").canExecute()) return null
            System.getenv("XTSC_TEST_LAUNCHER")?.let { return File(it, "xtsc") }
            val anchor = "com/xemantic/typescript/compiler/server/CompileServer.class"
            val url = LauncherClientArmTest::class.java.classLoader.getResource(anchor)
                ?: fail("$anchor is not on the test classpath")
            var dir: File? = File(url.toURI())
            while (dir != null) {
                val candidate = File(dir, "scripts/xtsc")
                if (candidate.isFile) return candidate
                dir = dir.parentFile
            }
            return null
        }
    }

}
