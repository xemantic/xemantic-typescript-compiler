// go: github.com/microsoft/typescript-go/internal/core.LinkStore f19b4f42
// OVERRIDE (performance, docs/goport-perf.md § 14): Go's `map[K]*V` keyed by a pointer is one cheap hash of the
// address; the port's map reads the key's header for an identity hash and probes a random slot, and ~half of a
// warm check's map time was these link reads (~8% of the thread). A key with a dense index (runtime.GoLinkKey:
// `*ast.Node`, `*ast.Symbol` — the porter's LINK_KEY_TYPES) is filed in [dense] by that index instead, so the
// links of a file's nodes sit together; any other key (`*ast.SourceFile` stores, a spent index) keeps [entries].
// [dense] is created with the store and shared by a copy, as [entries] is once it exists: a store is a checker
// field and is never copied while live. The values are the arena's, as in Go; nothing else reads either table.
class LinkStore<K, V>(
    @kotlin.jvm.JvmField val goElem_K: GoElem<K>,
    @kotlin.jvm.JvmField val goElem_V: GoElem<V>,
    @kotlin.jvm.JvmField var entries: GoMap<K, V?> = GoMap.nil<K, V?>(GoElem.ref<V?>()),
    @kotlin.jvm.JvmField var arena: Arena<V> = Arena<V>(goElem_T = goElem_V),
    @kotlin.jvm.JvmField var dense: GoLinkTable = GoLinkTable(),
) {

    fun goCopy(): LinkStore<K, V> = LinkStore(goElem_K = goElem_K, goElem_V = goElem_V, entries = entries, arena = arena.goCopy(), dense = dense)

    fun goSet(o: LinkStore<K, V>) {
        entries = o.entries
        arena = o.arena.goCopy()
        dense = o.dense
    }

    fun goEquals(o: LinkStore<K, V>): Boolean = entries == o.entries && arena.goEquals(o.arena) && dense === o.dense

    fun goHash(): Int = 31 * entries.hashCode() + 31 * arena.goHash()

    companion object {
        fun <K, V> elem(goElem_K: GoElem<K>, goElem_V: GoElem<V>): GoElem<LinkStore<K, V>> = GoElem({ LinkStore<K, V>(goElem_K = goElem_K, goElem_V = goElem_V) }, { it.goCopy() })
    }
}
