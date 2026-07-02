#!/usr/bin/env python3
"""Rank failing errors-tests by how FEW EXTRA (FP) diagnostic header lines we emit, and
show the missing count too. A test with few extras + zero missing flips by pure
suppression; few extras + few missing may flip with a suppress + small add. Codes shown.

Usage: python3 scripts/over_emit.py [N]
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


def codeset(hdrs):
    return sorted(set(re.search(r'error (TS\d+)', l).group(1) for l in hdrs))


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
        exp_h = headers(minus)
        our_h = headers(plus)
        if not our_h:
            continue  # no extras -> not over-emit
        base = re.sub(r'_ts(__.*)?$', '', nm.group(1).split(' ')[0])
        rows.append((len(our_h), len(exp_h), base, codeset(our_h), our_h[:3]))

rows.sort()
seen = set()
shown = 0
for nex, nmiss, base, codes, sample in rows:
    if base in seen:
        continue
    seen.add(base)
    print(f"=== +{nex}extra -{nmiss}miss  {base}  FP:[{','.join(codes)}]")
    for l in sample:
        print("   +", l[:135])
    shown += 1
    if shown >= N:
        break
print(f"\n(over-emit candidates: {len(seen)})")
