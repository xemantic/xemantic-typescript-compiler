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

package com.xemantic.typescript.tsgo.go.testing

import com.xemantic.typescript.tsgo.go.fmt.sprintf

/** `testing.Testing()`: the port's code is never a `go test` binary — false. */
fun testing(): Boolean = false

/**
 * `*testing.T`, as the ported test harness (`testrunner`, `harnessutil`, (TSGO.2)) uses it: a test
 * that skips or fails ends by throwing [TestSkipped] / [TestFailed] — Go's `runtime.Goexit`, which
 * ends the test's goroutine — so the caller (the Kotlin driver) catches it per test. `Errorf` records
 * a failure and continues, as in Go. The methods live on the embedded [Common] (Go's `testing.common`):
 * the lowering reaches promoted methods through the embedded field.
 */
class T(name: String = "") : com.xemantic.typescript.tsgo.repo.SkippableTest {

    /** Go's embedded `testing.common`. */
    val common: Common = Common(name)

    fun name(): String = common.name()

    override fun helper() = common.helper()

    override fun skipf(format: String, vararg args: Any?): Nothing = common.skipf(format, *args)

    /**
     * `t.Run(name, f)`: runs [f] as a subtest (Go: in its own goroutine, waited for) and reports
     * whether it passed. A skip or fatal ([TestSkipped]/[TestFailed], Go's `runtime.Goexit`) ends
     * the SUBTEST only; a failed subtest also fails its parent, as in Go. Anything else is a panic,
     * which in Go aborts the whole test binary: it propagates.
     */
    fun run(name: String, f: ((T?) -> Unit)?): Boolean {
        val sub = T(common.name() + "/" + name)
        try {
            f!!(sub)
        } catch (_: TestSkipped) {
        } catch (e: TestFailed) {
            sub.common.errors += e.message ?: ""
        }
        if (sub.common.failed()) common.errors += "subtest ${sub.name()} failed"
        return !sub.common.failed()
    }
}

/** Go's `testing.common`: the methods `T` and `B` share. */
class Common(private val name: String) {

    /** The `Errorf`/`Error` messages, in order. */
    val errors: MutableList<String> = ArrayList()

    fun name(): String = name

    fun helper() {}

    fun skipf(format: String, vararg args: Any?): Nothing = throw TestSkipped(sprintf(format, *args))

    fun skip(vararg args: Any?): Nothing = throw TestSkipped(args.joinToString(" "))

    fun fatalf(format: String, vararg args: Any?): Nothing = throw TestFailed(sprintf(format, *args))

    fun fatal(vararg args: Any?): Nothing = throw TestFailed(args.joinToString(" ") { it.toString() })

    fun errorf(format: String, vararg args: Any?) {
        errors += sprintf(format, *args)
    }

    fun failed(): Boolean = errors.isNotEmpty()
}

/** `t.Skipf`: the test ended as SKIPPED with [message]. */
class TestSkipped(message: String) : RuntimeException(message)

/** `t.Fatalf`: the test ended as FAILED with [message]. */
class TestFailed(message: String) : RuntimeException(message)
