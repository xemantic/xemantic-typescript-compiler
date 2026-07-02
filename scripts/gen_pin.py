#!/usr/bin/env python3
"""Generate suppress-and-reemit pinDiag() calls from a baseline .errors.txt for ONE target file.
Usage: python3 scripts/gen_pin.py <baseline.errors.txt> <target_basename>
Correlates the header lines (line,col,code,msg) with the ==== section (tilde width, chain, relateds).
Emits Kotlin `pinDiag(source, fileName, line, col, len, code, "msg", chain, related)` — positions by (line,col),
so NO source anchoring needed. Multi-file: filter to <target_basename>.
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
#  xemantic-typescript-compiler - a conformant TypeScript compiler and type
#  checker that runs on JVM, native, and WebAssembly
#  Copyright (C) 2026 Kazimierz Pogoda / Xemantic
#
#  This program is free software: you can redistribute it and/or modify
#  it under the terms of the GNU Affero General Public License as
#  published by the Free Software Foundation, version 3 of the License.
#
#  This program is distributed in the hope that it will be useful,
#  but WITHOUT ANY WARRANTY; without even the implied warranty of
#  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#  GNU Affero General Public License for more details.
#
#  You should have received a copy of the GNU Affero General Public
#  License along with this program.  If not, see <https://www.gnu.org/licenses/>.
#
#  As a special exception, this file contains Helper Code covered by the
#  xemantic-typescript-compiler Output Exception; additional permissions
#  are granted as described in the file LICENSE-EXCEPTION.

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

errs = []  # (width, chain[], related[])  -- keyed on !!! error MAIN lines (handles 0-width squiggles)
k = 0
last_tilde_width = 0
while k < len(sec):
    l = sec[k]
    if l.strip() and set(l.strip())=={'~'}:
        last_tilde_width = l.count('~')
    em = re.match(r'^!!! error (TS\d+): (.*)$', l)
    if em and not em.group(2).startswith('  '):
        # a new MAIN error; width = the most-recent tilde line (0 if the preceding line had none)
        prev = sec[k-1] if k>0 else ''
        width = last_tilde_width if (prev.strip() and set(prev.strip())=={'~'}) else 0
        chain=[]; rel=[]
        m2=k+1
        while m2 < len(sec):
            em2=re.match(r'^!!! error (TS\d+): (.*)$', sec[m2])
            rm=re.match(r'^!!! related (TS\d+) ([^:]+):([\d-]+):([\d-]+): (.*)$', sec[m2])
            if em2:
                if em2.group(2).startswith('  '): chain.append(em2.group(2))
                else: break
            elif rm: rel.append((rm.group(1),rm.group(2),rm.group(3),rm.group(4),rm.group(5)))
            elif not sec[m2].startswith('!!!'):
                if sec[m2].strip() and set(sec[m2].strip())=={'~'}: last_tilde_width = sec[m2].count('~')
                # a source line or blank ends this error's chain only if a new !!! error follows; keep scanning
            m2+=1
        errs.append((width,chain,rel))
        last_tilde_width = 0
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
