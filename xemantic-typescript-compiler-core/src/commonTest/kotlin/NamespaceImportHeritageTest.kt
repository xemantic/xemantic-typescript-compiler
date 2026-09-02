/*
 * Copyright 2025-2026 Kazimierz Pogoda / Xemantic
 *
 * Licensed under the GNU Affero General Public License, Version 3 (AGPL-3.0-only)
 * WITH LicenseRef-xtsc-output-exception, see LICENSE.md.
 *
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.79) A dotted heritage base whose HEAD is a namespace import inside an ambient
 * module block — `@types/node`'s whole shape: `declare module "tls" { import * as net
 * from "node:net"; class TLSSocket extends net.Socket {} }`, where `declare module
 * "node:net"` is `export * from "net"`, and `declare module "node:stream"` is `import
 * stream = require("stream"); export = stream;` over a `stream` block ending in
 * `export = Stream` (a class merged with a namespace).
 *
 * Reproduced on a scratch project against tsgo 7.0.2 (2026-09-02): the nine write
 * probes below read the inherited member's type in tsgo; here, before the fix, the
 * class bases were SILENT (an unresolved base is `any`) and the interface bases were a
 * false TS2339 on the inherited member. The head resolved all along — the block's
 * `import * as net` alias sits in the carrier's exports and (CHK.77)'s consult finds
 * it — and `resolveAlias` leaves such an alias unresolved (its target is no file), so
 * the member lookup read `exports` of an alias. `import net = require("net")` to the
 * DECLARING block already worked (B113 answers the carrier); through a wiring block it
 * did not.
 *
 * Every consumer pin reads the type OUT OF A MESSAGE (an unresolved base is silent);
 * the lens pins read declaration IDENTITY through `CheckedLens.heritageBaseSymbol`,
 * which is what the externals generator asks.
 */
class NamespaceImportHeritageTest {

    private fun messages(diagnostics: List<Diagnostic>, code: Int): List<String> =
        diagnostics.filter { it.code == code }.map { it.message }

    private val directives = "// @strict: true\n// @module: commonjs"

    private val streamDts = """
        declare module "stream" {
            class Stream { zzStream: number; }
            namespace Stream {
                class Duplex extends Stream { zzDuplex: string; }
                interface DuplexOptions { zzDuplexOpt: boolean; }
            }
            export = Stream;
        }
        declare module "node:stream" {
            import stream = require("stream");
            export = stream;
        }
    """

    private val netDts = """
        declare module "net" {
            import * as stream from "node:stream";
            class Socket extends stream.Duplex { zzSock: number; }
            class Server { zzServ: string; }
            interface ServerOpts { zzOpts: boolean; }
            interface SocketOpts extends stream.DuplexOptions { zzSockOpt: number; }
        }
        declare module "node:net" {
            export * from "net";
        }
    """

    private val tlsDts = """
        declare module "tls" {
            import * as net from "node:net";
            class TLSSocket extends net.Socket { zzTls: string; }
            interface TlsOptions extends net.ServerOpts { zzTlsOpt: number; }
            class Server extends net.Server { zzTlsServer: boolean; }
        }
        declare module "node:tls" {
            export * from "tls";
        }
    """

    private val ttyDts = """
        declare module "tty" {
            import net = require("net");
            import netx = require("node:net");
            class ReadStream extends net.Socket { zzTty: number; }
            interface TtyOpts extends net.ServerOpts { zzTtyOpt: string; }
            class WriteStream extends netx.Socket { zzTtyW: number; }
        }
    """

    private val negDts = """
        declare module "cyc1" { export * from "cyc2"; }
        declare module "cyc2" { export * from "cyc1"; }
        declare module "neg" {
            import * as net from "node:net";
            import * as tlsm from "node:tls";
            import * as cyc from "cyc1";
            class Bad extends net.Nope { zzBad: number; }
            const notNs: number;
            class Bad2 extends notNs.Foo { zzBad2: number; }
            class Bad3 extends tlsm.net { zzBad3: number; }
            class Bad4 extends cyc.Nope { zzBad4: number; }
        }
    """

    private fun program(consumer: String): List<Diagnostic> = diagnose(
        """
        // @Filename: stream.d.ts
        $streamDts
        // @Filename: net.d.ts
        $netDts
        // @Filename: tls.d.ts
        $tlsDts
        // @Filename: tty.d.ts
        $ttyDts
        // @Filename: neg.d.ts
        $negDts
        // @Filename: main.ts
        $consumer
        """,
        directives = directives,
    )

    // --- the consumer path -------------------------------------------------------------

    @Test
    fun `a class extending a member of a namespace import of a star re-exporting ambient module inherits it`() {
        val d = program(
            """
            import tls = require("tls");
            declare const s: tls.TLSSocket;
            const p1: string = s.zzSock;
            const p2: boolean = s.zzStream;
            const p3: number = s.zzDuplex;
            declare const sv: tls.Server;
            const p5: number = sv.zzServ;
            """
        )
        assert(messages(d, 2322) == listOf(
            "Type 'number' is not assignable to type 'string'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'string' is not assignable to type 'number'.",
            "Type 'string' is not assignable to type 'number'.",
        ))
        assert(d.none { it.code == 2339 && it.fileName == "main.ts" })
    }

    @Test
    fun `an interface extending a member of a namespace import of a star re-exporting ambient module inherits it`() {
        val d = program(
            """
            import tls = require("tls");
            declare const o: tls.TlsOptions;
            const p4: string = o.zzOpts;
            """
        )
        assert(messages(d, 2322) == listOf("Type 'boolean' is not assignable to type 'string'."))
        assert(d.none { it.code == 2339 && it.fileName == "main.ts" })
    }

    @Test
    fun `an import-equals require head resolves to the declaring block and through a star re-export alike`() {
        val d = program(
            """
            import tty = require("tty");
            declare const r: tty.ReadStream;
            const p6: string = r.zzSock;
            declare const to: tty.TtyOpts;
            const p7: string = to.zzOpts;
            declare const w: tty.WriteStream;
            const p8: string = w.zzSock;
            """
        )
        assert(messages(d, 2322) == listOf(
            "Type 'number' is not assignable to type 'string'.",
            "Type 'boolean' is not assignable to type 'string'.",
            "Type 'number' is not assignable to type 'string'.",
        ))
        assert(d.none { it.code == 2339 && it.fileName == "main.ts" })
    }

    @Test
    fun `a head aliasing an export-equals of a require alias reaches the namespace the last block exports`() {
        val d = program(
            """
            import net = require("net");
            declare const so: net.SocketOpts;
            const p8: string = so.zzDuplexOpt;
            declare const ns: net.Socket;
            const p9: string = ns.zzStream;
            """
        )
        assert(messages(d, 2322) == listOf(
            "Type 'boolean' is not assignable to type 'string'.",
            "Type 'number' is not assignable to type 'string'.",
        ))
        assert(d.none { it.code == 2339 && it.fileName == "main.ts" })
    }

    // --- the lens ----------------------------------------------------------------------

    /** Records what the lens answers for every heritage base, keyed `<name>:<clause>`. */
    private class Recorder : CheckedNodeSink {
        val heritageBaseDecl = HashMap<String, Node?>()
        val seen = HashSet<String>()

        override fun expression(node: Expression, lens: CheckedLens) {}

        private fun recordHeritage(name: String, clauses: List<HeritageClause>?, lens: CheckedLens) {
            for (clause in clauses ?: return) {
                val base = clause.types.firstOrNull()?.expression ?: continue
                val kind = if (clause.token == SyntaxKind.ImplementsKeyword) "implements" else "extends"
                seen.add("$name:$kind")
                heritageBaseDecl["$name:$kind"] = lens.heritageBaseSymbol(base)?.declarations?.firstOrNull()
            }
        }

        override fun declaration(node: Node, lens: CheckedLens) {
            when (node) {
                is InterfaceDeclaration -> recordHeritage(node.name.text, node.heritageClauses, lens)
                is ClassDeclaration -> node.name?.text?.let { recordHeritage(it, node.heritageClauses, lens) }
                else -> {}
            }
        }
    }

    /** One shared [Binder] for every file, as the production multi-file path binds. */
    private fun runLens(vararg files: Pair<String, String>): Pair<Recorder, List<SourceFile>> {
        val options = CompilerOptions()
        val binder = Binder(options)
        val sourceFiles = files.map { (name, text) -> Parser(text.trimIndent(), name).parse() }
        val results = sourceFiles.map { binder.bind(it) }
        val recorder = Recorder()
        Checker(options, results, isMultiFileSource = true, checkedSink = recorder)
        return recorder to sourceFiles
    }

    private fun declarationNamed(file: SourceFile, name: String): Node {
        var found: Node? = null
        fun walk(node: Node) {
            when (node) {
                is InterfaceDeclaration -> if (node.name.text == name) found = node
                is ClassDeclaration -> if (node.name?.text == name) found = node
                else -> {}
            }
            forEachChild(node) { walk(it) }
        }
        walk(file)
        return found!!
    }

    private fun lensRun(): Pair<Recorder, List<SourceFile>> = runLens(
        "/proj/stream.d.ts" to streamDts,
        "/proj/net.d.ts" to netDts,
        "/proj/tls.d.ts" to tlsDts,
        "/proj/tty.d.ts" to ttyDts,
        "/proj/neg.d.ts" to negDts,
    )

    @Test
    fun `the lens answers a base whose head is a namespace import through an export star block`() {
        val (recorder, files) = lensRun()
        val (_, net, _, _, _) = files
        val tlsSocketExtendsSocket = recorder.heritageBaseDecl["TLSSocket:extends"] === declarationNamed(net, "Socket")
        val tlsOptionsExtendsServerOpts = recorder.heritageBaseDecl["TlsOptions:extends"] === declarationNamed(net, "ServerOpts")
        val tlsServerExtendsServer = recorder.heritageBaseDecl["Server:extends"] === declarationNamed(net, "Server")
        assert(tlsSocketExtendsSocket)
        assert(tlsOptionsExtendsServerOpts)
        assert(tlsServerExtendsServer)
    }

    @Test
    fun `the lens answers a base whose head is an import-equals require of the declaring block or of a star block`() {
        val (recorder, files) = lensRun()
        val (_, net, _, _, _) = files
        val readStreamExtendsSocket = recorder.heritageBaseDecl["ReadStream:extends"] === declarationNamed(net, "Socket")
        val ttyOptsExtendsServerOpts = recorder.heritageBaseDecl["TtyOpts:extends"] === declarationNamed(net, "ServerOpts")
        val writeStreamExtendsSocket = recorder.heritageBaseDecl["WriteStream:extends"] === declarationNamed(net, "Socket")
        assert(readStreamExtendsSocket)
        assert(ttyOptsExtendsServerOpts)
        assert(writeStreamExtendsSocket)
    }

    @Test
    fun `the lens answers a base whose head aliases an export-equals chain to a merged class namespace`() {
        val (recorder, files) = lensRun()
        val (stream, _, _, _, _) = files
        val socketExtendsDuplex = recorder.heritageBaseDecl["Socket:extends"] === declarationNamed(stream, "Duplex")
        val socketOptsExtendsDuplexOptions = recorder.heritageBaseDecl["SocketOpts:extends"] === declarationNamed(stream, "DuplexOptions")
        assert(socketExtendsDuplex)
        assert(socketOptsExtendsDuplexOptions)
    }

    @Test
    fun `negative control - the lens answers null for a member the module surface lacks and for a non-namespace head`() {
        val (recorder, _) = lensRun()
        // Each base was asked (the recorder saw the clause) and answered null: a
        // name no re-exported block declares (tsgo: TS2339 at the base), a `const`
        // head, a block's own import binding read as a member, and a star cycle.
        val asked = recorder.seen.containsAll(listOf("Bad:extends", "Bad2:extends", "Bad3:extends", "Bad4:extends"))
        assert(asked)
        val bad = recorder.heritageBaseDecl["Bad:extends"]
        val bad2 = recorder.heritageBaseDecl["Bad2:extends"]
        val bad3 = recorder.heritageBaseDecl["Bad3:extends"]
        val bad4 = recorder.heritageBaseDecl["Bad4:extends"]
        assert(bad == null)
        assert(bad2 == null)
        assert(bad3 == null)
        assert(bad4 == null)
    }

    @Test
    fun `negative control - a consumer of an unresolvable base stays silent on the derived members`() {
        // The base of `Bad` is unresolvable; its OWN member still types. tsgo reports
        // TS2339 at the base expression, which (CHK.80)(b) emits at the member name
        // (`NamespaceResolutionFollowUpTest` pins the rows); nothing reaches the consumer.
        val d = program(
            """
            import neg = require("neg");
            declare const b: neg.Bad;
            const q: string = b.zzBad;
            """
        )
        assert(messages(d, 2322) == listOf("Type 'number' is not assignable to type 'string'."))
        assert(d.none { it.code == 2339 && it.fileName == "main.ts" })
        assert(d.any { it.code == 2339 && it.fileName == "neg.d.ts" && it.message == "Property 'Nope' does not exist on type 'typeof import(\"node:net\")'." })
    }
}
