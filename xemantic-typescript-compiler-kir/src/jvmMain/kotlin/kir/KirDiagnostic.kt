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

package com.xemantic.typescript.compiler.kir

import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.SourceFile

/**
 * Why the backend refused to lower something.
 *
 * The spike's value depends on it never PRETENDING (`docs/kir-lowering.md` §8):
 * an unsupported construct, a missed oracle answer or an unmapped type produces
 * one of these and the emission aborts. Nothing degrades to `Any?` and carries
 * on, because a backend that quietly widens reports success while compiling
 * nonsense — the one outcome from which nothing can be learned.
 */
public class KirDiagnostic(
    /** What went wrong, in the imperative present: "cannot lower …". */
    public val message: String,
    /** The TypeScript file the construct came from. */
    public val fileName: String,
    /** 1-based, as an editor counts. */
    public val line: Int,
    /** 1-based, as an editor counts. */
    public val column: Int,
) {

    override fun toString(): String = "$fileName:$line:$column $message"

}

/**
 * Thrown by the lowering the moment it cannot proceed.
 *
 * An exception rather than an accumulated list because there is nothing useful
 * to do after the first refusal: the IR under construction is incomplete, and
 * continuing would need a poison value — which is exactly the silent widening
 * §8 forbids. The compilation boundary catches it and turns it back into a
 * [KirDiagnostic] on a failed result.
 */
public class KirLoweringException(
    public val diagnostic: KirDiagnostic
) : RuntimeException(diagnostic.toString())

/**
 * Turns a raw source offset into an editor's `(line, column)`.
 *
 * `-core`'s `computeLineStarts` is `internal`, so this is a deliberate second
 * implementation — the same exemption `-project`'s `LineMap` has. It carries
 * `-core`'s one load-bearing rule verbatim: `\n`, `\r\n` and a LONE `\r` each
 * end exactly one line, a lone `\r` being the case every naive converter gets
 * wrong in one of the two possible directions.
 */
internal fun lineAndColumnAt(text: String, offset: Int): Pair<Int, Int> {
    var line = 1
    var lineStart = 0
    var index = 0
    val limit = minOf(offset, text.length)
    while (index < limit) {
        val breakWidth = when {
            text[index] == '\n' -> 1
            text[index] != '\r' -> 0
            index + 1 < text.length && text[index + 1] == '\n' -> 2
            else -> 1
        }
        if (breakWidth == 0) {
            index++
        } else {
            index += breakWidth
            line++
            lineStart = index
        }
    }
    return line to (limit - lineStart + 1)
}

/**
 * Refuses [node], naming it in a way its author can find.
 *
 * [sourceFile] is needed because a [Node] carries a raw offset and nothing
 * else. Note the offset is `Node.pos`, which is already past leading trivia (it
 * is tsc's `getStart()`, not tsc's `pos`), so it points at the construct.
 */
public fun refuse(
    sourceFile: SourceFile,
    node: Node,
    message: String
): Nothing {
    val (line, column) = lineAndColumnAt(sourceFile.text, node.pos)
    throw KirLoweringException(
        KirDiagnostic(message, sourceFile.fileName, line, column)
    )
}
