import json,statistics,sys,glob,os
d=sys.argv[1]
runs={os.path.basename(p)[:-5]:json.load(open(p)) for p in sorted(glob.glob(d+'/*.json'))}
def rows(r): return {(x['lib'],x['arm']):x for x in r['rows']}
print("run  placement(tsgo)  " )
for name,r in runs.items():
    print(name, r['placement']['tsgo'], 'kir:', r['placement']['kir'], 'equiv:', {l:v['verdict'] for l,v in r['equivalence'].items()})
keys=sorted(rows(next(iter(runs.values()))))
print(f"{'lib/arm':12} " + " ".join(f"{n:>22}" for n in runs))
for k in keys:
    cells=[]
    for n,r in runs.items():
        x=rows(r)[k]; s=x['samples']; cv=statistics.pstdev(s)/statistics.mean(s)*100
        cells.append(f"{x['median']:>6.0f} cv{cv:4.1f}% r{max(s)-min(s):>4}")
    print(f"{k[0]+'/'+k[1]:12} " + " ".join(f"{c:>22}" for c in cells))
