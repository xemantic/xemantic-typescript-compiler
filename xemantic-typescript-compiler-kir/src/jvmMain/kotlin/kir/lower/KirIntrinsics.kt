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

@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.xemantic.typescript.compiler.kir.lower

import com.xemantic.typescript.compiler.kir.emit.IrProgramBuilder
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType

/**
 * Every function outside the generated program that the lowering may call.
 *
 * A TABLE, deliberately, rather than a special case per call site. Two
 * populations live here and they are different in kind:
 *
 * - the **runtime**, which exists because a JavaScript behaviour is not
 *   expressible as IR the Kotlin backend would already generate — number
 *   formatting, `+` overloading, truthiness, strict equality;
 * - the **library intrinsics**, which are TypeScript declarations this backend
 *   never lowers because they live in a lib `.d.ts`. `console.log` is the only
 *   one in the spike subset, and it is named by the checker's own rendering of
 *   the receiver's type plus the member name — not by matching the syntax
 *   `console.log`, which a local variable called `console` would fool.
 *
 * Resolution is lazy: a program that never concatenates should not fail to
 * compile because `String.plus` could not be found.
 */
internal class KirIntrinsics(
    private val builder: IrProgramBuilder,
    private val irFile: IrFile,
) {

    private val runtimePackage = "com.xemantic.typescript.compiler.kir.runtime"

    val consoleLog: IrSimpleFunctionSymbol by lazy { runtime("consoleLog") }
    val jsToString: IrSimpleFunctionSymbol by lazy { runtime("jsToString") }
    val jsToNumber: IrSimpleFunctionSymbol by lazy { runtime("jsToNumber") }
    val jsAdd: IrSimpleFunctionSymbol by lazy { runtime("jsAdd") }
    val jsTruthy: IrSimpleFunctionSymbol by lazy { runtime("jsTruthy") }
    val jsStrictEquals: IrSimpleFunctionSymbol by lazy { runtime("jsStrictEquals") }

    /** `String.plus(Any?)` — the only `plus` a `String` receiver has. */
    val stringPlus: IrSimpleFunctionSymbol by lazy {
        builder.referenceMemberFunction(irFile, "kotlin.String", "plus")
    }

    /**
     * The `Double` arithmetic operators, each selected by its OPERAND type.
     *
     * `Double.plus` has an overload per numeric type and picking the wrong one
     * links against a method that does not exist at the call site's argument
     * type — a failure that surfaces at class-load time, far from here. Hence
     * the explicit predicate rather than `single()`.
     */
    fun doubleOperator(name: String, doubleType: IrType): IrSimpleFunctionSymbol =
        builder.referenceMemberFunction(irFile, "kotlin.Double", name) {
            it.owner.parameters.size == 2 && it.owner.parameters.last().type == doubleType
        }

    /**
     * The library member [memberName] on a receiver the checker renders as
     * [receiverTypeText], or null when it is not one this backend knows.
     */
    fun libraryMember(receiverTypeText: String?, memberName: String?): IrSimpleFunctionSymbol? =
        when ("$receiverTypeText.$memberName") {
            "Console.log" -> consoleLog
            else -> null
        }

    private fun runtime(name: String): IrSimpleFunctionSymbol =
        builder.referenceFunction(irFile, runtimePackage, name)

}
