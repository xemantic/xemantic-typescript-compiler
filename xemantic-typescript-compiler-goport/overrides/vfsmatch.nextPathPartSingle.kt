// go: github.com/microsoft/typescript-go/internal/vfs/vfsmatch.nextPathPartSingle e86f7965
// OVERRIDE (performance, docs/goport-perf.md § 16): Go's `rest := s[offset:]` is an O(1) slice; lowered as `substring`
// it COPIED the rest of the path once per component (quadratic in the path's length, ~a quarter of a 1,445-file
// project's config phase with nextPathPartParts). The same search over `s` from `offset`, the same results.
fun nextPathPartSingle(s: String, offset_0: Int): Tuple3<String, Int, Boolean> {
    var offset: Int = offset_0
    if (offset >= s.length) {
        return Tuple3<String, Int, Boolean>("", offset, false)
    }
    if (offset == 0 && s.length > 0 && s[0].code == 47) {
        return Tuple3<String, Int, Boolean>("", 1, true)
    }
    while (offset < s.length && s[offset].code == 47) {
        offset++
    }
    if (offset >= s.length) {
        return Tuple3<String, Int, Boolean>("", offset, false)
    }
    val end: Int = s.indexOf('/', offset)
    if (end >= 0) {
        return Tuple3<String, Int, Boolean>(s.substring(offset, end), end, true)
    }
    return Tuple3<String, Int, Boolean>(s.substring(offset), s.length, true)
}
