// go: github.com/microsoft/typescript-go/internal/core.NewLimitedSemaphore 7b85113c
// OVERRIDE ((TSGO.5)): `make(chan struct{}, maxConcurrency)` is a CountingSemaphore — see core.LimitedSemaphore.
fun newLimitedSemaphore(maxConcurrency: Int): LimitedSemaphore? {
    if (maxConcurrency <= 0) {
        goPanic("maxConcurrency must be positive")
    }
    val s = LimitedSemaphore(ch = com.xemantic.typescript.tsgo.go.sync.CountingSemaphore(maxConcurrency))
    s.release = fun() { s.ch!!.release() }
    return s
}
