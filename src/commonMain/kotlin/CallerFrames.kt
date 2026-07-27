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
 * (TYPE.1) round 737: the caller-attribution primitive.
 *
 * Returns `method:line|method:line` for the two frames immediately above the
 * caller, skipping any frame whose method name is in [skipMethods] (the
 * instrumented entry point and its own accessors). Used ONLY under
 * `PassTiming.callerAttr`, i.e. never in a production compile — an
 * attribution run is an offline measurement, not a mode the compiler ships in.
 *
 * A platform that cannot walk its own stack returns `""`, which the caller
 * interns as a single "(unattributed)" site rather than failing.
 *
 * **The reported line number WRAPS modulo 65536** for `Checker.kt` (the JVM
 * `LineNumberTable` is a `u2` and the file exceeds 90k lines) — the same trap
 * as a stack trace. Add 65536 before trusting a line, and prefer the METHOD
 * name, which does not wrap.
 */
internal expect fun captureCallerFrames(skipMethods: Set<String>): String
