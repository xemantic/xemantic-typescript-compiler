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
import java.net.URLClassLoader
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
 * (AOT.4)(a), round 840 adds two pins for the launcher's **main class**, which
 * is a field of that same fingerprint (`mainclass`). The launcher was pointed at
 * the server/daemon dispatcher, so that `--serve` and `--daemon` can be reached
 * at all; [theLauncherReachesTheServerDispatcher] is the discriminating pin for
 * that, and [theLauncherAndTheTrainerNameTheSameMainClass] names the half-swap
 * the fixture would otherwise report only as "did not start from USE".
 *
 * (AOT.4)(b), round 840(b) adds a third: the same gap existed in the GraalVM
 * native image, whose entry point is set in `build.gradle.kts`. So the three
 * shipped entry points — launcher, AOT trainer, native image — are now pinned to
 * agree, by [theNativeImageIsBuiltFromTheServerDispatcher]. Note the whole
 * main-class family reads FILES (shell scripts, the build script) rather than
 * the running program, which is why it fails by construction when the suite is
 * driven from a jar (`scripts/aot-corpus-suite.sh`, both arms alike).
 *
 * (AOT.4)(c), round 840(c) adds a fourth file-reading pin,
 * [theTrainerTrainsWithEmitIntoAThrowawayDirectory]: the training run now emits
 * (worth −6.9% on an emitting compile) and must send that output to a temp
 * directory rather than the user's project.
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

    // -------------------------------------------- (AOT.4)(a) the main class

    /**
     * THE HALF-SWAP. `XTSC_MAIN_CLASS` is the `mainclass` field of the
     * fingerprint block, so the trainer and the launcher must name the same
     * class or the cache is trained under a fingerprint the launcher never looks
     * up: every launch reads `SKIP no-cache-file`, silently and forever, and the
     * only symptom is that the 1.638x quietly stops happening.
     *
     * **HONEST ABOUT ITS OWN COVERAGE (round 840 ablation):** this is NOT a
     * uniquely-discriminating signal. Ablating one script alone fails **nine** of
     * the ten pins here, because the fixture builds its manifest with `xtsc-aot`
     * and then decides with `xtsc`, so *any* disagreement already breaks its
     * `USE` precondition. What this pin adds is the diagnosis: the other eight
     * fail saying the fixture did not start from `USE`, and only this one names
     * the two classes that differ.
     */
    @Test
    fun `the launcher and the trainer name the same main class`() {
        val scripts = launcherDir() ?: return
        val launcher = mainClassOf(File(scripts, "xtsc"))
        val trainer = mainClassOf(File(scripts, "xtsc-aot"))
        assert(launcher == trainer)
    }

    // ------------------------------------- (AOT.4)(c) what the trainer runs

    /**
     * THE TRAINING RUN EMITS, AND EMITS SOMEWHERE THROWAWAY. Round 840(c)
     * measured a cache trained under `--noEmit` as **−1,258 ms (−6.9%)** worse
     * than an emit-trained one on an emitting compile (10 of 11 paired runs;
     * `docs/perf/aot-cache.md` § 9) — the profile for the Transformer and the
     * Emitter is simply absent from it. So `train` emits.
     *
     * The half of that which needs a pin is not the speed, it is the
     * **blast radius**: the trainer runs against the *user's own project*, so an
     * emitting training run that used the project's `outDir` would drop JS into
     * their `dist/` (or fail on a read-only tree). `--outDir <mktemp -d>` is the
     * whole guard, and `ProjectOutDirTest` pins the compiler side of it.
     *
     * Reading the script text is deliberate and is the same technique as the
     * main-class pins above: the alternative is a ~30 s, 51 MB training run.
     */
    @Test
    fun `the trainer trains with emit into a throwaway directory`() {
        val scripts = launcherDir() ?: return
        val text = File(scripts, "xtsc-aot").readText()
        val invocation = text.lines()
            .dropWhile { !it.contains("AOTCacheOutput=\$tmp") }
            .take(2)
            .joinToString(" ")
        assert(invocation.contains("\$XTSC_MAIN_CLASS"))
        // It must EMIT: --noEmit is exactly what round 840(c) measured as the loss.
        assert(!invocation.contains("--noEmit"))
        // …into a throwaway directory, never the project's own outDir.
        assert(invocation.contains("--outDir \"\$emitdir\""))
        assert(text.contains("emitdir=\"\$(mktemp -d"))
        // …which is removed on every exit path, killed runs included.
        assert(text.contains("trap 'rm -f \"\$tmp\" \"\$tmp.config\"; rm -rf \"\$emitdir\"' EXIT"))
        assert(text.contains("rm -rf \"\$emitdir\""))
    }

    /**
     * THE TRAINING RUN IS SEQUENTIAL, AND THAT IS A MEASUREMENT (round 840(d),
     * `docs/perf/aot-cache.md` § 10). Round 839 § 7.3 makes the opposite look
     * obvious — cached `--workers 4` is the fastest configuration this project
     * has — but training and running are different questions. A cache trained
     * under `--workers 4` measured **−0.9% on a `--workers 4` compile** (inside
     * the band) and **+3.5% on the sequential one**, which is the path a user
     * gets with no flags: 60 compiles, two independent batches agreeing at every
     * level. Unlike emit — where the Transformer/Emitter profile was simply
     * *absent*, so adding it took nothing away — worker count is the same code
     * reached through a different thread structure, so the shared profile is a
     * **trade**, and the default sits on the losing side of it.
     *
     * This pin exists because the change it forbids is a one-word edit that
     * would look like an improvement in review.
     */
    @Test
    fun `the trainer trains sequentially`() {
        val scripts = launcherDir() ?: return
        val text = File(scripts, "xtsc-aot").readText()
        val invocation = text.lines()
            .dropWhile { !it.contains("AOTCacheOutput=\$tmp") }
            .take(2)
            .joinToString(" ")
        assert(invocation.contains("\$XTSC_MAIN_CLASS"))
        assert(!invocation.contains("--workers"))
    }

    /**
     * THE LAUNCHER MUST REACH THE DISPATCHER. Round 839 found `scripts/xtsc`
     * could not run `--serve` or `--daemon` at all: its main class was the
     * one-shot `…compiler.MainKt`, while the mode-dispatching `main` is
     * `…compiler.server.XtscMainKt`. The failure is silent — `MainKt` treats
     * `--daemon` and `--socket` as unknown flags and simply compiles — so the
     * pin has to read a signal only the dispatcher can produce.
     *
     * That signal is the client's fallback line: pointed at a socket no server
     * is listening on, the dispatcher announces the fallback on stderr and then
     * compiles in-process. Both halves are asserted, so a launcher that reached
     * the dispatcher but stopped delegating would fail too.
     *
     * Costs one ~1.3 s JVM start against an empty project (no sources to check).
     * `XTSC_AOT=off` keeps the user's real cache out of it.
     *
     * **It discriminates, and uniquely (round 840 ablation).** With BOTH scripts
     * put back on `…compiler.MainKt` — round 839's state — this is the only pin
     * of the ten that fails; the same is true of a typo'd class name, which
     * nothing else in the repo would notice until a user ran the launcher.
     */
    @Test
    fun `the launcher reaches the server dispatcher`() {
        val scripts = launcherDir() ?: return
        val javaBin = File(File(System.getProperty("java.home"), "bin"), "java")
        if (!javaBin.canExecute()) return
        val tmp = File.createTempFile("xtsc-dispatch-", "").let { it.delete(); it.mkdirs(); it }
        tmp.deleteOnExit()
        val project = File(tmp, "proj").apply { mkdirs() }
        val absentSocket = File(tmp, "absent.sock")
        val pb = ProcessBuilder(
            File(scripts, "xtsc").absolutePath,
            "--daemon", "--socket", absentSocket.absolutePath,
            "--noEmit", project.absolutePath
        ).directory(tmp)
        pb.environment() += mapOf(
            "XTSC_CP" to testClasspath(),
            "XTSC_JAVA" to javaBin.absolutePath,
            "XTSC_JAVA_OPTS" to "-Xmx1g",
            "XTSC_AOT" to "off",
            "XTSC_AOT_DIR" to File(tmp, "cache").absolutePath
        )
        val p = pb.start()
        val out = p.inputStream.readBytes().decodeToString()
        val err = p.errorStream.readBytes().decodeToString()
        p.waitFor()
        // Only …server.XtscMainKt prints this; …compiler.MainKt ignores the flag.
        assert("no compile server on" in err)
        // …and it must still have delegated to the ordinary compiler.
        assert("whole-project build" in out)
    }

    /**
     * (AOT.4)(b), round 840. THE THIRD SHIPPED ENTRY POINT. The GraalVM native
     * image carried the same gap [theLauncherReachesTheServerDispatcher] closed
     * for the shell launcher: `build.gradle.kts` built it from
     * `…compiler.MainKt`, so the one artifact whose KDoc says it exists to *be*
     * the thin client of a warm server ("a native start costs milliseconds") was
     * the one artifact that could not talk to one.
     *
     * Measured on the stale 2026-07-30 binary before the swap:
     * `xtsc --serve --socket /tmp/x.sock` bound no socket, took the socket path
     * as the project, emitted 173 files and exited **0** — a silent wrong
     * success, identical for `--daemon`.
     *
     * **WHAT IT ASSERTS AND WHY THAT SHAPE.** Agreement with `scripts/xtsc`,
     * not a hardcoded class name. The two pins then COMPOSE:
     * [theLauncherReachesTheServerDispatcher] *executes* the launcher and so
     * proves its class is a real dispatcher, and this one propagates that
     * property to the image. A hardcoded literal here would instead have to be
     * hand-edited on any package rename, and would still not prove the named
     * class dispatches anything.
     *
     * **WHAT IT DOES NOT DISCRIMINATE, stated rather than implied.** It cannot
     * see whether the image *builds* or whether `--serve` works once built:
     * GraalVM is not installed on the development box, so `./gradlew nativeImage`
     * has never been run against the dispatcher and `--serve` has never run on a
     * native image at all. This pin catches the constant regressing or a new
     * entry point being added and forgotten — nothing about native-image's
     * closed-world analysis of the socket path.
     *
     * **Ablation (round 840):** reverting the constant to `…compiler.MainKt`
     * fails this pin and only this pin, of all 11 here.
     *
     * The `val nativeImageMainClass =` anchor is deliberate — `build.gradle.kts`
     * also names `…MainKt` in `compileTsProject`, a dev `JavaExec` that is
     * correctly left alone, and this must neither match it nor break when it is
     * edited.
     */
    @Test
    fun `the native image is built from the server dispatcher`() {
        val root = projectRoot()
        val line = File(root, "build.gradle.kts").readLines()
            .firstOrNull { it.trimStart().startsWith("val nativeImageMainClass") }
            ?: fail("no `val nativeImageMainClass` assignment in build.gradle.kts")
        val nativeImageMainClass = line.substringAfter('=').trim().trim('"')
        // The build file is the MODULE's; scripts/ is the REPO root's.
        assert(nativeImageMainClass == mainClassOf(File(repoRoot(), "scripts/xtsc")))
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

        /** The `XTSC_MAIN_CLASS=…` assignment of a launcher script, unquoted. */
        fun mainClassOf(script: File): String {
            val line = script.readLines().firstOrNull { it.startsWith("XTSC_MAIN_CLASS=") }
                ?: fail("no XTSC_MAIN_CLASS assignment in ${script.absolutePath}")
            return line.substringAfter('=').trim().trim('"')
        }

        /**
         * The classpath to hand a child JVM. A Gradle test worker's
         * `java.class.path` is not necessarily the test classpath (the worker jar
         * may be all of it), so the loader chain is asked first and the property
         * is the fallback — the union, deduplicated, so neither source can leave
         * a hole.
         */
        fun testClasspath(): String {
            val fromLoaders = generateSequence(AotCacheGuardTest::class.java.classLoader) { it.parent }
                .filterIsInstance<URLClassLoader>()
                .flatMap { it.urLs.asSequence() }
                .mapNotNull { runCatching { File(it.toURI()).absolutePath }.getOrNull() }
            val fromProperty = (System.getProperty("java.class.path") ?: "").split(':')
            return (fromLoaders.toList() + fromProperty).filter { it.isNotEmpty() }
                .distinct().joinToString(":")
        }

        fun sha256(file: File): String =
            MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }

        /**
         * The launcher lives beside the sources, not on the classpath, so it is
         * located from the compiled-classes root the same way
         * [HugeMethodLimitTest] locates that root. Returns null — skipping the
         * class — only where a POSIX shell cannot run it at all.
         */
        /**
         * The repository root, located the same way as [launcherDir] but
         * DELIBERATELY ignoring `XTSC_TEST_LAUNCHER`: that override exists to
         * point the cache-decision pins at a copied `scripts/` dir, and a copy
         * has no `build.gradle.kts`. The build-file pin is about the real tree.
         */
        fun projectRoot(): File = ancestorContaining("build.gradle.kts")

        /**
         * The REPOSITORY root — the directory holding `settings.gradle.kts`.
         *
         * Distinct from [projectRoot] since the module split, and the two must
         * not be conflated: `build.gradle.kts` is now this MODULE's, while
         * `scripts/` stayed at the repo root. Keyed on `settings.gradle.kts`
         * because exactly one directory in the tree has one, whereas every
         * module has a `build.gradle.kts` — so an upward walk for the latter
         * stops at the first module it meets, which is what broke this pin.
         */
        fun repoRoot(): File = ancestorContaining("settings.gradle.kts")

        /** The nearest ancestor of the compiled classes that holds [marker]. */
        private fun ancestorContaining(marker: String): File {
            val anchor = "com/xemantic/typescript/compiler/Checker.class"
            val url = AotCacheGuardTest::class.java.classLoader.getResource(anchor)
                ?: fail("$anchor is not on the test classpath")
            var dir: File? = File(url.toURI())
            while (dir != null) {
                if (File(dir, marker).isFile) return dir
                dir = dir.parentFile
            }
            fail("could not locate $marker upward from $url")
        }

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
