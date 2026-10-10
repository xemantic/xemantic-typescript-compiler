// go: github.com/microsoft/typescript-go/internal/vfs/internal.Common.ReadFile c6a005d3
// OVERRIDE (performance, docs/goport-perf.md § 17): Go's `string(b)` of the file's bytes is one copy; generated, it
// was a `[]byte` slice filled element by element from the host's byte array, then a string built from it element by
// element again — ~20% of the GraalVM image's parse phase. A host file system that reads a file straight into a byte
// string (go.os.StringFileFS) is asked for it; any other FS keeps Go's path. `decodeBytes` (the BOM handling) and the
// `ok` results are Go's.
fun Common?.readFile(path: String): Tuple2<String, Boolean> {
    val t0 = this.rootAndPath(path)
    val fsys: FS? = t0.first
    val rest: String = t0.third
    if (fsys == null) {
        return Tuple2<String, Boolean>("", false)
    }
    if (fsys is com.xemantic.typescript.tsgo.go.os.StringFileFS) {
        val r = fsys.readFileString(rest)
        if (r.second != null) return Tuple2<String, Boolean>("", false)
        if (r.first.isEmpty()) return Tuple2<String, Boolean>("", true)
        return decodeBytes(r.first)
    }
    val t1 = com.xemantic.typescript.tsgo.go.io.fs.readFile(fsys, rest)
    val b: GoSlice<Int> = t1.first
    val err: GoError? = t1.second
    if (err != null) {
        return Tuple2<String, Boolean>("", false)
    }
    if (b.len == 0) {
        return Tuple2<String, Boolean>("", true)
    }
    val s: String = goBytesToString((b).slice(0, 0 + b.len))
    return decodeBytes(s)
}
