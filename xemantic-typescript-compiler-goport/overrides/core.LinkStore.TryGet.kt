// go: github.com/microsoft/typescript-go/internal/core.LinkStore.TryGet a5de5e26
// OVERRIDE (performance, docs/goport-perf.md § 14): see core.LinkStore — an indexed key reads `dense`.
@Suppress("UNCHECKED_CAST")
fun <K, V> LinkStore<K, V>?.tryGet(key: K): V? {
    val s = this!!
    val i = (key as? GoLinkKey)?.goLinkIndex ?: -1
    if (i >= 0) return s.dense[i] as V?
    return s.entries[key]
}
