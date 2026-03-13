/*
 * TypeScript to JavaScript transpiler in Kotlin multiplatform
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.typescript.compiler

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.time.measureTime

/**
 * Performance benchmark for the TypeScript transpiler.
 *
 * Compiles all `.ts` files from the zod project to measure throughput.
 * Each file is benchmarked independently (warmup + measured iterations)
 * to keep memory bounded — the parser allocates heavily per file.
 *
 * Skips automatically if `benchmark-projects/zod` is not cloned.
 *
 * Run:
 * ```
 * ./gradlew cloneBenchmarkProjects
 * ./gradlew jvmTest --tests '*.TranspilerBenchmark*'
 * ```
 */
class TranspilerBenchmark {

    private val zodSrcDir = Path("benchmark-projects/zod/packages/zod/src")

    private val skipDirs = setOf("tests", "benchmarks", "__tests__")

    /** Warmup iterations per file (JIT compilation). */
    private val warmupIterations = 3

    /** Measured iterations per file. */
    private val measuredIterations = 10

    @Test
    fun benchmarkZodTranspilation() {
        if (!SystemFileSystem.exists(zodSrcDir)) {
            println("SKIP: benchmark-projects/zod not found. Run: ./gradlew cloneBenchmarkProjects")
            return
        }

        val sourceFiles = collectTypeScriptFiles(zodSrcDir)
        if (sourceFiles.isEmpty()) {
            println("SKIP: No .ts files found in $zodSrcDir")
            return
        }

        val totalLoc = sourceFiles.sumOf { it.lines }

        println("=== Transpiler Benchmark: zod ===")
        println("Files: ${sourceFiles.size}, Total LOC: $totalLoc")
        println("Per-file: $warmupIterations warmup + $measuredIterations measured iterations")
        println()

        // Benchmark each file independently to keep memory bounded
        data class FileResult(
            val name: String,
            val lines: Int,
            val avgMs: Double,
            val error: Boolean
        )

        val results = mutableListOf<FileResult>()
        var errorCount = 0

        for (file in sourceFiles) {
            // Warmup
            var hasError = false
            repeat(warmupIterations) {
                if (hasError) return@repeat
                try {
                    TypeScriptCompiler().compile(file.content, file.name)
                } catch (e: Throwable) {
                    hasError = true
                    errorCount++
                    println("ERROR in ${file.name}: ${e::class.simpleName}: ${e.message?.take(80)}")
                }
            }

            if (hasError) {
                results.add(FileResult(file.name, file.lines, 0.0, error = true))
                continue
            }

            // Measured iterations
            var totalMs = 0.0
            repeat(measuredIterations) {
                val duration = measureTime {
                    TypeScriptCompiler().compile(file.content, file.name)
                }
                totalMs += duration.inWholeNanoseconds / 1_000_000.0
            }
            results.add(FileResult(file.name, file.lines, totalMs / measuredIterations, error = false))
        }

        if (errorCount > 0) {
            println()
            println("$errorCount file(s) with compilation errors (excluded from results)")
            println()
        }

        // Aggregate stats — sum per-file averages to get "full compilation" time
        val successResults = results.filter { !it.error }
        val totalAvgMs = successResults.sumOf { it.avgMs }
        val compiledLoc = successResults.sumOf { it.lines }
        val locPerSec = compiledLoc / (totalAvgMs / 1000.0)

        println("=== Results ===")
        println("Total avg compilation time: ${formatMs(totalAvgMs, 1)} ms")
        println("Compiled LOC: $compiledLoc")
        println("Throughput: ${locPerSec.toLong()} LOC/sec")
        println()

        // Per-file breakdown (top 20 by avg time)
        println("=== Per-file breakdown (top 20 by avg time) ===")
        for (r in successResults.sortedByDescending { it.avgMs }.take(20)) {
            println("  ${formatMs(r.avgMs, 2)} ms  ${r.lines} LOC  ${r.name}")
        }
        println()
        println("=== Benchmark complete ===")
    }

    private data class SourceFile(
        val name: String,
        val content: String,
        val lines: Int
    )

    private fun collectTypeScriptFiles(dir: Path): List<SourceFile> {
        val result = mutableListOf<SourceFile>()
        collectRecursive(dir, result)
        return result.sortedBy { it.name }
    }

    /** KMP-compatible decimal formatting. */
    private fun formatMs(value: Double, decimals: Int): String {
        val factor = when (decimals) {
            1 -> 10.0
            2 -> 100.0
            else -> 1.0
        }
        val rounded = kotlin.math.round(value * factor) / factor
        val s = rounded.toString()
        val dotIdx = s.indexOf('.')
        if (dotIdx == -1) return s + "." + "0".repeat(decimals)
        val currentDecimals = s.length - dotIdx - 1
        return if (currentDecimals >= decimals) s.substring(0, dotIdx + 1 + decimals)
        else s + "0".repeat(decimals - currentDecimals)
    }

    private fun collectRecursive(dir: Path, result: MutableList<SourceFile>) {
        val entries = try {
            SystemFileSystem.list(dir).sortedBy { it.toString() }
        } catch (_: Exception) {
            return
        }
        for (entry in entries) {
            val name = entry.name
            val metadata = SystemFileSystem.metadataOrNull(entry)
            if (metadata?.isDirectory == true) {
                if (name !in skipDirs) {
                    collectRecursive(entry, result)
                }
            } else if (name.endsWith(".ts") && !name.endsWith(".d.ts")) {
                val content = "// @target: ESNext\n// @module: ESNext\n" + entry.readText()
                val lines = content.count { it == '\n' } + 1
                val displayName = entry.toString().substringAfter("packages/zod/src/")
                result.add(SourceFile(displayName, content, lines))
            }
        }
    }
}
