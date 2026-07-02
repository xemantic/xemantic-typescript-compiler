#!/usr/bin/env python3
"""Find failing errors-tests that are PURE under-emission: we produce NO wrong/extra
diagnostic header line; the only diff is EXPECTED header lines we DON'T produce. Flipping
these means ADDING the missing diagnostic(s). Ranks by (#distinct missing codes, #missing
lines) — fewest first — and shows the missing codes so single-code-family misses surface.

Usage: python3 scripts/pure_missing.py [N]
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

import glob, re, html, sys

N = int(sys.argv[1]) if len(sys.argv) > 1 else 40


def headers(lines):
    return [l for l in lines if re.search(r'\): error TS\d', l)]


rows = []
for f in glob.glob('build/test-results/jvmTest/*.xml'):
    data = open(f, encoding='utf-8', errors='replace').read()
    for part in data.split('<testcase ')[1:]:
        nm = re.match(r'name="([^"]+)"', part)
        if not nm or 'expected errors' not in nm.group(1):
            continue
        body = part.split('</testcase>')[0]
        if '<failure' not in body:
            continue
        fm = re.search(r'<failure[^>]*>(.*?)</failure>', body, re.S)
        if not fm:
            continue
        txt = html.unescape(fm.group(1))
        minus = [l[1:] for l in txt.splitlines() if l.startswith('-') and not l.startswith('---')]
        plus = [l[1:] for l in txt.splitlines() if l.startswith('+') and not l.startswith('+++')]
        exp_h = headers(minus)   # expected-only header lines (we MISS these)
        our_h = headers(plus)    # our-only header lines (we WRONGLY emit these)
        if our_h:
            continue  # we emit something wrong -> not pure-missing
        if not exp_h:
            continue
        codes = sorted(set(re.search(r'error (TS\d+)', l).group(1) for l in exp_h))
        base = re.sub(r'_ts(__.*)?$', '', nm.group(1).split(' ')[0])
        rows.append((len(codes), len(exp_h), base, codes, exp_h[:4]))

rows.sort()
seen = set()
shown = 0
for ncodes, nlines, base, codes, sample in rows:
    if base in seen:
        continue
    seen.add(base)
    print(f"=== {ncodes}code {nlines}miss  {base}  [{','.join(codes)}]")
    for l in sample:
        print("   -", l[:140])
    shown += 1
    if shown >= N:
        break
print(f"\n(pure-missing candidates: {len(seen)})")
