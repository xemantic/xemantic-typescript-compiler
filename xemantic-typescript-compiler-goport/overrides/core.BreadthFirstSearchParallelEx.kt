// go: github.com/microsoft/TypeScript/tsc/internal/core.BreadthFirstSearchParallelEx a0aa22b8
// import com.xemantic.typescript.tsgo.collections.size
// import com.xemantic.typescript.tsgo.collections.addIfAbsent
// import com.xemantic.typescript.tsgo.collections.entryAt
// import com.xemantic.typescript.tsgo.collections.values
// OVERRIDE ((TSGO.7.1)): Go declares `type result struct{…}` over this function's type parameters, which the porter
// cannot hoist (refused local-type); TypeScript 7.1's project system reaches it (findOrCreateDefaultConfiguredProject,
// auto-import preparation). A SEQUENTIAL port with Go's answer: Go visits a level's jobs in parallel but decides
// by index — the lowest-index goal wins, the lowest-index non-stopping result is the fallback, and the next level is
// the jobs' neighbors merged in job order, first key wins — so visiting each level in index order (stopping at the
// goal, which Go's lower-index jobs never depend on) returns the same path. Only `visit` calls on jobs after the goal,
// which Go may make concurrently and discard, are not made.
fun <K, N> breadthFirstSearchParallelEx(goElem_K: GoElem<K>, goElem_N: GoElem<N>, start: N, neighbors: ((N) -> GoSlice<N>)?, visit: ((N) -> Tuple2<Boolean, Boolean>)?, options: BreadthFirstSearchOptions<K, N>, getKey: ((N) -> K)?): BreadthFirstSearchResult<N> {
    val visited: SyncSet<K> = options.visited ?: SyncSet<K>(goElem_T = goElem_K)
    val jobElem = GoElem.ref<com.xemantic.typescript.tsgo.core.breadthFirstSearchJob<N>?>()
    val entryElem = GoElem.ref<com.xemantic.typescript.tsgo.collections.MapEntry<K, com.xemantic.typescript.tsgo.core.breadthFirstSearchJob<N>?>>()
    var fallback: com.xemantic.typescript.tsgo.core.breadthFirstSearchJob<N>? = null
    fun createPath(job0: com.xemantic.typescript.tsgo.core.breadthFirstSearchJob<N>?): GoSlice<N> {
        var path: GoSlice<N> = goElem_N.nilSlice
        var job = job0
        while (job != null) {
            path = path.append1(job.node)
            job = job.parent
        }
        return path
    }
    fun entry(key: K, job: com.xemantic.typescript.tsgo.core.breadthFirstSearchJob<N>?) =
        com.xemantic.typescript.tsgo.collections.MapEntry<K, com.xemantic.typescript.tsgo.core.breadthFirstSearchJob<N>?>(goElem_K = goElem_K, goElem_V = jobElem, key = key, value = job)
    var level: OrderedMap<K, com.xemantic.typescript.tsgo.core.breadthFirstSearchJob<N>?>? = com.xemantic.typescript.tsgo.collections.newOrderedMapFromList(
        goElem_K, jobElem, GoSlice.of(entryElem, entry(getKey!!(start), com.xemantic.typescript.tsgo.core.breadthFirstSearchJob(goElem_N = goElem_N, node = start))),
    )
    while (level.size() > 0) {
        options.preprocessLevel?.invoke(BreadthFirstSearchLevel(goElem_K = goElem_K, goElem_N = goElem_N, jobs = level))
        var goal = -1
        var lowestFallback = -1
        val next = ArrayList<com.xemantic.typescript.tsgo.core.breadthFirstSearchJob<N>>()
        var i = 0
        level.values()!!.invoke { j ->
            val index = i++
            // Already visited at a previous level: `visit` returned false there (jobs are deduplicated per level).
            if (!visited.addIfAbsent(getKey(j!!.node))) return@invoke true
            val (isResult, stop) = visit!!(j.node)
            if (isResult) {
                if (stop) {
                    goal = index
                    return@invoke false
                }
                if (fallback == null && lowestFallback < 0) lowestFallback = index
            }
            val neighborNodes = neighbors!!(j.node)
            for (k in 0 until neighborNodes.len) next += com.xemantic.typescript.tsgo.core.breadthFirstSearchJob(goElem_N = goElem_N, node = neighborNodes[k], parent = j)
            true
        }
        if (goal >= 0) return BreadthFirstSearchResult(goElem_N = goElem_N, stopped = true, path = createPath(level.entryAt(goal).second))
        if (fallback == null && lowestFallback >= 0) fallback = level.entryAt(lowestFallback).second
        val seen = HashSet<K>()
        val entries = next.filter { seen.add(getKey(it.node)) }.map { entry(getKey(it.node), it) }
        level = com.xemantic.typescript.tsgo.collections.newOrderedMapFromList(goElem_K, jobElem, GoSlice.of(entryElem, *entries.toTypedArray()))
    }
    return BreadthFirstSearchResult(goElem_N = goElem_N, stopped = false, path = createPath(fallback))
}
