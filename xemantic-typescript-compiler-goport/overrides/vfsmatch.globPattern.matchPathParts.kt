// go: github.com/microsoft/typescript-go/internal/vfs/vfsmatch.globPattern.matchPathParts e8ec35a9
// OVERRIDE (performance, docs/goport-perf.md § 22): the same walk over the path's components, with each component
// located IN PLACE in `prefix` + `suffix` rather than copied out (Go's `rest[:idx]` is an O(1) slice; lowered, every
// component of every candidate path became a `substring`, and the config phase's include matching was ~half of that
// phase on a 1,445-file project). A literal component is compared region to region; a substring is made only for a
// wildcard, a case-insensitive compare and the hidden/package-folder test. The component is read without the struct
// copy (`goCopy`) the lowering adds: nothing here writes it.
fun com.xemantic.typescript.tsgo.vfs.vfsmatch.globPattern?.matchPathParts(prefix: String, suffix: String, pathOffset_0: Int, compIdx_1: Int, prefixOnly: Boolean): Boolean {
    var pathOffset: Int = pathOffset_0
    var compIdx: Int = compIdx_1
    val totalLen: Int = prefix.length + suffix.length
    // Positions are in the path prefix + suffix (one of the two may be empty); a component never straddles them.
    fun part(start: Int, end: Int): String =
        if (end <= prefix.length) prefix.substring(start, end) else suffix.substring(start - prefix.length, end - prefix.length)
    l0@ while (true) {
        // nextPathPartParts, inlined: the component is [start, end) (start -1: no more components), and the walk
        // continues at nextOffset.
        var start: Int
        var end: Int
        var nextOffset: Int
        if (suffix.length == 0 || prefix.length == 0) {
            val s = if (suffix.length == 0) prefix else suffix
            var offset = pathOffset
            if (offset >= s.length) {
                start = -1; end = -1; nextOffset = offset
            } else if (offset == 0 && s[0].code == 47) {
                start = 0; end = 0; nextOffset = 1
            } else {
                while (offset < s.length && s[offset].code == 47) offset++
                if (offset >= s.length) {
                    start = -1; end = -1; nextOffset = offset
                } else {
                    val slash = s.indexOf('/', offset)
                    start = offset
                    end = if (slash >= 0) slash else s.length
                    nextOffset = end
                }
            }
        } else {
            var offset = pathOffset
            if (offset >= totalLen) {
                start = -1; end = -1; nextOffset = offset
            } else if (offset == 0 && prefix[0].code == 47) {
                start = 0; end = 0; nextOffset = 1
            } else {
                var done = false
                start = -1; end = -1; nextOffset = offset
                if (offset < prefix.length) {
                    while (offset < prefix.length && prefix[offset].code == 47) offset++
                    if (offset < prefix.length) {
                        val slash = prefix.indexOf('/', offset)
                        if (slash < 0) goPanicSlice("[:-1]")
                        start = offset; end = slash; nextOffset = slash
                        done = true
                    }
                }
                if (!done) {
                    val sOff = offset - prefix.length
                    if (sOff >= suffix.length) {
                        start = -1; end = -1; nextOffset = offset
                    } else {
                        start = prefix.length + sOff; end = totalLen; nextOffset = totalLen
                    }
                }
            }
        }
        if (start < 0) {
            if (prefixOnly) {
                return true
            }
            return this.patternSatisfied(compIdx)
        }
        if (compIdx >= this!!.components.len) {
            return this!!.isExclude && !prefixOnly
        }
        val comp: com.xemantic.typescript.tsgo.vfs.vfsmatch.component = this!!.components[compIdx]
        when (comp.kind.value) {
            2 -> {
                if (this.matchPathParts(prefix, suffix, pathOffset, compIdx + 1, prefixOnly)) {
                    return true
                }
                if (!this!!.isExclude) {
                    val pathPart = part(start, end)
                    if (isHiddenPath(pathPart) || isPackageFolder(pathPart)) {
                        return false
                    }
                }
                pathOffset = nextOffset
                continue@l0
            }
            0 -> {
                if (comp.skipPackageFolders && isPackageFolder(part(start, end))) {
                    goPanic("unreachable: literal components never have skipPackageFolders")
                }
                val lit = comp.literal
                if (this!!.caseSensitive) {
                    val len = end - start
                    if (lit.length != len) return false
                    val equal = if (end <= prefix.length) prefix.regionMatches(start, lit, 0, len) else suffix.regionMatches(start - prefix.length, lit, 0, len)
                    if (!equal) return false
                } else if (!this.stringsEqual(lit, part(start, end))) {
                    return false
                }
            }
            1 -> {
                val pathPart = part(start, end)
                if (comp.skipPackageFolders && isPackageFolder(pathPart)) {
                    return false
                }
                if (!this.matchWildcard(comp.segments, pathPart)) {
                    return false
                }
            }
        }
        pathOffset = nextOffset
        compIdx++
    }
    goUnreachable()
}
