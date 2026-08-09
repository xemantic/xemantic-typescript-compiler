#!/usr/bin/env python3
"""(WARM.13) round 866 — reduce the warm GATED-vs-plain logs to paired deltas.

The pairing is WITHIN a process and WITHIN a tier-list position pair: the tier
loop alternates the two arms, so runs (0,1) and (2,3) are one pair each, and a
pair's delta is always gated - plain regardless of which arm held the earlier
slot.  Round 840(c)'s rule is why the batches are also reported separately: a
sign-consistent paired batch is not a result.
"""
import json
import statistics
import sys
from pathlib import Path

OUT = Path("build/bench/round866")


def draws(log):
    rows = []
    for line in log.read_text().splitlines():
        if '"instrumented"' in line:
            rows.append(json.loads(line))
    return rows


def main():
    logs = sorted(OUT.glob("b*.log"))
    if not logs:
        sys.exit("no logs in %s" % OUT)
    pairs = []          # (batch, process, gated_ms, plain_ms)
    arm = {"gated": [], "plain": []}
    for log in logs:
        rows = draws(log)
        batch = log.name[1]
        if len(rows) != 4:
            sys.exit("%s: expected 4 instrumented rebuilds, got %d" % (log.name, len(rows)))
        for r in rows:
            arm[r["tier"]].append(r["ms"])
            if r["files"] != 78 or r["errors"] != 46:
                sys.exit("%s: an arm answered a different program" % log.name)
        for a, b in ((rows[0], rows[1]), (rows[2], rows[3])):
            g = a if a["tier"] == "gated" else b
            p = a if a["tier"] == "plain" else b
            if g["tier"] != "gated" or p["tier"] != "plain":
                sys.exit("%s: a pair is not one of each arm" % log.name)
            pairs.append((batch, log.stem, g["ms"], p["ms"]))

    print("pair                       gated       plain      delta      pct")
    for batch, proc, g, p in pairs:
        print("%-22s %9.1f %11.1f %+10.1f %+8.2f%%" % (proc, g, p, g - p, 100 * (g - p) / p))

    def summarise(label, subset):
        d = [g - p for _, _, g, p in subset]
        gs = [g for _, _, g, _ in subset]
        ps = [p for _, _, _, p in subset]
        faster = sum(1 for x in d if x < 0)
        print(
            "%-10s n=%d  median %+8.1f ms (%+.2f%%)  mean %+8.1f  "
            "gated faster %d/%d  gated mean %.1f  plain mean %.1f"
            % (label, len(d), statistics.median(d),
               100 * statistics.median(d) / statistics.mean(ps),
               statistics.mean(d), faster, len(d),
               statistics.mean(gs), statistics.mean(ps))
        )

    print()
    summarise("batch 1", [x for x in pairs if x[0] == "1"])
    summarise("batch 2", [x for x in pairs if x[0] == "2"])
    summarise("ALL", pairs)
    print()
    for name, xs in arm.items():
        sd = statistics.stdev(xs)
        print("arm %-6s n=%d  mean %8.1f  sd %6.1f (%.2f%%)  min %.1f  max %.1f"
              % (name, len(xs), statistics.mean(xs), sd,
                 100 * sd / statistics.mean(xs), min(xs), max(xs)))


if __name__ == "__main__":
    main()
