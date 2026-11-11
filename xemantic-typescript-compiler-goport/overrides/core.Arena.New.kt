// go: github.com/microsoft/TypeScript/tsc/internal/core.Arena.New 99b75211
// OVERRIDE (performance, docs/goport-perf.md § 6): Go's arena batches node allocations into one backing
// array; on the JVM every struct object is its own allocation whatever holds it, so the slab buys nothing
// and its bookkeeping — a new GoSlice header per call, the slot's zero materialized through GoSlice.load —
// was ~4% of a check's allocation. A fresh zero T is what `&a.data[index]` is, observably: that slot is
// never handed out again and NewSlice's windows are clipped to their own length (slice3), so nothing else
// can alias the returned value, and `data` is read by no other method.
//
// TypeScript 7.1 also instantiates an arena with a NON-struct T (`Arena[[]*Type]`): Go's `*T` is then a pointer to
// a slice variable, which the port's `*T`-of-a-type-parameter convention represents as the `GoPtr` itself (a box),
// as `goOpaqueAddr` does; the call site sees `GoPtr<…>` (CallLowering.opaquePtrResult).
@Suppress("UNCHECKED_CAST")
fun <T> Arena<T>?.new(): T? {
    val e = this!!.goElem_T
    return if (e.copy != null) e.zeroValue() else GoBox(e.zeroValue()) as T?
}
