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

package com.xemantic.typescript.compiler.buildlogic

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.powerassert.gradle.PowerAssertGradleExtension
import org.jetbrains.kotlin.powerassert.gradle.PowerAssertGradlePlugin

/**
 * Cross-cutting Kotlin configuration shared by every module of this build.
 *
 * Deliberately narrow: it carries only the settings that must be identical
 * everywhere, because a module that silently drifts from them is exactly the
 * failure this plugin exists to prevent. Everything module-specific — targets,
 * source sets, dependencies, code generation, publishing — stays in the module's
 * own build script.
 *
 * Three of the settings below are load-bearing rather than cosmetic:
 *
 *  - `extraWarnings` + `progressiveMode` are what keep this build warning-clean.
 *    Some of those warnings become hard errors on a Kotlin version bump, and in
 *    practice a warning here has repeatedly flagged a real defect, so a module
 *    that drops them accumulates both silently.
 *  - power-assert is registered for exactly two functions. They are the project's
 *    only sanctioned assertion idiom, and they take no message argument because
 *    the generated diagram renders every subexpression value instead. Registering
 *    a different set in one module would make its assertions render as bare
 *    booleans, which reads as a worse failure message rather than as misconfig.
 *  - no test framework is selected. The JVM tests run on the Kotlin Gradle
 *    plugin's default, and switching it (for instance to JUnit Platform) would
 *    change how the whole generated corpus is discovered.
 */
@Suppress("unused") // instantiated by Gradle, by id
class XtscConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.applyConvention()
    }

}

private fun Project.applyConvention() {

    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    val javaTarget = libs.findVersion("javaTarget").get().toString()
    val kotlinTarget = KotlinVersion.fromVersion(
        libs.findVersion("kotlinTarget").get().toString()
    )
    val jvmTarget = JvmTarget.fromTarget(javaTarget)

    plugins.apply(PowerAssertGradlePlugin::class.java)
    extensions.configure<PowerAssertGradleExtension> {
        functions.set(
            listOf(
                "com.xemantic.kotlin.test.assert",
                "com.xemantic.kotlin.test.have"
            )
        )
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(javaTarget.toInt())
    }

    // Tests resolve fixture paths (the TypeScript checkout, generated baselines)
    // relative to their own module, so the working directory has to be the module
    // rather than whatever directory Gradle was invoked from.
    tasks.withType<Test>().configureEach {
        workingDir = projectDir
    }

    extensions.findByType<KotlinMultiplatformExtension>()?.configure(kotlinTarget, jvmTarget)
}

private fun KotlinMultiplatformExtension.configure(
    kotlinTarget: KotlinVersion,
    jvmTargetVersion: JvmTarget,
) {

    compilerOptions {
        apiVersion.set(kotlinTarget)
        languageVersion.set(kotlinTarget)
        extraWarnings.set(true)
        progressiveMode.set(true)
        freeCompilerArgs.addAll(
            "-Xcontext-sensitive-resolution",
            // StackOverflowError is expect/actual with a JVM typealias actual,
            // which is the sanctioned use case for an expect/actual class.
            "-Xexpect-actual-classes"
        )
    }

    // Configured lazily through `configureEach`, so it applies to whichever
    // modules declare a jvm target without this plugin forcing one to exist —
    // the client module is built for native hosts as well.
    targets.withType(KotlinJvmTarget::class.java).configureEach {
        compilerOptions {
            apiVersion.set(kotlinTarget)
            languageVersion.set(kotlinTarget)
            jvmTarget.set(jvmTargetVersion)
            progressiveMode.set(true)
            freeCompilerArgs.add("-Xjdk-release=${jvmTargetVersion.target}")
        }
    }

}
