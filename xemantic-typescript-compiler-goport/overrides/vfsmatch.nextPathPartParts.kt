// go: github.com/microsoft/typescript-go/internal/vfs/vfsmatch.nextPathPartParts 4159d623
// OVERRIDE (performance, docs/goport-perf.md § 16): see nextPathPartSingle — `prefix[offset:]` searched in place
// instead of copied; the same results.
fun nextPathPartParts(prefix: String, suffix: String, offset_0: Int): Tuple3<String, Int, Boolean> {
    var offset: Int = offset_0
    if (suffix.length == 0) {
        return nextPathPartSingle(prefix, offset)
    }
    if (prefix.length == 0) {
        return nextPathPartSingle(suffix, offset)
    }
    val totalLen: Int = prefix.length + suffix.length
    if (offset >= totalLen) {
        return Tuple3<String, Int, Boolean>("", offset, false)
    }
    if (offset == 0 && prefix[0].code == 47) {
        return Tuple3<String, Int, Boolean>("", 1, true)
    }
    if (offset < prefix.length) {
        while (offset < prefix.length && prefix[offset].code == 47) {
            offset++
        }
        if (offset < prefix.length) {
            // Go: idx is >= 0 here (prefix ends in '/'); a -1 would make Go's rest[:idx] panic, and substring throws.
            val end: Int = prefix.indexOf('/', offset)
            if (end < 0) goPanicSlice("[:-1]")
            return Tuple3<String, Int, Boolean>(prefix.substring(offset, end), end, true)
        }
    }
    val sOff: Int = offset - prefix.length
    if (sOff >= suffix.length) {
        return Tuple3<String, Int, Boolean>("", offset, false)
    }
    return Tuple3<String, Int, Boolean>(suffix.substring(sOff), totalLen, true)
}
