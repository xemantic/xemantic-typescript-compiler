#!/usr/bin/env python3
"""Aggregate a JFR CPU profile of ParseBenchMain's tsgo arm (docs/goport-perf.md).

    jfr print --events jdk.ExecutionSample --stack-depth 512 tsgo.jfr > samples.txt
    python3 scripts/tsgo_parse_profile.py samples.txt [thread] [stack-depth]

Only samples on the `parse-bench-deep-stack` thread count. Prints: self time by leaf
frame:line; self time charged to the nearest com.xemantic frame (a stdlib leaf belongs to its
caller); the same charged to the nearest PORTED frame (runtime/ and go/ shims belong to the
generated caller); inclusive time; and the mechanism families named in the doc. Refuses a dump
whose deepest stack reaches the --stack-depth cap (round 868's truncation trap).
"""
import collections, re, sys

def main(path, cap=512, thread='parse-bench-deep-stack'):
    evs = open(path).read().split('jdk.ExecutionSample {')[1:]
    fr = re.compile(r'^\s+([\w.$<>\-]+)\(.*?line: (\d+)', re.M)
    n = 0; maxd = 0
    leaf = collections.Counter(); owner = collections.Counter(); ported = collections.Counter()
    incl = collections.Counter(); fam = collections.Counter()
    families = [
        ('Arena.new: lazy zero node (embedded-struct chain) + per-New GoSlice', lambda j: 'ArenaKt.new' in j),
        ('scanASCIIWhile: Function1 predicate (megamorphic, boxed Int)', lambda j: 'scanASCIIWhile' in j),
        ('GoMap.lookup: containsKey + get + Tuple2', lambda j: 'GoMap.lookup' in j),
        ('GoMap.get', lambda j: 'GoMap.get' in j),
        ('overrideParentInImmediateChildren', lambda j: 'overrideParentInImmediateChildren' in j),
        ('findImportOrRequire: suffix slice + indexAny', lambda j: 'findImportOrRequire' in j),
        ('GoString.fromUtf16 (host text conversion)', lambda j: 'GoString.fromUtf16' in j),
        ('JSDoc parsing', lambda j: 'Jsdoc' in j),
        ('String copy (substring/copyOfRange)', lambda j: 'copyOfRange' in j or 'StringLatin1.newString' in j),
        ('goCopy', lambda j: 'goCopy' in j),
    ]
    for e in evs:
        if thread not in e:
            continue
        fs = [(m.group(1), m.group(2)) for m in fr.finditer(e)]
        if not fs:
            continue
        n += 1; maxd = max(maxd, len(fs))
        names = [f for f, _ in fs]; j = ' '.join(names)
        leaf[f'{fs[0][0]}:{fs[0][1]}'] += 1
        owner[next((f for f in names if f.startswith('com.xemantic')), None)] += 1
        ported[next((f for f in names if f.startswith('com.xemantic') and '.runtime.' not in f and '.tsgo.go.' not in f), None)] += 1
        for f in set(names):
            incl[f] += 1
        for k, p in families:
            if p(j):
                fam[k] += 1
    if maxd >= cap:
        sys.exit(f'refusing: a stack reached the --stack-depth cap ({cap}); re-print with a larger depth')
    print(f'samples {n}  max stack depth {maxd}')
    for title, c, k in [('self, leaf:line', leaf, 25), ('self -> nearest com.xemantic', owner, 25),
                        ('self -> nearest ported frame', ported, 25), ('inclusive', incl, 30),
                        ('mechanism families (inclusive)', fam, 20)]:
        print(f'== {title}')
        for key, v in c.most_common(k):
            print(f'{v * 100 / n:5.1f}% {key}')

if __name__ == '__main__':
    # optional 2nd argument: the bench thread (CheckBenchMain's is check-bench-deep-stack)
    # optional 3rd: the --stack-depth the dump was printed with (the check path recurses past 512)
    main(sys.argv[1], thread=sys.argv[2] if len(sys.argv) > 2 else 'parse-bench-deep-stack',
         cap=int(sys.argv[3]) if len(sys.argv) > 3 else 512)
