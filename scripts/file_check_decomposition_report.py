#!/usr/bin/env python3
"""(INC.37) step 1 — render the per-file check decomposition from one or more
`scripts/file-check-decomposition.sh` artifacts.

The quantity throughout is `own(F) = narrowed(F) - floor`, taken per PASS and
per WALL. Nothing here is a share carried across arms: a share rises when
everything else gets faster (round 830), so ms is quoted first and a share only
ever against the SAME arm's own total.
"""
import collections
import sys

SHORT = lambda p: p.split('/')[-1]
LADDER = ['es2019.ts', 'semver.ts', 'path.ts', 'binder.ts', 'parser.ts', 'checker.ts']
FLOOR = 'contain.ts'


def parse(paths):
    plain = collections.defaultdict(list)           # (tag,target) -> [median ms]
    sizes = {}
    passes = collections.defaultdict(dict)          # (arm,target,draw) -> name -> ns
    totals = {}                                     # (arm,target,draw) -> dict
    disp = collections.defaultdict(lambda: collections.defaultdict(lambda: [0, 0, 0]))
    walk = {}                                       # (target,draw) -> (prologue, tail, nodes, ovh)
    for path in paths:
        for line in open(path, errors='replace'):
            f = line.split()
            if not f:
                continue
            if f[0] == 'PLAIN':
                sizes[SHORT(f[-1])] = int(f[2])
                plain[(f[1], SHORT(f[-1]))].append(int(f[3]))
            elif f[0] == 'PT.total':
                arm, t, d = f[1].split('|')
                totals[(arm, SHORT(t), d, path)] = {k: int(v) for k, v in (x.split('=') for x in f[2:])}
            elif f[0] == 'PT':
                arm, t, d = f[1].split('|')
                passes[(arm, SHORT(t), d, path)][line.split(None, 4)[4].strip()] = int(f[2])
            elif f[0] == 'DISPATCH.wall':
                t, d = f[1].split('|')
                walk.setdefault((SHORT(t), d, path), {})['ovh'] = int(f[3].split('=')[1]) if '=' in f[3] else \
                    int(f[4].split('=')[1])
            elif f[0] == 'DISPATCH.walk':
                t, d = f[1].split('|')
                w = walk.setdefault((SHORT(t), d, path), {})
                for kv in f[2:]:
                    k, v = kv.split('=')
                    w[k] = int(v)
            elif f[0] == 'DCSV' and len(f) >= 3:
                rest = line.split(None, 2)[2].strip()
                if rest.startswith('phase,') or not rest:
                    continue
                c = rest.split(',')
                if len(c) != 7:
                    continue
                t, d = f[1].split('|')
                e = disp[(SHORT(t), d, path)][c[0] + ' ' + c[1]]
                e[0] += int(c[4]); e[1] += int(c[5]); e[2] += int(c[6])
    return plain, sizes, passes, totals, disp, walk


def best(d, arm, target, key=None):
    """The MINIMUM over every draw of an arm — a probe run can only be made
    slower by an interruption, never faster, so the min is the least-perturbed
    draw. (A median over 2-3 draws would be the same number or one of them.)"""
    cand = [v for k, v in d.items() if k[0] == arm and k[1] == target]
    if not cand:
        return {}
    if key is None:
        names = set().union(*(set(c) for c in cand))
        return {n: min(c.get(n, 0) for c in cand) for n in names}
    return min(c.get(key, 0) for c in cand)


def main(paths):
    plain, sizes, passes, totals, disp, walk = parse(paths)

    print('== 1. WALL: own(F) = narrowed(F) - floor  (probe-free arms) ==\n')
    fl = sorted(plain[('early', FLOOR)] + plain[('late', FLOOR)])
    floor_ms = fl[len(fl) // 2]
    print(f'floor arm (recheckOnly names a file the program does not contain): '
          f'{floor_ms} ms   draws {fl}\n')
    print(f'{"file":<12} {"bytes":>9} {"wall ms":>8} {"own ms":>7} {"us/KB":>8} {"own/floor":>9}')
    own = {}
    for t in LADDER:
        d = sorted(plain[('early', t)] + plain[('late', t)])
        w = d[len(d) // 2]
        own[t] = w - floor_ms
        kb = sizes[t] / 1024.0
        print(f'{t:<12} {sizes[t]:>9} {w:>8} {own[t]:>7} {1000 * own[t] / kb:>8.0f} '
              f'{own[t] / floor_ms:>9.2f}x')

    print('\n-- linearity: own(F) fitted a + b*bytes over the three largest --')
    a, b = LADDER[3], LADDER[5]
    slope = (own[b] - own[a]) / (sizes[b] - sizes[a])
    icept = own[a] - slope * sizes[a]
    print(f'   own(F) ~= {icept:.1f} ms + {slope * 1024:.3f} ms/KB')
    for t in LADDER:
        pred = icept + slope * sizes[t]
        print(f'   {t:<12} measured {own[t]:>6} ms   model {pred:>7.1f} ms   '
              f'ratio {own[t] / pred:.2f}')

    print('\n== 2. PER PASS: the `rows` tier, narrowed(F) - floor ==\n')
    floorp = best(passes, 'rows', FLOOR)
    print(f'{"file":<12} {"own(pass) ms":>12} {"checkSpine":>11} {"share":>6} '
          f'{"fltm":>6} {"tail walkers":>12} {"share":>6} {"#tail>0.5ms":>11}')
    for t in LADDER:
        cur = best(passes, 'rows', t)
        delta = {n: cur.get(n, 0) - floorp.get(n, 0) for n in set(cur) | set(floorp)}
        pos = sum(v for v in delta.values() if v > 0) / 1e6
        spine = delta.get('checkSpine', 0) / 1e6
        fltm = delta.get('init:buildFileLocalTypeMaps', 0) / 1e6
        tail = pos - spine - fltm
        big = sum(1 for n, v in delta.items()
                  if v > 500_000 and n not in ('checkSpine', 'init:buildFileLocalTypeMaps'))
        print(f'{t:<12} {pos:>12.1f} {spine:>11.1f} {100 * spine / pos:>5.1f}% '
              f'{fltm:>6.1f} {tail:>12.1f} {100 * tail / pos:>5.1f}% {big:>11}')

    print('\n== 3. INSIDE checkSpine: the type-system sub-counters (`full` tier) ==')
    print('   ms are the FULL tier\'s own; the share is against the SAME arm\'s')
    print('   checkSpine row, then applied to the `rows` tier\'s ms (last column).\n')
    print(f'{"file":<12} {"spine(rows)":>11} {"spine(full)":>11} {"probe x":>7} '
          f'{"relation":>17} {"typeNode":>17} {"member":>16} {"narrow":>17} {"typeOfExpr*":>18}')
    for t in LADDER:
        r = best(passes, 'rows', t).get('checkSpine', 0) / 1e6
        f = best(passes, 'full', t).get('checkSpine', 0) / 1e6
        tt = {k: v for k, v in totals.items() if k[0] == 'full' and k[1] == t}
        pick = min(tt.values(), key=lambda d: d['init'])
        cells = []
        for key in ('relation', 'typeNode', 'memberResolve', 'narrowWalkNanos', 'typeOfExprNanos'):
            ms = pick[key] / 1e6
            sh = 100 * ms / f if f else 0
            cells.append(f'{sh:>5.1f}% ->{sh * r / 100:>7.1f}')
        print(f'{t:<12} {r:>11.1f} {f:>11.1f} {f / max(r, .01):>6.2f}x ' + ' '.join(
            f'{c:>17}' for c in cells))
    print('\n   * typeOfExprNanos DOUBLE-COUNTS a nested subtree once per level '
          '(CLAUDE.md), so it\n     is an upper bound and it OVERLAPS the other four.'
          ' relation/typeNode/member/narrow\n     are outermost-guarded and mutually '
          'disjoint at the top frame.')
    print('\n   narrowing walks launched, and typing calls, per file:')
    print(f'   {"file":<12} {"narrowWalks":>12} {"typeOfExprCalls":>16} {"walks/KB":>9}')
    for t in LADDER:
        tt = {k: v for k, v in totals.items() if k[0] == 'full' and k[1] == t}
        pick = min(tt.values(), key=lambda d: d['init'])
        print(f'   {t:<12} {pick["narrowWalks"]:>12} {pick["typeOfExprCalls"]:>16} '
              f'{pick["narrowWalks"] / (sizes[t] / 1024.0):>9.2f}')

    print('\n== 4. INSIDE checkSpine: the per-HANDLER split (`dispatch` tier) ==\n')
    for t in LADDER:
        keys = [k for k in disp if k[0] == t]
        if not keys:
            continue
        agg = {}
        for h in set().union(*(set(disp[k]) for k in keys)):
            agg[h] = (max(disp[k][h][0] for k in keys),
                      min(disp[k][h][2] for k in keys if h in disp[k]))
        wk = [walk[k] for k in walk if k[0] == t]
        ovh = min(w.get('ovh', 0) for w in wk) if wk else 0
        nodes = max(w.get('nodes', 0) for w in wk) if wk else 0
        prologue = min(w.get('prologue', 0) for w in wk) if wk else 0
        tail = min(w.get('tail', 0) for w in wk) if wk else 0
        totc = sum(v[0] for v in agg.values())
        net = {h: (ns - ovh * c) for h, (c, ns) in agg.items()}
        tnet = sum(max(v, 0) for v in net.values()) / 1e6
        rows_spine = best(passes, 'rows', t).get('checkSpine', 0) / 1e6
        print(f'-- {t}   nodes={nodes}  consults={totc} ({totc / max(nodes, 1):.1f}/node)  '
              f'probe pair={ovh} ns')
        print(f'   handlers(net) {tnet:.1f} ms | prologue {prologue / 1e6:.1f} | '
              f'tail {tail / 1e6:.1f} | rows-tier checkSpine {rows_spine:.1f} ms')
        for h, v in sorted(net.items(), key=lambda x: -x[1])[:8]:
            print(f'     {h:<36} {v / 1e6:>8.1f} ms net  {100 * v / 1e6 / max(tnet, .01):>5.1f}%  '
                  f'{v / max(agg[h][0], 1):>6.0f} ns/consult')
        print()


if __name__ == '__main__':
    main(sys.argv[1:] or ['build/bench/inc37/run2.txt'])
