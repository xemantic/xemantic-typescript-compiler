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

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test

/**
 * (TSGO.5) THE COMMAND-LINE GATE (docs/goport-cli.md): every case the shipped tsgo binary
 * (`tools/tsgo-7.0.2/lib/tsc`) ran in `build/goport/cli-oracle` (scripts/tsgo-cli-oracle.py: the 8 tsc
 * profiles, the census libraries, cronstrue, marked, type-oracle projects and command-line shapes) is run
 * through the PORTED command line in process ([TsgoCli.run]: same arguments, working directory, environment
 * and default-library directory). Its stdout must equal the binary's BYTE FOR BYTE, its exit status must be
 * the binary's, and for a case run in a scratch copy of its project the files it created or changed must be
 * the same set with the same bytes.
 *
 * Opt-in: `TSGO_CLI=1` (`TSGO_CLI_FILTER=s` runs the cases whose name contains s). FAILS on any difference.
 * Positive control: `TSGO_CLI_INJECT=<case>` corrupts that case's recorded stdout, which must read red.
 */
class CliParityTest {

    private val root = File("..").absoluteFile.normalize()

    private companion object {
        /**
         * Cases whose program does not fit the default test heap, by the heap (GB) they need: they run only when
         * the test JVM has it, and are reported as SKIPPED otherwise. type-fest's whole-program check is ~3.5 GB
         * live in the tsgo binary (`--extendedDiagnostics`: 10.6 M symbols, 5.1 M types) and the port's objects
         * are larger than Go's.
         */
        val HEAVY = mapOf("lib-typefest-noemit" to 8L)
    }

    private class Case(val name: String, val cwd: String, val copy: String?, val args: List<String>, val env: Map<String, String>)

    @Test
    fun `the ported command line prints, exits and emits as the tsgo binary`() {
        if (System.getenv("TSGO_CLI") == null) {
            println("CliParityTest: skipped (set TSGO_CLI=1; record with scripts/tsgo-cli-oracle.py)")
            return
        }
        val dir = File(root, "build/goport/cli-oracle")
        val lines = File(dir, "cases.tsv").readLines().filter { it.isNotBlank() }
        val libDir = lines.first { it.startsWith("#libDir\t") }.substringAfter('\t')
        val filter = System.getenv("TSGO_CLI_FILTER")?.takeIf { it.isNotBlank() }
        val inject = System.getenv("TSGO_CLI_INJECT")?.takeIf { it.isNotBlank() }
        val cases = lines.filter { !it.startsWith("#") }.map { l ->
            val f = l.split('\t')
            Case(
                f[0], f[1], f[2].ifEmpty { null },
                f[3].split('\u001f').filter { it.isNotEmpty() },
                f[4].split('\u001f').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=') },
            )
        }.filter { filter == null || filter in it.name }
        val work = File(root, "build/goport/cli-work/kotlin")
        val out = File(root, "build/goport/cli-kotlin").apply { mkdirs() }
        val progress = File(out, "progress.txt").apply { writeText("") }
        val report = StringBuilder()
        val table = StringBuilder()
        var equal = 0
        var differ = 0
        val skipped = ArrayList<String>()
        val maxHeapGb = Runtime.getRuntime().maxMemory() / (1L shl 30)
        val t0 = System.nanoTime()
        for (c in cases) {
            val needGb = HEAVY[c.name]
            if (needGb != null && maxHeapGb < needGb) {
                skipped += "${c.name} (needs a ${needGb} GB heap, has $maxHeapGb: TSGO_TEST_HEAP=${needGb}g)"
                table.appendLine("%-40s SKIPPED (heap)".format(c.name))
                continue
            }
            val expectDir = File(dir, c.name)
            var wantStdout = File(expectDir, "stdout.txt").readBytes()
            if (c.name == inject) wantStdout += "injected\n".toByteArray()
            val result = File(expectDir, "result.json").readText()
            val wantExit = Regex("\"exit\": (-?\\d+)").find(result)!!.groupValues[1].toInt()
            val wantFiles = Regex("\"([^\"]+)\": \"([0-9a-f]{64})\"").findAll(result.substringAfter("\"files\"")).associate { it.groupValues[1] to it.groupValues[2] }
            val wantStderr = Regex("\"stderr\": \"((?:[^\"\\\\]|\\\\.)*)\"").find(result)!!.groupValues[1]

            val caseWork = c.copy?.let { File(work, c.name).also { w -> prepareCopy(File(it), w) } }
            val cwd = if (caseWork != null) File(caseWork, c.cwd).canonicalFile else File(c.cwd)
            val before = caseWork?.let { snapshot(it) } ?: emptyMap()
            val buf = ByteArrayOutputStream()
            val s0 = System.nanoTime()
            val exit = TsgoCli.run(c.args, cwd.path, { buf.write(it) }, libDirectory = libDir, environment = { c.env[it] })
            val ms = (System.nanoTime() - s0) / 1_000_000
            var gotStdout = buf.toByteArray()
            if (caseWork != null) gotStdout = replaceAll(gotStdout, caseWork.canonicalPath.toByteArray(), "{WORK}".toByteArray())
            val gotFiles = caseWork?.let { w -> snapshot(w).filter { (k, v) -> before[k] != v } } ?: emptyMap()

            val problems = ArrayList<String>()
            if (wantStderr.isNotEmpty()) problems += "tsgo wrote to stderr: ${wantStderr.take(200)}"
            if (exit != wantExit) problems += "exit $exit, tsgo $wantExit"
            if (!gotStdout.contentEquals(wantStdout)) {
                File(out, "${c.name}.stdout.txt").writeBytes(gotStdout)
                problems += "stdout differs (${gotStdout.size} vs ${wantStdout.size} bytes): ${firstDifference(wantStdout, gotStdout)}"
            }
            if (gotFiles != wantFiles) {
                val missing = wantFiles.keys - gotFiles.keys
                val extra = gotFiles.keys - wantFiles.keys
                val changed = wantFiles.keys.intersect(gotFiles.keys).filter { wantFiles[it] != gotFiles[it] }
                problems += "files: ${wantFiles.size} vs ${gotFiles.size}; missing ${missing.take(5)}, extra ${extra.take(5)}, different ${changed.take(5)}"
            }
            val row = "%-40s %s exit=%d stdout=%dB files=%d %d ms".format(c.name, if (problems.isEmpty()) "equal " else "DIFFER", exit, gotStdout.size, gotFiles.size, ms)
            table.appendLine(row)
            val rt = Runtime.getRuntime()
            progress.appendText("$row heap=${(rt.totalMemory() - rt.freeMemory()) shr 20}MB\n")
            if (problems.isEmpty()) equal++ else {
                differ++
                report.append("${c.name} (${c.args.joinToString(" ")}) in ${cwd.path}\n").append(problems.joinToString("") { "  $it\n" })
            }
        }
        val secs = (System.nanoTime() - t0) / 1e9
        val summary = "CliParityTest: ${cases.size} cases in ${"%.1f".format(secs)} s: equal $equal, differ $differ, skipped ${skipped.size}" +
            skipped.joinToString("") { "\n  skipped $it" }
        File(out, "report.txt").writeText("$summary\n\n$table\n$report")
        println(summary)
        println(table)
        if (report.isNotEmpty()) println(report.take(20000))
        check(differ == 0 && equal > 0) { "CLI parity: $differ of ${cases.size} cases differ (build/goport/cli-kotlin/report.txt)" }
    }

    /**
     * Deletes [dir] WITHOUT following symbolic links. Never `File.deleteRecursively()` here: it descends into a
     * linked directory and empties the link's TARGET — a work copy's `node_modules` links to the original project's.
     */
    private fun deleteTree(dir: File) {
        val p = dir.toPath()
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(p).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }

    private fun prepareCopy(src: File, dst: File) {
        deleteTree(dst)
        val skip = setOf(".git", "dist", "node_modules")
        val from = src.toPath()
        Files.walk(from).use { stream ->
            for (p in stream) {
                val rel = from.relativize(p)
                // shutil.ignore_patterns: a directory of one of these names is skipped at any depth
                if (rel.toString().isNotEmpty() && rel.any { it.toString() in skip }) continue
                val target = dst.toPath().resolve(rel.toString())
                when {
                    Files.isSymbolicLink(p) -> Files.createSymbolicLink(target, Files.readSymbolicLink(p))
                    Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) -> Files.createDirectories(target)
                    else -> Files.copy(p, target)
                }
            }
        }
        if (File(src, "node_modules").isDirectory) Files.createSymbolicLink(File(dst, "node_modules").toPath(), File(src, "node_modules").toPath())
    }

    /** Every regular file under [root] (symbolic links not followed) → its sha256. */
    private fun snapshot(root: File): Map<String, String> {
        val out = HashMap<String, String>()
        val base = root.toPath()
        Files.walk(base).use { stream ->
            for (p in stream) {
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue
                if (hasLinkedParent(base, p)) continue
                out[base.relativize(p).toString()] = sha256(Files.readAllBytes(p))
            }
        }
        return out
    }

    private fun hasLinkedParent(base: Path, p: Path): Boolean {
        var q = p.parent
        while (q != null && q != base) {
            if (Files.isSymbolicLink(q)) return true
            q = q.parent
        }
        return false
    }

    private fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun replaceAll(b: ByteArray, from: ByteArray, to: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        var i = 0
        while (i < b.size) {
            if (i + from.size <= b.size && (from.indices).all { b[i + it] == from[it] }) {
                o.write(to)
                i += from.size
            } else o.write(b[i++].toInt())
        }
        return o.toByteArray()
    }

    private fun firstDifference(want: ByteArray, got: ByteArray): String {
        val w = String(want, Charsets.UTF_8).lines()
        val g = String(got, Charsets.UTF_8).lines()
        for (i in 0 until maxOf(w.size, g.size)) {
            if (w.getOrNull(i) != g.getOrNull(i)) return "line ${i + 1}: tsgo «${w.getOrNull(i)?.take(160)}» port «${g.getOrNull(i)?.take(160)}»"
        }
        return "(same lines)"
    }
}
