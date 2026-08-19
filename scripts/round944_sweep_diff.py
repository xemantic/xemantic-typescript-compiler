#!/usr/bin/env python3
"""Difference two `pristine_sweep.py` JSONs: ours-only rows, pristine-only rows, and the
per-fixture verdicts in BOTH directions.

Round 941's lesson is why the pristine-only column is printed at all: a fix that removes
an ours-only row by disabling a check also removes TRUE POSITIVES, and only the
pristine-only column shows it.
"""
import json, sys

b = json.load(open(sys.argv[1])); a = json.load(open(sys.argv[2]))
print(f"sweep ours-only rows: {b['total_ours_only_rows']} -> {a['total_ours_only_rows']}")
print(f"sweep fixtures with ours-only: {b['fixtures_with_ours_only']} -> {a['fixtures_with_ours_only']}")
pb = sum(v['pristine_only'] for v in b['results'].values())
pa = sum(v['pristine_only'] for v in a['results'].values())
print(f"sweep pristine-only rows: {pb} -> {pa}")
print(f"ran: {b['ran']} -> {a['ran']}   skipped: {b['skipped_no_source']} -> {a['skipped_no_source']}")

worse = {k: (len(b['results'].get(k, {'ours_only': []})['ours_only']), len(a['results'][k]['ours_only']))
         for k in a['results']
         if len(a['results'][k]['ours_only']) > len(b['results'].get(k, {'ours_only': []})['ours_only'])}
print("FIXTURES REGRESSED (ours-only UP):", worse or "none")
for k in worse:
    old = {tuple(r) for r in b['results'].get(k, {'ours_only': []})['ours_only']}
    for r in a['results'][k]['ours_only']:
        if tuple(r) not in old:
            print("   NEW ROW", k, r)
gone = [k for k in b['results']
        if len(b['results'][k]['ours_only']) > len(a['results'].get(k, {'ours_only': []})['ours_only'])]
for k in sorted(gone):
    old = {tuple(r) for r in b['results'][k]['ours_only']}
    new = {tuple(r) for r in a['results'].get(k, {'ours_only': []})['ours_only']}
    print(f"  improved {k}: {len(old)} -> {len(new)}")
    for r in sorted(old - new):
        print("     GONE", r)
up = [(k, b['results'].get(k, {'pristine_only': 0})['pristine_only'], a['results'][k]['pristine_only'])
      for k in a['results']
      if a['results'][k]['pristine_only'] > b['results'].get(k, {'pristine_only': 0})['pristine_only']]
print("PRISTINE-ONLY UP (a true positive LOST):", up or "none")
down = [(k, b['results'][k]['pristine_only'], a['results'][k]['pristine_only'])
        for k in a['results']
        if k in b['results'] and a['results'][k]['pristine_only'] < b['results'][k]['pristine_only']]
print("PRISTINE-ONLY DOWN (a true positive GAINED):", down or "none")
