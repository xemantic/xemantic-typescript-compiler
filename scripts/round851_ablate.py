#!/usr/bin/env python3
"""Round 851 — the ONE-MISTAKE-AT-A-TIME ablation of the (WARM.5) probe pins.

Round 807: a COMBINED ablation cannot attribute — six faults injected together
failed six pins and read as full coverage, and one of them turned out to be
covered by a later guard rather than by any pin. So each fault below is applied
ALONE, built alone, and measured alone, and a pin that stays green under its own
fault is recorded as UNDISCRIMINATED rather than claimed as coverage.

Usage: python3 scripts/round851_ablate.py <fault-name> apply|revert
The driver (`scripts/round851-ablate.sh`) sequences build/test/revert.
"""
import subprocess
import sys

CORE = "xemantic-typescript-compiler-core/src"
SPINE = f"{CORE}/commonMain/kotlin/SpineDispatch.kt"
CHECKER = f"{CORE}/commonMain/kotlin/Checker.kt"
BENCH = f"{CORE}/commonTest/kotlin/BenchMain.kt"

FAULTS = {
    # A1 — the COARSE arm stops being coarse: every boundary is taken, so the
    # ON-minus-COARSE differential reads ~0 ns and the whole warm calibration is
    # silently a cold one.
    "coarse-anchor": (SPINE, """        if (mode == OFF || depth != 1) return
        if (mode == COARSE && !coarseAnchor[sec]) return
        val now = PassTiming.nowNanos()
        nanos[cur] += now - curT
        calls[cur]++
        cur = sec
        curT = now
    }""", """        if (mode == OFF || depth != 1) return
        val now = PassTiming.nowNanos()
        nanos[cur] += now - curT
        calls[cur]++
        cur = sec
        curT = now
    }"""),
    # A2 — the nested sub-measures stop being ON-only. `t()` still returns 0
    # under COARSE, so every such row is charged a span measured from the epoch.
    "nested-on-only": (SPINE, """    inline fun close(sec: Int, t0: Long) {
        if (mode != ON) return
        val d = PassTiming.nowNanos() - t0
        nanos[sec] += d
        calls[sec]++
        // (WARM.5) park the two spans""", """    inline fun close(sec: Int, t0: Long) {
        if (mode == OFF) return
        val d = PassTiming.nowNanos() - t0
        nanos[sec] += d
        calls[sec]++
        // (WARM.5) park the two spans"""),
    # A3 — the census keeps its exact TOTAL and attributes every exit to one
    # fixed row. This is the fault a partition-sum assertion cannot see.
    "census-row": (SPINE, """                exitInvRow[cur]++
                if (emitted) exitEmitRow[cur]++
                exitPrologueNanos[cur] += pendingPrologue
                exitCalleeNanos[cur] += pendingCallee
                if (pendingCalleeBail) exitCalleeBail[cur]++""",
                    """                exitInvRow[B216]++
                if (emitted) exitEmitRow[B216]++
                exitPrologueNanos[B216] += pendingPrologue
                exitCalleeNanos[B216] += pendingCallee
                if (pendingCalleeBail) exitCalleeBail[B216]++"""),
    # A4 — the census runs under COARSE too, which would make a COARSE arm's
    # census a partition of a DIFFERENT row set (COARSE has four rows).
    "census-on-only": (SPINE, """            calls[cur]++
            if (mode == ON) {
                exitInvRow[cur]++""", """            calls[cur]++
            if (mode != OFF) {
                exitInvRow[cur]++"""),
    # A5 — round 850's label defect, restored: disarm, then dump. The anchor is
    # `measureTier`, not the harness loop: the first draft injected the fault in
    # `main`, which no test can run, and every pin stayed green (round 807's
    # blind-pin mechanism, recorded and then FIXED rather than claimed).
    "report-order": (BENCH, """    tierBegin(tier)
    val value = build()
    val text = tierReport(tier)
    tierStop()""", """    tierBegin(tier)
    val value = build()
    tierStop()
    val text = tierReport(tier)"""),
    # A6 — the "did it buy anything" column goes dark while every other column
    # stays right.
    "emitted": (CHECKER, """            CallSections.end(emitted = diagnostics.size > diag0)""",
                """            CallSections.end(emitted = false)"""),
}


def main():
    name, action = sys.argv[1], sys.argv[2]
    path, before, after = FAULTS[name]
    if action == "revert":
        subprocess.check_call(["git", "checkout", "--", path])
        print(f"reverted {path}")
        return
    src = open(path).read()
    old, new = (before, after) if action == "apply" else (after, before)
    n = src.count(old)
    if n != 1:
        sys.exit(f"!! {name}: anchor matched {n} times in {path}, expected exactly 1")
    open(path, "w").write(src.replace(old, new))
    print(f"applied {name} to {path}")


if __name__ == "__main__":
    main()
