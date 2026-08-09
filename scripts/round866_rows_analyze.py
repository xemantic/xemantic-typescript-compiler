#!/usr/bin/env python3
"""(WARM.13) round 866 — reduce the three-arm `checkSpine` logs to a decomposition.

`checkSpine` is the only row the per-kind dispatch table can move, and all three
arms arm the `rows` pass probe identically, so its boundaries cancel (round 793)
and every difference below is the DISPATCH.

    rows       production dispatch                        (the control)
    gatedrows  the gated machinery over the DERIVED table  -> delta = G_t - R
    gatedfull  the same machinery, skipping nothing        -> delta = G_f

with G_t / G_f the machinery's price at 21.65 / 59 dispatched consultations per
node and R the production cost of the 37.35 consultations the table skips — i.e.
R is exactly the prize a production per-kind table would collect.

Pairs are formed WITHIN a process from adjacent tier runs, so a pair's two
rebuilds sit at the same warmth and the pairing survives the rotated orders.
"""
import json
import re
import statistics
import sys
from pathlib import Path

OUT = Path("build/bench/round866rows")
ROW = re.compile(r"^\s*([0-9.]+)\s+\d+\s+\d+\s+\d+\s+checkSpine\s*$")

# Round 732's census, re-derived by `SpineDispatch.report()`: 59 handler
# consultations per node today, 21.65 under the derived table, over the
# single-threaded spine's node population.
NODES = 856_962
CONSULTS_NOW = 59.0
CONSULTS_TABLED = 21.65


def draws(log):
    """[(tier, wall_ms, checkSpine_ms)] in run order."""
    rows, tier, wall = [], None, None
    for line in log.read_text().splitlines():
        if '"instrumented"' in line and '"tier"' in line:
            d = json.loads(line)
            if d.get("files") != 78 or d.get("errors") != 46:
                sys.exit("%s: an arm answered a different program" % log.name)
            tier, wall = d["tier"], d["ms"]
            continue
        m = ROW.match(line)
        if m and tier is not None:
            rows.append((tier, wall, float(m.group(1))))
            tier = None
    return rows


def main():
    logs = sorted(OUT.glob("[pq]*.log"))
    if not logs:
        sys.exit("no logs in %s" % OUT)
    pairs = []          # (treated arm, process, treated ms, control ms, walls)
    for log in logs:
        rs = draws(log)
        if len(rs) != 4:
            sys.exit("%s: expected 4 checkSpine rows, got %d" % (log.name, len(rs)))
        for a, b in ((rs[0], rs[1]), (rs[2], rs[3])):
            ctl = a if a[0] == "rows" else b
            trt = b if a[0] == "rows" else a
            if ctl[0] != "rows" or trt[0] == "rows":
                sys.exit("%s: a pair is not one treated arm and one control" % log.name)
            pairs.append((trt[0], log.stem, trt[2], ctl[2], trt[1], ctl[1]))

    print("arm         proc   treated.spine  rows.spine      delta      pct   "
          "| treated.wall rows.wall")
    for arm, proc, t, c, tw, cw in pairs:
        print("%-11s %-5s %12.1f %11.1f %+10.1f %+7.2f%%   | %10.1f %9.1f"
              % (arm, proc, t, c, t - c, 100 * (t - c) / c, tw, cw))

    def stats(arm):
        subset = [x for x in pairs if x[0] == arm]
        d = [t - c for _, _, t, c, _, _ in subset]
        ctl = [c for _, _, _, c, _, _ in subset]
        return d, ctl

    print()
    summary = {}
    for arm in ("gatedrows", "gatedfull"):
        d, ctl = stats(arm)
        summary[arm] = statistics.median(d)
        print("%-10s n=%d  median %+8.1f ms (%+.2f%% of the row)  mean %+8.1f  "
              "min %+.1f  max %+.1f  treated faster %d/%d"
              % (arm, len(d), statistics.median(d),
                 100 * statistics.median(d) / statistics.mean(ctl),
                 statistics.mean(d), min(d), max(d),
                 sum(1 for x in d if x < 0), len(d)))

    ctl_all = [c for _, _, _, c, _, _ in pairs]
    print("\ncontrol `rows` checkSpine: n=%d  mean %.1f  sd %.1f (%.2f%%)  "
          "min %.1f  max %.1f"
          % (len(ctl_all), statistics.mean(ctl_all), statistics.stdev(ctl_all),
             100 * statistics.stdev(ctl_all) / statistics.mean(ctl_all),
             min(ctl_all), max(ctl_all)))

    # --- the decomposition, AND WHAT IT CANNOT IDENTIFY -----------------------
    #
    # Per node the spine makes K = 21.65 KEPT and S = 37.35 SKIPPED consultations.
    # Write s_p for a skipped consultation's production cost (a pure reject —
    # that total IS the prize R = S*s_p*N), d for the gated machinery's tax on a
    # KEPT consultation and d' for its tax on a rejecting one, A for its per-node
    # fixed cost. Then
    #
    #     delta(gatedrows) = A + K*d - S*s_p            = A + K*d - R
    #     delta(gatedfull) = A + K*d + S*d'
    #
    # so R = A + K*d - delta(gatedrows): the prize is exactly the tax GATED pays
    # on the consultations it KEEPS, less the margin by which it trails
    # production. d is not measured by any arm here, and d <= d' is all that can
    # be argued (a rejecting handler is the most inlinable in production, so it
    # loses the most by being called through a tableswitch). Two corners:
    #
    #   d = d'  (a uniform per-consultation tax)  -> the point estimate below
    #   d = 0   (the whole tax falls on rejects)  -> R = A - delta(gatedrows) ~ 0
    #
    # An honest reader takes the range, not the point.
    gf, gtr = summary["gatedfull"], summary["gatedrows"]
    S = CONSULTS_NOW - CONSULTS_TABLED
    uniform_d = gf * 1e6 / (NODES * CONSULTS_NOW)             # ns, A = 0
    r_uniform = uniform_d * NODES * CONSULTS_TABLED / 1e6 - gtr
    print("\n-- the decomposition (medians), and its identification gap --------")
    print("delta(gatedfull) = A + K*d + S*d'           = %+8.1f ms   (%.1f M "
          "dispatched consultations)" % (gf, NODES * CONSULTS_NOW / 1e6))
    print("delta(gatedrows) = A + K*d - R              = %+8.1f ms" % gtr)
    print("S*(s_p + d')     = the difference           = %+8.1f ms   "
          "(%.2f ns per skipped consultation, %.1f M of them)"
          % (gf - gtr, (gf - gtr) * 1e6 / (NODES * S), NODES * S / 1e6))
    print()
    print("R = A + K*d - delta(gatedrows), and d is identified by NO arm here:")
    print("  corner d = d' (uniform tax, %.2f ns/consultation, A=0):  R = %+7.1f ms"
          % (uniform_d, r_uniform))
    print("  corner d = 0  (the tax is all on the rejects):            R = %+7.1f ms"
          % max(0.0, -gtr))
    print("  independent cap: round 847's warm probe upper bound        R <=  352 ms")





if __name__ == "__main__":
    main()
