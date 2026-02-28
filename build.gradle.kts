@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jreleaser.model.Active

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.power.assert)
    alias(libs.plugins.dokka)
    alias(libs.plugins.versions)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.jreleaser)
    alias(libs.plugins.xemantic.conventions)
}

group = "com.xemantic.typescript"

xemantic {
    description = "TypeScript to JavaScript transpiler in Kotlin Multiplatform"
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

val javaTarget = libs.versions.javaTarget.get()
val kotlinTarget = KotlinVersion.fromVersion(libs.versions.kotlinTarget.get())

kotlin {

    compilerOptions {
        apiVersion = kotlinTarget
        languageVersion = kotlinTarget
        freeCompilerArgs.addAll(
            "-Xcontext-parameters",
            "-Xcontext-sensitive-resolution"
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
    macosX64 {
        binaries.executable()
    }
    macosArm64 {
        binaries.executable()
    }

    // tier 2
    linuxX64 {
        binaries.executable()
    }
    linuxArm64 {
        binaries.executable()
    }

    sourceSets {

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
// TypeScript compiler test harness
// ---------------------------------------------------------------------------

/**
 * The local directory where the TypeScript repository is sparse-cloned.
 * Listed in .gitignore — persists across `./gradlew clean` runs.
 */
val typeScriptRepoDir = projectDir.resolve("typescript-repo")

/**
 * Performs a sparse, shallow clone of the Microsoft TypeScript repository,
 * fetching only the compiler test cases and their expected baselines.
 *
 * The clone is idempotent: if `typescript-repo/.git` already exists the task
 * reports the fact and exits immediately, making repeated builds fast.
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
    description = "Sparse-clones the TypeScript repository (tests only) for the compiler test harness."
    outputs.dir(typeScriptRepoDir)

    doLast {
        if (typeScriptRepoDir.resolve(".git").exists()) {
            logger.lifecycle("TypeScript repository already present at: $typeScriptRepoDir")
            return@doLast
        }

        logger.lifecycle("Cloning TypeScript repository (sparse checkout) into: $typeScriptRepoDir ...")

        // Step 1: shallow clone with no blob objects and sparse-checkout enabled.
        //         Only tree/commit objects are fetched; blobs are lazy-loaded on demand.
        runCommand(
            "git", "clone",
            "--depth=1",
            "--filter=blob:none",
            "--sparse",
            "https://github.com/microsoft/TypeScript.git",
            typeScriptRepoDir.absolutePath,
        )

        // Step 2: restrict the working tree to only the paths we need.
        //         Git will now fetch blobs exclusively for these two directories.
        runCommand(
            "git", "sparse-checkout", "set",
            "tests/cases/compiler",
            "tests/baselines/reference",
            workingDir = typeScriptRepoDir,
        )

        logger.lifecycle("TypeScript repository cloned successfully.")
    }
}

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

        // Group by first character to keep individual files manageable
        val groups = testFiles.groupBy { file ->
            val ch = file.nameWithoutExtension.first()
            if (ch.isLetter()) ch.uppercaseChar() else '#'
        }

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
                val jsBaseline = baselinesDir.resolve("$name.js")
                val errorsBaseline = baselinesDir.resolve("$name.errors.txt")

                if (jsBaseline.exists()) {
                    sb.appendLine()
                    sb.appendLine("    @Test")
                    sb.appendLine("    fun `$name.ts compiles to JavaScript matching $name.js`() {")
                    sb.appendLine("        val source = Path(\"${D}typeScriptCasesDir/$name.ts\").readText()")
                    sb.appendLine("        TypeScriptCompiler().compile(source, \"$name.ts\").javascript")
                    sb.appendLine("            .sameAs(Path(\"${D}typeScriptBaselineDir/$name.js\"))")
                    sb.appendLine("    }")
                }

                if (errorsBaseline.exists()) {
                    sb.appendLine()
                    sb.appendLine("    @Test")
                    sb.appendLine("    fun `$name.ts has expected compilation errors matching $name.errors.txt`() {")
                    sb.appendLine("        val source = Path(\"${D}typeScriptCasesDir/$name.ts\").readText()")
                    sb.appendLine("        assertTrue(")
                    sb.appendLine("            actual = TypeScriptCompiler().compile(source, \"$name.ts\").hasErrors,")
                    sb.appendLine("            message = \"Expected compilation errors for $name.ts but none were produced\"")
                    sb.appendLine("        )")
                    sb.appendLine("    }")
                }
            }

            sb.appendLine()
            sb.appendLine("}")

            packageDir.resolve("$className.kt").writeText(sb.toString())
        }

        val totalTests = testFiles.count { file ->
            val name = file.nameWithoutExtension
            baselinesDir.resolve("$name.js").exists() || baselinesDir.resolve("$name.errors.txt").exists()
        }
        logger.lifecycle("Generated $totalTests test functions across ${groups.size} files in: $packageDir")
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
