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

package com.xemantic.typescript.compiler.project

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (INC.56) THE HOST'S FILESYSTEM PROMISE — `Project.trustFilesystem`.
 *
 * Every build re-reads and re-decodes every non-overlaid program file, although the
 * PARSE is already fully content-cached across builds: the bytes are read only to
 * compute the content key. That is O(PROJECT) work on every keystroke, and on a
 * 2,401-file application-shaped project it is the largest single row of the
 * incremental floor (crawl WALL 32-36 ms of ~92 ms, of which the sequential specifier
 * resolution is 10-12).
 *
 * ## What these pins are for
 *
 * The lever is a COUNT — reads that reach the backing store — because a build is not
 * observable from its result and a timed assertion over a compile is a coin flip. But
 * a count pin alone is satisfied by a build that answered NOTHING ((INC.40)'s a3
 * ablation stayed green against a language service reporting no errors at all), so
 * every count here is paired with a VALUE pin asserting the answer equals the
 * untrusting build's.
 *
 * ## And the other half: the pins that assert the promise's LIMIT
 *
 * The queue entry demands them by name — "the pin asserts the documented limit, not
 * that it magically works". A content change behind the promise IS missed, and
 * [Project.reloadFile] is what a host uses when it cannot describe a change. What is
 * NOT missed is the file SET: additions and deletions are discovered from the backing
 * store on every build, because nothing caches [Vfs.exists] or the directory listing.
 * Those two are the non-obvious soundness claims of the whole mechanism, so they are
 * pinned rather than argued.
 *
 * The DEFAULT is pinned too, per (INC.16): a mode that every pin installs for itself
 * is a default pinned by nothing, and an ablation restoring the old behaviour would
 * read 0 RED.
 */
class ProjectTrustedFilesystemTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val aFile = "/proj/src/a.ts"
    private val bFile = "/proj/src/b.ts"
    private val cFile = "/proj/src/c.ts"

    private val aText = "import { b } from './b';\nexport const a: number = b;\n"
    private val bText = "export const b: number = 1;\n"

    /** Carries a real error, so a build that answered nothing is distinguishable. */
    private val cText = "export const c: string = 1;\n"

    private fun store() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to config,
            aFile to aText,
            bFile to bText,
            cFile to cText,
        ),
    )

    private fun codesIn(project: Project) = project.diagnostics().map { it.code }.sorted()

    /**
     * The whole program's diagnostics after a WHOLE-PROGRAM build, which is what every
     * disk-change pin below must use.
     *
     * `diagnostics()` alone would not discriminate: (INC.46) narrows the check to the
     * files the host REPORTED as dirty and reuses the previous rows for the rest, so a
     * change nobody reported is invisible through it whether the filesystem is trusted
     * or not. That is the (INC.46) contract and not this mechanism, and a pin that
     * conflated the two would read as a defect in whichever arm ran first. Reading
     * [Project.files] forces the whole-program build, so what is left between the arms
     * is exactly the read.
     */
    private fun rebuiltCodes(project: Project): List<Int> {
        project.files
        return codesIn(project)
    }

    // ---- the default ---------------------------------------------------------

    @Test
    fun `the filesystem promise is OFF unless a host makes it`() {
        val project = Project.open("/proj", store())
        assert(!project.trustFilesystem)
    }

    /**
     * The negative control for every count below: WITHOUT the promise, an unchanged
     * file is read from the backing store again on the next build.
     */
    @Test
    fun `negative control - an untrusting project re-reads an unchanged file`() {
        val counting = CountingVfs(store())
        val project = Project.open("/proj", counting)
        project.diagnostics()
        val afterFirst = counting.readsOf(bFile)
        project.updateFile(aFile, "$aText// touched\n")
        project.diagnostics()
        assert(afterFirst > 0)
        assert(counting.readsOf(bFile) > afterFirst)
    }

    // ---- the lever -----------------------------------------------------------

    @Test
    fun `a trusted project does not re-read a file it has already read`() {
        val counting = CountingVfs(store())
        val project = Project.open("/proj", counting)
        project.trustFilesystem = true
        project.diagnostics()
        val bAfterFirst = counting.readsOf(bFile)
        val cAfterFirst = counting.readsOf(cFile)
        assert(bAfterFirst > 0)
        project.updateFile(aFile, "$aText// touched\n")
        project.diagnostics()
        assert(counting.readsOf(bFile) == bAfterFirst)
        assert(counting.readsOf(cFile) == cAfterFirst)
    }

    /**
     * THE VALUE HALF. A count pin cannot tell a served answer from an empty one, so
     * the trusted project must report exactly what the untrusting one reports — and
     * the untrusting one must report something.
     */
    @Test
    fun `a trusted project reports what an untrusting one reports`() {
        val untrusting = Project.open("/proj", store())
        val trusting = Project.open("/proj", store()).also { it.trustFilesystem = true }
        val expected = codesIn(untrusting)
        assert(expected.isNotEmpty())
        assert(codesIn(trusting) == expected)
        // And again after an edit, which is the state the promise is actually used in.
        untrusting.updateFile(cFile, "export const c: string = 'ok';\n")
        trusting.updateFile(cFile, "export const c: string = 'ok';\n")
        val after = codesIn(untrusting)
        assert(after.isEmpty())
        assert(codesIn(trusting) == after)
    }

    // ---- the promise's documented limit --------------------------------------

    /**
     * The limit, asserted rather than hoped: a content change nobody reported is
     * MISSED. This is the whole cost of the promise and the reason it is opt-in.
     */
    @Test
    fun `a content change behind the promise is missed - the documented limit`() {
        val store = store()
        val project = Project.open("/proj", store).also { it.trustFilesystem = true }
        assert(rebuiltCodes(project).isNotEmpty())
        // The error is repaired on disk, and nobody says so.
        store.writeText(cFile, "export const c: string = 'ok';\n")
        project.updateFile(aFile, "$aText// touched\n")
        assert(rebuiltCodes(project).isNotEmpty())
        // Reporting it is what makes it visible again.
        project.reloadFile(cFile)
        assert(rebuiltCodes(project).isEmpty())
    }

    /** And an untrusting project sees the same change with no report at all. */
    @Test
    fun `negative control - an untrusting project sees a change nobody reported`() {
        val store = store()
        val project = Project.open("/proj", store)
        assert(rebuiltCodes(project).isNotEmpty())
        store.writeText(cFile, "export const c: string = 'ok';\n")
        project.updateFile(aFile, "$aText// touched\n")
        assert(rebuiltCodes(project).isEmpty())
    }

    /** Turning the promise off drops everything retained, so a change is seen again. */
    @Test
    fun `withdrawing the promise drops what was retained`() {
        val store = store()
        val project = Project.open("/proj", store).also { it.trustFilesystem = true }
        assert(rebuiltCodes(project).isNotEmpty())
        store.writeText(cFile, "export const c: string = 'ok';\n")
        project.trustFilesystem = false
        project.updateFile(aFile, "$aText// touched\n")
        assert(rebuiltCodes(project).isEmpty())
    }

    // ---- what the promise does NOT cover, and must still work -----------------

    /**
     * ADDITIONS are still discovered. Nothing caches the file SET: the root-file glob
     * lists directories through the [com.xemantic.typescript.compiler.Vfs] on every
     * build. Without this the mechanism would be unusable in an editor, and the KDoc's
     * claim would be wishful.
     */
    @Test
    fun `a file added behind the promise still joins the program`() {
        val store = store()
        val project = Project.open("/proj", store).also { it.trustFilesystem = true }
        val before = rebuiltCodes(project)
        store.writeText("/proj/src/d.ts", "export const d: number = 'no';\n")
        project.updateFile(aFile, "$aText// touched\n")
        assert(project.files.any { it.endsWith("/d.ts") })
        assert(rebuiltCodes(project).size == before.size + 1)
    }

    /** DELETIONS likewise: an absent file is never retained, so it drops out. */
    @Test
    fun `a file deleted behind the promise still leaves the program`() {
        val store = store()
        val project = Project.open("/proj", store).also { it.trustFilesystem = true }
        assert(rebuiltCodes(project).isNotEmpty())
        store.delete(cFile)
        project.updateFile(aFile, "$aText// touched\n")
        assert(project.files.none { it.endsWith("/c.ts") })
        assert(rebuiltCodes(project).isEmpty())
    }

    /**
     * `tsconfig.json` is excluded from the promise by construction — it decides what
     * the program IS, and a stale one is a wrong program rather than a wrong
     * diagnostic.
     *
     * A CONTROL rather than a discriminating pin, and recorded as one: nothing offers
     * a config file to [OverlayVfs.retainRead] today, because that is called from the
     * crawl's fold and a `tsconfig` is read by the config loader instead — so this is
     * green against a binary with the `.json` refusal removed. What the refusal
     * actually protects is a CRAWLED `.json` (a `resolveJsonModule` import), and the
     * pin that sees it is `an offer is refused for json, for a tombstone and for an
     * overlaid path`.
     */
    @Test
    fun `a tsconfig change behind the promise is still seen`() {
        val store = store()
        val project = Project.open("/proj", store).also { it.trustFilesystem = true }
        assert(project.files.any { it.endsWith("/c.ts") })
        store.writeText(
            "/proj/tsconfig.json",
            """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
                """ "include": ["src/a.ts", "src/b.ts"] }""",
        )
        project.updateFile(aFile, "$aText// touched\n")
        assert(project.files.none { it.endsWith("/c.ts") })
    }

    // ---- the core wiring: the crawl really takes the resident path -----------

    /**
     * (INC.56) THE REGIME PIN. The saving is not the read — measured over 2,401 small
     * files, serving every read from memory moved the crawl's wall by NOTHING — it is
     * the per-file thread handoff the read forces, and only `Vfs.readTextIfResident`
     * skips that. Since [OverlayVfs.readText] serves the retention as well, a build
     * whose crawl stopped consulting the resident path would keep every
     * delegate-read count in this class green and quietly pay the handoff again.
     * Nothing else here can see that, so it is pinned as a count.
     */
    @Test
    fun `a trusted build answers its reads without the crawl's thread handoff`() {
        val project = Project.open("/proj", store()).also { it.trustFilesystem = true }
        project.diagnostics()
        val afterFirst = project.residentReadCount
        project.updateFile(aFile, "$aText// touched\n")
        project.files
        assert(project.residentReadCount > afterFirst)
    }

    /**
     * And an UNSAVED BUFFER takes that path with no promise at all: it is in memory by
     * construction, so the handoff for it was always pure loss.
     */
    @Test
    fun `an unsaved buffer is served without the handoff even untrusted`() {
        val project = Project.open("/proj", store())
        project.diagnostics()
        val afterFirst = project.residentReadCount
        project.updateFile(aFile, "$aText// touched\n")
        project.files
        assert(project.residentReadCount > afterFirst)
    }

    // ---- the resident-content protocol, pinned where it lives ----------------

    /**
     * (INC.56) [OverlayVfs.retainRead] is the ONLY writer of the retention, which is
     * what makes every read of it — from the crawl's concurrent workers — a read of a
     * map nothing is writing (round 825). A read does not retain.
     */
    @Test
    fun `reading does not retain - only an explicit offer does`() {
        val store = InMemoryVfs(mapOf("/x/a.ts" to "export const a = 1;\n"))
        val overlay = OverlayVfs(store)
        overlay.trustFilesystem = true
        assert(overlay.readText("/x/a.ts") == "export const a = 1;\n")
        assert(overlay.readTextIfResident("/x/a.ts") == null)
        overlay.retainRead("/x/a.ts", "export const a = 1;\n")
        assert(overlay.readTextIfResident("/x/a.ts") == "export const a = 1;\n")
    }

    /** Without the promise nothing is retained and nothing is resident. */
    @Test
    fun `negative control - an untrusting overlay retains nothing`() {
        val overlay = OverlayVfs(InMemoryVfs(mapOf("/x/a.ts" to "export const a = 1;\n")))
        overlay.retainRead("/x/a.ts", "export const a = 1;\n")
        assert(overlay.readTextIfResident("/x/a.ts") == null)
    }

    /**
     * An UNSAVED BUFFER is resident whether or not the filesystem is trusted — it is
     * in memory by construction — so the crawl need not hand it to an IO thread
     * either. This is the half of the mechanism that costs no promise at all.
     */
    @Test
    fun `an overlaid buffer is resident without any promise`() {
        val overlay = OverlayVfs(InMemoryVfs(mapOf("/x/a.ts" to "on disk")))
        overlay.put("/x/a.ts", "in the editor")
        assert(overlay.readTextIfResident("/x/a.ts") == "in the editor")
    }

    /**
     * (INC.85) The whole-store PRE-GATE, which is a different question from residency
     * and must stay one. `readAndScanBatch` probes every path of a wave only when this
     * is true, so it tracks [OverlayVfs.retained] — O(program under the promise) — and
     * deliberately NOT the overlaid buffers, which are O(open editors) and could never
     * repay an O(program) probe. An overlaid buffer is still RESIDENT (the pin above);
     * it simply does not license the scan.
     */
    @Test
    fun `the pre-gate tracks the promise and not the open buffers`() {
        val overlay = OverlayVfs(InMemoryVfs(mapOf("/x/a.ts" to "export const a = 1;\n")))
        assert(!overlay.hasResidentContent())

        // An unsaved buffer is resident and still does not open the gate.
        overlay.put("/x/open.ts", "in the editor")
        assert(overlay.readTextIfResident("/x/open.ts") == "in the editor")
        assert(!overlay.hasResidentContent())

        // The promise plus a read does.
        overlay.trustFilesystem = true
        assert(!overlay.hasResidentContent())
        overlay.retainRead("/x/a.ts", "export const a = 1;\n")
        assert(overlay.hasResidentContent())

        // Withdrawing it closes the gate again — the setter clears the retention, which
        // is what makes the `trustFilesystem` conjunct a belt rather than the rule.
        overlay.trustFilesystem = false
        assert(!overlay.hasResidentContent())
    }

    /** What the offer refuses, each for its own reason. */
    @Test
    fun `an offer is refused for json, for a tombstone and for an overlaid path`() {
        val overlay = OverlayVfs(InMemoryVfs())
        overlay.trustFilesystem = true
        overlay.retainRead("/x/tsconfig.json", "{}")
        assert(overlay.readTextIfResident("/x/tsconfig.json") == null)
        overlay.remove("/x/gone.ts")
        overlay.retainRead("/x/gone.ts", "export const gone = 1;\n")
        assert(overlay.readTextIfResident("/x/gone.ts") == null)
        overlay.put("/x/open.ts", "in the editor")
        overlay.retainRead("/x/open.ts", "on disk")
        assert(overlay.readTextIfResident("/x/open.ts") == "in the editor")
    }

    /**
     * A resident answer hands back the very instance that was offered, which is what
     * the memory and the cost arguments both rest on: the compiler's own
     * `CrawlParseCache` is already holding that instance, and its hit condition is
     * `e.content != source`, which returns on the reference compare when the two are
     * the same object. A copy would be a second megabyte AND an O(n) compare.
     */
    @Test
    fun `a resident answer is the same instance, not an equal copy`() {
        val text = "export const a = 1;\n"
        val copy = buildString { append(text) }
        assert(copy == text)
        assert(copy !== text)
        val overlay = OverlayVfs(InMemoryVfs(mapOf("/x/a.ts" to text)))
        overlay.trustFilesystem = true
        overlay.retainRead("/x/a.ts", copy)
        assert(overlay.readTextIfResident("/x/a.ts") === copy)
    }

    /** An overlaid buffer still wins over anything retained for the same path. */
    @Test
    fun `an overlay edit is not shadowed by a retained read`() {
        val project = Project.open("/proj", store()).also { it.trustFilesystem = true }
        assert(codesIn(project).isNotEmpty())
        project.updateFile(cFile, "export const c: string = 'ok';\n")
        assert(codesIn(project).isEmpty())
        // …and reverting the buffer brings the file on disk back, error and all.
        project.reloadFile(cFile)
        assert(codesIn(project).isNotEmpty())
    }
}
