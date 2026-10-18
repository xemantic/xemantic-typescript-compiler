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

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.plugins.signing.Sign
import org.jreleaser.model.Active

// The aggregator. It carries NO sources and NO compiler configuration — that
// lives in the modules. What stays here is what is genuinely whole-build:
// identity, the cross-module jar manifest, publication metadata, the release
// announcement, and the two pinned upstream checkouts.
plugins {
    alias(libs.plugins.dokka)
    alias(libs.plugins.versions)
    alias(libs.plugins.jreleaser)
    alias(libs.plugins.xemantic.conventions)
    // `apply false` — not used by this project, declared so the modules' plugin
    // versions align AND so the publication types below (MavenPom and friends)
    // are on this script's compile classpath. Without it the `pom { }` block
    // fails to resolve every one of its properties.
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.maven.publish) apply false
}

xemantic {
    description = "a conformant TypeScript compiler and type checker that runs on JVM, native, and WebAssembly"
    inceptionYear = "2026"
    // NOT applyAllConventions() — its applyJarManifests() cannot work in a
    // multi-module build, in two independent ways. It installs an `allprojects`
    // callback that (a) calls `archiveBaseName.get()` while the Jar task is being
    // created, which for a subproject runs BEFORE the Kotlin plugin has set that
    // property (callbacks fire in registration order, and the root is always
    // evaluated first — so a `base { archivesName = … }` in the module does not
    // help), and (b) reads `project.xemantic`, an extension that only exists on
    // the project applying this plugin — the root. The equivalent manifest
    // population is reinstated below, ordering-safe, so the artifacts keep their
    // Implementation-* attributes and META-INF/LICENSE.
    // NOT applySignBeforePublishing() / applyReportOnlyStableDependencyUpdates()
    // either — xemantic-conventions 0.7.0 DELETED both (0.6.8's `VersionsKt` and
    // the two `XemanticConfiguration` members are simply gone from the 0.7.x jar,
    // and `applyAllConventions()` there is jar-manifests + test-reporting +
    // JReleaser only). They are reinstated verbatim below, so the bump is
    // behaviour-preserving.
    applyAxTestReporting()
    applyJReleaserConventions()
}

// Reinstates 0.6.8's `applySignBeforePublishing()`: a repository publication must
// run after the signing tasks, or it uploads unsigned artifacts.
tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(tasks.withType<Sign>())
}

// Reinstates 0.6.8's `applyReportOnlyStableDependencyUpdates()`, including its
// default keyword list — the report must not propose a prerelease as an update.
val unstableVersionKeywords = listOf("alpha", "beta", "rc")

fun isNonStableVersion(version: String): Boolean {
    val lower = version.lowercase()
    return unstableVersionKeywords.any { it in lower }
}

tasks.withType<DependencyUpdatesTask>().configureEach {
    rejectVersionIf { isNonStableVersion(candidate.version) }
}

// Captured here because the `xemantic` extension exists only on this project,
// while every module needs these values.
val projectDescription = xemantic.description
val projectInceptionYear = xemantic.inceptionYear
val projectCopyright = xemantic.copyright
val gitHubAccount = xemantic.gitHubAccount
val organizationName = xemantic.organization
val organizationUrl = xemantic.organizationUrl

// Published on the ROOT project's extras and read as `rootProject.extra[...]`.
// Deliberately not inside `allprojects { }`: there, Kotlin resolves a bare
// `extra` to this SCRIPT's extension rather than to the block's receiver, so
// every module would silently read the root's value from the root's own map.
extra.set("projectCopyright", projectCopyright)

allprojects {
    group = "com.xemantic.typescript"
    repositories {
        mavenCentral()
    }
    // Registered from `afterEvaluate` deliberately: a Jar task runs its
    // configuration callbacks in the order they were registered, so registering
    // after the module has been evaluated is what guarantees the Kotlin plugin
    // has already given `archiveBaseName` its value.
    afterEvaluate {
        tasks.withType<Jar>().configureEach {
            manifest {
                attributes(
                    mapOf(
                        "Implementation-Title" to archiveBaseName.get(),
                        "Implementation-Version" to archiveVersion.get(),
                        "Implementation-Vendor" to organizationName,
                        "Implementation-Vendor-Id" to rootProject.name,
                        "Created-By" to "gradle",
                    )
                )
            }
            metaInf {
                from(rootProject.rootDir) {
                    include("LICENSE")
                }
            }
        }
    }
}

fun MavenPomDeveloperSpec.projectDevs() {
    developer {
        id = "morisil"
        name = "Kazik Pogoda"
        url = "https://github.com/morisil"
    }
}

// Publication metadata is identical for every module and is therefore configured
// once, from here, rather than copied into each module's build script.
subprojects {

    plugins.withId("com.vanniktech.maven.publish.base") {
        configure<MavenPublishBaseExtension> {

            publishToMavenCentral(automaticRelease = true)
            signAllPublications()

            pom {

                name = project.name
                description = projectDescription
                inceptionYear = projectInceptionYear
                url = "https://github.com/$gitHubAccount/${rootProject.name}"

                organization {
                    name = organizationName
                    url = organizationUrl
                }

                // (LIC.2), owner-approved 2026-09-02: the SPDX expression the 1,078 source
                // headers carry, plus a second entry for the output exception. The URLs
                // name this repository's own licence texts, because the exception is
                // project-specific and has no canonical home elsewhere.
                licenses {
                    license {
                        name = "AGPL-3.0-only WITH LicenseRef-xtsc-output-exception"
                        url = "https://github.com/$gitHubAccount/${rootProject.name}/blob/main/LICENSE"
                        distribution = "repo"
                    }
                    license {
                        name = "xemantic-typescript-compiler Output Exception"
                        url = "https://github.com/$gitHubAccount/${rootProject.name}/blob/main/LICENSE-EXCEPTION"
                        distribution = "repo"
                    }
                }

                scm {
                    url = "https://github.com/$gitHubAccount/${rootProject.name}"
                    connection = "scm:git:git://github.com/$gitHubAccount/${rootProject.name}.git"
                    developerConnection = "scm:git:ssh://git@github.com/$gitHubAccount/${rootProject.name}.git"
                }

                ciManagement {
                    system = "GitHub"
                    url = "https://github.com/$gitHubAccount/${rootProject.name}/actions"
                }

                issueManagement {
                    system = "GitHub"
                    url = "https://github.com/$gitHubAccount/${rootProject.name}/issues"
                }

                developers {
                    projectDevs()
                }

            }

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

// ---------------------------------------------------------------------------
// The two upstream checkouts, shared tooling at the repository root (.gitignored). Moved here from
// `-core`'s build when `-core` was deleted ((TSGO.8-e)): the port's oracles (`scripts/tsgo-diag-cases.py`),
// the bench profiles (`scripts/bench-compile-tsc.sh --materialize-only`) and the porter read them.
// Unqualified `./gradlew cloneTypeScriptRepo` keeps working because this is the only project defining it.
// ---------------------------------------------------------------------------

/** Runs a command, streaming its output; throws on a non-zero exit. */
fun runCommand(vararg cmd: String, workingDir: File = projectDir) {
    val exitCode = ProcessBuilder(*cmd)
        .directory(workingDir)
        .inheritIO()
        .start()
        .waitFor()
    check(exitCode == 0) { "Command failed (exit $exitCode): ${cmd.joinToString(" ")}" }
}

/** Runs a command and returns its stdout; throws on a non-zero exit. */
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

val typeScriptRepoDir: File = projectDir.resolve("typescript-repo")

/**
 * tsgo 7.0.2's `_submodules/TypeScript` sha (the `tsgo-port` branch tip, whose baselines are what TypeScript 7
 * emits). It and [typeScriptGoCommit] are a PAIR: tsgo's `.diff` records are against exactly this commit.
 * To follow tsgo forward, read its submodule sha and pin it directly:
 *   curl -s https://api.github.com/repos/microsoft/typescript-go/contents/_submodules/TypeScript | grep '"sha"'
 */
val typeScriptCommit = "4d4f005c8541e0255a9d8791205fdce326e462bc" // tsgo 7.0.2 `_submodules/TypeScript` (tsgo-port tip; 2nd parent = pristine 637d5746)

val typeScriptGoRepoDir: File = projectDir.resolve("typescript-go-repo")

/** Tag `typescript/v7.0.2` — the released tsgo this project adjudicates against (`tools/tsgo-7.0.2/lib/tsc`). */
val typeScriptGoCommit = "2bd066d87f5bafd315be9f40889d0a60b9e58e0b" // tag typescript/v7.0.2 (submodule side: 4d4f005c)

val cloneTypeScriptRepo = tasks.register("cloneTypeScriptRepo") {
    group = "typescript"
    description = "Sparse-clones the TypeScript repository (tests only), pinned to tsgo 7.0.2's submodule commit."
    inputs.property("typeScriptCommit", typeScriptCommit) // re-run when the pin changes
    outputs.dir(typeScriptRepoDir)

    doLast {
        // Only what a working tree needs: every reader (scripts/tsgo-diag-cases.py, bench-compile-tsc.sh)
        // extracts what else it wants from the pinned commit's objects, which the depth-1 fetch brings down.
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
 * (LEGACY.0b): ensures `typescript-go-repo/` is present and checked out at
 * [typeScriptGoCommit]: tsgo's own baselines and test cases (the diagnostics/emit oracles read them).
 *
 * THREE ways this deliberately differs from [cloneTypeScriptRepo], each for a measured
 * reason — do not "make them consistent":
 *
 *  1. **No `outputs.dir`.** That checkout is 390 MB / 55k files here and, unlike
 *     `typescript-repo`, it may PRE-EXIST as a full manual clone carrying tsgo's Go
 *     sources (the reference implementation CLAUDE.md sends agents to read). Declaring it
 *     as an output hands Gradle's stale-output handling a directory it did not create and
 *     costs a 55k-file snapshot on every build. The body is instead a fast no-op when the
 *     pin is already checked out, which is the same guarantee for ~30 ms a build.
 *  2. **`sparse-checkout set` only when the clone is already sparse.** A pre-existing full
 *     clone stays full: narrowing it would delete `internal/checker/checker.go` and
 *     `testdata/submoduleTriaged.txt`, both of which this project reads by name.
 *  3. **Sparse, and in NON-CONE mode, to the file kinds we read.** Cone mode can only take
 *     whole directories, and `submodule/{compiler,conformance}` is three quarters `.types` /
 *     `.symbols` that nothing here reads. Measured on a fresh clone: the cone form is 290 MB,
 *     the extension form below is 104 MB of working tree (~40 MB apparent) and 124 MB with
 *     the object store. `docs/tsgo-baselines.md`'s "≈29 MB" counted only the compiler suite's
 *     two extensions and is low by ~4x.
 *
 */
val cloneTypeScriptGoRepo = tasks.register("cloneTypeScriptGoRepo") {
    group = "typescript"
    description = "Ensures the typescript-go checkout is present and pinned to tsgo 7.0.2 (its baselines are the corpus reference)."
    inputs.property("typeScriptGoCommit", typeScriptGoCommit)

    doLast {
        // NON-CONE patterns (gitignore syntax, rooted): the baselines we compare against,
        // the `.diff` records that classify a divergence by LAYER in all three directories,
        // and tsgo's two classification lists (docs/tsgo-baselines.md). `.types`/`.symbols` are deliberately absent.
        val sparsePaths = arrayOf(
            "--no-cone",
            "/testdata/baselines/reference/submodule/compiler/*.js",
            "/testdata/baselines/reference/submodule/compiler/*.errors.txt",
            "/testdata/baselines/reference/submodule/compiler/*.diff",
            "/testdata/baselines/reference/submodule/conformance/*.js",
            "/testdata/baselines/reference/submodule/conformance/*.errors.txt",
            "/testdata/baselines/reference/submodule/conformance/*.diff",
            "/testdata/baselines/reference/submoduleAccepted/**",
            "/testdata/baselines/reference/submoduleTriaged/**",
            "/testdata/submoduleAccepted.txt",
            "/testdata/submoduleTriaged.txt",
        )
        val baselinesPresent = typeScriptGoRepoDir
            .resolve("testdata/baselines/reference/submodule/compiler").isDirectory

        if (typeScriptGoRepoDir.resolve(".git").exists()) {
            val head = runCatching {
                captureCommand("git", "rev-parse", "HEAD", workingDir = typeScriptGoRepoDir).trim()
            }.getOrNull()
            if (head == typeScriptGoCommit && baselinesPresent) {
                logger.info("typescript-go checkout already pinned to $typeScriptGoCommit.")
                return@doLast
            }
            logger.lifecycle("Re-pinning typescript-go repository to $typeScriptGoCommit ...")
            // `git config --get` EXITS 1 when the key is absent, so this must not go
            // through runCommand/captureCommand's exit-code check.
            val sparse = runCatching {
                captureCommand("git", "config", "--get", "core.sparseCheckout", workingDir = typeScriptGoRepoDir).trim()
            }.getOrNull() == "true"
            if (sparse) {
                runCommand("git", "sparse-checkout", "set", *sparsePaths, workingDir = typeScriptGoRepoDir)
            }
            runCommand("git", "fetch", "--depth=1", "origin", typeScriptGoCommit, workingDir = typeScriptGoRepoDir)
            runCommand("git", "checkout", "--force", typeScriptGoCommit, workingDir = typeScriptGoRepoDir)
            logger.lifecycle("typescript-go repository re-pinned successfully.")
            return@doLast
        }

        logger.lifecycle("Cloning typescript-go repository (pinned $typeScriptGoCommit, partial+sparse) into: $typeScriptGoRepoDir ...")
        runCommand(
            "git", "clone",
            "--depth=1",
            "--filter=blob:none",
            "--sparse",
            "--no-checkout",
            "https://github.com/microsoft/typescript-go.git",
            typeScriptGoRepoDir.absolutePath,
        )
        runCommand("git", "sparse-checkout", "set", *sparsePaths, workingDir = typeScriptGoRepoDir)
        runCommand("git", "fetch", "--depth=1", "origin", typeScriptGoCommit, workingDir = typeScriptGoRepoDir)
        runCommand("git", "checkout", typeScriptGoCommit, workingDir = typeScriptGoRepoDir)
        logger.lifecycle("typescript-go repository cloned + pinned successfully.")
    }
}
