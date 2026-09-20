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

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
    // Brings power-assert with the project's two sanctioned assertion functions,
    // the warning settings, and the jvm/language targets. The `powerAssert { }`
    // block that used to live here is gone WITH its plugin alias — declaring the
    // functions in two places is how they drift apart.
    id("xemantic-typescript-compiler.convention")
}

// The root applies the xemantic conventions, which reach into every project and
// leave `archivesName` unset here; without this, configuring `jvmJar` fails with
// "archiveBaseName has no value available".
//
// DELIBERATELY the product name and NOT `project.name`: the jar keeps the name
// it has always had, `xemantic-typescript-compiler-jvm-<version>.jar`. That name
// is a contract — `scripts/xtsc-aot-lib.sh` globs for it in both the development
// tree and an installed `XTSC_HOME/lib`, and `-core` is an internal module
// boundary that the shipped distribution has no reason to learn about.
base {
    archivesName = "xemantic-typescript-compiler"
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

    // ALL NATIVE TARGETS ARE OFF (2026-07-25, owner: keep the Claude Code loop
    // fast — the native test compile + optimizing link add ~7 min to `build`).
    //
    // CONSEQUENCE — READ BEFORE ADDING TO src/commonTest: with native off, the
    // ONLY compiler that sees commonTest is the JVM one, which is LOOSER than
    // Kotlin/Native. Nothing here will flag native-only violations any more, so
    // they accumulate silently and cost a cleanup session whenever a native
    // target comes back (round 672 removed 169 such errors accrued since round
    // 610). The three rules, and how to check them, are in CLAUDE.md § "Known
    // gotchas" — search "must compile for Kotlin/Native". In short:
    //   1. backtick test names: letters/digits/spaces/`-`/`_`/`'` only — NO
    //      `(`, `)`, `,`, `&`, `@` (the JVM accepts all of these; native does not)
    //   2. no `kotlin.assert` — use `com.xemantic.kotlin.test.assert(cond)`
    //   3. no JVM-only stdlib (e.g. `String.format`)
    // To verify: run the `kotlin-native` job of .github/workflows/native.yml (it
    // passes `-PenableNativeTargets=true`; `jvmTest` cannot see any of this).
    // NOT locally — a local K/N build froze the dev box for ~2 h (round 775).
    //
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
    //
    // OPT-IN ONLY (round 775, owner directive). The target registers ONLY under
    // `-PenableNativeTargets=true`, so a plain `./gradlew build` never sees it and
    // is unchanged. Native builds belong in CI (.github/workflows/native.yml,
    // 16 GB runners): a local Kotlin/Native test link froze the 7.7 GB dev box for
    // ~2 hours — see the CLAUDE.md rule, and note that `-Xmx` cannot bound it
    // because the konan/LLVM backend and the linked test executable are separate
    // processes outside any JVM heap.
    if (project.findProperty("enableNativeTargets") == "true") {
        linuxX64 {
            binaries.executable {
                entryPoint = "com.xemantic.typescript.compiler.main"
            }
        }
    }
//    linuxArm64 {
//        binaries.executable {
//            entryPoint = "com.xemantic.typescript.compiler.main"
//        }
//    }

    sourceSets {

        commonMain {
            // Two GENERATED directories also belong to this source set and are added
            // further down, next to the tasks that produce them, so that the srcDir can
            // be the task PROVIDER rather than a bare path (see the comments there):
            //   generated/real-lib  — RealLibFiles.kt, by generateRealLibSources
            //   generated/buildinfo — BuildInfo.kt, by generateBuildInfo
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
// Deliberately the ROOT directory, not this module's: the checkout is shared
// tooling, .gitignored at the repo root, and referenced as `typescript-repo/`
// by the scripts and by the tests (which resolve it against their working
// directory). Moving it under the module would silently re-clone it and leave
// every one of those references pointing at nothing.
val typeScriptRepoDir = rootProject.projectDir.resolve("typescript-repo")

/**
 * The exact TypeScript commit our test corpus is PINNED to: tsgo 7.0.2's
 * `_submodules/TypeScript` sha, i.e. the tip of the `tsgo-port` branch, whose
 * `tests/baselines/reference` are regenerated to what TypeScript 7 emits.
 *
 * (LEGACY.0a), 2026-09-12, owner directive: TypeScript 7.0 / tsgo 7.0.2 is the ONLY
 * compatibility target, and the corpus follows it. This supersedes the earlier rule
 * ("never pin to the tsgo submodule sha; pin to its pristine `main`-side parent")
 * that stood while pristine tsc was the reference.
 *
 * What the sha IS, measured (`docs/tsgo-baselines.md`): the `tsgo-port` tip is a
 * "Merge branch 'main' into tsgo-port" commit whose SECOND parent is pristine main
 * `637d5746` (the previous pin), and the branch differs from pristine by exactly ONE
 * source change — `stableTypeOrdering` defaulting ON (checker.ts:1545 + its
 * commandLineParser default) — so its baselines are pristine's with tsc's STABLE
 * type ordering (union/intersection constituents ordered by `compareTypes`, object
 * members by `compareSymbols` = declaration position) instead of type-id order.
 * 791 baselines differ, zero baselines are deleted, zero cases change.
 *
 * To follow tsgo forward, read tsgo's submodule sha and pin it DIRECTLY:
 *   curl -s https://api.github.com/repos/microsoft/typescript-go/contents/_submodules/TypeScript | grep '"sha"'
 * (For reference, its pristine main-side parent is `parents[1].sha` of that commit.)
 */
val typeScriptCommit = "4d4f005c8541e0255a9d8791205fdce326e462bc" // tsgo 7.0.2 `_submodules/TypeScript` (tsgo-port tip; 2nd parent = pristine 637d5746)

// (LEGACY.0b) Deliberately the ROOT directory, like [typeScriptRepoDir] above and for a
// STRONGER reason: this checkout is shared tooling that is NOT only a baseline source —
// CLAUDE.md points every agent at `typescript-go-repo/internal/checker/checker.go` as the
// reference implementation, and `tools/tsgo-7.0.2/lib/tsc` is its shipped binary. Narrowing
// or relocating it would silently delete the sources those references name.
val typeScriptGoRepoDir = rootProject.projectDir.resolve("typescript-go-repo")

/**
 * The exact `microsoft/typescript-go` commit whose CHECKED-IN BASELINES the corpus reads:
 * tag `typescript/v7.0.2`, i.e. the released tsgo this project adjudicates against
 * (`tools/tsgo-7.0.2/lib/tsc`).
 *
 * (LEGACY.0b), 2026-09-13. tsgo does not store a patch over tsc's baselines — it checks in
 * its OWN full output under `testdata/baselines/reference/submodule/<suite>/`, and the
 * `.diff` files beside it are RECORDS of tsgo-vs-tsc whose hunk headers are rewritten to
 * `@@= skipped =@@` (neither `patch` nor `git apply` accepts them). So this pin selects a
 * baseline ROOT; see `docs/tsgo-baselines.md` § 1.
 *
 * The "old side" of every one of those `.diff` files is TypeScript [typeScriptCommit]
 * `4d4f005c` — the same sha this corpus's case files and fallback baselines come from.
 * The two pins are therefore a PAIR: bumping one without the other makes every diff-derived
 * bucket count below meaningless, which is what the asserted constants exist to catch.
 */
val typeScriptGoCommit = "2bd066d87f5bafd315be9f40889d0a60b9e58e0b" // tag typescript/v7.0.2 (submodule side: 4d4f005c)

/**
 * (LEGACY.0b): the three-way fallback's ASSERTED bucket sizes for the [typeScriptGoCommit] /
 * [typeScriptCommit] pin.
 *
 * A silent shift between baseline ROOTS is the exact failure this corpus exists to prevent
 * — it would shrink the gate with a green build — so the generator counts every subtest it
 * resolves and fails when a count moves. Bumping either pin means re-measuring all four and
 * saying, in the round note, which cases moved and why.
 *
 *  - [tsgoExpectedDeleted]: tsc has the baseline, tsgo RAN the case (a `.diff` records it)
 *    and emitted nothing — the subtest is DELETED.
 *  - [tsgoExpectedKeptTsc]: tsgo has no baseline AND no `.diff`, i.e. it never ran that
 *    configuration at all — the tsc baseline is KEPT so the subtest survives. This is the
 *    bucket that pins PRISTINE TypeScript 6, so it is the one (LEGACY.1) shrinks: 87 -> 3
 *    at (g), when [tsconfigInTestUsesRemovedFeature] learned tsgo's embedded-`baseUrl` /
 *    embedded-`moduleResolution` skips.
 *  - [tsgoExpectedNew]: tsgo emits a baseline where tsc had none — a NEW subtest.
 *  - [tsgoExpectedAdopted]: every subtest served from the tsgo root (the other two buckets'
 *    complement); a control, so that "adopted collapsed to zero" cannot read as green.
 *
 * MEASURED 2026-09-13. `docs/tsgo-baselines.md` predicted 9 / 87 / **24**, and the third is
 * 23: its sizing was taken against the PRISTINE pin `637d5746`, where
 * `coAndContraVariantInferences5.errors.txt` did not exist — (LEGACY.0a) moved to
 * `4d4f005c`, which already carries it, so one of the 24 landed a round early. The
 * projected corpus size is unaffected (the base moved by the same one).
 *
 * RE-MEASURED 2026-09-17 at (LEGACY.1)(g), which widened [tsconfigInTestUsesRemovedFeature]
 * to the embedded `baseUrl` / `moduleResolution` forms: `keptTsc` 87 -> 3 and the other
 * THREE are byte-identical. That the whole 84-subtest drop lands in `keptTsc` — and that
 * `adopted`, `new` and `deleted` do not move by one — is the receipt that the widening
 * removed only PRISTINE-pinned subtests and lost no tsgo answer; a gradeable case caught by
 * the rule would have shown up as `adopted` falling.
 *
 * RE-MEASURED AGAIN 2026-09-17 at (P18.133), which taught the same predicate to follow a root
 * `tsconfig.json`'s `extends` chain: **`keptTsc` 3 -> 1, `adopted` / `new` / `deleted` unmoved.**
 * The drop is **2 subtests from ONE case** — `pathMappingInheritedBaseUrl`, whose `baseUrl` is
 * inherited from `/other/tsconfig.base.json` and which contributes BOTH an `.errors.txt` and a
 * `.js` baseline, each in this bucket because tsgo has no artifact for the case at all. An
 * independent scan of every case file writing `"extends"` confirms it is the ONLY one the walk
 * newly reaches, so the 2 is one case x two baselines rather than two cases; expecting `3 -> 2`
 * is the natural mistake here, and the bucket counts BASELINES, not cases.
 */
val tsgoExpectedDeleted = 9
val tsgoExpectedKeptTsc = 1
val tsgoExpectedNew = 23
val tsgoExpectedAdopted = 8765

/**
 * (LEGACY.0b): which baseline file a generated subtest compares against, and what we know
 * about it.
 *
 * [layer] is tsgo's own classification of the divergence and is the guard against following
 * a tsgo BUG — it is emitted into the generated test as a comment:
 *  - `submoduleAccepted` — listed in tsgo's `testdata/submoduleAccepted.txt`: an INTENDED
 *    divergence from tsc, i.e. a row to implement.
 *  - `submoduleTriaged` — listed in `testdata/submoduleTriaged.txt`, whose header reads
 *    "known diffs that we intend to fix": a tsgo DEFECT. **No round may target such a
 *    family**; if its baseline is red, ledger it, do not chase it.
 *  - `submodule` — in neither list: an UNTRIAGED delta, i.e. unclassified by tsgo itself.
 *  - `null` — tsgo's output is byte-identical to tsc's (or the baseline is the tsc one).
 */
data class TsgoBaselineChoice(
    /** The baseline file the generated subtest reads. */
    val file: File,
    /** The generated Kotlin path expression naming it. */
    val pathExpr: String,
    /** tsgo's layer for this baseline, or null when tsgo and tsc agree. */
    val layer: String?,
    /** False only for the [tsgoExpectedKeptTsc] bucket. */
    val fromTsgo: Boolean,
)

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
/**
 * M3.0: the conformance-category ALLOWLIST, as paths under
 * `tests/cases/conformance`. Adopting the whole conformance tree at once would
 * bury real regressions under a red wave, so categories land one at a time and
 * only when their failures are triaged into queue items.
 *
 * Each entry widens the sparse checkout AND the generated corpus. The category's
 * files are walked RECURSIVELY (conformance categories nest, unlike the flat
 * `tests/cases/compiler`), and their basenames are known not to collide with the
 * compiler corpus, so the generated backtick function names need no
 * disambiguation. Baselines need nothing: the sparse checkout already takes the
 * whole flat `tests/baselines/reference`, and the generator's existing
 * `paramBaselineName` already produces conformance's `name(target=es5).ext` form.
 */
val conformanceCategories = listOf(
    "expressions/functions",
    // Round 695. Adopted after measuring twelve candidate categories in one suite
    // run (see the M3.0 queue item for the full redness table) — these three were
    // the only ones under three failures; the other nine are 5-21 each and stay
    // unadopted until their gaps are worked.
    "es6/defaultParameters",
    "es6/restParameters",
    "expressions/commaOperator",
)

/**
 * M3.0: conformance cases whose ERROR baseline exposes a known, QUEUED compiler
 * gap. Their JS-emit subtests still run — only the `.errors.txt` comparison is
 * deferred, so the category is adopted rather than withheld while the gap is
 * open.
 *
 * The corpus is a hard zero-failure gate that every round's verification depends
 * on, so a known-red test would degrade that gate for everyone. Each entry MUST
 * have a queue item naming the missing behaviour; delete the entry when it lands.
 * This is not a place to park a fresh failure — triage first, queue it, then add.
 */
val conformanceDeferredErrorBaselines = setOf(
    // PARKED (round 714), not in progress: the case's TS18048 x3 and the over-emitted
    // TS7019/TS7006 are FIXED (rounds 693/704/706/707). What is left is its TS7006 x2 on
    // argument arrows in a file whose only directive is @strictNullChecks, i.e.
    // PURE-DEFAULT mode, where the full implicit-any walker is deliberately off and the
    // narrow default-mode one covers a single shape on purpose. Closing it means
    // broadening that walker — the change recorded as having regressed ~19 tests — which
    // is not worth it for one case. See the M3.0-gap-2 queue item.
    "contextuallyTypedIifeStrict",
    // The comma operator's result type is not the RIGHT operand's type, so an
    // inferred `return x, y` return and a `var r: T1 = (x, y)` assignment miss
    // TS2322 x2. See the M3.0-gap-3 queue item.
    "commaOperatorOtherInvalidOperation",
)

/**
 * PARITY.1: one corpus baseline switched off because our output diverges from
 * pristine tsc's in FORM but not in MEANING (owner directive 2026-07-26 — see
 * `docs/logical-parity.md` for the form-vs-meaning decision procedure).
 *
 * This list is the single source of truth: the generator emits the matching test
 * `@Ignore`d (so it stays VISIBLE as skipped rather than vanishing from the total),
 * regenerates the ledger in `docs/logical-parity.md`, and FAILS the build on a stale
 * entry or a missing [pinnedBy] class. An unlogged disable is indistinguishable from
 * a hidden regression, which is why the declaration — not the ledger — is the input.
 *
 * NOT the same thing as [conformanceDeferredErrorBaselines], which defers a case
 * where we are genuinely WRONG. Do not move an entry between the two.
 */
data class LogicalParityDivergence(
    /** Exact baseline file name under `tests/baselines/reference` (e.g. `foo.errors.txt`). */
    val baseline: String,
    /** The round that switched it off. */
    val round: Int,
    /** Test class pinning the LOGIC the baseline used to pin; must exist in src/commonTest. */
    val pinnedBy: String,
    /** Which form axis differs, and why the meaning is preserved. Per case, no boilerplate. */
    val reason: String,
)

/**
 * The live set. Empty is the healthy state — an entry is a deliberate, argued
 * divergence, added only via the procedure in `docs/logical-parity.md`.
 */
val logicalParityDivergences = listOf(
    LogicalParityDivergence(
        baseline = "acceptableAlias1.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; tsc had no baseline for this case at all, so nothing was switched off " +
            "that ever ran.",
    ),
    LogicalParityDivergence(
        baseline = "accessorInferredReturnTypeErrorInReturnStatement.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; the tsc comparison this baseline used to make is reproduced verbatim in " +
            "the pinning class, so no coverage is lost.",
    ),
    LogicalParityDivergence(
        baseline = "aliasInaccessibleModule.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; tsc had no baseline for this case at all, so nothing was switched off " +
            "that ever ran.",
    ),
    LogicalParityDivergence(
        baseline = "checkingObjectWithThisInNamePositionNoCrash.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; the tsc comparison this baseline used to make is reproduced verbatim in " +
            "the pinning class, so no coverage is lost.",
    ),
    LogicalParityDivergence(
        baseline = "classExpressionWithDecorator1.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; the tsc comparison this baseline used to make is reproduced verbatim in " +
            "the pinning class, so no coverage is lost.",
    ),
    LogicalParityDivergence(
        baseline = "constructorWithIncompleteTypeAnnotation.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; the tsc comparison this baseline used to make is reproduced verbatim in " +
            "the pinning class, so no coverage is lost.",
    ),
    LogicalParityDivergence(
        baseline = "declarationEmitNameConflictsWithAlias.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; tsc had no baseline for this case at all, so nothing was switched off " +
            "that ever ran.",
    ),
    LogicalParityDivergence(
        baseline = "declarationEmitTypeofThisInClass.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; tsc had no baseline for this case at all, so nothing was switched off " +
            "that ever ran.",
    ),
    LogicalParityDivergence(
        baseline = "exportImportNonInstantiatedModule.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; tsc had no baseline for this case at all, so nothing was switched off " +
            "that ever ran.",
    ),
    LogicalParityDivergence(
        baseline = "interfaceMayNotBeExtendedWitACall.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; the tsc comparison this baseline used to make is reproduced verbatim in " +
            "the pinning class, so no coverage is lost.",
    ),
    LogicalParityDivergence(
        baseline = "isolatedModulesExportImportUninstantiatedNamespace.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; the tsc comparison this baseline used to make is reproduced verbatim in " +
            "the pinning class, so no coverage is lost.",
    ),
    LogicalParityDivergence(
        baseline = "manyCompilerErrorsInTheTwoFiles.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; the tsc comparison this baseline used to make is reproduced verbatim in " +
            "the pinning class, so no coverage is lost.",
    ),
    LogicalParityDivergence(
        baseline = "missingCloseParenStatements(alwaysstrict=true).errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; the tsc comparison this baseline used to make is reproduced verbatim in " +
            "the pinning class, so no coverage is lost.",
    ),
    LogicalParityDivergence(
        baseline = "noUnusedLocals_selfReference.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; the tsc comparison this baseline used to make is reproduced verbatim in " +
            "the pinning class, so no coverage is lost.",
    ),
    LogicalParityDivergence(
        baseline = "reachabilityChecksNoCrash1.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; the tsc comparison this baseline used to make is reproduced verbatim in " +
            "the pinning class, so no coverage is lost.",
    ),
    LogicalParityDivergence(
        baseline = "reverseMappedPartiallyInferableTypes.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; the tsc comparison this baseline used to make is reproduced verbatim in " +
            "the pinning class, so no coverage is lost.",
    ),
    LogicalParityDivergence(
        baseline = "shorthandPropertyAssignmentsInDestructuring(target=es2015).errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; the tsc comparison this baseline used to make is reproduced verbatim in " +
            "the pinning class, so no coverage is lost.",
    ),
    LogicalParityDivergence(
        baseline = "shorthandPropertyAssignmentsInDestructuring_ES6.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; the tsc comparison this baseline used to make is reproduced verbatim in " +
            "the pinning class, so no coverage is lost.",
    ),
    LogicalParityDivergence(
        baseline = "superCallsInConstructor.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; the tsc comparison this baseline used to make is reproduced verbatim in " +
            "the pinning class, so no coverage is lost.",
    ),
    LogicalParityDivergence(
        baseline = "withStatement.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; the tsc comparison this baseline used to make is reproduced verbatim in " +
            "the pinning class, so no coverage is lost.",
    ),
    LogicalParityDivergence(
        baseline = "withStatementErrors.errors.txt",
        round = 86,
        pinnedBy = "TsgoHarnessSelfCheckBaselinesTest",
        reason =
            "tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: " +
            "Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no " +
            "TypeScript code, and tsgo files the family under `submoduleTriaged` -- \"known diffs " +
            "that we intend to fix\", group \"checker order dependence creating diagnostic " +
            "instability in API scenarios\", whose own note reads \"ANY test with a TS-1 " +
            "indicates a problem\". MEANING is preserved because there is no meaning in the file " +
            "to follow; the tsc comparison this baseline used to make is reproduced verbatim in " +
            "the pinning class, so no coverage is lost.",
    ),
    LogicalParityDivergence(
        baseline = "jsxRuntimePragma(jsx=react-jsxdev).js",
        round = 86,
        pinnedBy = "JsxDevRuntimeFileNameTest",
        reason = "tsgo's HARNESS mounts a test's files on a virtual filesystem rooted at " +
            "`/.src/`, so its baseline hoists `const _jsxFileName = \"/.src/two.tsx\"` " +
            "where ours (and tsc 6's, byte-for-byte on this case) hoists `\"two.tsx\"`. " +
            "The prefix is a property of where someone else's runner put the file, not of " +
            "TypeScript 7 — no tsconfig, directive or source text produces it here — so it " +
            "is not an implementable row. Everything the baseline tested about the dev " +
            "runtime (the hoist, its per-file name, every jsxDEV call going through the " +
            "binding, the fileName/lineNumber/columnNumber debug argument, and the classic " +
            "pragma override) is pinned instead, with a negative control that no emitted " +
            "path carries `/.src/`. See docs/tsgo-baselines.md § 3 and § 7 risk 2.",
    ),
)

/**
 * (LEGACY.0a): a corpus baseline the pinned TypeScript 7 reference produces and this
 * compiler does not produce YET — a row to IMPLEMENT, not a decision not to follow it.
 *
 * The corpus is pinned to tsgo 7.0.2's own baselines (see [typeScriptCommit]), so a
 * red baseline is by definition tsgo's answer. While its family is unimplemented the
 * subtest is generated `@Ignore`d — VISIBLE as skipped, counted in the build log as
 * `tsgo-pending: N`, and STALE-CHECKED: an entry naming no generated subtest fails
 * the build, so a baseline that starts passing (or is renamed) cannot sit in the list
 * unnoticed. Unlike [LogicalParityDivergence] it needs no `pinnedBy` class — nothing
 * is being pinned instead; the entry IS the queue. Remove the entry when the row lands.
 *
 * Procedure and ledger: `docs/logical-parity.md` § "tsgo-pending".
 */
data class TsgoPendingBaseline(
    /** Exact baseline file name under `tests/baselines/reference` (e.g. `foo.errors.txt`). */
    val baseline: String,
    /** One line: the family and the tsgo row this compiler does not produce yet. */
    val reason: String,
)

/**
 * The live set. Two groups, in landing order: first the 17 ORDER rows (LEGACY.0a) left
 * behind (the `tsgo-port` sha's stable type ordering), then the 268 (LEGACY.0b) rows the
 * baseline-ROOT switch left — every one of which carries its FAMILY and tsgo's own LAYER
 * for the divergence, so a family round can select its work with a grep. The full first-run
 * classification is in the (LEGACY.0b) round note.
 */
val tsgoPendingBaselines = listOf(
    TsgoPendingBaseline(
        "coAndContraVariantInferences5.errors.txt",
        "NEW in the tsgo-port baselines: a TS2322 on a contravariant callback property " +
            "(`onChange: (status: Thing | null) => void` against `(key: KeyT) => void`) that " +
            "generic inference does not reach here; not an ordering row.",
    ),
    TsgoPendingBaseline(
        "namespaceDisambiguationInUnion.errors.txt",
        "RECLASSIFIED (LEGACY.0b step 9) ORDER -> CHAIN-PICKER: the union's own display is " +
            "now CORRECT (`Foo.Yep | Bar.Yep`, verified) and the whole residue is one chain " +
            "sub-line — tsgo names `\"bar.yep\"` and we name `\"foo.yep\"`. tsc's " +
            "`typeRelatedToSomeType` reports a union TARGET with no discriminant match " +
            "against its LAST constituent; the var-decl chain here picks the first. " +
            "`findBestUnionConstituent` already keeps the LAST on a tie ((LEGACY.0a)), so " +
            "this chain does not go through it — that is the gap.",
    ),
    TsgoPendingBaseline(
        "noInferUnionExcessPropertyCheck1.errors.txt",
        "ORDER-model. **THE RECORDED \"it is not `compareSymbols`\" REASON IS REFUTED** " +
            "(re-measured (P18.135) recon): it IS `compareSymbols`. The old reason took `T`'s " +
            "declaration to be the CONSTRAINT's type literal, but `T` is inferred to the " +
            "ARGUMENT object literal, which is LATER in the file than the function-type node " +
            "— giving function-type-first, which is exactly tsgo's answer. Measured: tsgo " +
            "canonicalizes both written orders identically and orders a named reference first " +
            "(`Fn<NoInfer<...>> | NoInfer<...>`). Old reason, kept so the refutation is " +
            "checkable: `the residue is the order of two " +
            "ANONYMOUS constituents — a function type and an object type — and their " +
            "DECLARATION positions give the opposite of tsgo's answer (row 23 is (() => { x: " +
            "string; }) | { x: string; } where the object's declaration, T's constraint, is " +
            "the EARLIER node), so it is not compareSymbols.` Rows 7/15 still need the other half: " +
            "`NoInfer<T>` is a `Substitution` type in tsc (bit 24, after `Object`'s bit 20) " +
            "where this model represents it as its own argument with an alias display. NOT " +
            "served by the TS2353 walker step 9 ordered — a `FunctionType` constituent makes " +
            "that one bail.",
    ),
    TsgoPendingBaseline(
        "reverseMappedTypeIntersectionConstraint.errors.txt",
        "**PIN-SERVED, AND THE RECORDED ORDER-MODEL REASON IS REFUTED** (re-measured " +
            "(P18.135) recon, verified independently): this whole baseline is re-emitted by " +
            "the wipe-and-pin walker `checkReverseMappedIntersectionConstraint` " +
            "(`Checker.kt:69136`), whose four member orders are HARDCODED `pinDiag` strings " +
            "over a file it wipes first — so NO engine rule produces these rows and no " +
            "`reverse-mapped MARK` is involved. Closing the row is a RE-TRANSCRIPTION of " +
            "those strings or a RETIREMENT of the walker, never an engine fix. The engine " +
            "claim behind the old reason is false too: tsgo orders a plain `keyof X` mapped " +
            "type by DECLARATION order (byte-identical to ours), and alphabetically only " +
            "when the key source is `keyof A & keyof B`, which it reduces to a SORTED " +
            "literal union where we leave it unreduced — 4 active cases write that shape. " +
            "Old reason, kept so the refutation is checkable: `all four rows are the MEMBER " +
            "order inside one anonymous object display ... Needs a reverse-mapped MARK on " +
            "the type — sorting every anonymous object's members by name is a whole-corpus " +
            "change.`",
    ),
    TsgoPendingBaseline(
        "typeParameterDiamond4.errors.txt",
        "ORDER-model at a per-READER DISPLAY site. **THE RECORDED \"RESOLUTION gap / " +
            "ENCLOSING function's type parameter\" REASON IS REFUTED** (re-measured " +
            "(P18.135) recon, verified independently): the divergence reproduces with ALL " +
            "THREE type parameters LOCAL to ONE function — `function flat<Top, T, U>() { " +
            "var top!: Top; var middle!: Top | T | U; top = middle }` renders `Top | T | U` " +
            "here and `T | Top | U` in tsgo — so no enclosing scope is involved and nothing " +
            "is degraded. The decisive corroboration is that `typeParameterDiamond3` is " +
            "ACTIVE and GREEN and its chain requires the SORTED `T | Top | U` for the very " +
            "same union: the comparator is fine, and it is the TYPE-PARAMETER-TARGET branch " +
            "of the assignment reader that renders its source in WRITTEN order instead of " +
            "the stably-ordered resolved type. At-risk if that branch is fixed: 4 green " +
            "baselines (`typeParameterDiamond2/3`, `doNotElaborateAssignabilityToTypeParameters`, " +
            "`quickIntersectionCheckCorrectlyCachesErrors`). The same fixture also shows a " +
            "SECOND, separate gap: tsgo adds `'Top' could be instantiated with an arbitrary " +
            "type which could be unrelated to ...` where we are silent.",
    ),
    // -------------------------------------------------------------------- (LEGACY.0b)
    TsgoPendingBaseline(
        "argumentsReferenceInFunction1_Js.errors.txt",
        "RECLASSIFIED (LEGACY.0b step 2) F9 -> type DISPLAY: the code, span and sentence agree and only a rendered TYPE differs; layer `submoduleAccepted`. tsgo: " +
        "index.js(13,29): error TS2345: Argument of type 'IArguments' is not assignable to " +
        "parameter of type '[f?: any]'. | ours: index.js(13,29): error TS2345: Argument of type " +
        "'IArguments' is not assignable to parameter of type '[f?: any, ...any[]]'."
    ),
    TsgoPendingBaseline(
        "asyncArrowInClassES5(target=es2015).js",
        "JS emit; layer `submoduleAccepted`. tsgo: (none) | ours: var _a;"
    ),
    TsgoPendingBaseline(
        "augmentExportEquals2.js",
        "JS emit; layer `submoduleAccepted`. tsgo: //// [file3.ts] | ours: //// [file1.js]"
    ),
    TsgoPendingBaseline(
        "classFieldSuperNotAccessibleJs.errors.txt",
        "NARROWED to ONE ROW (LEGACY.0b) step 25 — our answer is a SUBSET of tsgo's, " +
        "byte-identical on the five rows we emit. (P18.131)'s J1 gave the property-access " +
        "funnel a JavaScript class EXPANDO MEMBER MODEL " +
        "([Checker.jsClassAccessAdmitted]), so a `this.`/`super.` receiver whose class " +
        "chain is modellable is decidable: the three TS2339 rows at (7,14), (26,22) and " +
        "(29,22) now fire at tsgo's positions with tsgo's messages, and the two CORRECT " +
        "TS2855 rows for `roots` and `foo` stay because the model REFUSES the access for a " +
        "name that IS an expando member. What is left is J3 and it is NOT a JavaScript " +
        "question: `this['literalElementAccess'];` is TS7053 in tsgo — a two-line chain " +
        "anchored at the WHOLE element access — where our element funnel would give TS2339 " +
        "at the index, and the SAME divergence reproduces in a `.ts` file, so opening the " +
        "element funnel would propagate a wrong code and span into a second file kind. " +
        "tsgo: index.js(9,9): error TS7053: Element implicitly has an 'any' type because " +
        "expression of type '\"literalElementAccess\"' can't be used to index type " +
        "'YaddaBase'. | ours: (nothing)"
    ),
    TsgoPendingBaseline(
        "commonjsAccessExports.errors.txt",
        "TS2683-residue: the TS2683 row is CORRECT since (LEGACY.0b step 7); what is left " +
        "is TS7009 for a PROPERTY-ACCESS callee (`new exports.Cls()` where `exports.Cls = " +
        "function(){}`), and that gap is GENERAL rather than JS-specific — measured, we are " +
        "silent for `new O.m()` and `new N.f()` in a plain .ts file too where tsgo reports " +
        "both. `checkNewExprImplicitAny` demands `callee is Identifier`; the fix is to decide " +
        "TS7009 from the callee TYPE (tsgo: the resolved signature's declaration is not a " +
        "constructor / construct signature / constructor type), which is its own family. " +
        "tsgo: /a.js(12,18): error TS7009 | ours: nothing"
    ),
    TsgoPendingBaseline(
        "complexRecursiveCollections.errors.txt",
        "**PIN-SERVED** (re-measured (P18.135) recon, verified independently): the row is a " +
        "hardcoded `pinDiag` chain in the wipe-and-pin walker `checkComplexRecursiveCollections` " +
        "(`Checker.kt:69241`), which wipes the file first — so the earlier `chain CONTENT` " +
        "classification is true of the BYTES and misleading about the CAUSE: no engine rule " +
        "chooses this message. Closing it is a RE-TRANSCRIPTION (`The types of` -> `The types " +
        "returned by`) or a walker RETIREMENT, and retirement is NOT yet viable — the engine's " +
        "own path accumulation is still short for this fixture. layer `submoduleAccepted`. tsgo: The types " +
        "returned by 'map(...).size' are incompatible between these types. | ours: The types of " +
        "'map(...).size' are incompatible between these types."
    ),
    TsgoPendingBaseline(
        "controlFlowInstanceof.errors.txt",
        "TS2683-residue: the `uglify.js(5,23)` TS2683 row is CORRECT since (LEGACY.0b step " +
        "7); the residue is THREE other mechanisms and none is implicit-`this`. Ours-only: " +
        "controlFlowInstanceof.ts(20,7) TS2339 `Property 'add' does not exist on type " +
        "'Promise<any> | Set<number>'` (an `instanceof` narrow that tsgo resolves to " +
        "`Set<number>`) and controlFlowInstanceof.ts(105,5) TS2721 `Cannot invoke an object " +
        "which is possibly 'null'`; missing: uglify.js(9,7) TS2339 `Property 'val' does not " +
        "exist on type '{}'`. layer `submoduleAccepted`."
    ),
    TsgoPendingBaseline(
        "es6ExportEqualsInterop.errors.txt",
        "RECLASSIFIED (LEGACY.0b step 3) F6 -> pin-walker + type DISPLAY: this baseline's 31 " +
        "diagnostics (the nine TS2497 rows included) are re-emitted VERBATIM by the dedicated " +
        "walker `checkEs6ExportEqualsInteropPin`, so step 3's deletion of every TS2497 EMITTER " +
        "does not reach it; closing the row means retiring that walker AND typing " +
        "`import * as ns` of an `export = <fn>` module as its synthetic default. Layer " +
        "`submoduleAccepted`. tsgo: main.ts(56,4): error TS2339: Property 'a' does not exist " +
        "on type '{ default: () => any; }'. | ours: same row on type '() => any', plus nine " +
        "TS2497 rows tsgo does not have."
    ),
    TsgoPendingBaseline(
        "esModuleInteropTslibHelpers.errors.txt",
        "F6 top code differs (tsgo TS2354 / ours -); layer `submoduleAccepted`. tsgo: " +
        "file.ts(1,1): error TS2354: This syntax requires an imported helper but module 'tslib' " +
        "cannot be found. | ours: ==== file.ts (0 errors) ===="
    ),
    TsgoPendingBaseline(
        "exportAssignmentMembersVisibleInAugmentation.errors.txt",
        "F6 top code differs (tsgo TS4060 / ours TS2304,TS2664); layer `submoduleTriaged`. " +
        "tsgo: /a.ts(3,26): error TS4060: Return type of exported function has or is using " +
        "private name 'T'. | ours: /a.ts(2,16): error TS2664: Invalid module name in " +
        "augmentation, module 'foo' cannot be found."
    ),
    TsgoPendingBaseline(
        "expressionWithJSDocTypeArguments.errors.txt",
        "F6 top code differs (tsgo TS1110 / ours TS17019,TS17020,TS8020); layer " +
        "`submoduleAccepted`. tsgo: expressionWithJSDocTypeArguments.ts(9,22): error TS1110: " +
        "Type expected. | ours: expressionWithJSDocTypeArguments.ts(9,21): error TS8020: JSDoc " +
        "types can only be used inside documentation comments."
    ),
    TsgoPendingBaseline(
        "importDeclWithExportModifierAndExportAssignment.js",
        "JS emit; layer `submoduleAccepted`. tsgo: Object.defineProperty(exports, " +
        "\"__esModule\", { value: true }); | ours: module.exports = x;"
    ),
    TsgoPendingBaseline(
        "incorrectRecursiveMappedTypeConstraint.errors.txt",
        "F0 rows tsgo emits that ours does not: a related TS2751 `Circularity originates in " +
        "type at this location.` on incorrectRecursiveMappedTypeConstraint.ts:3:10. REFUSED (LEGACY.0b) step 19 and it must " +
        "stay refused: the divergence LAYER is `submoduleTriaged` — the `.diff` for this " +
        "baseline lives in testdata/baselines/reference/submoduleTriaged/compiler/, whose " +
        "header reads \"known diffs that we intend to fix\", i.e. a tsgo DEFECT. (The " +
        "base `.errors.txt` under submodule/ is the adopted baseline every case has; it " +
        "is NOT the layer, and reading it as one is what made this row look targetable.) " +
        "tsgo attaches it at TS2313 x2. | ours: nothing"
    ),
    TsgoPendingBaseline(
        "invariantGenericErrorElaboration.errors.txt",
        "(P18.135) RE-MEASURED, and the family label was wrong: it is not the missing " +
        "per-level relation header that round added (`Constraint<A>`'s chain does not go " +
        "through one), it is tsgo's VARIANCE measurement. `Constraint<A extends " +
        "Runtype<any>>` uses A both covariantly (`underlying: A`) and contravariantly " +
        "(`check: (x: A['witness']) => void`), so tsc's `relateVariances` measures A " +
        "INVARIANT, fails the type-argument check, RESETS the error info and falls back to " +
        "the STRUCTURAL comparison -- which is where the `Types of property 'underlying'` " +
        "line and the REVERSED `Constraint<Runtype<any>>` / `Constraint<Num>` header come " +
        "from. This checker measures no variances (CLAUDE.md: global variance analysis in " +
        "the relation engine is DEAD, ~263 regressions at round 336), so BOTH missing lines " +
        "and the direction of the header they carry are downstream of that one absent " +
        "mechanism. tsgo: Type 'Constraint<Runtype<any>>' is not assignable to type " +
        "'Constraint<Num>'. | ours: Property 'tag' is missing in type 'Runtype<any>' but " +
        "required in type 'Num'."
    ),
    TsgoPendingBaseline(
        "jsEnumCrossFileExport.errors.txt",
        "RE-SIZED (LEGACY.0b step 14): the two enumDef.js TS1003 rows now MATCH ((P18.99) M2); " +
        "what remains is (a) the two index.js TS2749 rows on a QUALIFIED expando name " +
        "(`Host.UserMetrics.Action`), which tsgo resolves through the JSDoc-namespace " +
        "declarations a `@typedef {…} Host.UserMetrics.Bargh` creates (a plain expando is " +
        "TS2503 there, measured) — unmodelled here — and (b) the baseline RENDERING of the " +
        "(14,21) row, whose width-1 range is the NEWLINE: tsgo's harness prints an empty " +
        "squiggle line, the next source line and the message after it, where ours prints a " +
        "`~` at column 21."
    ),
    TsgoPendingBaseline(
        "jsExportMemberMergedWithModuleAugmentation.errors.txt",
        "PARTIAL since (P18.121): the TS2671 row now MATCHES tsgo, at /index.ts(3,16), and the " +
        "TS2741 it used to carry is gone — the augmentation is refused rather than merged. The " +
        "residue is a SECOND mechanism this row needs and its sibling (…Augmentation2, closed) " +
        "did not: an import from a CJS `module.exports = {objLit}` JS file binds a VALUE ONLY, " +
        "so tsgo reports /index.ts(11,10) TS2749 `'Abcde' refers to a value, but is being used " +
        "as a type here. Did you mean 'typeof Abcde'?` where we still resolve `Abcde` as a type " +
        "and report /index.ts(11,20) TS2353 `Object literal may only specify known properties, " +
        "and 'b' does not exist in type 'Abcde'.` The fixture's own comment states the rule " +
        "(\"the type meaning from /test.js does not propagate through the object literal " +
        "export\"). Needs the value-only-import change plus its TS2353 suppression; NOT an " +
        "augmentation question."
    ),
    TsgoPendingBaseline(
        "jsdocFunctionClassPropertiesDeclaration.errors.txt",
        "TS2683-residue: the three TS2683 rows and the TS7009 row are CORRECT since " +
        "(LEGACY.0b step 7); the residue is that a JSDoc `@param {number | undefined} x` tag " +
        "does not TYPE the parameter it names, so we add three ours-only rows tsgo does not " +
        "have — /a.js(5,21) and (5,24) TS7006 `Parameter 'x'/'y' implicitly has an 'any' " +
        "type.` plus /a.js(5,17) TS7023 `'Foo' implicitly has return type 'any'…`. JSDoc " +
        "parameter typing is its own family. layer `submodule`."
    ),
    TsgoPendingBaseline(
        "jsdocImportTypeNodeNamespace.errors.txt",
        "F6 top code differs (tsgo TS2694 / ours TS2352); layer `submodule`. tsgo: " +
        "Main.js(2,49): error TS2694: Namespace '\"GeometryType\"' has no exported member " +
        "'default'. | ours: Main.js(2,21): error TS2352: Conversion of type 'string' to type " +
        "'typeof _default' may be a mistake because neither type sufficiently overlaps with th"
    ),
    TsgoPendingBaseline(
        "jsdocParameterParsingInfiniteLoop.errors.txt",
        "F6 top code differs (tsgo TS1005 / ours TS1110,TS2304,TS7014); layer `submodule`. " +
        "tsgo: example.js(3,19): error TS1005: '}' expected. | ours: example.js(3,11): error " +
        "TS7014: Function type, which lacks return-type annotation, implicitly has an 'any' " +
        "return type."
    ),
    TsgoPendingBaseline(
        "jsdocRestParameter.errors.txt",
        "F6 top code differs (tsgo TS2554 / ours TS2345); layer `submodule`. tsgo: /a.js(8,6): " +
        "error TS2554: Expected 1 arguments, but got 2. | ours: /a.js(7,3): error TS2345: " +
        "Argument of type 'number[]' is not assignable to parameter of type 'number'."
    ),
    TsgoPendingBaseline(
        "noParameterReassignmentIIFEAnnotated.errors.txt",
        "JSDoc: an OURS-ONLY TS8029. (LEGACY.0b) F6a is CLOSED for this row -- its " +
            "TS2740 leaf now matches tsgo byte for byte -- and what is left is a " +
            "different family: `@param {...unknown} rest` on a zero-parameter function " +
            "that reads `arguments`. A VARIADIC JSDoc param IS an array type, so tsc's " +
            "`It would match 'arguments' if it had an array type` rung must not fire; " +
            "our emitter does not test the tag's variadic-ness. tsgo reports nothing " +
            "here. Layer `submoduleAccepted`. tsgo: index.js(6,42): error TS2740 (which " +
            "we now match) | ours: that row PLUS index.js(3,28): error TS8029: JSDoc " +
            "'@param' tag has name 'rest', but there is no parameter with that name.",
    ),
    TsgoPendingBaseline(
        "overloadOnConstNoAnyImplementation2.errors.txt",
        "REFUSED with a measurement (LEGACY.0b step 18): the mechanism is tsgo's type-NODE " +
        "REUSE, not quote preservation. tsgo prints the SOURCE TEXT of a parameter's written " +
        "annotation whenever the rendered signature's declaration is a function-like with a " +
        "BODY (arrow / function expression / an inferred `const`), and renders structurally " +
        "otherwise — measured: an interface MethodSignature (`overloadOnConstInheritance2`, " +
        "ACTIVE and GREEN, source `(x: 'bar')` rendered `(x: \"bar\")`), a FunctionTypeNode " +
        "annotation (`declare const h: (x: 'e') => number` renders `\"e\"`) and an " +
        "instantiated generic alias (`Func<T,U>`) all render structurally. Reuse is VERBATIM, " +
        "so it also keeps a type ALIAS unresolved (`(x: Al)` where we print `(x: \"zz\")`), a " +
        "keyword alias (`(x: Nm)` for `number`) and a generic spelling (`Array<string>`), and " +
        "even a backslash escape (`'it\\'s'`). COST: `typeToString` renders from a `Type` and " +
        "has neither the declaring file's source nor a tight end for a `TypeNode` (only " +
        "`AsExpression` carries `tightEnd`), so it is a display-layer change; exposure is 186 " +
        "tsgo errors baselines rendering an annotated-parameter signature, 94 of them " +
        "subtests in the live generated tree (66 with a one-parameter signature), all " +
        "currently GREEN and gated by the corpus alone ((PARITY.1)). tsgo: Argument of type " +
        "'(x: 'bye') => number' | ours: '(x: \"bye\") => number'; both sides' chain sub-lines " +
        "are double-quoted in tsgo too."
    ),
    TsgoPendingBaseline(
        "overloadOnConstNoStringImplementation2.errors.txt",
        "REFUSED with a measurement (LEGACY.0b step 18): the mechanism is tsgo's type-NODE " +
        "REUSE, not quote preservation. tsgo prints the SOURCE TEXT of a parameter's written " +
        "annotation whenever the rendered signature's declaration is a function-like with a " +
        "BODY (arrow / function expression / an inferred `const`), and renders structurally " +
        "otherwise — measured: an interface MethodSignature (`overloadOnConstInheritance2`, " +
        "ACTIVE and GREEN, source `(x: 'bar')` rendered `(x: \"bar\")`), a FunctionTypeNode " +
        "annotation (`declare const h: (x: 'e') => number` renders `\"e\"`) and an " +
        "instantiated generic alias (`Func<T,U>`) all render structurally. Reuse is VERBATIM, " +
        "so it also keeps a type ALIAS unresolved (`(x: Al)` where we print `(x: \"zz\")`), a " +
        "keyword alias (`(x: Nm)` for `number`) and a generic spelling (`Array<string>`), and " +
        "even a backslash escape (`'it\\'s'`). COST: `typeToString` renders from a `Type` and " +
        "has neither the declaring file's source nor a tight end for a `TypeNode` (only " +
        "`AsExpression` carries `tightEnd`), so it is a display-layer change; exposure is 186 " +
        "tsgo errors baselines rendering an annotated-parameter signature, 94 of them " +
        "subtests in the live generated tree (66 with a one-parameter signature), all " +
        "currently GREEN and gated by the corpus alone ((PARITY.1)). tsgo: Argument of type " +
        "'(x: 'bye') => number' | ours: '(x: \"bye\") => number'; both sides' chain sub-lines " +
        "are double-quoted in tsgo too."
    ),
    TsgoPendingBaseline(
        "pathsValidation5.errors.txt",
        "RECLASSIFIED (LEGACY.0b step 2) F9 -> summary ORDER; re-confirmed at step 9 that " +
            "it is NOT a union order and shares no mechanism with the ORDER family: the " +
            "TS5090 wording matches and the ONLY difference is where a `tsconfig.json` row " +
            "sorts against a source file's in the summary — tsgo lists `src/main.ts(1,8): " +
            "TS2882` FIRST and we list it last. Changing it reorders the summary of every " +
            "multi-file baseline, so it needs its own round. MEASURED (LEGACY.0b step 15): " +
            "tsgo's rule is pure path order (`ast.CompareDiagnostics`), and adopting it moves " +
            "SEVEN green baselines whose tsconfig rows stay first — all seven are baseUrl / " +
            "moduleResolution=node cases tsgo 7 does not run (no baseline under " +
            "typescript-go-repo/testdata), so their order is a (LEGACY.1) question, not a rule.",
    ),
    TsgoPendingBaseline(
        "typeParameterWithInvalidConstraintType.errors.txt",
        "F0 rows tsgo emits that ours does not: a related TS2751 `Circularity originates in " +
        "type at this location.` on typeParameterWithInvalidConstraintType.ts:4:17. REFUSED (LEGACY.0b) step 19 and it must " +
        "stay refused: the divergence LAYER is `submoduleTriaged` — the `.diff` for this " +
        "baseline lives in testdata/baselines/reference/submoduleTriaged/compiler/, whose " +
        "header reads \"known diffs that we intend to fix\", i.e. a tsgo DEFECT. (The " +
        "base `.errors.txt` under submodule/ is the adopted baseline every case has; it " +
        "is NOT the layer, and reading it as one is what made this row look targetable.) " +
        "tsgo attaches it at TS2313. | ours: nothing"
    ),
)

val cloneTypeScriptRepo = tasks.register("cloneTypeScriptRepo") {
    group = "typescript"
    description = "Sparse-clones the TypeScript repository (tests only), pinned to tsgo 7.0.2's submodule commit."
    inputs.property("typeScriptCommit", typeScriptCommit) // re-run when the pin changes
    // M3.0: the sparse checkout is DERIVED from the allowlist (see `sparsePaths`
    // below), so the allowlist is an input of this task and is declared as one.
    // `generateTypeScriptTests` already declares the same property for the same
    // reason. NOT a demonstrated bug fix — round 831 tried to show that adopting a
    // category leaves this task UP-TO-DATE (which would silently generate zero tests
    // for it) and could NOT: removing an input property is itself an invalidation, so
    // the experiment is uninterpretable. Declared because it is true, not because a
    // failure was observed.
    inputs.property("conformanceCategories", conformanceCategories)
    outputs.dir(typeScriptRepoDir)

    doLast {
        val sparsePaths = arrayOf("tests/cases/compiler", "tests/baselines/reference") +
            conformanceCategories.map { "tests/cases/conformance/$it" }

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
 * [typeScriptGoCommit], so the generator can read tsgo's own baselines.
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
 * The generator declares [typeScriptGoCommit] as a task PROPERTY rather than these
 * directories as input trees, for the same reason `baselinesDir` is not an input of it
 * today: the baselines are a function of the pin and of nothing else.
 */
val cloneTypeScriptGoRepo = tasks.register("cloneTypeScriptGoRepo") {
    group = "typescript"
    description = "Ensures the typescript-go checkout is present and pinned to tsgo 7.0.2 (its baselines are the corpus reference)."
    inputs.property("typeScriptGoCommit", typeScriptGoCommit)

    doLast {
        // NON-CONE patterns (gitignore syntax, rooted): the baselines we compare against,
        // the `.diff` records that classify a divergence by LAYER in all three directories,
        // and tsgo's two classification lists, which the KDoc of [TsgoBaselineChoice] cites
        // and a reader will want on disk. `.types`/`.symbols` are deliberately absent.
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

/**
 * Embeds EVERY real TypeScript `.d.ts` lib source from `src/lib` as generated Kotlin
 * (`RealLibFiles.kt` in commonMain), read straight from the pinned commit's object DB —
 * the sparse working tree does not materialize `src/lib`, but the depth-1 fetch of
 * [typeScriptCommit] brought all of its blobs down, so extraction works offline.
 *
 * (LIB.1)(b), round 731: the DOM / webworker / scripthost sets used to be filtered OUT
 * here. That filter was a SILENT WRONG ANSWER, not a missing feature: an unshipped lib
 * lands in `RealLibResolver.Resolution.unavailable`, which nothing consumes, so a
 * browser project's DOM types degraded to `any` and compiled clean. Do NOT reintroduce
 * a name filter — an unshipped lib must be an absent lib, and (c) makes a REQUESTED
 * absent lib a diagnostic.
 *
 * SIZE, because it is the reason the filter existed: the host set is 3.14 MB of the
 * 3.71 MB total (dom.generated alone is 2.35 MB — mostly MDN doc comments), so this
 * task emits ~4 MB of Kotlin. Measured cost of adding it: see the round-731 note.
 *
 * TWO TRAPS this generator exists to dodge, both measured, neither optional:
 *
 *  1. A JVM class-file string constant caps at 65,535 bytes of modified UTF-8
 *     (es5.d.ts is 218 KB, dom.generated 2.35 MB) — the text is emitted as
 *     `StringBuilder.append(...)` chunks of ≤ 60,000 value bytes, split at line
 *     boundaries, concatenated at runtime. Never fold the chunks back into a single
 *     literal (or a `const val` concatenation, which constant-folds at compile time).
 *  2. The KOTLIN COMPILER runs out of heap on one big generated file. Emitting all
 *     3.71 MB as a single `RealLibFiles.kt` with one `buildMap { }` lambda failed with
 *     "Not enough memory to run compilation" after 7m34s at the 5 GB the BUILD.1 pin
 *     gives the Kotlin daemon. So the chunks are spread over `RealLibFilesPart*.kt`
 *     files of ~250 KB each, and `RealLibFiles` concatenates and re-cuts them by
 *     recorded lengths. Do NOT merge the parts back together.
 */
val generateRealLibSources = tasks.register("generateRealLibSources") {
    group = "typescript"
    description = "Generates RealLibFiles.kt embedding the real TypeScript lib .d.ts sources (all libs, DOM included)."

    dependsOn(cloneTypeScriptRepo)
    inputs.property("typeScriptCommit", typeScriptCommit)
    val outputDir = layout.buildDirectory.dir("generated/real-lib")
    outputs.dir(outputDir)

    doLast {
        // Wipe first: the emission is split into a VARIABLE number of part files, so a
        // shrinking pin would otherwise leave a stale RealLibFilesPartN.kt behind and it
        // would still compile into the module.
        outputDir.get().asFile.deleteRecursively()
        val packageDir = outputDir.get().asFile.resolve("com/xemantic/typescript/compiler")
        packageDir.mkdirs()

        val names = captureCommand(
            "git", "ls-tree", "--name-only", typeScriptCommit, "src/lib/",
            workingDir = typeScriptRepoDir,
        ).lines()
            .map { it.removePrefix("src/lib/") }
            .filter { it.endsWith(".d.ts") }
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

        val header = "// Auto-generated by ./gradlew generateRealLibSources. Do not edit.\n" +
            "// Real TypeScript lib sources pinned to commit $typeScriptCommit.\n" +
            "package com.xemantic.typescript.compiler\n\n"

        // One flat chunk stream in lib-name order, plus each lib's CHAR length so the
        // facade can cut the concatenation back apart. Chars, not bytes: the chunker
        // splits on mutf8 byte budget but always at char boundaries, and `substring` is
        // char-indexed. Two parallel lists rather than a local data class — the Gradle
        // Kotlin-DSL script compiler fails codegen on a local class declared inside a
        // task-configuration lambda ("Script compilation error", no further detail).
        val libKeys = mutableListOf<String>()
        val libChars = mutableListOf<Int>()
        val allChunks = mutableListOf<String>()
        var totalBytes = 0L
        for (name in names) {
            val content = captureCommand(
                "git", "show", "$typeScriptCommit:src/lib/$name",
                workingDir = typeScriptRepoDir,
            )
            totalBytes += content.sumOf { mutf8Len(it) }
            libKeys.add(name.removeSuffix(".d.ts"))
            libChars.add(content.length)
            allChunks.addAll(chunk(content))
        }

        // Group the chunk stream into PART FILES of ~250 KB. Round 731: emitting all
        // 3.71 MB as one file with one giant `buildMap` lambda exhausted the 5 GB Kotlin
        // daemon heap ("Not enough memory to run compilation") after 7.5 minutes; the
        // split is what makes the DOM set compilable at the pinned heap. Do NOT merge the
        // parts back into one file.
        val partBudget = 250_000
        val parts = mutableListOf<MutableList<String>>()
        var partBytes = 0
        for (c in allChunks) {
            val cBytes = c.sumOf { mutf8Len(it) }
            if (parts.isEmpty() || partBytes + cBytes > partBudget) {
                parts.add(mutableListOf())
                partBytes = 0
            }
            parts.last().add(c)
            partBytes += cBytes
        }

        for ((i, part) in parts.withIndex()) {
            val pb = StringBuilder(partBudget * 2)
            pb.append(header)
            pb.appendLine("/** Chunk group $i of the embedded lib sources — see [RealLibFiles]. */")
            pb.appendLine("internal object RealLibFilesPart$i {")
            pb.appendLine("    fun append(sb: StringBuilder) {")
            for (c in part) pb.appendLine("        sb.append(\"${escape(c)}\")")
            pb.appendLine("    }")
            pb.appendLine("}")
            packageDir.resolve("RealLibFilesPart$i.kt").writeText(pb.toString())
        }

        val sb = StringBuilder(64 * 1024)
        sb.append(header)
        sb.appendLine("/**")
        sb.appendLine(" * The real TypeScript `.d.ts` lib sources from `src/lib` — ALL of them, the DOM /")
        sb.appendLine(" * webworker / scripthost host sets included — keyed by bare lib name (`es5`,")
        sb.appendLine(" * `es2015.core`, `dom.generated`, `decorators`, ...). Content is byte-identical")
        sb.appendLine(" * to the pinned tsc commit's files (CRLF line endings preserved).")
        sb.appendLine(" *")
        sb.appendLine(" * The text lives in `RealLibFilesPart*.kt` as one flat stream of string chunks")
        sb.appendLine(" * (each under the 65,535-byte JVM class-file constant cap); this object")
        sb.appendLine(" * concatenates them once and cuts the result back into per-lib strings by the")
        sb.appendLine(" * recorded char lengths.")
        sb.appendLine(" */")
        sb.appendLine("object RealLibFiles {")
        sb.appendLine()
        sb.appendLine("    // Declared BEFORE `files`: an object's properties initialize in source order,")
        sb.appendLine("    // so these would still be null if they came after it.")
        sb.appendLine("    private val libNames: Array<String> = arrayOf(")
        for (key in libKeys) sb.appendLine("        \"$key\",")
        sb.appendLine("    )")
        sb.appendLine()
        sb.appendLine("    private val libLengths: IntArray = intArrayOf(")
        libChars.chunked(12).forEach { row ->
            sb.appendLine("        " + row.joinToString(", ") + ",")
        }
        sb.appendLine("    )")
        sb.appendLine()
        sb.appendLine("    val files: Map<String, String> = run {")
        sb.appendLine("        val sb = StringBuilder(${libChars.sum()})")
        for (i in parts.indices) sb.appendLine("        RealLibFilesPart$i.append(sb)")
        sb.appendLine("        val text = sb.toString()")
        sb.appendLine("        val map = LinkedHashMap<String, String>(libNames.size * 2)")
        sb.appendLine("        var pos = 0")
        sb.appendLine("        for (i in libNames.indices) {")
        sb.appendLine("            val end = pos + libLengths[i]")
        sb.appendLine("            map[libNames[i]] = text.substring(pos, end)")
        sb.appendLine("            pos = end")
        sb.appendLine("        }")
        sb.appendLine("        map")
        sb.appendLine("    }")
        sb.appendLine("}")

        packageDir.resolve("RealLibFiles.kt").writeText(sb.toString())
        logger.lifecycle(
            "Generated RealLibFiles.kt + ${parts.size} part file(s): " +
                "${names.size} lib files, $totalBytes bytes of lib source, ${allChunks.size} chunks."
        )
    }
}

// RealLibFiles.kt is commonMain source. The srcDir takes the task PROVIDER, never a bare
// `layout.buildDirectory.dir(...)`: the provider carries the dependency to EVERY consumer
// of the source set, where a hand-written `dependsOn` on the compile tasks covers only the
// consumers it names — the sources jars then read a generated directory nobody declared
// they depend on, which Gradle 9 fails the build over (`publishToMavenLocal` → jvmSourcesJar).
kotlin.sourceSets.commonMain {
    kotlin.srcDir(generateRealLibSources)
}

/**
 * Generates BuildInfo.kt (commonMain) carrying the compiler's build identity —
 * the git sha at build time, with a `.dirty` suffix when the working tree has
 * local changes, or `unknown` when git is unavailable. Consumed by the
 * INV.7(d3) `.xtsbuildinfo` validation: a persisted buildinfo is reusable only
 * when it was written by the SAME compiler build (a stale-compiler reuse of
 * kept diagnostics is otherwise silent — tsc embeds its version for the same
 * reason). Approved as a build-system change by the owner 2026-07-19.
 */
val xtscBuildId: String = try {
    val sha = captureCommand("git", "rev-parse", "HEAD").trim()
    val dirty = captureCommand("git", "status", "--porcelain").isNotBlank()
    if (dirty) "$sha.dirty" else sha
} catch (_: Exception) {
    "unknown"
}

val generateBuildInfo = tasks.register("generateBuildInfo") {
    group = "typescript"
    description = "Generates BuildInfo.kt with the compiler build identity (git sha)."

    inputs.property("xtscBuildId", xtscBuildId)
    val outputDir = layout.buildDirectory.dir("generated/buildinfo")
    outputs.dir(outputDir)

    doLast {
        val packageDir = outputDir.get().asFile.resolve("com/xemantic/typescript/compiler")
        packageDir.mkdirs()
        packageDir.resolve("BuildInfo.kt").writeText(
            """
            // Generated by the generateBuildInfo Gradle task — do not edit.
            package com.xemantic.typescript.compiler

            /**
             * Compiler build identity: the git sha at build time (`.dirty`-suffixed for a
             * tree with local changes; `unknown` when git was unavailable). The INV.7(d3)
             * `.xtsbuildinfo` validation refuses cross-process reuse for `unknown`/dirty
             * ids — only a clean, identical build may reuse persisted diagnostics.
             */
            internal const val XTSC_BUILD_ID: String = "$xtscBuildId"

            """.trimIndent()
        )
        logger.lifecycle("Generated BuildInfo.kt: XTSC_BUILD_ID=$xtscBuildId")
    }
}

// BuildInfo.kt is commonMain source — added through the task provider for the same reason
// as generated/real-lib above.
kotlin.sourceSets.commonMain {
    kotlin.srcDir(generateBuildInfo)
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
val generateTypeScriptTests = tasks.register("generateTypeScriptTests") {
    group = "typescript"
    description = "Generates Kotlin test cases from the TypeScript compiler test suite."

    dependsOn(cloneTypeScriptRepo)
    // (LEGACY.0b): the corpus reads tsgo's OWN baselines as its primary root.
    dependsOn(cloneTypeScriptGoRepo)

    val testsDir = typeScriptRepoDir.resolve("tests/cases/compiler")
    val conformanceRootDir = typeScriptRepoDir.resolve("tests/cases/conformance")
    val baselinesDir = typeScriptRepoDir.resolve("tests/baselines/reference")
    // (LEGACY.0b): tsgo checks in its OWN full output per SUITE; the three sibling
    // `submodule*` directories hold only `.diff` RECORDS, which classify a divergence by
    // LAYER and never replace a baseline. See docs/tsgo-baselines.md § 1.
    val tsgoBaselinesRoot = typeScriptGoRepoDir.resolve("testdata/baselines/reference")
    val tsgoBaselinesDir = tsgoBaselinesRoot.resolve("submodule")
    val outputDir = layout.buildDirectory.dir("generated/typescript-tests")
    // PARITY.1: the ledger doc is rewritten from `logicalParityDivergences` (see
    // below), and the declared `pinnedBy` classes are looked up here. Deliberately
    // NOT declared as a task output — it carries hand-written prose that Gradle's
    // stale-output handling has no business touching.
    // docs/ stayed at the repo root when the compiler moved into this module.
    val logicalParityDoc = rootProject.layout.projectDirectory.file("docs/logical-parity.md").asFile
    val commonTestDir = layout.projectDirectory.dir("src/commonTest/kotlin").asFile
    val divergences = logicalParityDivergences
    val pending = tsgoPendingBaselines

    inputs.dir(testsDir).optional()
    // M3.0: re-generate when the allowlist changes, or when an allowlisted
    // category's sources do.
    inputs.property("conformanceCategories", conformanceCategories)
    for (category in conformanceCategories) {
        inputs.dir(conformanceRootDir.resolve(category)).optional()
    }
    // PARITY.1: re-generate (and re-validate, and rewrite the ledger) when a
    // divergence is declared, edited, or removed.
    inputs.property("logicalParityDivergences", divergences.map { it.toString() })
    // (LEGACY.0a): likewise for the tsgo-pending list.
    inputs.property("tsgoPendingBaselines", pending.map { it.toString() })
    // (LEGACY.0b): the baseline ROOT is a function of this pin and of nothing else, so
    // the pin — not tsgo's 46k-file baseline tree — is what re-runs the generator. Same
    // reason `baselinesDir` has never been declared as an input tree either.
    inputs.property("typeScriptGoCommit", typeScriptGoCommit)
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

        // M3.0: the flat compiler corpus PLUS every allowlisted conformance category,
        // walked recursively because conformance categories nest. Sorted by basename so
        // the alphabetical class grouping below is unaffected by which corpus a case
        // came from; basenames are collision-free across the two, so the generated
        // function names stay unique.
        val compilerFiles = testsDir.listFiles { f -> f.isFile && f.extension == "ts" }
            ?.toList() ?: emptyList()
        val conformanceFiles = conformanceCategories.flatMap { category ->
            conformanceRootDir.resolve(category).walkTopDown()
                .filter { it.isFile && it.extension == "ts" }
                .toList()
        }
        val testFiles = (compilerFiles + conformanceFiles).sortedBy { it.name }

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

        // 2b. A tsconfig.json EMBEDDED in the test (`@filename: .../tsconfig.json`) is LOADED by
        //     tsgo's harness — `GetConfigNameFromFileName` (harnessutil.go:1198) matches the
        //     BASENAME, and `CompileFiles` (harnessutil.go:87-108) seeds `compilerOptions` from
        //     it and only THEN lets `SetOptionsFromTestConfig` apply the `// @directive`s. So the
        //     options `SkipUnsupportedCompilerOptions` sees are `embedded tsconfig, DIRECTIVES
        //     OVERRIDING`, and the directive-level filter above — which reads directives alone —
        //     cannot see the embedded half. This predicate is that missing half.
        //
        //     (LEGACY.1)(g), owner decision 2026-09-17: extended from `module: amd/umd/system` +
        //     `outFile` to `baseUrl` and `moduleResolution: node/node10/classic`, because
        //     TypeScript 7 is the only compatibility target and tsgo answers NONE of those cases
        //     — measured over all of `typescript-go-repo/testdata`: 32 case files set `baseUrl` in
        //     an embedded tsconfig and tsgo has output for 0; 18 set a removed `moduleResolution`
        //     and tsgo has output for exactly 1. Keeping them pins PRISTINE TypeScript 6
        //     behaviour through (LEGACY.0b)'s *absent, no `.diff` -> keep tsc's* leg, which the
        //     2026-09-12 directive says is not a reference. `baseUrl` matches ANY string value
        //     including `""`: tsgo's test is `BaseUrl != ""` AFTER absolutization against the
        //     config dir, so an empty `baseUrl` is the config dir and still skips (and still
        //     reports TS5102 — measured through `tools/tsgo-7.0.2/lib/tsc`).
        //
        //     `target: es3/es5` is deliberately NOT checked, and the reason is MEASURED rather
        //     than incidental: 9 case files set it in an embedded tsconfig and tsgo answered 7.
        //     Under the directive rule below it would in fact skip NONE of the nine — three carry
        //     an overriding `@target: es2015` and four write `"ES3"`, which TypeScript 7 rejects
        //     as an invalid ARGUMENT (TS6046) leaving the target UNSET rather than ES5 — so
        //     adding it would be a no-op today. It stays out because the owner scoped it out and
        //     because a no-op rule is a trap for the next reader, not a safety net.
        //
        //     THE DIRECTIVE EXEMPTION IS LOAD-BEARING, not a nicety: `maxNodeModuleJsDepth-
        //     DefaultsToZero` sets `"moduleResolution": "node"` in its embedded tsconfig AND
        //     `// @moduleResolution: bundler` as a directive, so tsgo resolves Bundler, RAN it,
        //     and our corpus serves that subtest from tsgo's own baseline. An embedded-only regex
        //     would delete a gradeable tsgo answer — the very disqualifier that keeps `target`
        //     out. A directive that names the option is therefore checked by
        //     `usesUnsupportedOption` above and by nothing here, whatever value it gives.
        //
        //     Only a file NAMED exactly tsconfig.json counts (that is tsgo's own basename test);
        //     tsconfig1.json etc. are plain source-echo files — but see the `extends` walk below,
        //     which is how a file that is NOT so named still reaches this rule.
        //
        //     (P18.133) THE ROOT'S `extends` CHAIN IS FOLLOWED, and one case needed it.
        //     `pathMappingInheritedBaseUrl` writes `"baseUrl": "."` in `/other/tsconfig.base.json`
        //     and `{ "extends": "../other/tsconfig.base.json", … }` in `/project/tsconfig.json`,
        //     so the BASENAME scan above sees a root config that sets none of the four while the
        //     effective options plainly set `baseUrl`. tsgo's own skip reads the RESOLVED options
        //     (`SkipUnsupportedCompilerOptions` runs after the harness has built them) and so does
        //     see it — which is why the case has no tsgo artifact of any kind, and why keeping it
        //     pinned PRISTINE TypeScript 6's TS5101 through (LEGACY.0b)'s *absent, no `.diff` ->
        //     keep tsc's* leg. That is the population the owner approved dropping at (LEGACY.1)(g);
        //     this is the same rule reading one more file, not a wider one.
        //
        //     Measured 2026-09-17: it is the ONLY case in the corpus whose `baseUrl` /
        //     `moduleResolution` lives in a config the basename scan cannot reach —
        //     `tsgoExpectedKeptTsc` moves 3 -> 2 and `adopted` / `new` / `deleted` do not move,
        //     which is the receipt that exactly one pristine-pinned subtest left and nothing
        //     gradeable did.
        //
        //     The walk is deliberately conservative in three ways. The DIRECTIVE EXEMPTION still
        //     applies across the whole chain (a directive overrides whatever any config in it
        //     resolved, so it stays `usesUnsupportedOption`'s to judge). A target that is not a
        //     section of the test is simply not followed — an `extends` naming a package under
        //     `node_modules` has no body here and must not be guessed at. And the walk carries a
        //     visited set, because `extends` may cycle and a test is free to write one.
        fun tsconfigInTestUsesRemovedFeature(source: String, directives: Map<String, String>): Boolean {
            val sections = Regex("""(?im)^\s*//\s*@filename:\s*(\S+)\s*$""").findAll(source).toList()

            // `a/./b`, `a/../b` and a leading `/` folded away, so a section name and an `extends`
            // target resolved against a config's own directory compare as the same string.
            fun normalizePath(path: String): String {
                val out = ArrayList<String>()
                for (part in path.replace('\\', '/').split('/')) {
                    when {
                        part.isEmpty() || part == "." -> {}
                        part == ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                        else -> out.add(part)
                    }
                }
                return out.joinToString("/")
            }

            val bodyOf = HashMap<String, String>()
            val roots = ArrayList<String>()
            for ((i, m) in sections.withIndex()) {
                val name = m.groupValues[1]
                val start = m.range.last + 1
                val end = if (i + 1 < sections.size) sections[i + 1].range.first else source.length
                val key = normalizePath(name)
                bodyOf[key] = source.substring(start, end)
                if (name.substringAfterLast('/').equals("tsconfig.json", ignoreCase = true)) roots.add(key)
            }

            // A directive overrides the embedded value, so it is the directive filter's to judge.
            fun overridden(directive: String) = directives[directive]?.isNotBlank() == true
            fun bodyUsesRemovedFeature(body: String): Boolean {
                if (!overridden("module") &&
                    Regex("""(?i)"module"\s*:\s*"(amd|umd|system)"""").containsMatchIn(body)) return true
                if (!overridden("outfile") &&
                    Regex("""(?i)"outFile"\s*:\s*"[^"]+"""").containsMatchIn(body)) return true
                if (!overridden("baseurl") &&
                    Regex("""(?i)"baseUrl"\s*:\s*"[^"]*"""").containsMatchIn(body)) return true
                if (!overridden("moduleresolution") &&
                    Regex("""(?i)"moduleResolution"\s*:\s*"(node|node10|classic)"""").containsMatchIn(body)) return true
                return false
            }

            // `"extends"` is a string, or since TypeScript 5.0 an array of them.
            fun extendsTargetsOf(body: String): List<String> {
                val one = Regex("""(?i)"extends"\s*:\s*"([^"]+)"""").find(body)
                if (one != null) return listOf(one.groupValues[1])
                val many = Regex("""(?i)"extends"\s*:\s*\[([^\]]*)\]""").find(body) ?: return emptyList()
                return Regex(""""([^"]+)"""").findAll(many.groupValues[1]).map { it.groupValues[1] }.toList()
            }

            for (root in roots) {
                val seen = HashSet<String>()
                val worklist = ArrayList<String>()
                worklist.add(root)
                while (worklist.isNotEmpty()) {
                    val current = worklist.removeAt(worklist.size - 1)
                    if (!seen.add(current)) continue                 // `extends` may cycle.
                    val body = bodyOf[current] ?: continue           // not a file this test writes.
                    if (bodyUsesRemovedFeature(body)) return true
                    val dir = current.substringBeforeLast('/', "")
                    for (target in extendsTargetsOf(body)) {
                        val combined = if (dir.isEmpty()) target else "$dir/$target"
                        val resolved = normalizePath(combined)
                        worklist.add(resolved)
                        // tsc lets `extends` omit the extension.
                        if (!resolved.endsWith(".json", ignoreCase = true)) worklist.add("$resolved.json")
                    }
                }
            }
            return false
        }


        // ---------------------------------------------------------------------------
        // (LEGACY.0b) — the baseline ROOT, chosen per subtest with a three-way fallback.
        //
        // tsgo stores full output, not a patch, so "re-pin the corpus to what tsgo emits"
        // is a root switch. What makes it more than a path change is the case where tsgo
        // has NO file: that is two different facts wearing one absence, and reading them
        // as one silently shrinks (or silently freezes) the corpus.
        //
        //   present                     -> use tsgo's baseline.
        //   absent, a `.diff` exists    -> tsgo RAN the case and emitted nothing: DELETE.
        //   absent, no `.diff`          -> tsgo never ran the configuration: KEEP tsc's.
        //
        // Every bucket is counted and asserted below against the pinned constants.
        val tsgoDiffLayerOf = HashMap<String, String>()
        for (layer in listOf("submodule", "submoduleAccepted", "submoduleTriaged")) {
            for (suite in listOf("compiler", "conformance")) {
                val names = tsgoBaselinesRoot.resolve("$layer/$suite").list() ?: continue
                for (n in names) {
                    if (!n.endsWith(".diff")) continue
                    val key = "$suite/${n.removeSuffix(".diff")}"
                    val prior = tsgoDiffLayerOf.put(key, layer)
                    // tsgo's own runner fatals when a baseline is listed as both accepted
                    // and triaged; a third layer holding it too would make `layer` a
                    // function of iteration order rather than of tsgo's classification.
                    check(prior == null) {
                        "tsgo classifies $key in two layers at once ($prior and $layer) — " +
                            "the layer is supposed to be tsgo's single verdict on that diff."
                    }
                }
            }
        }
        var tsgoAdopted = 0
        var tsgoNew = 0
        var tsgoDeleted = 0
        var tscKept = 0
        // Only the baselines a subtest is actually generated from, keyed "<suite>/<name>"
        // so the layer census below cannot mis-attribute a name the two suites share.
        val adoptedTsgoFiles = LinkedHashMap<String, File>()

        /**
         * Resolves [baselineName] for a case in [suite], counting the bucket it falls in.
         * `null` means NO subtest is generated. Call it only once the case's own filters
         * (tsgo-removed options, deferred conformance error baselines) have passed, so the
         * counts describe ACTIVE subtests and nothing else.
         */
        fun resolveBaseline(suite: String, baselineName: String): TsgoBaselineChoice? {
            val tsgoFile = tsgoBaselinesDir.resolve(suite).resolve(baselineName)
            val tscFile = baselinesDir.resolve(baselineName)
            val layer = tsgoDiffLayerOf["$suite/$baselineName"]
            if (tsgoFile.isFile) {
                tsgoAdopted++
                if (!tscFile.isFile) tsgoNew++
                adoptedTsgoFiles["$suite/$baselineName"] = tsgoFile
                return TsgoBaselineChoice(
                    tsgoFile,
                    "${D}typeScriptGoBaselineDir/$suite/$baselineName",
                    layer,
                    fromTsgo = true,
                )
            }
            if (layer != null) {
                if (tscFile.isFile) tsgoDeleted++
                return null
            }
            if (!tscFile.isFile) return null
            tscKept++
            return TsgoBaselineChoice(
                tscFile,
                "${D}typeScriptBaselineDir/$baselineName",
                layer = null,
                fromTsgo = false,
            )
        }

        /**
         * Emits tsgo's LAYER for a baseline that differs from tsc's, so a reader of the
         * generated test — and a `grep` over them — can tell an intended TypeScript 7
         * behaviour from a tsgo defect without leaving the file.
         */
        fun StringBuilder.appendTsgoLayer(choice: TsgoBaselineChoice) {
            val layer = choice.layer ?: return
            val gloss = when (layer) {
                "submoduleAccepted" -> "an INTENDED TypeScript 7 divergence (submoduleAccepted.txt)"
                "submoduleTriaged" -> "a tsgo DEFECT it intends to fix (submoduleTriaged.txt) — do NOT target this family"
                else -> "UNTRIAGED by tsgo (in neither list)"
            }
            appendLine("    // TSGO BASELINE (LEGACY.0b), layer `$layer`: $gloss.")
        }

        // PARITY.1 — a baseline whose divergence from pristine tsc is FORM, not
        // MEANING, is switched off HERE and nowhere else: the emission stays, carrying
        // `@Ignore` plus the reason, so the case remains visible as SKIPPED instead of
        // disappearing from the total. Keyed by baseline FILE name because that is
        // exactly one generated subtest — bare or parameterized, errors or emit.
        val divergenceByBaseline = divergences.associateBy { it.baseline }
        check(divergenceByBaseline.size == divergences.size) {
            "logicalParityDivergences declares the same baseline twice: " +
                divergences.groupingBy { it.baseline }.eachCount().filterValues { it > 1 }.keys
        }
        val usedDivergences = mutableSetOf<String>()
        // (LEGACY.0a): the tsgo-pending list, keyed the same way and emitted through the
        // same `@Ignore` path — a skipped subtest, never a vanished one.
        val pendingByBaseline = pending.associateBy { it.baseline }
        check(pendingByBaseline.size == pending.size) {
            "tsgoPendingBaselines declares the same baseline twice: " +
                pending.groupingBy { it.baseline }.eachCount().filterValues { it > 1 }.keys
        }
        check(pendingByBaseline.keys.none { it in divergenceByBaseline }) {
            "a baseline is in BOTH logicalParityDivergences and tsgoPendingBaselines: " +
                pendingByBaseline.keys.filter { it in divergenceByBaseline } +
                " — a row is either a decision not to follow tsgo or a row still to implement, never both."
        }
        val usedPending = mutableSetOf<String>()

        /**
         * Emits the divergence preamble when [baseline] is declared, and records the
         * declaration as used (an unused one fails the build below — a rotted ledger
         * is indistinguishable from a hidden regression).
         */
        fun StringBuilder.appendDivergence(baseline: String) {
            pendingByBaseline[baseline]?.let { p ->
                usedPending += baseline
                appendLine("    // TSGO-PENDING (LEGACY.0): a TypeScript 7 row this compiler does not produce")
                appendLine("    // yet. Declared in build.gradle.kts `tsgoPendingBaselines`; ledger in")
                appendLine("    // docs/logical-parity.md.")
                var line = StringBuilder()
                for (word in p.reason.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                    if (line.isNotEmpty() && line.length + 1 + word.length > 84) {
                        appendLine("    // $line"); line = StringBuilder()
                    }
                    if (line.isNotEmpty()) line.append(' ')
                    line.append(word)
                }
                if (line.isNotEmpty()) appendLine("    // $line")
                appendLine("    @kotlin.test.Ignore")
                return
            }
            val d = divergenceByBaseline[baseline] ?: return
            usedDivergences += baseline
            appendLine("    // LOGICAL-PARITY DIVERGENCE (round ${d.round}): switched off deliberately —")
            appendLine("    // our output differs from tsc's in FORM, not in MEANING, and the LOGIC is")
            appendLine("    // pinned by ${d.pinnedBy}. Declared in build.gradle.kts")
            appendLine("    // `logicalParityDivergences`; procedure + ledger in docs/logical-parity.md.")
            // Line comments, not KDoc: a reason quoting TypeScript or JSDoc could carry
            // `/*` or `*/` and silently swallow the rest of the file (see CLAUDE.md).
            var line = StringBuilder()
            for (word in d.reason.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                if (line.isNotEmpty() && line.length + 1 + word.length > 84) {
                    appendLine("    // $line"); line = StringBuilder()
                }
                if (line.isNotEmpty()) line.append(' ')
                line.append(word)
            }
            if (line.isNotEmpty()) appendLine("    // $line")
            appendLine("    @kotlin.test.Ignore")
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
                // M3.0: a conformance case lives in a nested directory, so it cannot use
                // the flat `typeScriptCasesDir`. Emit its path relative to the conformance
                // root instead; compiler cases keep their existing expression verbatim, so
                // their generated bodies are unchanged.
                val isConformance = file.absolutePath.startsWith(conformanceRootDir.absolutePath)
                val casePathExpr = if (isConformance) {
                    "${D}typeScriptConformanceDir/" +
                        file.relativeTo(conformanceRootDir).invariantSeparatorsPath
                } else {
                    "${D}typeScriptCasesDir/$name.ts"
                }
                // The reference baseline's provenance header echoes the case's REAL
                // corpus path, so a conformance case must tell the formatter its
                // category; compiler cases keep the default and emit unchanged.
                val baselineArgs = if (isConformance) {
                    val rel = file.parentFile.relativeTo(conformanceRootDir).invariantSeparatorsPath
                    "\"tests/cases/conformance/$rel\""
                } else ""
                // (LEGACY.0b): tsgo files its baselines by SUITE, where tsc's reference
                // directory is flat. The suite is the corpus the case came from.
                val tsgoSuite = if (isConformance) "conformance" else "compiler"
                // Kotlin 2.x does not allow dots in JVM method names, even in backtick-quoted identifiers.
                // Replace every dot in the base name with an underscore for the function identifier.
                val id = name.replace('.', '_')
                val source = file.readText()
                val directives = parseDirectives(source)

                // tsgo set-B (see the tsgoSkippedTests / usesUnsupportedOption definitions above):
                // (1) whole-file skip for tsgo's hardcoded skippedTests list.
                if (name in tsgoSkippedTests) continue
                // (1b) whole-file skip when a tsconfig.json EMBEDDED in the test sets an option
                //      tsgo's harness refuses, with a `// @directive` of the same name exempting it
                //      (tsgo lets the directive override) — see tsconfigInTestUsesRemovedFeature
                //      above. (LEGACY.1)(g) widened it from `module`/`outFile` to `baseUrl` and
                //      `moduleResolution`; the four buckets asserted below record what moved.
                if (tsconfigInTestUsesRemovedFeature(source, directives)) continue
                // (2) whole-CONFIG skip (errors AND emit) when the bare config's fixed directives
                //     resolve to a tsgo-removed option. Unlike the previous heuristic (which dropped
                //     only the JS-emit subtest and KEPT the error baseline for ES3/ES5/AMD/System/UMD),
                //     tsgo's SkipUnsupportedCompilerOptions skips the whole config, so the error
                //     baseline is dropped too.
                val bareUnsupported = usesUnsupportedOption(directives, emptyMap())

                // .d.ts sections in baselines are stripped by TypeScriptTestSupport.stripDtsSection()
                // so tests with declaration output can be included safely.
                val jsChoice = if (bareUnsupported) null else resolveBaseline(tsgoSuite, "$name.js")
                if (jsChoice != null) {
                    totalBareTests++
                    sb.appendLine()
                    sb.appendTsgoLayer(jsChoice)
                    sb.appendDivergence("$name.js")
                    sb.appendLine("    @Test")
                    sb.appendLine("    fun `${id}_ts compiles to JavaScript matching ${id}_js`() {")
                    sb.appendLine("        val source = Path(\"$casePathExpr\").readText()")
                    sb.appendLine("        TypeScriptCompiler().compile(source, \"$name.ts\").toBaseline($baselineArgs)")
                    sb.appendLine("            .sameAs(Path(\"${jsChoice.pathExpr}\"))")
                    sb.appendLine("    }")
                }

                // Parameterized test variations
                val variations = computeVariations(directives)

                for (config in variations) {
                    val paramName = paramBaselineName(name, config, "js")
                    // Skip this whole config if its resolved options include a tsgo-removed
                    // feature (the varying config value overrides the fixed directive of the
                    // same key; a fixed unsupported directive is caught via `directives`).
                    // (LEGACY.0b): asked BEFORE the baseline lookup, so a config the corpus
                    // does not run cannot land in a bucket count.
                    if (usesUnsupportedOption(directives, config)) continue
                    val paramChoice = resolveBaseline(tsgoSuite, paramName)
                    if (paramChoice != null) {
                        totalParamTests++
                        // Build config suffix for test function name (e.g., target_es5 or alwaysstrict_true_target_es2015)
                        val configId = config.entries.sortedBy { it.key }
                            .joinToString("_") { "${it.key}_${it.value}" }
                            .replace('.', '_')
                        // Build overrides map literal for generated code
                        val overridesStr = config.entries.sortedBy { it.key }
                            .joinToString(", ") { "\"${it.key}\" to \"${it.value}\"" }
                        sb.appendLine()
                        sb.appendTsgoLayer(paramChoice)
                        sb.appendDivergence(paramName)
                        sb.appendLine("    @Test")
                        sb.appendLine("    fun `${id}_ts__${configId}__compiles to JavaScript matching baseline`() {")
                        sb.appendLine("        val source = Path(\"$casePathExpr\").readText()")
                        sb.appendLine("        TypeScriptCompiler().compile(source, \"$name.ts\", mapOf($overridesStr)).toBaseline($baselineArgs)")
                        sb.appendLine("            .sameAs(Path(\"${paramChoice.pathExpr}\"))")
                        sb.appendLine("    }")
                    }
                }

                // .errors.txt baseline test (bare-name). tsgo skips the WHOLE config (the error
                // baseline too, not just emit) for a removed-feature option, so gate on bareUnsupported.
                val errorBaselineDeferred = isConformance && name in conformanceDeferredErrorBaselines
                val errorsChoice = if (bareUnsupported || errorBaselineDeferred) null
                    else resolveBaseline(tsgoSuite, "$name.errors.txt")
                if (errorsChoice != null) {
                    totalErrorTests++
                    sb.appendLine()
                    sb.appendTsgoLayer(errorsChoice)
                    sb.appendDivergence("$name.errors.txt")
                    sb.appendLine("    @Test")
                    sb.appendLine("    fun `${id}_ts has expected errors matching ${id}_errors_txt`() {")
                    sb.appendLine("        val source = Path(\"$casePathExpr\").readText()")
                    sb.appendLine("        TypeScriptCompiler().compile(source, \"$name.ts\")")
                    sb.appendLine("            .errorsMatchBaseline(Path(\"${errorsChoice.pathExpr}\"))")
                    sb.appendLine("    }")
                }

                // .errors.txt parameterized baseline tests
                for (config in variations) {
                    val paramErrorName = paramBaselineName(name, config, "errors.txt")
                    if (usesUnsupportedOption(directives, config) || errorBaselineDeferred) continue
                    val paramErrorChoice = resolveBaseline(tsgoSuite, paramErrorName)
                    if (paramErrorChoice != null) {
                        totalErrorTests++
                        val configId = config.entries.sortedBy { it.key }
                            .joinToString("_") { "${it.key}_${it.value}" }
                            .replace('.', '_')
                        val overridesStr = config.entries.sortedBy { it.key }
                            .joinToString(", ") { "\"${it.key}\" to \"${it.value}\"" }
                        sb.appendLine()
                        sb.appendTsgoLayer(paramErrorChoice)
                        sb.appendDivergence(paramErrorName)
                        sb.appendLine("    @Test")
                        sb.appendLine("    fun `${id}_ts__${configId}__has expected errors matching baseline`() {")
                        sb.appendLine("        val source = Path(\"$casePathExpr\").readText()")
                        sb.appendLine("        TypeScriptCompiler().compile(source, \"$name.ts\", mapOf($overridesStr))")
                        sb.appendLine("            .errorsMatchBaseline(Path(\"${paramErrorChoice.pathExpr}\"))")
                        sb.appendLine("    }")
                    }
                }
            }

            sb.appendLine()
            sb.appendLine("}")

            packageDir.resolve("$className.kt").writeText(sb.toString())
        }

        // (LEGACY.0b) validation — the three-way fallback's bucket sizes, asserted.
        //
        // A wrong fallback is SILENT: it does not fail a test, it removes one (or freezes
        // one on the old root), and a shrunken corpus reads exactly like a green one. The
        // counts are therefore part of the pin, not diagnostics.
        val bucketReport = "adopted=$tsgoAdopted (of which new=$tsgoNew), " +
            "deleted=$tsgoDeleted, kept-tsc=$tscKept"
        check(tsgoDeleted == tsgoExpectedDeleted && tscKept == tsgoExpectedKeptTsc &&
            tsgoNew == tsgoExpectedNew && tsgoAdopted == tsgoExpectedAdopted
        ) {
            "the tsgo baseline buckets moved: $bucketReport, expected adopted=" +
                "$tsgoExpectedAdopted (of which new=$tsgoExpectedNew), deleted=" +
                "$tsgoExpectedDeleted, kept-tsc=$tsgoExpectedKeptTsc.\nEither a pin " +
                "(typeScriptCommit / typeScriptGoCommit) moved, or a filter did. Re-measure " +
                "all four and update them together with the round note saying which cases " +
                "moved — never adjust one constant to make the build green."
        }

        // (LEGACY.0b) guard — `/.src/` is tsgo's own virtual-filesystem ROOT, so a baseline
        // carrying it (`_jsxFileName = "/.src/two.tsx"`) is pinning a HARNESS ARTIFACT that
        // our compiler cannot produce by construction. Such a row is a decision not to
        // follow tsgo, i.e. a `logicalParityDivergences` entry; it may never simply land.
        val srcRootLeaks = adoptedTsgoFiles
            .filterKeys { it.substringAfter('/') !in divergenceByBaseline }
            .filterValues { it.readText().contains("/.src/") }
            .keys
        check(srcRootLeaks.isEmpty()) {
            "${srcRootLeaks.size} adopted tsgo baseline(s) carry tsgo's VFS root `/.src/`:\n" +
                srcRootLeaks.sorted().joinToString("\n") { "  - $it" } +
                "\nThat path is unreachable by construction here. Declare each in " +
                "logicalParityDivergences (with the class pinning the LOGIC it used to pin) " +
                "or exclude it; see docs/tsgo-baselines.md § 3."
        }

        // (LEGACY.0b) — the layer census, and the per-baseline record a family round reads
        // to know whether a red row is an INTENDED TypeScript 7 answer or a tsgo defect.
        // Deliberately NOT a declared task output, for the same reason `docs/logical-parity.md`
        // is not: it is a report about the run, not an input to any compilation.
        val layered = adoptedTsgoFiles.keys.mapNotNull { k -> tsgoDiffLayerOf[k]?.let { k to it } }
        val layerCounts = layered.groupingBy { it.second }.eachCount()
        val layerReport = layout.buildDirectory.file("tsgo-baselines/layers.txt").get().asFile
        layerReport.parentFile.mkdirs()
        layerReport.writeText(
            buildString {
                appendLine("# (LEGACY.0b) adopted tsgo baselines that DIFFER from tsc's, by layer.")
                appendLine("# tsgo pin: $typeScriptGoCommit / TypeScript pin: $typeScriptCommit")
                appendLine("# submoduleAccepted = intended; submoduleTriaged = a tsgo defect; submodule = untriaged.")
                for ((n, l) in layered.sortedWith(compareBy({ it.second }, { it.first }))) {
                    appendLine("$l\t$n")
                }
            }
        )
        logger.lifecycle("tsgo baselines: $bucketReport; differing ${layered.size} (" +
            layerCounts.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" } +
            "); layer report: $layerReport")

        // PARITY.1 validation — the two controls that keep the ledger honest.
        val stale = divergences.filter { it.baseline !in usedDivergences }
        check(stale.isEmpty()) {
            "logicalParityDivergences has ${stale.size} entr(y|ies) matching no generated test:\n" +
                stale.joinToString("\n") { "  - ${it.baseline} (round ${it.round}, ${it.pinnedBy})" } +
                "\nEither the baseline was renamed/removed, or its test is already skipped for " +
                "another reason (tsgo-removed option, deferred error baseline). A ledger entry " +
                "that switches nothing off is indistinguishable from a hidden regression — " +
                "delete it or fix the baseline name. See docs/logical-parity.md."
        }
        if (divergences.isNotEmpty()) {
            val testSources = commonTestDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.readText() }
                .toList()
            val unpinned = divergences.filter { d ->
                val decl = Regex("\\bclass\\s+${Regex.escape(d.pinnedBy)}\\b")
                testSources.none { decl.containsMatchIn(it) }
            }
            check(unpinned.isEmpty()) {
                "logicalParityDivergences names ${unpinned.size} pinnedBy class(es) that do not " +
                    "exist under src/commonTest/kotlin:\n" +
                    unpinned.joinToString("\n") { "  - ${it.pinnedBy} (for ${it.baseline})" } +
                    "\nSwitching a baseline off REQUIRES a test pinning the logic it used to " +
                    "pin — that is the whole policy. See docs/logical-parity.md § 2 step 2."
            }
        }

        // (LEGACY.0a) validation — a pending entry that switches nothing off is stale.
        val stalePending = pending.filter { it.baseline !in usedPending }
        check(stalePending.isEmpty()) {
            "tsgoPendingBaselines has ${stalePending.size} entr(y|ies) matching no generated test:\n" +
                stalePending.joinToString("\n") { "  - ${it.baseline}" } +
                "\nEither the baseline was renamed/removed, or its test is already skipped for " +
                "another reason. Delete the entry or fix the baseline name."
        }

        // PARITY.1 — rewrite the ledger from the declarations so the doc cannot drift.
        val ledger = if (divergences.isEmpty()) {
            "_No baseline is currently switched off under the logical-parity policy._"
        } else {
            buildString {
                appendLine("| baseline | round | logic pinned by | why this is FORM, not MEANING |")
                appendLine("|---|---:|---|---|")
                for (d in divergences.sortedWith(compareBy({ it.round }, { it.baseline }))) {
                    val reason = d.reason.split(Regex("\\s+")).filter { it.isNotEmpty() }
                        .joinToString(" ").replace("|", "\\|")
                    appendLine("| `${d.baseline}` | ${d.round} | `${d.pinnedBy}` | $reason |")
                }
                append("\n**${divergences.size} baseline(s) switched off.**")
            }
        }
        val beginMarker = "<!-- BEGIN GENERATED LEDGER -->"
        val endMarker = "<!-- END GENERATED LEDGER -->"
        check(logicalParityDoc.isFile) {
            "docs/logical-parity.md is missing — it is part of the PARITY.1 mechanism, " +
                "not decoration; restore it before generating."
        }
        val docText = logicalParityDoc.readText()
        val begin = docText.indexOf(beginMarker)
        val end = docText.indexOf(endMarker)
        check(begin >= 0 && end > begin) {
            "docs/logical-parity.md lost its $beginMarker / $endMarker region — the ledger " +
                "is generated into it and cannot be hand-maintained."
        }
        var updated = docText.substring(0, begin + beginMarker.length) +
            "\n" + ledger + "\n" + docText.substring(end)
        // (LEGACY.0a) — the tsgo-pending ledger, rewritten the same way.
        val pendingLedger = if (pending.isEmpty()) {
            "_No baseline is currently pending under the tsgo-pending list._"
        } else {
            buildString {
                appendLine("| baseline | the TypeScript 7 row still to implement |")
                appendLine("|---|---|")
                for (p in pending.sortedBy { it.baseline }) {
                    val reason = p.reason.split(Regex("\\s+")).filter { it.isNotEmpty() }
                        .joinToString(" ").replace("|", "\\|")
                    appendLine("| `${p.baseline}` | $reason |")
                }
                append("\n**${pending.size} baseline(s) pending.**")
            }
        }
        val pendingBegin = "<!-- BEGIN GENERATED TSGO-PENDING -->"
        val pendingEnd = "<!-- END GENERATED TSGO-PENDING -->"
        val pb = updated.indexOf(pendingBegin)
        val pe = updated.indexOf(pendingEnd)
        check(pb >= 0 && pe > pb) {
            "docs/logical-parity.md lost its $pendingBegin / $pendingEnd region — the " +
                "tsgo-pending ledger is generated into it and cannot be hand-maintained."
        }
        updated = updated.substring(0, pb + pendingBegin.length) +
            "\n" + pendingLedger + "\n" + updated.substring(pe)
        if (updated != docText) {
            logicalParityDoc.writeText(updated)
            logger.lifecycle("Rewrote the logical-parity ledger in ${logicalParityDoc.name}.")
        }
        logger.lifecycle("tsgo-pending: ${pending.size}")

        logger.lifecycle("Generated $totalBareTests bare-name JS + $totalParamTests parameterized JS + $totalErrorTests error baseline = ${totalBareTests + totalParamTests + totalErrorTests} test functions across ${groups.size} files in: $packageDir (${divergences.size} logical-parity divergence(s) switched off)")
    }
}

// Make every Kotlin test compilation task depend on the generator so that
// `./gradlew jvmTest` (or any platform test) is all that's needed.
tasks.matching { it.name.startsWith("compile") && "Test" in it.name && "Kotlin" in it.name }
    .configureEach { dependsOn(generateTypeScriptTests) }

// Ensure tests run with the REPO root as working directory so that kotlinx.io's
// Path("typescript-repo") resolves correctly on all platforms. This overrides the
// convention plugin's per-module default, and it is the repo root rather than
// this module because the checkout is shared — see typeScriptRepoDir above.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    workingDir = rootProject.projectDir
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

// https://kotlinlang.org/docs/dokka-migration.html#adjust-configuration-options
dokka {
    pluginsConfiguration.html {
        // Derived from the `xemantic` extension, which exists only on the root.
        footerMessage = rootProject.extra["projectCopyright"] as String
    }
}

