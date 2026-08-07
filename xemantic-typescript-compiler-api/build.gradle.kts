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

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.serialization)
    id("xemantic-typescript-compiler.convention")
}

repositories {
    mavenCentral()
}

// The root project applies the xemantic conventions, which reach into every
// project and leave `archivesName` unset here; without this, configuring
// `jvmJar` fails with "archiveBaseName has no value available".
base {
    archivesName = project.name
}

kotlin {

    // This module is the contract between two separately-built binaries, so its
    // surface is checked rather than inferred — an accidentally-public helper
    // here becomes something a shipped client depends on.
    explicitApi()

    jvm()

    // Native stays opt-in, as everywhere in this build: the daemon is JVM-only,
    // so nothing needs a native `-api` until the client module lands, and a
    // native link is expensive enough that it must never ride along by default.
    // Unlike the compiler module this one is small, so when it IS enabled the
    // targets cost seconds, not minutes.
    if (project.findProperty("enableNativeTargets") == "true") {
        linuxX64()
        linuxArm64()
        macosArm64()
        macosX64()
        // Windows AF_UNIX is real but conditional — see SocketPath.kt.
        mingwX64()
    }

    sourceSets {

        commonMain {
            dependencies {
                // `api`, not `implementation`: both peers deserialize these
                // types themselves, so the serialization runtime is part of
                // this module's surface rather than an internal detail.
                api(libs.kotlinx.serialization.json)
                // Likewise the frame codec below is written against ktor's
                // channels, which therefore appear in this module's signatures.
                api(libs.ktor.network)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.xemantic.kotlin.test)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

    }

}
