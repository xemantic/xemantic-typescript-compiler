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
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.xemantic.kotlin.test)
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
 * Run explicitly before the first test run, or simply invoke `jvmTest`
 * (which depends on this task):
 * ```
 * ./gradlew cloneTypeScriptRepo
 * ./gradlew jvmTest
 * ```
 */
val cloneTypeScriptRepo by tasks.registering {
    group = "typescript"
    description = "Sparse-clones the TypeScript repository (tests only) for the compiler test harness."

    doLast {
        if (typeScriptRepoDir.resolve(".git").exists()) {
            logger.lifecycle("TypeScript repository already present at: $typeScriptRepoDir")
            return@doLast
        }

        logger.lifecycle("Cloning TypeScript repository (sparse checkout) into: $typeScriptRepoDir ...")

        // Step 1: shallow clone with no blob objects and sparse-checkout enabled.
        //         Only tree/commit objects are fetched; blobs are lazy-loaded on demand.
        project.exec {
            commandLine(
                "git", "clone",
                "--depth=1",
                "--filter=blob:none",
                "--sparse",
                "https://github.com/microsoft/TypeScript.git",
                typeScriptRepoDir.absolutePath,
            )
        }

        // Step 2: restrict the working tree to only the paths we need.
        //         Git will now fetch blobs exclusively for these two directories.
        project.exec {
            workingDir = typeScriptRepoDir
            commandLine(
                "git", "sparse-checkout", "set",
                "tests/cases/compiler",
                "tests/baselines/reference",
            )
        }

        logger.lifecycle("TypeScript repository cloned successfully.")
    }
}

// Pass the TypeScript repo location to the JVM test runner so the harness can
// locate the test cases.  The task also depends on cloneTypeScriptRepo so that
// a plain `./gradlew jvmTest` is all that's needed.
tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest") {
    dependsOn(cloneTypeScriptRepo)
    systemProperty("typescript.repo.dir", typeScriptRepoDir.absolutePath)
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
