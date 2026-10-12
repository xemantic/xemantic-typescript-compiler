// go: github.com/microsoft/typescript-go/internal/checker.getRelationKey efe123b6
// OVERRIDE (performance, docs/goport-perf.md § 28): a PLAIN key (`'s'`, source id, target id, intersection state)
// is the 128-bit value itself instead of its xxh3 hash — no key builder, no buffer, no hashing (the relation-key
// path was ~10% of the checker thread, § 10). ts-rust keeps the same split (`PlainRelationKey`). A relation key
// is only ever compared for equality (`Relation.results`, `maybeKeysSet`), so any injective encoding answers what
// Go's does: `lo` is a bijective mix of the two 32-bit type ids (good `hashCode` bits; Go's xxh3 is not observable),
// `hi` the intersection state under a non-zero tag. A generic-reference key (`'g'`) still hashes, as in Go; a plain
// key equals a hashed one with the probability of an xxh3 collision, the risk Go already takes between two hashes.
fun getRelationKey(source_0: Type?, target_1: Type?, intersectionState: IntersectionState, isIdentity: Boolean, ignoreConstraints: Boolean): Tuple2<CacheHashKey, Boolean> {
    var source: Type? = source_0
    var target: Type? = target_1
    if (isIdentity && source!!.id.value > target!!.id.value) {
        val t2 = target
        val t3 = source
        source = t2
        target = t3
    }
    if (!(isTypeReferenceWithGenericArguments(source) && isTypeReferenceWithGenericArguments(target))) {
        // splitmix64's finalizer: a bijection of the packed ids.
        var z = (source!!.id.value.toLong() shl 32) or target!!.id.value.toLong()
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return Tuple2<CacheHashKey, Boolean>(
            CacheHashKey(0x706c616900000000uL or intersectionState.value.toULong(), z.toULong()),
            false,
        )
    }
    val ls4 = com.xemantic.typescript.tsgo.checker.keyBuilder.POOL.stack()
    val b: com.xemantic.typescript.tsgo.checker.keyBuilder = ls4.acquire()
    try {
        b.writeByte(103)
        val constrained = b.writeGenericTypeReferences(source, target, ignoreConstraints)
        hashWrite32<IntersectionState>(IntersectionState.ELEM, b.h, intersectionState)
        return Tuple2<CacheHashKey, Boolean>(b.hash(), constrained)
    } finally {
        ls4.release()
    }
}
