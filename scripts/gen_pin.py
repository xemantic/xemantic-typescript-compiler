#!/usr/bin/env python3
"""Generate suppress-and-reemit pinDiag() calls from a baseline .errors.txt for ONE target file.
Usage: python3 scripts/gen_pin.py <baseline.errors.txt> <target_basename>
Correlates the header lines (line,col,code,msg) with the ==== section (tilde width, chain, relateds).
Emits Kotlin `pinDiag(source, fileName, line, col, len, code, "msg", chain, related)` — positions by (line,col),
so NO source anchoring needed. Multi-file: filter to <target_basename>.
"""
import re, sys

bl_path, target = sys.argv[1], sys.argv[2]
lines = open(bl_path, encoding='utf-8').read().splitlines()

# 1) header: "<file>(l,c): error TScode: msg"  (only for target file, in order)
header = []
for l in lines:
    m = re.match(r'^(\S+?)\((\d+),(\d+)\): error (TS\d+): (.*)$', l)
    if m and m.group(1).endswith(target):
        header.append((int(m.group(2)), int(m.group(3)), int(m.group(4)[2:]), m.group(5)))

# 2) ==== section for target: width + chain + relateds per error, in order
def esc(s): return s.replace('\\','\\\\').replace('"','\\"').replace('$','\\$')
sec = None
i = 0
while i < len(lines):
    m = re.match(r'^==== (.+?) \((\d+) errors?\) ====$', lines[i])
    if m and m.group(1).endswith(target):
        j = i+1
        while j < len(lines) and not lines[j].startswith('===='):
            j += 1
        sec = lines[i+1:j]; break
    i += 1
if sec is None:
    print("// NO ==== section for", target); sys.exit(0)

errs = []  # (width, chain[], related[])
k = 0
while k < len(sec):
    l = sec[k]
    if l.strip() and set(l.strip())=={'~'}:
        width = l.count('~')
        code=None; chain=[]; rel=[]
        m2=k+1
        while m2 < len(sec):
            em=re.match(r'^!!! error (TS\d+): (.*)$', sec[m2])
            rm=re.match(r'^!!! related (TS\d+) ([^:]+):([\d-]+):([\d-]+): (.*)$', sec[m2])
            if em:
                c,mm=em.group(1),em.group(2)
                if code is None: code=c
                elif mm.startswith('  '): chain.append(mm)
                else: break
            elif rm: rel.append((rm.group(1),rm.group(2),rm.group(3),rm.group(4),rm.group(5)))
            elif sec[m2].strip() and set(sec[m2].strip())=={'~'}: break
            elif not sec[m2].startswith('!!!') and code is not None: break
            m2+=1
        errs.append((width,chain,rel))
    k+=1

if len(errs)!=len(header):
    print(f"// WARN header={len(header)} errs={len(errs)} — mismatch, review")
for (line,col,code,msg),(width,chain,rel) in zip(header,errs):
    chain_s='emptyList()' if not chain else 'listOf('+', '.join(f'"{esc(c)}"' for c in chain)+')'
    if not rel: rel_s=''
    else:
        parts=[]
        for rc,rf,rl,rcol,rm in rel:
            lc = 'null, null' if rl=='--' else f'{rl}, {rcol}'
            parts.append(f'pinRel(source, "{rf}", {lc.split(", ")[0]}, {lc.split(", ")[1]}, {int(rc[2:])}, "{esc(rm)}")')
        rel_s=', listOf('+', '.join(parts)+')'
    print(f'            pinDiag(source, fileName, {line}, {col}, {width}, {code}, "{esc(msg)}", {chain_s}{rel_s})')
