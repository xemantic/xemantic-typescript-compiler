// go: github.com/microsoft/typescript-go/internal/core.Arena.New a66311d8
// OVERRIDE (performance, docs/goport-perf.md § 6): Go's arena batches node allocations into one backing
// array; on the JVM every struct object is its own allocation whatever holds it, so the slab buys nothing
// and its bookkeeping — a new GoSlice header per call, the slot's zero materialized through GoSlice.load —
// was ~4% of a check's allocation. A fresh zero T is what `&a.data[index]` is, observably: that slot is
// never handed out again and NewSlice's windows are clipped to their own length (slice3), so nothing else
// can alias the returned value, and `data` is read by no other method.
fun <T> Arena<T>?.new(): T? = this!!.goElem_T.zeroValue()
