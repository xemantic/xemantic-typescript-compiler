#!/usr/bin/env python3
"""(TSGO.6) Self / inclusive / caller tables from `perf script -F comm,tid,period,ip,sym` output.

`perf report`'s inclusive view is hard to read with Kotlin/Native's mangled names; this folds the
stacks once and answers the three questions a profile round asks:

    perf script -i perf.data -F comm,tid,period,ip,sym > stacks.txt
    scripts/tsgo_perf_stacks.py stacks.txt                 # self + inclusive top 30
    scripts/tsgo_perf_stacks.py stacks.txt --callers binarySearchFunc   # who calls it (inclusive %)
    scripts/tsgo_perf_stacks.py stacks.txt --mechanisms    # self time grouped by mechanism

Percentages are of ALL cycles (every thread, each sample weighted by its PERIOD), i.e. of CPU
time, not of wall time. Weight by period, never by sample count: in frequency mode (`-F`) every new
thread starts at a tiny period, and with hundreds of goroutine threads an unweighted count reads the
kernel's thread-start work as ~50% of the run.
"""
import argparse
import collections
import re

def short(sym):
    s = re.sub(r'\(.*$', '', sym)                      # drop the signature
    s = s.replace('kfun:com.xemantic.typescript.tsgo.', '')
    s = re.sub(r'__at__com\.xemantic\.typescript\.tsgo\.[\w.]+\?', '', s)
    return s[:110]

def stacks(path):
    comm, period, frames = None, 1, []
    for line in open(path, errors='replace'):
        if not line.strip():
            if comm is not None:
                yield comm, period, frames
            comm, period, frames = None, 1, []
        elif line[0] in ' \t':
            parts = line.strip().split(None, 1)
            sym = parts[1] if len(parts) > 1 else '[unknown]'
            sym = re.sub(r'\+0x[0-9a-f]+$', '', sym)
            if sym == '[unknown]':
                sym = '[kernel]' if parts[0].startswith('ffffffff') else '[unknown]'
            frames.append(sym)
        else:
            head = line.split()
            comm, period = head[0], int(head[-1]) if head[-1].isdigit() else 1
    if comm is not None:
        yield comm, period, frames

MECHANISMS = [
    ('GC mark', r'InMark|ParallelMark|gc::mark|RootSet'),
    ('GC sweep', r'Sweep|Finaliz'),
    ('allocation', r'alloc::|Allocat|AllocInstance|AllocArray|ObjectFactory'),
    ('kernel (page faults, futex, mmap)', r'^\[kernel\]$'),
    ('libc (memset/memcpy, malloc)', r'^\[unknown\]$|libc'),
    ('GoMap (Go maps)', r'runtime\.GoMap'),
    ('GoSlice / slices', r'runtime\.GoSlice|go\.slices'),
    ('strings', r'Kotlin_String|kotlin\.text|GoString|StringBuilder'),
    ('locks / park / safepoints', r'go\.sync|pthread|safePoint|Suspension|futex'),
    ('boxing / type checks', r'-box>|IsInstance|CheckInstance|Kotlin_Any|Int-box'),
]

def mechanism(sym):
    for name, rx in MECHANISMS:
        if re.search(rx, sym):
            return name
    if 'kfun:com.xemantic' in sym:
        return 'ported code (gen/ + runtime) self time'
    return 'other runtime'

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('script')
    ap.add_argument('--top', type=int, default=30)
    ap.add_argument('--callers')
    ap.add_argument('--mechanisms', action='store_true')
    a = ap.parse_args()
    total = 0
    self_ = collections.Counter()
    incl = collections.Counter()
    callers = collections.Counter()
    mech = collections.Counter()
    for comm, w, fr in stacks(a.script):
        if not fr:
            continue
        total += w
        self_[short(fr[0])] += w
        mech[mechanism(fr[0])] += w
        for f in set(short(x) for x in fr):
            incl[f] += w
        if a.callers:
            for i, f in enumerate(fr):
                if a.callers in f:
                    callers[short(fr[i + 1]) if i + 1 < len(fr) else '<root>'] += w
                    break
    pct = lambda n: f'{100.0 * n / total:6.2f}%'
    print(f'cycles: {total}')
    if a.mechanisms:
        print('\n== self time by mechanism ==')
        for k, v in mech.most_common():
            print(pct(v), k)
    if a.callers:
        print(f'\n== callers of *{a.callers}* (inclusive) ==')
        for k, v in callers.most_common(a.top):
            print(pct(v), k)
        return
    print('\n== self ==')
    for k, v in self_.most_common(a.top):
        print(pct(v), k)
    print('\n== inclusive ==')
    for k, v in incl.most_common(a.top):
        print(pct(v), k)

if __name__ == '__main__':
    main()
