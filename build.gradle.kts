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

@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jreleaser.model.Active

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.power.assert)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.versions)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.jreleaser)
    alias(libs.plugins.xemantic.conventions)
}

group = "com.xemantic.typescript"

xemantic {
    description = "a conformant TypeScript compiler and type checker that runs on JVM, native, and WebAssembly"
    inceptionYear = "2026"
    applyAllConventions()
}

fun MavenPomDeveloperSpec.projectDevs() {
    developer {
        id = "morisil"
        name = "Kazik Pogoda"
        url = "https://github.com/morisil"
    }
}

/**
 * Runs a shell command, streaming its output to the Gradle console.
 * Throws an [IllegalStateException] if the process exits with a non-zero code.
 */
fun runCommand(vararg cmd: String, workingDir: File = projectDir) {
    val exitCode = ProcessBuilder(*cmd)
        .directory(workingDir)
        .inheritIO()
        .start()
        .waitFor()
    check(exitCode == 0) { "Command failed (exit $exitCode): ${cmd.joinToString(" ")}" }
}

/**
 * Runs a shell command and returns its captured stdout as a UTF-8 string.
 * Throws an [IllegalStateException] if the process exits with a non-zero code.
 */
fun captureCommand(vararg cmd: String, workingDir: File = projectDir): String {
    val process = ProcessBuilder(*cmd)
        .directory(workingDir)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    // Drain stdout fully BEFORE waitFor to avoid pipe-buffer deadlock.
    val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
    val exitCode = process.waitFor()
    check(exitCode == 0) { "Command failed (exit $exitCode): ${cmd.joinToString(" ")}" }
    return output
}

val javaTarget = libs.versions.javaTarget.get()
val kotlinTarget = KotlinVersion.fromVersion(libs.versions.kotlinTarget.get())

kotlin {

    compilerOptions {
        apiVersion = kotlinTarget
        languageVersion = kotlinTarget
        freeCompilerArgs.addAll(
            "-Xcontext-sensitive-resolution",
            // StackOverflowError is expect/actual (JVM typealias interop) — the
            // sanctioned use case for expect/actual classes.
            "-Xexpect-actual-classes"
        )
        extraWarnings = true
        progressiveMode = true
        //optIn.addAll("add opt ins here")
    }

    jvm {
        // set up according to https://jakewharton.com/gradle-toolchains-are-rarely-a-good-idea/
        compilerOptions {
            apiVersion = kotlinTarget
            languageVersion = kotlinTarget
            jvmTarget = JvmTarget.fromTarget(javaTarget)
            freeCompilerArgs.add("-Xjdk-release=$javaTarget")
            progressiveMode = true
        }
    }

    // native, see https://kotlinlang.org/docs/native-target-support.html
    // tier 1
//    macosX64 {
//        binaries.executable {
//            entryPoint = "com.xemantic.typescript.compiler.main"
//        }
//    }
//    macosArm64 {
//        binaries.executable {
//            entryPoint = "com.xemantic.typescript.compiler.main"
//        }
//    }

    // tier 2
    // INV.7 native re-enable (pre-approved M5 exception; round 610): host-buildable
    // target only — Apple targets stay commented until a macOS builder exists.
    linuxX64 {
        binaries.executable {
            entryPoint = "com.xemantic.typescript.compiler.main"
        }
    }
//    linuxArm64 {
//        binaries.executable {
//            entryPoint = "com.xemantic.typescript.compiler.main"
//        }
//    }

    sourceSets {

        commonMain {
            // RealLibFiles.kt — the real TypeScript lib .d.ts sources, generated by
            // generateRealLibSources (all Kotlin compile tasks depend on it, see below).
            kotlin.srcDir(layout.buildDirectory.dir("generated/real-lib"))
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                // Filesystem access for the whole-project build driver (CLI + tsconfig
                // loading + module resolution). kotlinx-io is multiplatform (JVM/Native/WASI),
                // so the project driver stays in commonMain rather than a jvm-only source set.
                implementation(libs.kotlinx.io.core)
                // tsconfig.json / package.json parsing (JSONC: comments + trailing commas).
                implementation(libs.kotlinx.serialization.json)
            }
        }

        commonTest {
            kotlin.srcDir(layout.buildDirectory.dir("generated/typescript-tests"))
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.xemantic.kotlin.test)
                implementation(libs.kotlinx.io.core)
            }
        }

    }

}

// ---------------------------------------------------------------------------
// Whole-project build CLI runner
// ---------------------------------------------------------------------------
//
// Runs the filesystem-based project compiler (com.xemantic.typescript.compiler.main)
// against a real directory containing a tsconfig.json. Usage:
//
//   ./gradlew compileTsProject -Pargs="/path/to/project"
//   ./gradlew compileTsProject -Pargs="--project zod --noEmit"
//
tasks.register<JavaExec>("compileTsProject") {
    group = "application"
    description = "Compile a real on-disk TypeScript project (tsconfig + globs + module resolution)."
    val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
    dependsOn(jvmMain.compileTaskProvider)
    classpath = files(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    mainClass.set("com.xemantic.typescript.compiler.MainKt")
    (project.findProperty("args") as String?)?.let { setArgs(it.split(" ").filter { a -> a.isNotEmpty() }) }
}

// ---------------------------------------------------------------------------
// TypeScript compiler test harness
// ---------------------------------------------------------------------------

/**
 * The local directory where the TypeScript repository is sparse-cloned.
 * Listed in .gitignore — persists across `./gradlew clean` runs.
 */
val typeScriptRepoDir = projectDir.resolve("typescript-repo")

/**
 * The exact mainline TypeScript commit our test corpus is PINNED to. This is the
 * PRISTINE tsc commit that tsgo's `_submodules/TypeScript` submodule MERGES IN — the
 * `main`-side parent of tsgo's current `tsgo-port` merge — NOT the tsgo-port branch
 * tip itself.
 *
 * That distinction is LOAD-BEARING: the tsgo-port branch REGENERATES its reference
 * baselines to the Go compiler's output (e.g. a different, type-id-based union-member
 * display ordering: `'boolean' | 'number'` where tsc emits `'number' | 'boolean'`),
 * which are tsgo DIVERGENCES. We deliberately diff char-by-char against ORIGINAL tsc
 * (project owner's directive), so we pin to the pristine `main` commit tsgo tracks —
 * giving tsgo's exact test-case set (set A) with real tsc baselines.
 *
 * To follow tsgo forward, read tsgo's submodule sha, then take its `main`-side parent:
 *   S=$(curl -s https://api.github.com/repos/microsoft/typescript-go/contents/_submodules/TypeScript | grep '"sha"')
 *   # that sha is a "Merge branch 'main' into tsgo-port" commit; the pristine target
 *   # is its 2nd parent:
 *   curl -s https://api.github.com/repos/microsoft/TypeScript/commits/<sha>   # -> parents[1].sha
 */
val typeScriptCommit = "637d5746b70257028fb95aad32ddec6b26ab0a14" // pristine tsc @ 2026-06-25 (main-parent of tsgo pin 4d4f005c)

/**
 * Performs a sparse, PINNED, partial clone of the Microsoft TypeScript repository,
 * fetching only the compiler test cases and their expected baselines.
 *
 * The clone is PINNED to [typeScriptCommit] (tsgo's submodule commit) and the task
 * is idempotent: if `typescript-repo/.git` already exists it fetches + checks out
 * the pin in place (a no-op if already there); otherwise it does a fresh
 * partial+sparse clone and checks out the pin. [typeScriptCommit] is declared as a
 * task input, so bumping it re-runs the task and the corpus follows the pin.
 *
 * Run explicitly before the first test run, or simply invoke any test task
 * (which depends on this task transitively via `generateTypeScriptTests`):
 * ```
 * ./gradlew cloneTypeScriptRepo
 * ./gradlew jvmTest
 * ```
 */
val cloneTypeScriptRepo by tasks.registering {
    group = "typescript"
    description = "Sparse-clones the TypeScript repository (tests only), pinned to tsgo's submodule commit."
    inputs.property("typeScriptCommit", typeScriptCommit) // re-run when the pin changes
    outputs.dir(typeScriptRepoDir)

    doLast {
        val sparsePaths = arrayOf("tests/cases/compiler", "tests/baselines/reference")

        if (typeScriptRepoDir.resolve(".git").exists()) {
            // Re-pin an existing clone. `fetch --depth=1 <sha>` grabs just the pinned
            // commit (GitHub serves a reachable SHA); checkout is a no-op if already there.
            logger.lifecycle("Re-pinning TypeScript repository to $typeScriptCommit ...")
            runCommand("git", "sparse-checkout", "set", *sparsePaths, workingDir = typeScriptRepoDir)
            runCommand("git", "fetch", "--depth=1", "origin", typeScriptCommit, workingDir = typeScriptRepoDir)
            runCommand("git", "checkout", "--force", typeScriptCommit, workingDir = typeScriptRepoDir)
            logger.lifecycle("TypeScript repository re-pinned successfully.")
            return@doLast
        }

        logger.lifecycle("Cloning TypeScript repository (pinned $typeScriptCommit, partial+sparse) into: $typeScriptRepoDir ...")

        // Step 1: depth-1 partial (blob:none) + sparse + no-checkout clone — fetch only
        //         tree/commit objects (blobs lazy-loaded) and don't materialize a working
        //         tree until we pin the commit.
        runCommand(
            "git", "clone",
            "--depth=1",
            "--filter=blob:none",
            "--sparse",
            "--no-checkout",
            "https://github.com/microsoft/TypeScript.git",
            typeScriptRepoDir.absolutePath,
        )
        // Step 2: restrict the working tree to only the paths we need (blobs fetched
        //         exclusively for these two directories).
        runCommand("git", "sparse-checkout", "set", *sparsePaths, workingDir = typeScriptRepoDir)
        // Step 3: fetch and check out exactly the pinned commit (depth=1 — no history).
        runCommand("git", "fetch", "--depth=1", "origin", typeScriptCommit, workingDir = typeScriptRepoDir)
        runCommand("git", "checkout", typeScriptCommit, workingDir = typeScriptRepoDir)

        logger.lifecycle("TypeScript repository cloned + pinned successfully.")
    }
}

/**
 * Embeds the real TypeScript `.d.ts` lib sources from `src/lib` (the non-DOM ES set — DOM /
 * webworker / scripthost are M2.4, post-v1) as generated Kotlin (`RealLibFiles.kt`
 * in commonMain), read straight from the pinned commit's object DB — the sparse
 * working tree does not materialize `src/lib`, but the depth-1 fetch of
 * [typeScriptCommit] brought all of its blobs down, so extraction works offline.
 *
 * TRAP this generator exists to dodge: a JVM class-file string constant caps at
 * 65,535 bytes of modified UTF-8 (es5.d.ts is 218 KB) — each file is emitted as
 * `StringBuilder.append(...)` chunks of ≤ 60,000 value bytes, split at line
 * boundaries, concatenated at runtime. Never fold the chunks back into a single
 * literal (or a `const val` concatenation, which constant-folds at compile time).
 */
val generateRealLibSources by tasks.registering {
    group = "typescript"
    description = "Generates RealLibFiles.kt embedding the real TypeScript lib .d.ts sources (non-DOM set)."

    dependsOn(cloneTypeScriptRepo)
    inputs.property("typeScriptCommit", typeScriptCommit)
    val outputDir = layout.buildDirectory.dir("generated/real-lib")
    outputs.dir(outputDir)

    doLast {
        val packageDir = outputDir.get().asFile.resolve("com/xemantic/typescript/compiler")
        packageDir.mkdirs()

        val names = captureCommand(
            "git", "ls-tree", "--name-only", typeScriptCommit, "src/lib/",
            workingDir = typeScriptRepoDir,
        ).lines()
            .map { it.removePrefix("src/lib/") }
            .filter { it.endsWith(".d.ts") }
            .filterNot { it.startsWith("dom.") || it.startsWith("webworker.") || it.startsWith("scripthost") }
            .sorted()
        check(names.isNotEmpty()) { "No lib .d.ts files found at $typeScriptCommit:src/lib/" }

        // Modified-UTF-8 byte length of one char in a class-file string constant.
        fun mutf8Len(ch: Char): Int = when {
            ch == '\u0000' -> 2 // NUL uses the 2-byte form in modified UTF-8
            ch.code < 0x80 -> 1
            ch.code < 0x800 -> 2
            else -> 3
        }

        fun escape(s: String): String = buildString(s.length + 16) {
            for (ch in s) when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\\$")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) {
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else append(ch)
            }
        }

        /** Splits [content] into chunks of ≤ [maxBytes] modified-UTF-8 value bytes, at line boundaries. */
        fun chunk(content: String, maxBytes: Int = 60_000): List<String> {
            val chunks = mutableListOf<String>()
            var chunkStart = 0
            var chunkBytes = 0
            var lineStart = 0
            var i = 0
            fun flushAt(pos: Int) {
                if (pos > chunkStart) chunks.add(content.substring(chunkStart, pos))
                chunkStart = pos
                chunkBytes = 0
            }
            while (i < content.length) {
                var lineBytes = 0
                var j = lineStart
                while (j < content.length && content[j] != '\n') { lineBytes += mutf8Len(content[j]); j++ }
                if (j < content.length) { lineBytes += 1; j++ } // the '\n'
                if (chunkBytes + lineBytes > maxBytes) {
                    flushAt(lineStart)
                    // Degenerate single line longer than maxBytes: hard-split by chars
                    // (safe — modified UTF-8 encodes each char independently, so even a
                    // surrogate pair split across two constants reassembles at runtime).
                    while (lineBytes > maxBytes) {
                        var bytes = 0
                        var k = chunkStart
                        while (k < j && bytes + mutf8Len(content[k]) <= maxBytes) { bytes += mutf8Len(content[k]); k++ }
                        flushAt(k)
                        lineBytes -= bytes
                    }
                    chunkBytes = lineBytes
                } else {
                    chunkBytes += lineBytes
                }
                lineStart = j
                i = j
            }
            flushAt(content.length)
            return chunks
        }

        val sb = StringBuilder(4 * 1024 * 1024)
        sb.appendLine("// Auto-generated by ./gradlew generateRealLibSources. Do not edit.")
        sb.appendLine("// Real TypeScript lib sources pinned to commit $typeScriptCommit.")
        sb.appendLine("package com.xemantic.typescript.compiler")
        sb.appendLine()
        sb.appendLine("/**")
        sb.appendLine(" * The real TypeScript `.d.ts` lib sources from `src/lib` (non-DOM ES set), keyed by bare")
        sb.appendLine(" * lib name (`es5`, `es2015.core`, `decorators`, ...). Content is byte-identical")
        sb.appendLine(" * to the pinned tsc commit's files (CRLF line endings preserved).")
        sb.appendLine(" */")
        sb.appendLine("object RealLibFiles {")
        sb.appendLine("    val files: Map<String, String> = buildMap {")
        sb.appendLine("        val sb = StringBuilder(262_144)")
        var totalBytes = 0L
        for (name in names) {
            val content = captureCommand(
                "git", "show", "$typeScriptCommit:src/lib/$name",
                workingDir = typeScriptRepoDir,
            )
            totalBytes += content.sumOf { mutf8Len(it) }
            val chunks = chunk(content)
            sb.appendLine("        // $name (${chunks.size} chunk(s))")
            for (c in chunks) sb.appendLine("        sb.append(\"${escape(c)}\")")
            sb.appendLine("        put(\"${name.removeSuffix(".d.ts")}\", sb.toString())")
            sb.appendLine("        sb.setLength(0)")
        }
        sb.appendLine("    }")
        sb.appendLine("}")

        packageDir.resolve("RealLibFiles.kt").writeText(sb.toString())
        logger.lifecycle("Generated RealLibFiles.kt: ${names.size} lib files, $totalBytes bytes of lib source.")
    }
}

// RealLibFiles.kt is commonMain source — every Kotlin compilation needs it generated first.
tasks.matching { it.name.startsWith("compile") && "Kotlin" in it.name }
    .configureEach { dependsOn(generateRealLibSources) }

/**
 * Generates Kotlin multiplatform `@Test` functions from the official TypeScript compiler
 * test suite. Each TypeScript test case and its baseline reference files become one or more
 * standard `kotlin.test.@Test` functions with descriptive backtick names.
 *
 * Generated tests live in `build/generated/typescript-tests/` which is wired into the
 * `commonTest` source set. Run this task (or any test task, which depends on it) to
 * regenerate after the TypeScript repo is updated:
 * ```
 * ./gradlew generateTypeScriptTests
 * ./gradlew jvmTest
 * ```
 *
 * ### Test naming
 * Test names use Kotlin backtick syntax so they read as sentences, e.g.:
 * - `` `2dArrays.ts compiles to JavaScript matching 2dArrays.js` ``
 * - `` `2dArrays.ts has expected compilation errors matching 2dArrays.errors.txt` ``
 *
 * This allows an LLM running `./gradlew jvmTest` to immediately understand which
 * TypeScript file failed and what baseline was expected.
 *
 * ### Assertions
 * JavaScript output tests use [String?.sameAs(Path)][com.xemantic.typescript.compiler.sameAs]
 * which produces a unified diff on failure — giving the LLM a precise, token-efficient
 * signal about what changed.
 */
val generateTypeScriptTests by tasks.registering {
    group = "typescript"
    description = "Generates Kotlin test cases from the TypeScript compiler test suite."

    dependsOn(cloneTypeScriptRepo)

    val testsDir = typeScriptRepoDir.resolve("tests/cases/compiler")
    val baselinesDir = typeScriptRepoDir.resolve("tests/baselines/reference")
    val outputDir = layout.buildDirectory.dir("generated/typescript-tests")

    inputs.dir(testsDir).optional()
    outputs.dir(outputDir)

    doLast {
        val packageDir = outputDir.get().asFile
            .resolve("com/xemantic/typescript/compiler")
        packageDir.mkdirs()

        if (!testsDir.exists()) {
            logger.lifecycle("TypeScript test cases not found — skipping test generation.")
            logger.lifecycle("Run: ./gradlew cloneTypeScriptRepo generateTypeScriptTests")
            return@doLast
        }

        val testFiles = testsDir.listFiles { f -> f.isFile && f.extension == "ts" }
            ?.sortedBy { it.name }
            ?: emptyList()

        logger.lifecycle("Generating Kotlin tests for ${testFiles.size} TypeScript test cases...")

        // $ sign for use in generated Kotlin string templates
        val D = "\$"

        // Directives that use commas as list separators, NOT multi-value variation
        val nonVaryDirectives = setOf("lib", "types", "paths", "rootdirs", "typeroots")

        // Regex to extract // @option: value directives from test source
        val directiveRegex = Regex("""^//\s*@(\w+)\s*:\s*(.+)""", RegexOption.MULTILINE)

        /**
         * Parse directives from a test source file, returning a map of
         * lowercase option name to raw value string.
         */
        fun parseDirectives(source: String): Map<String, String> {
            val directives = mutableMapOf<String, String>()
            for (match in directiveRegex.findAll(source)) {
                val key = match.groupValues[1].trim().lowercase()
                val value = match.groupValues[2].trim()
                if (key != "filename") { // @Filename is structural, not an option
                    directives[key] = value
                }
            }
            return directives
        }

        /**
         * Compute parameterized test variations from multi-value directives.
         * Returns empty list if no multi-value directives are found.
         * Each variation is a map of option name to single value.
         */
        fun computeVariations(directives: Map<String, String>): List<Map<String, String>> {
            val varyBy = mutableListOf<Pair<String, List<String>>>()
            for ((key, value) in directives) {
                if (key in nonVaryDirectives) continue
                if (',' !in value) continue
                val values = value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                if (values.size > 1) {
                    varyBy.add(key to values)
                }
            }
            if (varyBy.isEmpty()) return emptyList()

            // Compute Cartesian product (keys sorted alphabetically)
            var result = listOf(emptyMap<String, String>())
            for ((key, values) in varyBy.sortedBy { it.first }) {
                result = result.flatMap { existing ->
                    values.map { v -> existing + (key to v) }
                }
                if (result.size > 25) {
                    // Safety limit exceeded — skip parameterized tests for this file
                    return emptyList()
                }
            }
            return result
        }

        /**
         * Construct parameterized baseline filename:
         * name(key1=value1,key2=value2).ext
         */
        fun paramBaselineName(baseName: String, config: Map<String, String>, ext: String): String {
            val configStr = config.entries.sortedBy { it.key }
                .joinToString(",") { "${it.key}=${it.value}" }
            return "$baseName($configStr).$ext"
        }

        // tsgo set-B — the EXACT two mechanisms tsgo's harness uses to reduce the tsc corpus
        // (verbatim from microsoft/typescript-go, see TSGO-RELEVANCE.md):
        //
        // 1. `skippedTests` (internal/testrunner/compiler_runner.go) — files tsgo drops ENTIRELY.
        //    Two groups: tests that depend on `typescript.d.ts` (the TS public API — we don't
        //    implement it), and tests using options tsgo removed so completely they no longer PARSE
        //    (verbatimModuleSyntax compat shims, preserveValueImports, importsNotUsedAsValues,
        //    keyofStringsOnly, noStrictGenericChecks, module:none emit, noImplicitUseStrict, …).
        //    (tsgo's `skippedEmitTests` — 8 files skipped only because Go's PARALLEL emit is
        //    nondeterministic — is deliberately NOT mirrored: it is a Go-runtime artifact, not a
        //    removed feature, and our single-threaded harness is deterministic.)
        val tsgoSkippedTests = setOf(
            // depend on typescript.d.ts (TS public-API self-hosting tests)
            "APILibCheck", "APISample_Watch", "APISample_WatchWithDefaults",
            "APISample_WatchWithOwnWatchHost", "APISample_compile", "APISample_jsdoc",
            "APISample_linter", "APISample_parseConfig", "APISample_transform", "APISample_watcher",
            // options removed in tsgo → fail to parse there
            "preserveUnusedImports", "noCrashWithVerbatimModuleSyntaxAndImportsNotUsedAsValues",
            "verbatimModuleSyntaxCompat", "verbatimModuleSyntaxCompat2", "verbatimModuleSyntaxCompat3",
            "verbatimModuleSyntaxCompat4", "preserveValueImports",
            "preserveValueImports_importsNotUsedAsValues", "preserveValueImports_errors",
            "preserveValueImports_mixedImports", "preserveValueImports_module",
            "importsNotUsedAsValues_error", "alwaysStrictNoImplicitUseStrict",
            "nonPrimitiveIndexingWithForInSupressError", "parameterInitializerBeforeDestructuringEmit",
            "mappedTypeUnionConstraintInferences", "lateBoundConstraintTypeChecksCorrectly",
            "keyofDoesntContainSymbols", "isolatedModulesOut", "noStrictGenericChecks",
            "noImplicitUseStrict_umd", "noImplicitUseStrict_system", "noImplicitUseStrict_es6",
            "noImplicitUseStrict_commonjs", "noImplicitUseStrict_amd", "noImplicitAnyIndexingSuppressed",
            "excessPropertyErrorsSuppressed", "moduleNoneDynamicImport", "moduleNoneErrors",
            "moduleNoneOutFile", "noErrorUsingImportExportModuleAugmentationInDeclarationFile1",
            "noErrorUsingImportExportModuleAugmentationInDeclarationFile2",
            "noErrorUsingImportExportModuleAugmentationInDeclarationFile3",
            "requireOfJsonFileWithModuleEmitNone", "requireOfJsonFileWithModuleNodeResolutionEmitNone",
        )

        // 2. `SkipUnsupportedCompilerOptions` (internal/testutil/harnessutil/harnessutil.go) — skip a
        //    whole test CONFIG (BOTH its .errors.txt AND its .js/.d.ts subtests) when its resolved
        //    options include a tsgo-removed feature. Verbatim port of that function's switch/if chain.
        //    tsgo applies it to the harness-PARSED options (explicit directives only — unset options
        //    stay at their zero value and never match), so matching on source directives here is
        //    faithful: it fires only on an EXPLICIT directive. (es3 is included alongside es5 — es3 was
        //    removed one release earlier; both are dead emit targets. "node" is the legacy alias for
        //    the removed node10 resolution.)
        fun usesUnsupportedOption(directives: Map<String, String>, config: Map<String, String>): Boolean {
            fun getVal(key: String): String? = config[key] ?: directives[key]
            fun anyOf(key: String, bad: Set<String>): Boolean =
                getVal(key)?.split(',')?.any { it.trim().lowercase() in bad } == true
            fun isFalse(key: String): Boolean =
                getVal(key)?.split(',')?.any { it.trim().lowercase() == "false" } == true
            return anyOf("target", setOf("es3", "es5")) ||
                anyOf("module", setOf("amd", "umd", "system")) ||
                anyOf("moduleresolution", setOf("node", "node10", "classic")) ||
                getVal("outfile")?.isNotBlank() == true ||
                getVal("baseurl")?.isNotBlank() == true ||
                isFalse("esmoduleinterop") ||
                isFalse("allowsyntheticdefaultimports") ||
                isFalse("alwaysstrict")
        }

        // 2b. tsgo's SkipUnsupportedCompilerOptions runs on the harness-PARSED options, so a
        //     tsconfig.json EMBEDDED in the test (`@filename: .../tsconfig.json`) BYPASSES the
        //     directive-based filter above — tsgo itself still runs such tests (its compiler then
        //     rejects the option at config-load), but our baselines are pinned to PRISTINE tsc,
        //     so keeping them would pin removed-feature behavior.
        //     DELIBERATELY NARROWER than usesUnsupportedOption (2026-07-02, user-approved): only
        //     the options whose implementation was physically REMOVED from this compiler are
        //     checked — `module: amd/umd/system` (the UMD/System/AMD transforms are deleted) and
        //     `outFile` (the bundling concat is deleted). tsconfig-in-test `target: es5`,
        //     `moduleResolution: node10`, and `baseUrl` are NOT checked: ~55 active tests use them
        //     INCIDENTALLY while pinning still-relevant behavior (paths mapping, suffix
        //     resolution, declaration emit) that this compiler handles gracefully.
        //     Only a file NAMED exactly tsconfig.json counts (the harness loads it as project
        //     config); tsconfig1.json etc. are plain source-echo files.
        fun tsconfigInTestUsesRemovedFeature(source: String): Boolean {
            val sections = Regex("""(?im)^\s*//\s*@filename:\s*(\S+)\s*$""").findAll(source).toList()
            for ((i, m) in sections.withIndex()) {
                if (!m.groupValues[1].substringAfterLast('/').equals("tsconfig.json", ignoreCase = true)) continue
                val start = m.range.last + 1
                val end = if (i + 1 < sections.size) sections[i + 1].range.first else source.length
                val body = source.substring(start, end)
                if (Regex("""(?i)"module"\s*:\s*"(amd|umd|system)"""").containsMatchIn(body)) return true
                if (Regex("""(?i)"outFile"\s*:\s*"[^"]+"""").containsMatchIn(body)) return true
            }
            return false
        }

        // Group by first character to keep individual files manageable
        val groups = testFiles.groupBy { file ->
            val ch = file.nameWithoutExtension.first()
            if (ch.isLetter()) ch.uppercaseChar() else '#'
        }

        var totalBareTests = 0
        var totalParamTests = 0
        var totalErrorTests = 0

        for ((groupChar, files) in groups.entries.sortedBy { it.key }) {
            val suffix = if (groupChar == '#') "Numeric" else groupChar.toString()
            val className = "TypeScriptCompilerTests_$suffix"
            val sb = StringBuilder()

            sb.appendLine("// Auto-generated by ./gradlew generateTypeScriptTests. Do not edit.")
            sb.appendLine("package com.xemantic.typescript.compiler")
            sb.appendLine()
            sb.appendLine("import kotlinx.io.files.Path")
            sb.appendLine("import kotlin.test.Test")
            sb.appendLine("import kotlin.test.assertTrue")
            sb.appendLine()
            sb.appendLine("class $className {")

            for (file in files) {
                val name = file.nameWithoutExtension
                // Kotlin 2.x does not allow dots in JVM method names, even in backtick-quoted identifiers.
                // Replace every dot in the base name with an underscore for the function identifier.
                val id = name.replace('.', '_')
                val source = file.readText()
                val directives = parseDirectives(source)

                // tsgo set-B (see the tsgoSkippedTests / usesUnsupportedOption definitions above):
                // (1) whole-file skip for tsgo's hardcoded skippedTests list.
                if (name in tsgoSkippedTests) continue
                // (1b) whole-file skip when a tsconfig.json EMBEDDED in the test sets a
                //      removed-module/outFile option (bypasses the directive-based filter — see
                //      tsconfigInTestUsesRemovedFeature above). Drops exactly 4 tests as of
                //      2026-07-02: deprecatedCompilerOptions2/6, tsconfigMapOptionsAreCaseInsensitive,
                //      outFileIsDeprecated.
                if (tsconfigInTestUsesRemovedFeature(source)) continue
                // (2) whole-CONFIG skip (errors AND emit) when the bare config's fixed directives
                //     resolve to a tsgo-removed option. Unlike the previous heuristic (which dropped
                //     only the JS-emit subtest and KEPT the error baseline for ES3/ES5/AMD/System/UMD),
                //     tsgo's SkipUnsupportedCompilerOptions skips the whole config, so the error
                //     baseline is dropped too.
                val bareUnsupported = usesUnsupportedOption(directives, emptyMap())

                val jsBaseline = baselinesDir.resolve("$name.js")
                // .d.ts sections in baselines are stripped by TypeScriptTestSupport.stripDtsSection()
                // so tests with declaration output can be included safely.
                if (jsBaseline.exists() && !bareUnsupported) {
                    totalBareTests++
                    sb.appendLine()
                    sb.appendLine("    @Test")
                    sb.appendLine("    fun `${id}_ts compiles to JavaScript matching ${id}_js`() {")
                    sb.appendLine("        val source = Path(\"${D}typeScriptCasesDir/$name.ts\").readText()")
                    sb.appendLine("        TypeScriptCompiler().compile(source, \"$name.ts\").toBaseline()")
                    sb.appendLine("            .sameAs(Path(\"${D}typeScriptBaselineDir/$name.js\"))")
                    sb.appendLine("    }")
                }

                // Parameterized test variations
                val variations = computeVariations(directives)

                for (config in variations) {
                    val paramName = paramBaselineName(name, config, "js")
                    val paramBaseline = baselinesDir.resolve(paramName)
                    if (paramBaseline.exists()) {
                        // Skip this whole config if its resolved options include a tsgo-removed
                        // feature (the varying config value overrides the fixed directive of the
                        // same key; a fixed unsupported directive is caught via `directives`).
                        if (usesUnsupportedOption(directives, config)) continue
                        totalParamTests++
                        // Build config suffix for test function name (e.g., target_es5 or alwaysstrict_true_target_es2015)
                        val configId = config.entries.sortedBy { it.key }
                            .joinToString("_") { "${it.key}_${it.value}" }
                            .replace('.', '_')
                        // Build overrides map literal for generated code
                        val overridesStr = config.entries.sortedBy { it.key }
                            .joinToString(", ") { "\"${it.key}\" to \"${it.value}\"" }
                        sb.appendLine()
                        sb.appendLine("    @Test")
                        sb.appendLine("    fun `${id}_ts__${configId}__compiles to JavaScript matching baseline`() {")
                        sb.appendLine("        val source = Path(\"${D}typeScriptCasesDir/$name.ts\").readText()")
                        sb.appendLine("        TypeScriptCompiler().compile(source, \"$name.ts\", mapOf($overridesStr)).toBaseline()")
                        sb.appendLine("            .sameAs(Path(\"${D}typeScriptBaselineDir/$paramName\"))")
                        sb.appendLine("    }")
                    }
                }

                // .errors.txt baseline test (bare-name). tsgo skips the WHOLE config (the error
                // baseline too, not just emit) for a removed-feature option, so gate on bareUnsupported.
                val errorsBaseline = baselinesDir.resolve("$name.errors.txt")
                if (errorsBaseline.exists() && !bareUnsupported) {
                    totalErrorTests++
                    sb.appendLine()
                    sb.appendLine("    @Test")
                    sb.appendLine("    fun `${id}_ts has expected errors matching ${id}_errors_txt`() {")
                    sb.appendLine("        val source = Path(\"${D}typeScriptCasesDir/$name.ts\").readText()")
                    sb.appendLine("        TypeScriptCompiler().compile(source, \"$name.ts\")")
                    sb.appendLine("            .errorsMatchBaseline(Path(\"${D}typeScriptBaselineDir/$name.errors.txt\"))")
                    sb.appendLine("    }")
                }

                // .errors.txt parameterized baseline tests
                for (config in variations) {
                    val paramErrorName = paramBaselineName(name, config, "errors.txt")
                    val paramErrorBaseline = baselinesDir.resolve(paramErrorName)
                    if (paramErrorBaseline.exists() && !usesUnsupportedOption(directives, config)) {
                        totalErrorTests++
                        val configId = config.entries.sortedBy { it.key }
                            .joinToString("_") { "${it.key}_${it.value}" }
                            .replace('.', '_')
                        val overridesStr = config.entries.sortedBy { it.key }
                            .joinToString(", ") { "\"${it.key}\" to \"${it.value}\"" }
                        sb.appendLine()
                        sb.appendLine("    @Test")
                        sb.appendLine("    fun `${id}_ts__${configId}__has expected errors matching baseline`() {")
                        sb.appendLine("        val source = Path(\"${D}typeScriptCasesDir/$name.ts\").readText()")
                        sb.appendLine("        TypeScriptCompiler().compile(source, \"$name.ts\", mapOf($overridesStr))")
                        sb.appendLine("            .errorsMatchBaseline(Path(\"${D}typeScriptBaselineDir/$paramErrorName\"))")
                        sb.appendLine("    }")
                    }
                }
            }

            sb.appendLine()
            sb.appendLine("}")

            packageDir.resolve("$className.kt").writeText(sb.toString())
        }

        logger.lifecycle("Generated $totalBareTests bare-name JS + $totalParamTests parameterized JS + $totalErrorTests error baseline = ${totalBareTests + totalParamTests + totalErrorTests} test functions across ${groups.size} files in: $packageDir")
    }
}

// Make every Kotlin test compilation task depend on the generator so that
// `./gradlew jvmTest` (or any platform test) is all that's needed.
tasks.matching { it.name.startsWith("compile") && "Test" in it.name && "Kotlin" in it.name }
    .configureEach { dependsOn(generateTypeScriptTests) }

// Ensure tests run with the project root as working directory so that
// kotlinx.io's Path("typescript-repo") resolves correctly on all platforms.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    workingDir = projectDir
}

// ---------------------------------------------------------------------------

repositories {
    mavenCentral()
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.ow2.asm") {
            useVersion(libs.versions.asm.get())
        }
    }
}

powerAssert {
    functions = listOf(
        "com.xemantic.kotlin.test.assert",
        "com.xemantic.kotlin.test.have"
    )
}

// https://kotlinlang.org/docs/dokka-migration.html#adjust-configuration-options
dokka {
    pluginsConfiguration.html {
        footerMessage = xemantic.copyright
    }
}

mavenPublishing {

    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    pom {

        name = rootProject.name
        description = xemantic.description
        inceptionYear = xemantic.inceptionYear
        url = "https://github.com/${xemantic.gitHubAccount}/${rootProject.name}"

        organization {
            name = xemantic.organization
            url = xemantic.organizationUrl
        }

        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }

        scm {
            url = "https://github.com/${xemantic.gitHubAccount}/${rootProject.name}"
            connection = "scm:git:git://github.com/${xemantic.gitHubAccount}/${rootProject.name}.git"
            developerConnection = "scm:git:ssh://git@github.com/${xemantic.gitHubAccount}/${rootProject.name}.git"
        }

        ciManagement {
            system = "GitHub"
            url = "https://github.com/${xemantic.gitHubAccount}/${rootProject.name}/actions"
        }

        issueManagement {
            system = "GitHub"
            url = "https://github.com/${xemantic.gitHubAccount}/${rootProject.name}/issues"
        }

        developers {
            projectDevs()
        }

    }

}

val releaseAnnouncementSubject = """🚀 ${rootProject.name} $version has been released!"""
val releaseAnnouncement = """
$releaseAnnouncementSubject

${xemantic.description}

${xemantic.releasePageUrl}
""".trim()

jreleaser {

    announce {
        webhooks {
            create("discord") {
                active = Active.ALWAYS
                message = releaseAnnouncement
                messageProperty = "content"
                structuredMessage = true
            }
        }
        linkedin {
            active = Active.ALWAYS
            subject = releaseAnnouncementSubject
            message = releaseAnnouncement
        }
        bluesky {
            active = Active.ALWAYS
            status = releaseAnnouncement
        }
    }

}
