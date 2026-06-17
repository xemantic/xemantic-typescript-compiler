#!/usr/bin/env python3
"""Find failing errors-tests that are PURE over-emission: every diagnostic line the
baseline expects we ALSO produce, and our only diff is EXTRA diagnostic lines (FPs).
Suppressing those FPs flips the test. Ranks by number of extra FP lines (fewest first).

Also a second class: tests where the baseline has FEWER total errors than us and the
expected lines are a strict subset of ours (pure FP cascade).

Usage: python3 scripts/pure_fp.py [N]
"""
import glob, re, html, sys

N = int(sys.argv[1]) if len(sys.argv) > 1 else 30


def code_lines(lines):
    # diagnostic header lines like `file.ts(1,2): error TSxxxx: ...`
    return [l for l in lines if re.search(r'\): error TS\d', l)]


rows = []
for f in glob.glob('build/test-results/jvmTest/*.xml'):
    data = open(f, encoding='utf-8', errors='replace').read()
    for part in data.split('<testcase ')[1:]:
        nm = re.match(r'name="([^"]+)"', part)
        if not nm:
            continue
        name = nm.group(1)
        if 'expected errors' not in name:
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
        exp_codes = set(code_lines(minus))   # expected-only header lines
        our_codes = set(code_lines(plus))    # our-only header lines
        # PURE FP: no expected header line is missing from ours -> expected ⊆ ours
        # i.e. the '-' side has NO diagnostic header lines (only context/squiggle removed)
        if exp_codes:
            continue  # baseline expects some header line we don't produce -> not pure FP
        if not our_codes:
            continue  # no extra header -> structural-only diff
        base = re.sub(r'_ts(__.*)?$', '', name.split(' ')[0])
        rows.append((len(our_codes), base, sorted(our_codes)[:5]))

rows.sort()
seen = set()
shown = 0
for n, base, extras in rows:
    if base in seen:
        continue
    seen.add(base)
    print(f"=== +{n} FP  {base}")
    for e in extras:
        print("   +", e[:150])
    shown += 1
    if shown >= N:
        break
print(f"\n(pure-FP candidates: {len(seen)})")
