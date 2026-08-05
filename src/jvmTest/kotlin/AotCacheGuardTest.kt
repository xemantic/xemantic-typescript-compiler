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

import com.xemantic.kotlin.test.assert
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.fail

/**
 * (JIT.2b) — the seam pins for the AOT cache's **fail-safe** contract.
 *
 * JDK 25's AOT cache is worth 1.638x on a real compile and has **no
 * invalidation of its own**: round 828 physically removed `Checker.class` from
 * the jar, gave the jar a fresh mtime, and the cached run exited 0 printing
 * `OK — 0 errors` while the uncached run correctly died with
 * `NoClassDefFoundError`. A stale cache silently runs the previous build's
 * bytecode; `-XX:AOTMode=on` does not change that. `scripts/xtsc` therefore
 * refuses to hand the JVM a cache whose provenance it has not just verified,
 * and **this class is what notices if that refusal ever stops working**.
 *
 * The pins drive the launcher's decision function through its
 * `XTSC_AOT_DECIDE_ONLY` hook — no JVM is launched, no 49 MiB cache is trained,
 * so the whole class costs milliseconds. What it cannot see is the *use* of a
 * verified cache; that is round 832's manual gate (the `--listAll` diff on the
 * compiler profile, `docs/perf/aot-cache.md` § 5).
 *
 * **The load-bearing pin is [aMutatedClasspathEntryIsRefused]**, which is round
 * 828's hazard reduced to one changed byte. It discriminates: with the manifest
 * comparison removed from `xtsc_aot_decide`, it reads `USE …` and fails, while
 * every other pin here stays green (verified by ablation, round 832).
 *
 * Set `XTSC_TEST_LAUNCHER` to point the pins at a different `scripts/`
 * directory — that is how the ablation is run.
 */
class AotCacheGuardTest {

    @Test
    fun `a fully verified cache is used`() {
        val f = fixture() ?: return
        assert(f.decide() == "USE ${f.cacheFile.absolutePath}")
    }

    /**
     * ROUND 828'S HAZARD, IN ONE BYTE. The classpath entry's content changes
     * while its name — and therefore the path the launcher looks the cache up
     * under — is planted so the cache is still FOUND. Only the content
     * comparison can refuse this, which is precisely what makes it the pin that
     * discriminates.
     */
    @Test
    fun `a mutated classpath entry is refused`() {
        val f = fixture() ?: return
        f.jars[0].writeText("payload-A-modified")
        // Plant the stale pair under the NEW fingerprint's file name, so the
        // refusal cannot come from "the file is simply not there".
        val newId = f.fingerprintId()
        File(f.cacheDir, "xtsc-$newId.aot").writeBytes(f.cacheFile.readBytes())
        File(f.cacheDir, "xtsc-$newId.aot.manifest").writeText(f.manifestFile.readText())
        assert(f.decide() == "SKIP manifest-mismatch")
    }

    /**
     * The same mutation without the planting step: the launcher looks a cache up
     * by fingerprint, so a rebuilt artifact misses by construction. This is the
     * realistic upgrade path and the reason "delete on upgrade" is hygiene
     * rather than a correctness dependency.
     */
    @Test
    fun `a rebuilt artifact does not find the previous build's cache`() {
        val f = fixture() ?: return
        f.jars[1].appendText("+rebuilt")
        assert(f.decide() == "SKIP no-cache-file")
    }

    /** A cache whose bytes changed after training — truncation, tampering, a half-written file. */
    @Test
    fun `a cache whose content no longer matches the manifest is refused`() {
        val f = fixture() ?: return
        f.cacheFile.writeBytes(f.cacheFile.readBytes().copyOf(64))
        assert(f.decide() == "SKIP cache-corrupt")
    }

    /** A cache with no manifest beside it carries no provenance at all. */
    @Test
    fun `a cache with no manifest is refused`() {
        val f = fixture() ?: return
        f.manifestFile.delete()
        assert(f.decide() == "SKIP no-manifest")
    }

    /**
     * An exploded class directory cannot be dumped from (round 828 § 6: `Cannot
     * have non-empty directory in paths`) and cannot be content-hashed as one
     * file — so it can never be part of a verified provenance.
     */
    @Test
    fun `a classpath containing a directory is refused`() {
        val f = fixture() ?: return
        val decision = f.decide(cp = "${f.jars[0].absolutePath}:${f.cacheDir.absolutePath}")
        assert(decision == "SKIP classpath-not-jar-only")
    }

    @Test
    fun `the cache can be switched off`() {
        val f = fixture() ?: return
        assert(f.decide(extra = mapOf("XTSC_AOT" to "off")) == "SKIP disabled")
    }

    /**
     * NEGATIVE CONTROL for the fingerprint's inputs: the guard is bound to
     * CONTENT, never to a timestamp. A touched artifact whose bytes are intact
     * must keep its cache — a guard that invalidated on mtime would be safe but
     * would throw the 1.638x away on every `touch`, every checkout and every
     * container rebuild.
     */
    @Test
    fun `a touched but unchanged artifact keeps its cache`() {
        val f = fixture() ?: return
        f.jars[0].setLastModified(System.currentTimeMillis() + 10_000)
        assert(f.decide() == "USE ${f.cacheFile.absolutePath}")
    }

    // ---------------------------------------------------------------- fixture

    private class Fixture(
        val scripts: File,
        val root: File,
        val jars: List<File>,
        val cacheDir: File,
        var cacheFile: File,
        var manifestFile: File
    ) {
        fun cp() = jars.joinToString(":") { it.absolutePath }

        fun env(cp: String?, extra: Map<String, String>) = buildMap {
            put("XTSC_CP", cp ?: cp())
            put("XTSC_AOT_DIR", cacheDir.absolutePath)
            putAll(extra)
        }

        fun decide(cp: String? = null, extra: Map<String, String> = emptyMap()): String =
            run(File(scripts, "xtsc"), env(cp, extra) + ("XTSC_AOT_DECIDE_ONLY" to "1")).trim()

        fun fingerprintId(): String =
            run(File(scripts, "xtsc-aot"), env(null, emptyMap()), "status")
                .lineSequence().first { it.startsWith("fingerprint") }.substringAfterLast(' ')

        fun manifestBlock(): String =
            run(File(scripts, "xtsc-aot"), env(null, emptyMap()), "manifest")

        fun run(script: File, env: Map<String, String>, vararg args: String): String {
            val pb = ProcessBuilder(listOf(script.absolutePath) + args)
                .directory(root)
                .redirectErrorStream(false)
            pb.environment().putAll(env)
            val p = pb.start()
            val out = p.inputStream.readBytes().decodeToString()
            val err = p.errorStream.readBytes().decodeToString()
            val code = p.waitFor()
            if (code != 0) fail("${script.name} ${args.joinToString(" ")} exited $code\n$out\n$err")
            return out
        }
    }

    private fun fixture(): Fixture? {
        val scripts = launcherDir() ?: return null
        val tmp = File.createTempFile("xtsc-aot-guard-", "").let {
            it.delete(); it.mkdirs(); it
        }
        tmp.deleteOnExit()
        val lib = File(tmp, "lib").apply { mkdirs() }
        val cacheDir = File(tmp, "cache").apply { mkdirs() }
        val jars = listOf(
            File(lib, "app.jar").apply { writeText("payload-A") },
            File(lib, "dep.jar").apply { writeText("payload-B") }
        )
        val f = Fixture(scripts, tmp, jars, cacheDir, File(tmp, "unset"), File(tmp, "unset"))
        // Build a legitimately trained-looking pair: the fingerprint block exactly
        // as the launcher computes it, plus the cache's own size and digest. The
        // fixture asks the script for the block rather than reimplementing it —
        // reimplementing would pin this test to a copy of the logic instead of to
        // the logic.
        val id = f.fingerprintId()
        f.cacheFile = File(cacheDir, "xtsc-$id.aot")
        f.manifestFile = File(cacheDir, "xtsc-$id.aot.manifest")
        f.cacheFile.writeBytes(ByteArray(4096) { (it % 251).toByte() })
        f.manifestFile.writeText(
            f.manifestBlock() + "cache ${f.cacheFile.length()} ${sha256(f.cacheFile)}\n"
        )
        // The fixture must START from USE, or every negative pin below is vacuous.
        assert(f.decide() == "USE ${f.cacheFile.absolutePath}")
        return f
    }

    private companion object {

        fun sha256(file: File): String =
            MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }

        /**
         * The launcher lives beside the sources, not on the classpath, so it is
         * located from the compiled-classes root the same way
         * [HugeMethodLimitTest] locates that root. Returns null — skipping the
         * class — only where a POSIX shell cannot run it at all.
         */
        fun launcherDir(): File? {
            if (!File("/bin/sh").canExecute()) return null
            val override = System.getenv("XTSC_TEST_LAUNCHER")
            if (override != null) return File(override)
            val marker = "com/xemantic/typescript/compiler/Checker.class"
            val url = AotCacheGuardTest::class.java.classLoader.getResource(marker)
                ?: fail("$marker is not on the test classpath")
            var dir: File? = File(url.toURI())
            while (dir != null) {
                val candidate = File(dir, "scripts/xtsc")
                if (candidate.isFile) return candidate.parentFile
                dir = dir.parentFile
            }
            fail("could not locate scripts/xtsc upward from $url")
        }
    }
}
