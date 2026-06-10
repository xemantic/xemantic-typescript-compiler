#!/usr/bin/env python3
"""Round-138 miner: rank ALL failing errors-tests by raw expected-vs-actual diff size
from the test-result XMLs — INDEPENDENT of find_candidates.py's <=3-code window, so it
sees multi-code diffs the bucket view hides. Excludes any base name mentioned anywhere
in PLAN-PHASE-4.md OR PLAN-PHASE-4-HISTORY.md (one mention = previously triaged; session
notes and the skip log were archived to the history file 2026-06-10, so both must be
consulted; check the plan/history before re-investigating a shown candidate anyway —
variant suffixes can slip the filter).

Usage: python3 scripts/mine_small_diffs.py [N]   (N = how many to show, default 25)
Run AFTER a clean full suite (needs fresh build/test-results/jvmTest/*.xml).
"""
import glob, re, html, sys

N = int(sys.argv[1]) if len(sys.argv) > 1 else 25
plan = open('PLAN-PHASE-4.md', encoding='utf-8').read()
try:
    plan += open('PLAN-PHASE-4-HISTORY.md', encoding='utf-8').read()
except OSError:
    pass
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
        rows.append((delta, base, kind, base in plan, minus[:4], plus[:4]))

rows.sort()
shown = 0
seen = set()
for delta, base, kind, mentioned, minus, plus in rows:
    if mentioned or kind != 'errors' or delta == 0 or base in seen:
        continue
    seen.add(base)
    print(f"=== D{delta} {base}")
    for l in minus:
        print("  ", l[:135])
    for l in plus:
        print("  ", l[:135])
    shown += 1
    if shown >= N:
        break
print(f"\n(total failing: {len(rows)}; D0 entries are 'none produced' or crash-style failures)")
