// go: github.com/microsoft/typescript-go/internal/compiler.compilerHost.GetSourceFile 8d8b7a4a
// OVERRIDE (performance, docs/goport-perf.md § 21): a bundled lib parsed ahead into the GraalVM image heap
// (com.xemantic.typescript.tsgo.TsgoLibSnapshot) is answered for the same parse options and the same text; anything
// else parses as Go does.
override fun getSourceFile(opts: SourceFileParseOptions): SourceFile? {
    val t0 = this.fs()!!.readFile(opts.fileName)
    val text: String = t0.first
    val ok: Boolean = t0.second
    if (!ok) {
        return null
    }
    com.xemantic.typescript.tsgo.TsgoLibSnapshot.lookup(opts, text)?.let { return it }
    return com.xemantic.typescript.tsgo.parser.parseSourceFile(opts.goCopy(), text, com.xemantic.typescript.tsgo.core.getScriptKindFromFileName(opts.fileName))
}
