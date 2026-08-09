#!/usr/bin/env python3
"""(WARM.14) round 867 — read the amplification logs and solve for `s_p`.

    p(r)  = boundary + r * (skeleton + S * s_p)      [REAL arm]
    pc(r) = boundary + r * skeleton                  [CONTROL arm]

Two values of `r` cancel the boundary; the two arms cancel the skeleton; `S` is
MEASURED (consults / (nodes * r)), never assumed.  Batch 2 (`--drop-first`)
discards each process's leading amp rebuild, which is the one that warms the
amplified path — it is never exercised by the uninstrumented loop.
"""
import re, glob, sys, statistics as st

drop_first = "--drop-first" in sys.argv
paths = [a for a in sys.argv[1:] if not a.startswith("-")]
rows = []
for log in sorted(paths):
    proc = log.split('/')[-1].split('.')[0]
    txt = open(log).read()
    tiers = re.findall(r'\{"instrumented":true,"tier":"(\w+)","run":(\d+),"ms":([\d.]+),"files":(\d+),"errors":(\d+)', txt)
    reps = re.findall(r'arm: (\w+).*?reps: (-?\d+)\s+bracketed nodes: (\d+)\s+total \d+ ms\s+nanos: (\d+)', txt)
    cons = re.findall(r'consultations performed: (\d+)\s+would-consult \(S x nodes\): (\d+)\s+(?:exact multiple|control suppressed): (\w+)', txt)
    assert len(tiers) == len(reps) == len(cons), (log, len(tiers), len(reps), len(cons))
    for i in range(len(tiers)):
        if drop_first and i == 0:
            continue
        tier, run, ms, files, errors = tiers[i]
        arm, r, nodes, nanos = reps[i]
        c, exp, ok = cons[i]
        rows.append(dict(proc=proc, arm=arm, r=abs(int(r)), nodes=int(nodes), nanos=int(nanos),
                         consults=int(c), expected=int(exp), ok=ok == 'true',
                         ms=float(ms), files=int(files), errors=int(errors), p=int(nanos) / int(nodes)))

print("=== falsification (round 759's law: ARITHMETIC, never timing) ===")
print("rebuilds analysed          :", len(rows))
print("every rebuild 78 files/46 errors:", all(x['files'] == 78 and x['errors'] == 46 for x in rows))
print("sink identity holds in all :", all(x['ok'] for x in rows))
bad = [x for x in rows if x['arm'] == 'REAL' and x['consults'] != x['r'] * x['expected']]
print("REAL: consults == r*expected, exactly, in all:", not bad)
print("CONTROL: consults == 0 over a non-empty population:",
      all(x['consults'] == 0 and x['expected'] > 0 for x in rows if x['arm'] == 'CONTROL'))
print("bracketed nodes (distinct) :", sorted({x['nodes'] for x in rows}))
print("would-consult   (distinct) :", sorted({x['expected'] for x in rows}))
S = st.mean(x['expected'] / x['nodes'] for x in rows)
print("S (consultations per node) : %.4f" % S)

print("\n=== p(r), ns per bracketed node ===")
print(f"{'arm':8} {'r':>3} {'n':>2} {'median':>9} {'mean':>9} {'sd':>8} {'sd%':>7} {'min':>9} {'max':>9}")
tbl = {}
for arm in ("REAL", "CONTROL"):
    for r in sorted({x['r'] for x in rows}):
        v = [x['p'] for x in rows if x['arm'] == arm and x['r'] == r]
        if not v:
            continue
        tbl[(arm, r)] = v
        sd = st.stdev(v) if len(v) > 1 else 0.0
        print(f"{arm:8} {r:3d} {len(v):2d} {st.median(v):9.2f} {st.mean(v):9.2f} {sd:8.2f} "
              f"{100 * sd / st.mean(v):6.2f}% {min(v):9.2f} {max(v):9.2f}")

rs = sorted({x['r'] for x in rows})
pairs = [(rs[0], rs[1]), (rs[1], rs[2]), (rs[0], rs[2])]
print("\n=== slopes (ns per PASS per node), medians ===")
slope = {}
for arm in ("REAL", "CONTROL"):
    for a, b in pairs:
        slope[(arm, a, b)] = (st.median(tbl[(arm, b)]) - st.median(tbl[(arm, a)])) / (b - a)
        print(f"  {arm:8} ({a:2d},{b:2d}) -> {slope[(arm, a, b)]:8.3f}")

print("\n=== s_p = (slope_real - slope_control) / S ===")
sps = []
for a, b in pairs:
    sp = (slope[("REAL", a, b)] - slope[("CONTROL", a, b)]) / S
    sps.append(sp)
    print(f"  pair ({a:2d},{b:2d}): real {slope[('REAL', a, b)]:8.3f}  control "
          f"{slope[('CONTROL', a, b)]:7.3f}  ->  s_p = {sp:6.3f} ns")
print(f"  three slopes: min {min(sps):.3f}  max {max(sps):.3f}  median {st.median(sps):.3f}  "
      f"spread {100 * (max(sps) - min(sps)) / st.mean(sps):.1f}%")

# Per-process slopes: within ONE JVM the arm's compiled code and the box's state
# are held fixed, so this is the spread the estimate actually carries.
print("\n=== per-process slopes (widest r pair), and the arm-paired s_p ===")
per = {}
for arm in ("REAL", "CONTROL"):
    for proc in sorted({x['proc'] for x in rows if x['arm'] == arm}):
        got = {x['r']: x['p'] for x in rows if x['proc'] == proc and x['arm'] == arm}
        if rs[0] in got and rs[-1] in got:
            s = (got[rs[-1]] - got[rs[0]]) / (rs[-1] - rs[0])
            per.setdefault(arm, []).append((proc, s))
            print(f"  {arm:8} {proc:10} slope {s:8.3f}")
for arm in ("REAL", "CONTROL"):
    v = [s for _, s in per[arm]]
    print(f"  {arm:8} n={len(v)} median {st.median(v):8.3f} mean {st.mean(v):8.3f} "
          f"sd {st.stdev(v):7.3f} ({100 * st.stdev(v) / st.mean(v):.1f}%)")
lo = (min(s for _, s in per['REAL']) - max(s for _, s in per['CONTROL'])) / S
hi = (max(s for _, s in per['REAL']) - min(s for _, s in per['CONTROL'])) / S
mid = (st.median([s for _, s in per['REAL']]) - st.median([s for _, s in per['CONTROL']])) / S
print(f"  s_p from per-process medians: {mid:.3f} ns   worst-case envelope [{lo:.3f}, {hi:.3f}] ns")

print("\n=== R = 32.0 M x s_p, against a ~6,900 ms warm rebuild ===")
N = 32006965
for label, sp in (("median of 3 r-pairs", st.median(sps)),
                  ("min of 3 r-pairs", min(sps)),
                  ("max of 3 r-pairs", max(sps)),
                  ("per-process medians", mid),
                  ("envelope low", lo), ("envelope high", hi)):
    R = N * sp / 1e6
    print(f"  {label:22} s_p {sp:6.3f} ns -> R {R:7.1f} ms = {100 * R / 6900:5.2f}% warm "
          f"({100 * R / 4960:5.2f}% of the checkSpine row)")
