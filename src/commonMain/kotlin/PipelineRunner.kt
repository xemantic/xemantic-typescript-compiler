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

import kotlinx.coroutines.CoroutineDispatcher

/**
 * INV.1: bridges the synchronous compiler entry points to the coroutine-based
 * front-end pipeline (docs/ARCHITECTURE-RETHINK.md § 4 — streams at the I/O
 * boundaries, demand-driven memoization in the core).
 *
 * `runBlocking` is not available in common code (Kotlin/JS has no blocking),
 * so the synchronous drivers ([ProjectCompiler.build], and later watch/emit
 * pipelines) enter coroutine land through this expect/actual seam. The JVM
 * actual is a plain `runBlocking`; a future JS/WASM target would need an
 * async-capable driver instead (the pipeline stays sequential there anyway —
 * single-threaded dispatchers).
 */
internal expect fun <T> runCompilerPipeline(block: suspend () -> T): T

/**
 * INV.1(b): the dispatcher for front-end file IO (read + UTF-8→UTF-16 decode).
 * `Dispatchers.IO` exists only on JVM/Native — common code reaches it through
 * this expect/actual; CPU-bound pipeline work (the specifier-extraction parse)
 * uses the common `Dispatchers.Default` directly.
 */
internal expect val pipelineIoDispatcher: CoroutineDispatcher
