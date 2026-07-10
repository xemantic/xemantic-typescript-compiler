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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Round 465 (M3.4 × B464): a concise-body arrow returning a CAPTURED
 * nullable reference types its return from the flow-narrowed state at the
 * arrow's position — the B464 flow-into-closures continuation proves tsc's
 * `packageJsonInfoCache ??= createPackageJsonInfoCache(…); return {
 * getPackageJsonInfoCache: () => packageJsonInfoCache, … }` non-nullish
 * (moduleNameResolver.ts:1300). Accepted ONLY as a nullish strip: a
 * narrow-DOWN or an un-narrowed capture keeps the declared type.
 */
class ConciseArrowCapturedNarrowingTest {

    private val cacheShape = """
        interface PackageJsonInfoCache {
            getPackageJsonInfo(path: string): string | undefined;
            clear(): void;
        }
        interface CacheHolder {
            getPackageJsonInfoCache(): PackageJsonInfoCache;
            clearAll(): void;
        }
        declare function createPackageJsonInfoCache(dir: string): PackageJsonInfoCache;
    """.trimIndent()

    @Test
    fun `a captured param narrowed by a preceding nullish-assign proves the arrow's return non-nullish`() {
        diagnose(
            cacheShape + """

            function createHolder(dir: string, packageJsonInfoCache: PackageJsonInfoCache | undefined): CacheHolder {
                packageJsonInfoCache ??= createPackageJsonInfoCache(dir);
                return {
                    ...packageJsonInfoCache,
                    getPackageJsonInfoCache: () => packageJsonInfoCache,
                    clearAll,
                };
                function clearAll() {}
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an un-narrowed captured param keeps the nullable return and the error`() {
        diagnose(
            cacheShape + """

            function createHolder(dir: string, packageJsonInfoCache: PackageJsonInfoCache | undefined): CacheHolder {
                return {
                    getPackageJsonInfoCache: () => packageJsonInfoCache,
                    clearAll,
                };
                function clearAll() {}
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a reassignment AFTER the closure withholds the narrowing`() {
        diagnose(
            cacheShape + """

            function createHolder(dir: string, packageJsonInfoCache: PackageJsonInfoCache | undefined): CacheHolder {
                packageJsonInfoCache ??= createPackageJsonInfoCache(dir);
                const holder = {
                    getPackageJsonInfoCache: () => packageJsonInfoCache,
                    clearAll,
                };
                packageJsonInfoCache = undefined;
                return holder;
                function clearAll() {}
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
