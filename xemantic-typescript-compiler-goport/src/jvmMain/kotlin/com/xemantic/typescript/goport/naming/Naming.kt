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

package com.xemantic.typescript.goport.naming

import com.xemantic.typescript.goport.ir.IrPackage
import java.io.File

/** docs/goport-design.md § 5. */
object Naming {

    const val ROOT = "com.xemantic.typescript.tsgo"
    const val RUNTIME = "$ROOT.runtime"
    /** Synthetic declarations the porter invents (anonymous interfaces, …). */
    const val SYNTH = "$ROOT.synth"

    private const val INTERNAL = IrPackage.MODULE + "/internal/"

    /** Kotlin package of a Go package path. */
    fun kotlinPackage(goPath: String): String =
        if (goPath.startsWith(INTERNAL)) {
            ROOT + "." + goPath.removePrefix(INTERNAL).replace('/', '.')
        } else {
            "$ROOT.go." + goPath.split('/').joinToString(".") { it.replace('.', '_').replace('-', '_') }
        }

    fun isPorted(goPath: String, ported: Set<String>): Boolean = goPath in ported

    /**
     * `GetTypeOfSymbol` → `getTypeOfSymbol`, `ID` → `id`, `URLPath` → `urlPath`, `x` → `x`.
     * A leading run of capitals is lowered except its last letter when a lowercase letter follows.
     */
    fun lowerCamel(name: String): String {
        if (name.isEmpty() || !name[0].isUpperCase()) return name
        var run = 0
        while (run < name.length && name[run].isUpperCase()) run++
        if (run == 1 || run == name.length) return name.substring(0, run).lowercase() + name.substring(run)
        val next = name[run]
        return if (next.isLowerCase()) {
            name.substring(0, run - 1).lowercase() + name.substring(run - 1)
        } else {
            name.substring(0, run).lowercase() + name.substring(run)
        }
    }

    /** Kotlin hard keywords (and a few soft words that break in declaration position). */
    val KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
        "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof",
        "val", "var", "when", "while",
    )

    fun escape(name: String): String = if (name in KEYWORDS) "`$name`" else name
}

/**
 * The rename table (`-goport/renames.txt`): `<go qualified name> <kotlin name>` per line, `#`
 * comments. Keyed by the GO name so a rename never makes an override stale.
 */
class RenameTable(private val map: Map<String, String>) {

    operator fun get(qname: String): String? = map[qname]

    val used = HashSet<String>()

    fun lookup(qname: String): String? = map[qname]?.also { used += qname }

    fun entries(): Map<String, String> = map

    companion object {
        fun load(file: File): RenameTable {
            if (!file.exists()) return RenameTable(emptyMap())
            val m = LinkedHashMap<String, String>()
            file.readLines().forEachIndexed { i, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val parts = line.split(Regex("\\s+"))
                require(parts.size == 2) { "${file.name}:${i + 1}: expected '<go qname> <kotlin name>'" }
                m[parts[0]] = parts[1]
            }
            return RenameTable(m)
        }
    }
}
