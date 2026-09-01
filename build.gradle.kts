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

// The aggregator. It carries NO sources and NO compiler configuration — that all
// lives in xemantic-typescript-compiler-core. What stays here is what is
// genuinely whole-build: identity, the cross-module jar manifest, publication
// metadata, and the release announcement.
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
