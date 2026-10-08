// go: github.com/microsoft/typescript-go/internal/checker.hashWrite32 81ac35fd
// OVERRIDE: `uint32(value)` on a type parameter `T ~int32 | ~uint32` — Kotlin generics cannot convert an
// erased value class; the three instantiations (AccessFlags, IntersectionState, TypeId) are listed.
// Same family as ast.getCombinedFlags (docs/goport-lowering.md § 4, basic-set type parameters).
fun <T : Comparable<T>> hashWrite32(goElem_T: GoElem<T>, h: com.xemantic.typescript.tsgo.go.github_com.zeebo.xxh3.Hasher?, value_1: T) {
    val v: UInt = when (value_1) {
        is AccessFlags -> value_1.value
        is IntersectionState -> value_1.value
        is TypeId -> value_1.value
        is Int -> value_1.toUInt()
        is UInt -> value_1
        else -> error("hashWrite32: unexpected ${value_1::class}")
    }
    // binary.LittleEndian.PutUint32 + Write, without the byte slice (docs/goport-perf.md § 6).
    h!!.writeU32le(v.toInt())
}
