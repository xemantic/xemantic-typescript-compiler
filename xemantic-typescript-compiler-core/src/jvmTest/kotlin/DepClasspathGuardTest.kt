package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import java.io.File
import kotlin.test.Test

/**
 * Pins `scripts/lib/dep-classpath.sh` — the shared, validating resolver for the
 * core module's `jvmRuntimeClasspath` dependency tail.
 *
 * WHY (round 858). Three consecutive rounds found a harness silently loading
 * something other than the code under test, exiting 0 and printing a plausible
 * number (853: a stale pre-split class dir; 857: an AOT prefix that was no longer
 * a prefix; 858: `build/bench/cp.txt`, a hand-frozen Jul-8 dependency tail still
 * naming kotlin-stdlib 2.4.0 / kotlinx-io 0.9.0 / serialization 1.9.0 after the
 * build had moved to 2.4.10 / 0.9.1 / 1.11.0). Every jar it named still existed
 * and the compiler still LINKED against them, so nothing failed — a reader just
 * measured a dependency tail that is not the shipping one.
 *
 * THE LOAD-BEARING PIN IS [`a cache older than libs versions toml is refused`].
 * `ab-warm.sh`'s previous guard compared the cache only against the MODULE's
 * `build.gradle.kts`, and a version bump lands in `gradle/libs.versions.toml`,
 * which leaves that file untouched — so the old guard was blind to exactly the
 * change that produces a stale tail. That pin is what makes the widening real
 * rather than decorative.
 *
 * ABLATION-VERIFIED (round 858), one mistake at a time, each against a COPY of
 * the script driven through `XTSC_DEP_SCRIPT`:
 *  - drop `libs.versions.toml` from `xtsc_dep_cache_inputs` → ONLY the
 *    libs-versions pin fails (the module-build-file pin stays green, which is
 *    the point: the old guard passed while being blind);
 *  - drop the entry-existence loop → ONLY the missing-jar pin fails;
 *  - drop the non-empty test → ONLY the empty-cache pin fails.
 *
 * The fixture is a synthetic tree driven through `XTSC_DEP_ROOT`, so these pins
 * never touch the real `build/` and cannot be perturbed by whatever a bench run
 * last wrote there.
 */
class DepClasspathGuardTest {

    @Test
    fun `a cache newer than every build input and naming existing files is accepted`() {
        val f = fixture()
        assert(f.validate(f.cache) == 0)
    }

    @Test
    fun `a cache older than libs versions toml is refused`() {
        val f = fixture()
        // The versions live here, and ONLY here — this is the round-858 bug.
        f.touchNewerThanCache("gradle/libs.versions.toml")
        assert(f.validate(f.cache) != 0)
    }

    @Test
    fun `a cache older than the core module build file is refused`() {
        val f = fixture()
        f.touchNewerThanCache("xemantic-typescript-compiler-core/build.gradle.kts")
        assert(f.validate(f.cache) != 0)
    }

    @Test
    fun `a cache naming a jar that no longer exists is refused`() {
        val f = fixture()
        assert(f.jar.delete())
        assert(f.validate(f.cache) != 0)
    }

    @Test
    fun `an empty cache is refused`() {
        val f = fixture()
        f.cache.writeText("")
        assert(f.validate(f.cache) != 0)
    }

    /**
     * A source-level pin that the wiring itself does not regress: no script may
     * go back to reading the frozen `build/bench/cp.txt` directly. A comment
     * mentioning it (every rewired reader carries one explaining why) is fine —
     * what is banned is a `cat`/`tr`/`<` that consumes it.
     *
     * ONE FILE IS EXEMPT, deliberately and by name:
     * `round858-deptail-equivalence.sh` reads the stale file ON PURPOSE, as the
     * experimental arm that measures what the staleness was worth. An exemption
     * carried as a named constant is visible; the alternative — writing the path
     * indirectly so the regex misses it — would defeat the pin silently.
     */
    @Test
    fun `no script reads the frozen cp txt directly`() {
        val scripts = File(repoRoot(), "scripts")
        val offenders = scripts.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".sh") && it.name !in EXEMPT }
            .flatMap { file ->
                file.readLines().withIndex().mapNotNull { (i, line) ->
                    val code = line.substringBefore('#')
                    if (Regex("""(cat|tr)\b[^|;]*build/bench/cp\.txt|<\s*build/bench/cp\.txt""")
                            .containsMatchIn(code)
                    ) "${file.name}:${i + 1}" else null
                }
            }
            .toList()
        assert(offenders.isEmpty())
    }

    // ---------------------------------------------------------------- fixture

    private class Fixture(val root: File, val cache: File, val jar: File, val script: File) {

        fun validate(cacheFile: File): Int {
            val pb = ProcessBuilder("bash", script.absolutePath, "--validate", cacheFile.absolutePath)
            pb.environment()["XTSC_DEP_ROOT"] = root.absolutePath
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.readBytes()
            return p.waitFor()
        }

        /** Make [rel] strictly newer than the cache, without touching the cache. */
        fun touchNewerThanCache(rel: String) {
            val f = File(root, rel)
            assert(f.isFile)
            assert(f.setLastModified(cache.lastModified() + 10_000))
        }
    }

    private fun fixture(): Fixture {
        val root = File.createTempFile("xtsc-depcp-", "").let { it.delete(); it.mkdirs(); it }
        root.deleteOnExit()
        // A synthetic build tree carrying exactly the inputs the resolver watches.
        for (rel in listOf(
            "gradle/libs.versions.toml",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "xemantic-typescript-compiler-core/build.gradle.kts",
        )) {
            val f = File(root, rel)
            f.parentFile.mkdirs()
            f.writeText("// fixture\n")
        }
        val jar = File(root, "deps/some-dep-1.0.jar")
        jar.parentFile.mkdirs()
        jar.writeText("not really a jar\n")

        val cache = File(root, "build/bench/cp-warm.txt")
        cache.parentFile.mkdirs()
        cache.writeText(jar.absolutePath)

        // The cache must start out newer than every input, or every pin below
        // would pass for the wrong reason.
        val newest = root.walkTopDown().filter { it.isFile }.maxOf { it.lastModified() }
        assert(cache.setLastModified(newest + 60_000))

        return Fixture(root, cache, jar, guardScript())
    }

    private companion object {

        /** See `no script reads the frozen cp txt directly` — the one deliberate arm. */
        val EXEMPT = setOf("round858-deptail-equivalence.sh")

        /**
         * The script under test. `XTSC_DEP_SCRIPT` points the pins at an ABLATED
         * copy — that override exists for the one-mistake-at-a-time ablation and
         * for nothing else.
         */
        fun guardScript(): File {
            System.getenv("XTSC_DEP_SCRIPT")?.let { return File(it) }
            return File(repoRoot(), "scripts/lib/dep-classpath.sh")
        }

        fun repoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
            return dir ?: error("could not locate the repo root from ${System.getProperty("user.dir")}")
        }
    }
}
