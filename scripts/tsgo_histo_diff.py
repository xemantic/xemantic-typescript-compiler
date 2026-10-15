#!/usr/bin/env python3
"""(docs/goport-perf.md § 29) What a phase leaves live: the difference of two `CheckBenchMain … histo=` histograms.

    scripts/tsgo_histo_diff.py <before.txt> <after.txt> [top=40]
"""
import sys,re,collections
def load(p):
    d={}
    for l in open(p):
        m=re.match(r'\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)',l)
        if m: d[m.group(3)]=(int(m.group(1)),int(m.group(2)))
    return d
a=load(sys.argv[1]); b=load(sys.argv[2]); top=int(sys.argv[3]) if len(sys.argv)>3 else 40
rows=[(k,b.get(k,(0,0))[0]-a.get(k,(0,0))[0],b.get(k,(0,0))[1]-a.get(k,(0,0))[1]) for k in set(a)|set(b)]
rows.sort(key=lambda r:-r[2])
tot=sum(r[2] for r in rows); n=sum(r[1] for r in rows)
print(f"TOTAL {tot/1e6:.1f} MB {n:,} objects")
for k,c,s in rows[:top]: print(f"{s/1e6:9.1f} MB {s*100/tot:5.1f}% {c:>11,} {k.replace('com.xemantic.typescript.tsgo.','')}")
