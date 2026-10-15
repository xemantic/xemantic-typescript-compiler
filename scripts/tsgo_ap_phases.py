#!/usr/bin/env python3
"""(docs/goport-perf.md § 29) An async-profiler COLLAPSED profile split by compiler phase.

    scripts/tsgo_ap_phases.py warm-cpu.collapsed cpu check-bench   # + the check phase by owner of the leaf
    scripts/tsgo_ap_phases.py warm-alloc.collapsed alloc           # + top allocated classes per phase

A sample with a parser/scanner frame is parse, else with a binder frame bind, else with a checker frame check.
"""
import sys,re,collections
P='com/xemantic/typescript/tsgo/'
def pkg(f):
    if not f.startswith(P): return None
    return f[len(P):].split('.')[0].rsplit('/',1)[0] if '/' in f[len(P):] else f[len(P):]
mode=sys.argv[2]  # cpu|alloc
thread=sys.argv[3] if len(sys.argv)>3 else None
phase=collections.Counter(); chk=collections.Counter(); cls=collections.defaultdict(collections.Counter); tot=0
for line in open(sys.argv[1]):
    stack,_,n=line.rstrip('\n').rpartition(' '); n=int(n)
    fr=[re.sub(r'_\[.\]$','',f) for f in stack.split(';')]
    if thread:
        if not fr or thread not in fr[0]: continue
    tot+=n
    pk=[pkg(f) for f in fr]
    if any(p in ('parser','scanner') for p in pk if p): ph='parse'
    elif 'binder' in pk: ph='bind'
    elif 'checker' in pk: ph='check'
    elif any(p and p.startswith('printer') or p=='transformers' or p=='emitter' for p in pk): ph='emit'
    else:
        last=[p for p in pk if p]; ph='other:'+(last[-1] if last else 'none')
    phase[ph]+=n
    leaf=fr[-1]
    if mode=='alloc':
        c=leaf.replace('com.xemantic.typescript.tsgo.','').replace('_[k]','').replace('_[i]','')
        cls[ph][c]+=n
    if ph=='check' and mode=='cpu':
        # nearest ported frame owning the leaf
        own=next((f for f in reversed(fr) if f.startswith(P)),'')
        op=pkg(own) or 'none'
        if 'stub' in leaf: cat='vm dispatch stub'
        elif 'LinkStore' in stack.split(';')[-1] or any('GoLinkTable' in f or 'LinkStore' in f for f in fr[-4:]): cat='node/symbol side tables (LinkStore)'
        elif op=='ast': cat='AST accessors/walks (ast pkg)'
        elif op=='checker': cat='checker code (own frame)'
        elif op.startswith('runtime'): cat='runtime:'+own[len(P+'runtime/'):].split('.')[0]
        else: cat='other:'+op
        chk[cat]+=n
print('total',tot)
for k,v in phase.most_common(15): print(f"{v*100/tot:6.2f}% {k}")
if chk:
    t=sum(chk.values()); print('-- check phase by owner, % of check')
    for k,v in chk.most_common(20): print(f"{v*100/t:6.2f}% {k}")
if cls:
    for ph in ('parse','bind','check'):
        t=sum(cls[ph].values()); print(f'-- {ph} alloc {t*100/tot:.1f}% top classes (% of phase)')
        for k,v in cls[ph].most_common(14): print(f"   {v*100/t:6.2f}% {k}")
