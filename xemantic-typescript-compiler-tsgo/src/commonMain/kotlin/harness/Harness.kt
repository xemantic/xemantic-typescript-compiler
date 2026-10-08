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

package com.xemantic.typescript.tsgo.harness

import com.xemantic.typescript.tsgo.collections.delete
import com.xemantic.typescript.tsgo.collections.range
import com.xemantic.typescript.tsgo.go.testing.T
import com.xemantic.typescript.tsgo.testrunner.XtscPrepared
import com.xemantic.typescript.tsgo.testutil.harnessutil.NamedTestConfiguration
import com.xemantic.typescript.tsgo.testutil.harnessutil.SourceFileCacheKey
import com.xemantic.typescript.tsgo.testutil.harnessutil.XtscCheckOnly
import com.xemantic.typescript.tsgo.testutil.harnessutil.sourceFileCache

// (TSGO.2) tsgo's compiler test harness, PORTED (docs/goport-diag-oracle.md § 4, the preferred route):
// a conformance-format case (`// @option: value` directives, `// @Filename:` units) goes through the
// runner's own `makeUnitsFromTest` / configuration enumeration / `newCompilerTest` prepare block and the
// harness's `CompileFiles` option derivation and pre-emit program — the generated `testrunner` and
// `harnessutil` packages — exactly as the oracle's overlay copies run them in Go. This file is only the
// entry points; every decision is the ported code's. A harness skip/fatal surfaces as
// [com.xemantic.typescript.tsgo.go.testing.TestSkipped] / [com.xemantic.typescript.tsgo.go.testing.TestFailed].

/** The `/.lib` test libraries' TypeScript checkout (`repo.TypeScriptSubmodulePath()`): set by the host. */
var typeScriptSubmodule: String
    get() = com.xemantic.typescript.tsgo.repo.typeScriptSubmodule
    set(v) { com.xemantic.typescript.tsgo.repo.typeScriptSubmodule = v }

/** The case text as tsgo's runner reads it from disk (`osvfs`: a UTF-8 BOM stripped, UTF-16 decoded). */
fun decodeCaseText(raw: String): String = com.xemantic.typescript.tsgo.vfs.internal.decodeBytes(raw).first

/** Whether tsgo's runner skips the case file [basename] outright (`compiler_runner.go` `skippedTests`). */
fun isSkippedCase(basename: String): Boolean = com.xemantic.typescript.tsgo.testrunner.xtscIsSkippedTest(basename)

/** The configurations `runTest` enumerates for [content]: `[null]` when the case varies by nothing. */
fun caseConfigurations(content: String, t: T = T()): List<NamedTestConfiguration?> {
    val named = com.xemantic.typescript.tsgo.testrunner.xtscCaseConfigurations(t, content)
    return if (named.len == 0) listOf(null) else List(named.len) { named[it] }
}

/** The configuration's directory/variation name, as the oracle names it: `_` without variations. */
fun variationName(named: NamedTestConfiguration?): String = named?.name?.ifEmpty { null } ?: "_"

/** One configuration compiled check-only (pre-emit), with what the runner prepared. */
class HarnessRun(val prepared: XtscPrepared, val check: XtscCheckOnly)

/**
 * Prepares and compiles one configuration of a case: `newCompilerTest`'s prepare block
 * (`XtscPrepare`), then the pre-emit half of `CompileFiles` (`XtscCompileCheckOnly`) — the program, its
 * phase-tagged, sorted and deduplicated diagnostics. [fileName] is the case's path (its base name names
 * a single-unit case's file).
 */
fun runConfiguration(fileName: String, content: String, named: NamedTestConfiguration?, t: T = T(fileName)): HarnessRun {
    val prep = com.xemantic.typescript.tsgo.testrunner.xtscPrepare(fileName, content, named)!!
    val check = com.xemantic.typescript.tsgo.testutil.harnessutil.xtscCompileCheckOnly(
        t, prep.toBeCompiled, prep.otherFiles, prep.harnessConfig, prep.tsConfig, prep.currentDirectory, prep.symlinks,
    )!!
    return HarnessRun(prep, check)
}

/**
 * Drops every parsed file but the bundled libs from the harness's process-wide `sourceFileCache`. Go's
 * harness keeps every parse for the whole run; sharing a parse across configurations is what the
 * cache is for, and re-parsing yields the same tree, so this only bounds a long run's heap.
 */
fun purgeSourceFileCache() {
    val drop = ArrayList<SourceFileCacheKey>()
    sourceFileCache.range { k, _ ->
        if (!k.opts.fileName.startsWith("bundled:")) drop += k
        true
    }
    for (k in drop) sourceFileCache.delete(k)
}
