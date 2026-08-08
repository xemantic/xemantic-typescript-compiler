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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * A fixture whose defaults are deliberately NOT the type's zero values.
 *
 * That is the whole point: the mistake [ModeLedger] exists to make impossible is
 * "restore by assigning a default back", and a fixture defaulting to
 * `false`/`0` cannot tell a correct restore from that mistake.
 */
private object LedgerFixture {
    var flag: Boolean = true
    var level: Int = 7
    var name: String = "production"
}

/**
 * (SERVE.1): the save-and-restore contract of [ModeLedger].
 *
 * The mechanism is what the CLI's argument loop uses so that a `--serve` daemon
 * cannot carry one request's debug modes into the next. These pins are about the
 * ledger itself; `CliModeRestoreTest` is the one that checks the argument loop
 * actually uses it for every flag, and `CompileServerModeLeakTest` the one that
 * checks it end to end through a real request.
 */
class ModeLedgerTest {

    private fun reinstateDefaults() {
        LedgerFixture.flag = true
        LedgerFixture.level = 7
        LedgerFixture.name = "production"
    }

    @Test
    fun `a restored field holds the value it had - not the type's default`() {
        reinstateDefaults()
        LedgerFixture.flag = false
        LedgerFixture.level = 3
        val ledger = ModeLedger()
        ledger.set(LedgerFixture::flag, true)
        ledger.set(LedgerFixture::level, 99)
        assert(LedgerFixture.flag)
        assert(LedgerFixture.level == 99)
        ledger.restore()
        // Not `true`/`7` — the values the caller had installed. A restore-to-default
        // implementation passes every other assertion in this file and fails here.
        assert(!LedgerFixture.flag)
        assert(LedgerFixture.level == 3)
        reinstateDefaults()
    }

    @Test
    fun `repeated writes to one field restore the value before the FIRST write`() {
        reinstateDefaults()
        val ledger = ModeLedger()
        // The shape of `--verifyLoopRetryAll` after `--verifyLoopRetry`- one arm
        // sets a field a previous arm already set. Forward-order undo would land
        // on the intermediate value.
        ledger.set(LedgerFixture::level, 1)
        ledger.set(LedgerFixture::level, 2)
        ledger.set(LedgerFixture::level, 3)
        assert(LedgerFixture.level == 3)
        ledger.restore()
        assert(LedgerFixture.level == 7)
    }

    @Test
    fun `restore empties the ledger so a second restore cannot re-apply an old value`() {
        reinstateDefaults()
        val ledger = ModeLedger()
        ledger.set(LedgerFixture::name, "probe")
        assert(ledger.pending == 1)
        ledger.restore()
        assert(ledger.pending == 0)
        assert(LedgerFixture.name == "production")
        LedgerFixture.name = "later request"
        ledger.restore()
        // A ledger that kept its undo list would stomp the next request's state.
        assert(LedgerFixture.name == "later request")
        reinstateDefaults()
    }

    @Test
    fun `an empty ledger restores nothing`() {
        reinstateDefaults()
        LedgerFixture.level = 42
        ModeLedger().restore()
        assert(LedgerFixture.level == 42)
        reinstateDefaults()
    }
}
