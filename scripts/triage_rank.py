#!/usr/bin/env python3
"""Rank ALL failing errors-tests by raw expected-vs-actual diff size, NO plan-mention filter
(after ~189 rounds every base name is mentioned somewhere). Tags each as NONE (we emit
nothing — baseline has only - lines we lack) vs CLOSE (we emit something). Optional substring
filter on argv to focus a family.

Usage: python3 scripts/triage_rank.py [N] [substr]
"""
import glob, re, html, sys

N = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 40
substr = None
for a in sys.argv[1:]:
    if not a.isdigit():
        substr = a
rows = []
for f in glob.glob('build/test-results/jvmTest/*.xml'):
    data = open(f, encoding='utf-8', errors='replace').read()
    for part in data.split('<testcase ')[1:]:
        nm = re.match(r'name="([^"]+)"', part)
        if not nm:
            continue
        body = part.split('</testcase>')[0]
        if '<failure' not in body:
            continue
        name = nm.group(1)
        fm = re.search(r'<failure[^>]*>(.*?)</failure>', body, re.S)
        if not fm:
            continue
        txt = html.unescape(fm.group(1))
        minus = [l for l in txt.splitlines() if l.startswith('-') and not l.startswith('---')]
        plus = [l for l in txt.splitlines() if l.startswith('+') and not l.startswith('+++')]
        delta = len(minus) + len(plus)
        base = re.sub(r'_ts(__.*)?$', '', name.split(' ')[0])
        kind = 'errors' if 'expected errors' in name else 'js'
        # NONE = our actual side has no diagnostic-bearing + lines (only structural)
        plus_codes = [l for l in plus if re.search(r'error TS\d', l)]
        tag = 'NONE' if not plus_codes else 'CLOSE'
        rows.append((delta, base, kind, tag, minus[:6], plus[:6]))

rows.sort()
shown = 0
seen = set()
for delta, base, kind, tag, minus, plus in rows:
    if kind != 'errors' or delta == 0 or base in seen:
        continue
    if substr and substr.lower() not in base.lower():
        continue
    seen.add(base)
    print(f"=== D{delta} [{tag}] {base}")
    for l in minus:
        print("  -", l[:140])
    for l in plus:
        print("  +", l[:140])
    shown += 1
    if shown >= N:
        break
print(f"\n(total failing errors-tests: {len([r for r in rows if r[2]=='errors'])})")
