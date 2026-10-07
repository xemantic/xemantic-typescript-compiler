// go: github.com/microsoft/typescript-go/internal/checker.hashWrite64 e87468d0
// OVERRIDE: `uint64(value)` on a type parameter `T ~int | ~uint | ~int64 | ~uint64` — see checker.hashWrite32;
// the instantiations are int, NodeId and SymbolId.
fun <T : Comparable<T>> hashWrite64(goElem_T: GoElem<T>, h: com.xemantic.typescript.tsgo.go.github_com.zeebo.xxh3.Hasher?, value_1: T) {
    val v: ULong = when (value_1) {
        is com.xemantic.typescript.tsgo.ast.NodeId -> value_1.value
        is com.xemantic.typescript.tsgo.ast.SymbolId -> value_1.value
        is Int -> value_1.toLong().toULong()
        is Long -> value_1.toULong()
        is ULong -> value_1
        else -> error("hashWrite64: unexpected ${value_1::class}")
    }
    h!!.write(GoSlice.of(GoElem.INT, (v and 0xFFuL).toInt(), ((v shr 8) and 0xFFuL).toInt(), ((v shr 16) and 0xFFuL).toInt(), ((v shr 24) and 0xFFuL).toInt(),
        ((v shr 32) and 0xFFuL).toInt(), ((v shr 40) and 0xFFuL).toInt(), ((v shr 48) and 0xFFuL).toInt(), ((v shr 56) and 0xFFuL).toInt()))
}
