// go: github.com/microsoft/typescript-go/internal/core.LimitedSemaphore 7e50933d
// OVERRIDE ((TSGO.5), docs/goport-cli.md): the struct's only state is a buffered `chan struct{}` used as a
// counting semaphore (Acquire sends, the release func receives) — the port has no channels, so the channel is
// `go.sync.CountingSemaphore` with the same capacity. TryAcquire's `select` races the send against
// `ctx.Done()`; the port's contexts never close their Done channel, so it is Acquire unless the context is
// already cancelled and no permit is free.
class LimitedSemaphore(
    @kotlin.jvm.JvmField var ch: com.xemantic.typescript.tsgo.go.sync.CountingSemaphore? = null,
    @kotlin.jvm.JvmField var release: (() -> Unit)? = null,
) : Semaphore {

    fun goCopy(): LimitedSemaphore = LimitedSemaphore(ch, release)

    // go: github.com/microsoft/typescript-go/internal/core.LimitedSemaphore.Acquire
    override fun acquire(): (() -> Unit)? {
        this.ch!!.acquire()
        return this.release
    }

    // go: github.com/microsoft/typescript-go/internal/core.LimitedSemaphore.TryAcquire
    override fun tryAcquire(ctx: Context?): Tuple2<(() -> Unit)?, Boolean> {
        if (this.ch!!.tryAcquire()) return Tuple2(this.release, true)
        if (ctx?.err() != null) return Tuple2(fun() {}, false)
        this.ch!!.acquire()
        return Tuple2(this.release, true)
    }
}
