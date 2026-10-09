#!/usr/bin/env python3
"""(TSGO.6-f) Tables from async-profiler COLLAPSED output of the port on the JVM.

    scripts/tsgo-jvm-profile.sh ...                        # writes cpu.collapsed / alloc.collapsed
    scripts/tsgo_ap_stacks.py cpu.collapsed --threads      # CPU by thread kind (JIT, GC, goroutines, bench)
    scripts/tsgo_ap_stacks.py cpu.collapsed --thread check-bench --mechanisms --leaves --owners
    scripts/tsgo_ap_stacks.py cpu.collapsed --callers runtime/GoMap.find
    scripts/tsgo_ap_stacks.py cpu.collapsed --thread goroutine --frame-kinds   # needs `ann`: interpreted vs C1 vs C2
    scripts/tsgo_ap_stacks.py alloc.collapsed --classes    # allocated bytes by class
    scripts/tsgo_ap_stacks.py alloc.collapsed --allocated-by 'byte[]'

A LEAF is a location, not a price: a JDK/Kotlin leaf (`String.equals`, `Arrays.copyOf`) is charged by
--owners to its nearest ported (`com/xemantic/typescript/tsgo/`) frame, and --mechanisms groups leaves
by what they ARE (a runtime class, a Go shim package, a generated package, a VM stub). Percentages
are of the selected samples (CPU ticks, or allocated bytes for an alloc profile).
"""
import argparse
import collections
import re

P = 'com/xemantic/typescript/tsgo/'


def frames(stack):
    return [re.sub(r'_\[.\]$', '', f) for f in stack.split(';')]


def thread_kind(t):
    t = re.sub(r' tid=\d+', '', t).strip('[]')
    return re.sub(r'\d+', 'N', t)


def mechanism(f):
    if f.startswith(P + 'runtime/'):
        return 'runtime:' + f[len(P + 'runtime/'):].split('.')[0].split('$')[0]
    if f.startswith(P + 'go/'):
        return 'go-shim:' + f[len(P + 'go/'):].split('/')[0]
    if f.startswith(P):
        return 'gen:' + f[len(P):].split('/')[0]
    if f.startswith('kotlin/'):
        return 'kotlin'
    if f.startswith(('java/', 'jdk/', 'sun/')):
        return 'jdk:' + '/'.join(f.split('/')[:3]).split('.')[0]
    return 'vm:' + f[:40]


def main():
    a = argparse.ArgumentParser()
    a.add_argument('file')
    a.add_argument('--thread', help='keep samples whose thread name contains this')
    a.add_argument('--threads', action='store_true')
    a.add_argument('--mechanisms', action='store_true')
    a.add_argument('--leaves', action='store_true')
    a.add_argument('--owners', action='store_true')
    a.add_argument('--classes', action='store_true')
    a.add_argument('--frame-kinds', action='store_true')
    a.add_argument('--callers')
    a.add_argument('--allocated-by')
    a.add_argument('--top', type=int, default=30)
    o = a.parse_args()
    thr, mech, leaf, own, cls, kinds, call, alloc = (collections.Counter() for _ in range(8))
    tot = 0
    for line in open(o.file):
        stack, _, n = line.rstrip().rpartition(' ')
        n = int(n)
        raw = stack.split(';')
        if raw[0].startswith('['):
            thr[thread_kind(raw[0])] += n
            if o.thread and o.thread not in raw[0]:
                continue
            raw = raw[1:]
        elif o.thread:
            continue
        fr = [re.sub(r'_\[.\]$', '', f) for f in raw]
        tot += n
        l = fr[-1]
        leaf[l] += n
        mech[mechanism(l)] += n
        own[next((f for f in reversed(fr) if f.startswith(P)), l)] += n
        cls[l] += n
        m = re.search(r'_\[(.)\]$', raw[-1])
        kinds[{'0': 'interpreted', '1': 'C1', 'j': 'C2', 'i': 'inlined (C1/C2)', 'k': 'kernel'}.get(m.group(1) if m else '', 'native/VM')] += n
        if o.callers:
            idx = [i for i, f in enumerate(fr) if o.callers in f]
            if idx:
                i = idx[-1]
                call[' <- '.join(reversed(fr[max(0, i - 2):i]))] += n
        if o.allocated_by and l == o.allocated_by:
            ported = [f for f in fr[:-1] if f.startswith(P)]
            alloc[' <- '.join(reversed(ported[-2:]))] += n

    def show(title, c, denom=None):
        d = denom or sum(c.values()) or 1
        print(f'== {title}')
        for k, v in c.most_common(o.top):
            print(f'{v:10d} {100 * v / d:6.2f}%  {k}')

    if o.threads:
        show('threads (all samples)', thr)
    print(f'selected samples: {tot}')
    if o.mechanisms:
        show('leaf mechanism', mech, tot)
    if o.leaves:
        show('leaf frame', leaf, tot)
    if o.owners:
        show('owner (nearest ported frame)', own, tot)
    if o.classes:
        show('allocated class', cls, tot)
    if o.frame_kinds:
        show('leaf frame kind', kinds, tot)
    if o.callers:
        show(f'callers of {o.callers}', call)
    if o.allocated_by:
        show(f'allocation sites of {o.allocated_by}', alloc)


if __name__ == '__main__':
    main()
