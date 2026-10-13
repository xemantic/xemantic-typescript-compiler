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

package com.xemantic.typescript.tsgo.runtime

import com.xemantic.typescript.tsgo.fswatch.XtscNotifier

/**
 * Opens an inotify-shaped notifier for fswatch's port backend (`overlay/fswatch`, docs/goport-cli.md § 2c): the
 * JVM's over `java.nio.file.WatchService`, Kotlin/Native's over inotify(7). Paths cross as Go byte strings.
 */
internal expect fun platformNewNotifier(): Tuple2<XtscNotifier?, GoError?>

/** `fswatch.XtscInstall` over [platformNewNotifier]: `fswatch.Default()` then watches the host file system. */
internal fun installFsNotify() = com.xemantic.typescript.tsgo.fswatch.xtscInstall { platformNewNotifier() }
