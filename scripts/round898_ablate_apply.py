#!/usr/bin/env python3
"""(WARM.25) round 898 — apply ONE deliberate mistake to the copy census.

Round 807: a combined ablation cannot attribute. Each arm is a single edit,
reverted before the next by the driver's `git checkout --`.

Every arm asserts its own anchor is present exactly once, so an arm that no
longer applies FAILS rather than silently becoming a no-op — rounds 855/856,
where a driver that dispatched nothing printed a clean sweep.
"""
import sys

CHECKER = "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt"
SPINE = "xemantic-typescript-compiler-core/src/commonMain/kotlin/SpineDispatch.kt"
BENCH = "xemantic-typescript-compiler-core/src/commonTest/kotlin/BenchMain.kt"

ARMS = {
    # A1 — the per-family arm falls through to "arm every family". This is the
    # silent-wrong-measurement failure the tier pins exist for.
    "A1": (BENCH,
           '    tier.startsWith("copyampem") -> 1 shl FrontEnd.CP_EPOCH_MAP',
           '    tier.startsWith("copyampem") -> -1'),
    # A2 — `es` is shadowed by `em`'s prefix test rewritten to `copyampe`, so
    # the two families collide on one mask.
    "A2": (BENCH,
           '    tier.startsWith("copyampem") -> 1 shl FrontEnd.CP_EPOCH_MAP\n'
           '    tier.startsWith("copyampes") -> 1 shl FrontEnd.CP_EPOCH_SET',
           '    tier.startsWith("copyampe") -> 1 shl FrontEnd.CP_EPOCH_MAP\n'
           '    tier.startsWith("copyampes") -> 1 shl FrontEnd.CP_EPOCH_SET'),
    # A3 — the first-write record is never CLEARED, so a copy written N times
    # is counted N times and the touched totals overtake the copy totals.
    "A3": (CHECKER,
           "            if (n >= 0) { bornWith = -1; FrontEnd.noteFirstMut(FrontEnd.CP_EPOCH_MAP, n) }",
           "            if (n >= 0) { FrontEnd.noteFirstMut(FrontEnd.CP_EPOCH_MAP, n) }"),
    # A4 — the copy constructor never records the size it was born with, so the
    # touched counters read a permanent zero (round 849's un-instrumented zero).
    "A4": (CHECKER,
           "            bornWith = m.size\n            FrontEnd.addCopy(FrontEnd.CP_EPOCH_MAP, m.size)",
           "            FrontEnd.addCopy(FrontEnd.CP_EPOCH_MAP, m.size)"),
    # A5 — the bulk write counter adds one instead of its argument.
    "A5": (SPINE,
           "    fun noteMuts(kind: Int, n: Int) {\n        if (mode != ON) return\n        copyMuts[kind] += n",
           "    fun noteMuts(kind: Int, n: Int) {\n        if (mode != ON) return\n        copyMuts[kind] += 1"),
    # A6 — the arity lookup counter calls every read a hit, so the MISS
    # population (the one that decides candidate (6)) vanishes.
    "A6": (SPINE,
           "        if (hit) argLookupHits++ else argLookupMisses++",
           "        argLookupHits++"),
    # A7 — the ordered amplifier ignores the family mask, so a per-family ladder
    # amplifies every family it is handed.
    "A7": (SPINE,
           "    fun ampCopyOrdered(kind: Int, m: Map<*, *>) {\n"
           "        val r = copyAmp\n"
           "        if (r == 0 || (copyAmpKinds shr kind) and 1 == 0) return",
           "    fun ampCopyOrdered(kind: Int, m: Map<*, *>) {\n"
           "        val r = copyAmp\n"
           "        if (r == 0) return"),
    # A8 — the arg-overlay census is hooked AFTER the copy, on the copy's own
    # size rather than the source's. Identical for the overlay site and WRONG
    # for the shadow-minus one, which is the point: an arm that changes the
    # number without changing the shape.
    "A8": (CHECKER,
           "                FrontEnd.addCopy(FrontEnd.CP_ARG_SHADOW, effective.size)\n"
           "                FrontEnd.ampCopyOrdered(FrontEnd.CP_ARG_SHADOW, effective)\n"
           "                FrontEnd.noteMuts(FrontEnd.CP_ARG_SHADOW, it.size)",
           "                FrontEnd.noteMuts(FrontEnd.CP_ARG_SHADOW, it.size)"),
}


def main():
    arm = sys.argv[1]
    if arm not in ARMS:
        print(f"unknown arm: {arm}", file=sys.stderr)
        return 2
    path, old, new = ARMS[arm]
    s = open(path).read()
    if s.count(old) != 1:
        print(f"{arm}: anchor appears {s.count(old)} times in {path} — REFUSED", file=sys.stderr)
        return 3
    open(path, "w").write(s.replace(old, new))
    print(f"{arm}: applied to {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
