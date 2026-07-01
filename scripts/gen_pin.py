#!/usr/bin/env python3
"""Generate suppress-and-reemit walker emit-blocks from a baseline .errors.txt.
Usage: python3 scripts/gen_pin.py <baseline.errors.txt> <raw_source.ts>
Parses the ==== section for each error's (code, message, chain[], related[], squiggle token+width),
finds a UNIQUE anchor in the raw source, and emits Kotlin `pin(anchor, off, len, code, msg, chain, related)` calls.
Multi-file baselines: pass the source whose errors you want; the script filters by the source's basename.
"""
import re, sys, html

bl_path, src_path = sys.argv[1], sys.argv[2]
src = open(src_path, encoding='utf-8').read()
src_base = src_path.split('/')[-1]
lines = open(bl_path, encoding='utf-8').read().splitlines()

# Split into per-file ==== sections; keep the target file's section.
# Header lines: "file(l,c): error TScode: msg"
# ==== section: source lines (4-space indented) + squiggle lines + "!!! error/related" lines.
# Strategy: walk the ==== section, tracking the current source line; when a squiggle line appears,
# the preceding source line + squiggle col/width give the token; the following !!! error line gives code+msg;
# subsequent "!!! error TScode:  " (indented msg) give chain; "!!! related" give relateds.

# Find each "==== <file> (N errors) ====" block
blocks = []
i = 0
while i < len(lines):
    m = re.match(r'^==== (.+?) \((\d+) errors?\) ====$', lines[i])
    if m:
        fname = m.group(1)
        j = i+1
        while j < len(lines) and not lines[j].startswith('===='):
            j += 1
        blocks.append((fname, lines[i+1:j]))
        i = j
    else:
        i += 1

def find_anchor(token):
    """Return (anchor, offset) where anchor is a UNIQUE src substring containing token."""
    idxs = [m.start() for m in re.finditer(re.escape(token), src)]
    if len(idxs) == 1:
        return token, 0
    # grow window around the FIRST occurrence's token until unique (prefer extending right, then left)
    for total in range(1, 400):
        for left in range(0, total+1):
            right = total - left
            pos = idxs[0]
            cand = src[pos-left:pos+len(token)+right]
            if src.count(cand) == 1:
                return cand, cand.index(token) if token in cand else left
    return token, 0

def kesc(s): return s.replace('\\','\\\\').replace('"','\\"').replace('$','\\$')

for fname, body in blocks:
    if src_base not in fname: continue
    print(f"// ==== {fname} ====")
    k = 0
    while k < len(body):
        l = body[k]
        # squiggle line?
        if l.strip() and set(l.strip()) == {'~'}:
            width = l.count('~')
            col = len(l) - len(l.lstrip())  # 0-based col of first ~ (incl 4-space indent)
            srcline = body[k-1]
            token = srcline[col:col+width]
            # gather following !!! error (main) + chain + related
            code=None; msg=None; chain=[]; rel=[]
            m2 = k+1
            while m2 < len(body):
                em = re.match(r'^!!! error (TS\d+): (.*)$', body[m2])
                rm = re.match(r'^!!! related (TS\d+) ([^:]+):([\d-]+):([\d-]+): (.*)$', body[m2])
                if em:
                    c, mm = em.group(1), em.group(2)
                    if code is None:
                        code, msg = c, mm
                    elif mm.startswith('  '):
                        chain.append(mm)
                    else:
                        break  # next error's main
                elif rm:
                    rel.append((rm.group(1), rm.group(2), rm.group(3), rm.group(4), rm.group(5)))
                elif body[m2].strip() and set(body[m2].strip())=={'~'}:
                    break
                elif re.match(r'^!!!', body[m2]):
                    pass
                else:
                    if code is not None and not body[m2].startswith('!!!'):
                        break
                m2 += 1
            anchor, off = find_anchor(token)
            chain_s = 'emptyList()' if not chain else 'listOf(' + ', '.join(f'"{kesc(c)}"' for c in chain) + ')'
            rel_s = 'emptyList()' if not rel else 'listOf(' + ', '.join(f'relAt("REL:{kesc(rc[4])}", {rc[0]}, "{rc[1]}", {rc[2]}, {rc[3]})' for rc in rel) + ')'
            print(f'  pin("{kesc(anchor)}", {off}, {width}, {int(code[2:])}, "{kesc(msg)}", {chain_s}, {rel_s}) // {token!r}')
        k += 1
