/*
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.sameAs
import kotlin.test.Test

/**
 * A demonstration test that intentionally fails to show how test output looks.
 *
 * This test class exists solely to answer the question in issue #8:
 * "Show me the output of an example failing test".
 * It should be deleted once the actual TypeScript compiler is implemented.
 */
class FailingExampleTest {

    /**
     * Simulates a future test failure where the compiler returns wrong JavaScript output.
     * Uses [sameAs] directly to show what a unified-diff failure looks like.
     */
    @Test
    fun `sameAs diff - actual output does not match expected baseline`() {
        val actual = "var ar;\n"
        val expected = "var ar = [];\n"
        actual sameAs expected
    }

    /**
     * Simulates the current state: the compiler is not yet implemented,
     * so every generated TypeScript test fails with [NotImplementedError].
     */
    @Test
    fun `2dArrays_ts compiles to JavaScript matching 2dArrays_js`() {
        val actual = TypeScriptCompiler().compile(
            source = "var ar: string[][];",
            fileName = "2dArrays.ts"
        ).javascript
        actual sameAs "var ar;\n"
    }

}
