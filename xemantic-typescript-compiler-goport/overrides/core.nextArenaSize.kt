// go: github.com/microsoft/TypeScript/tsc/internal/core.nextArenaSize 8dc2dfd9
// OVERRIDE: `unsafe.Sizeof(*new(T))` has no JVM meaning (every T is one reference). The value only bounds how
// many slots one chunk of `Arena.NewSlice` holds, which nothing observes; a T is taken as one 8-byte pointer,
// as tsgo sizes its chunks for node pointers. (`Arena.New` has its own override and never chunks.)
@Suppress("UNUSED_PARAMETER")
fun <T> nextArenaSize(goElem_T: GoElem<T>, size_0: Int): Int {
    val maxSize = maxOf(512 / 8, 1)
    return minOf(maxOf(size_0, 1) * 2, 256, maxSize)
}
