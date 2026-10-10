// go: github.com/microsoft/typescript-go/internal/core.LinkStore.Get cd5a7b8c
// OVERRIDE (performance, docs/goport-perf.md § 14): see core.LinkStore — an indexed key reads `dense`.
@Suppress("UNCHECKED_CAST")
fun <K, V> LinkStore<K, V>?.get(key: K): V? {
    val s = this!!
    val i = (key as? GoLinkKey)?.goLinkIndex ?: -1
    if (i >= 0) {
        val d = s.dense
        val v = d[i]
        if (v != null) return v as V
        val created = s.arena.new()
        d[i] = created
        return created
    }
    var value_1: V? = s.entries[key]
    if (value_1 != null) {
        return value_1
    }
    if (s.entries.isNil) {
        s.entries = GoMap.make<K, V?>(GoElem.ref<V?>())
    }
    value_1 = s.arena.new()
    s.entries[key] = value_1
    return value_1
}
