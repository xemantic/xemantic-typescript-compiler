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

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M1.6(b) (round 388): contextual typing of object-literal fn-valued members —
 * the TS7006 factory-idiom kill. An arrow/fn-expr/method VALUE of an
 * object-literal member is contextually typed by the literal's contextual
 * type's matching member fn type; a plain callable contextual slot suppresses
 * TS7006 up to its arity (params beyond it keep firing — B224's rule).
 * The contextual type reaches the literal from (a) a var-decl annotation
 * (`const checker: TypeChecker = {...}` — tsc's actual factory shape) and
 * (b) the enclosing function's RETURN annotation (threaded through the
 * implicit-any walker's statement dispatch to `return {...}`).
 */
class ContextualFnMemberParamsTest {

    private fun compile(source: String) =
        TypeScriptCompiler().compile("// @strict: true\n" + source, "t.ts")

    private val iface = """
        interface Sym { id: number; }
        interface TC {
            isUndef(symbol: Sym): boolean;
            count(a: number, b: string): number;
            spread(...args: number[]): void;
        }
    """.trimIndent()

    private fun assertNo7006(source: String, what: String) {
        val r = compile(source)
        assertTrue(
            r.diagnostics.none { it.code == 7006 },
            "$what must not draw TS7006: " +
                r.diagnostics.joinToString { "TS${it.code} ${it.message}" },
        )
    }

    @Test fun varDeclAnnotationFactoryArrowMembers() {
        assertNo7006(
            "$iface\nconst checker: TC = {\n" +
                "    isUndef: symbol => symbol.id === 0,\n" +
                "    count: (a, b) => a,\n" +
                "    spread: (x, y) => {},\n" +
                "};\n",
            "arrow members of an annotation-contextualized literal",
        )
    }

    @Test fun returnAnnotationFactoryArrowMembers() {
        assertNo7006(
            "$iface\nfunction create(): TC {\n" +
                "    return {\n" +
                "        isUndef: symbol => symbol.id === 0,\n" +
                "        count: (a, b) => a,\n" +
                "        spread: (x, y) => {},\n" +
                "    };\n" +
                "}\n",
            "arrow members of a return-annotation-contextualized literal",
        )
    }

    @Test fun methodMembersAreContextuallyTyped() {
        assertNo7006(
            "$iface\nconst checker: TC = {\n" +
                "    isUndef(symbol) { return symbol.id === 0; },\n" +
                "    count(a, b) { return a; },\n" +
                "    spread(x, y) {},\n" +
                "};\n",
            "object-literal METHOD members of a contextualized literal",
        )
    }

    @Test fun methodBodyReturnAnnotationAlsoThreads() {
        // The return-ctx threading must reset per function boundary: the inner
        // class method's own annotation drives ITS returns.
        assertNo7006(
            "$iface\nclass Factory {\n" +
                "    make(): TC {\n" +
                "        return {\n" +
                "            isUndef: symbol => symbol.id === 0,\n" +
                "            count: (a, b) => a,\n" +
                "            spread: (x, y) => {},\n" +
                "        };\n" +
                "    }\n" +
                "}\n",
            "class-method return-annotation factory",
        )
    }

    @Test fun directArrowReturnAgainstFnTypeAnnotation() {
        assertNo7006(
            "function f(): (a: number) => void {\n    return a => {};\n}\n",
            "a directly returned arrow against a fn-type return annotation",
        )
    }

    @Test fun excessParamBeyondContextualArityStillFires() {
        val r = compile(
            "interface One { f(a: number): void; }\n" +
                "const o: One = { f: (a, b) => {} };\n",
        )
        assertTrue(
            r.diagnostics.any { it.code == 7006 && it.message.contains("'b'") } &&
                r.diagnostics.none { it.code == 7006 && it.message.contains("'a'") },
            "param BEYOND the contextual arity fires TS7006, the covered one does not: " +
                r.diagnostics.joinToString { "TS${it.code} ${it.message}" },
        )
    }

    @Test fun uncontextualizedLiteralStillFires() {
        val r = compile("const o = { f: (a) => a };\n")
        assertTrue(
            r.diagnostics.any { it.code == 7006 },
            "an annotation-less literal's arrow params stay implicitly any: " +
                r.diagnostics.joinToString { "TS${it.code} ${it.message}" },
        )
    }

    @Test fun unannotatedReturnStillFires() {
        val r = compile("function create() {\n    return { f: (a) => a };\n}\n")
        assertTrue(
            r.diagnostics.any { it.code == 7006 },
            "a return without a return-type annotation provides no contextual type: " +
                r.diagnostics.joinToString { "TS${it.code} ${it.message}" },
        )
    }

    @Test fun nestedFunctionResetsReturnContext() {
        // The OUTER fn's TC annotation must NOT leak into the INNER
        // annotation-less function's return literal.
        val r = compile(
            "$iface\nfunction outer(): TC {\n" +
                "    function inner() {\n" +
                "        return { isUndef: (s) => true };\n" +
                "    }\n" +
                "    return {\n" +
                "        isUndef: symbol => symbol.id === 0,\n" +
                "        count: (a, b) => a,\n" +
                "        spread: (x, y) => {},\n" +
                "    };\n" +
                "}\n",
        )
        assertTrue(
            r.diagnostics.any { it.code == 7006 && it.message.contains("'s'") } &&
                r.diagnostics.none { it.code == 7006 && !it.message.contains("'s'") },
            "inner annotation-less fn keeps TS7006; outer factory members do not: " +
                r.diagnostics.joinToString { "TS${it.code} ${it.message}" },
        )
    }

    @Test fun restParamContextCoversAllPositionalParams() {
        assertNo7006(
            "interface R { go(...args: number[]): void; }\n" +
                "const r: R = { go: (a, b, c) => {} };\n",
            "a rest-param contextual signature covers every positional param",
        )
    }
}
