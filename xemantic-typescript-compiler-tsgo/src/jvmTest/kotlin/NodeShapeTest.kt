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

package com.xemantic.typescript.tsgo

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.tsgo.ast.Identifier
import com.xemantic.typescript.tsgo.ast.NodeFactoryHooks
import com.xemantic.typescript.tsgo.ast.asIdentifier
import com.xemantic.typescript.tsgo.ast.asNode
import com.xemantic.typescript.tsgo.ast.newIdentifier
import com.xemantic.typescript.tsgo.ast.newNodeFactory
import com.xemantic.typescript.tsgo.ast.text
import com.xemantic.typescript.tsgo.runtime.GoString
import kotlin.test.Test

/**
 * One object per node (docs/goport-lowering.md § 3): Go allocates a node as ONE struct — the data struct embeds the
 * `Node` and `newNode` points `n.data` at it — and the port mirrors that: the data object IS the node.
 */
class NodeShapeTest {

    @Test
    fun `a node, its data and the data's AsNode are one object`() {
        val factory = newNodeFactory(NodeFactoryHooks())
        val node = factory.newIdentifier(GoString.fromUtf16("x"))!!
        val data = node.asIdentifier()!!
        val same = node === data && node.data === node && data.asNode() === node
        assert(same)
        assert(GoString.toUtf16(node.text()) == "x")
        // A second node is a second object.
        val other = factory.newIdentifier(GoString.fromUtf16("y"))!!
        assert(other !== node)
    }
}
