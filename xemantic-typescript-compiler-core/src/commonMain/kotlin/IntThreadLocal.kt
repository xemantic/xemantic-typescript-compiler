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

package com.xemantic.typescript.compiler

/**
 * INV.6(6c0): a per-thread Int cell for the Symbol/Type id counters.
 *
 * The id sequences were process-global mutable companion vars — correct
 * single-threaded, but under INV.6 share-nothing parallel checking concurrent
 * workers would race the `nextId++` (lost updates can even hand one worker the
 * SAME id twice → id-keyed map corruption) and make id ORDER
 * scheduler-dependent (run-to-run nondeterministic union displays etc. — the
 * hard determinism requirement of docs/parallel-caching.md forbids this).
 *
 * Thread-local sequences make every thread's allocation order deterministic in
 * isolation. Each fresh thread starts at [initial] — including the per-compile
 * deep-stack thread, so every compile now allocates ids from a fixed base
 * (more hermetic than the old global carry-over; behavior depends only on
 * in-compile ORDER, which is unchanged). Worker threads of the parallel driver
 * re-base their sequences ABOVE the shared singleton-intrinsic id range before
 * binding (ids never cross the worker boundary, but singletons are shared, so
 * a worker id colliding with a singleton id inside the worker's id-keyed maps
 * would corrupt them).
 *
 * JS/WASM are single-threaded — a plain var actual is correct there.
 */
internal expect class IntThreadLocal(initial: Int) {
    fun get(): Int
    fun set(value: Int)
}
