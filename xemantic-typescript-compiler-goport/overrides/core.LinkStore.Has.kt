// go: github.com/microsoft/typescript-go/internal/core.LinkStore.Has 7d2e7898
// OVERRIDE (performance, docs/goport-perf.md § 14): see core.LinkStore — an indexed key reads `dense`.
fun <K, V> LinkStore<K, V>?.has(key: K): Boolean {
    val s = this!!
    val i = (key as? GoLinkKey)?.goLinkIndex ?: -1
    if (i >= 0) return s.dense[i] != null
    return s.entries.probe(key) !== GoMapAbsent
}
