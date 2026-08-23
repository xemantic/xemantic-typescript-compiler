#!/usr/bin/env python3
"""(INC.18) Compose the partition-gate FIXTURE from the miner's output.

`PassDiagMineMain` records, for every single-file conformance case compiled under
the fixture's OWN options, which checker passes NET a diagnostic. This performs a
greedy maximum-coverage selection over that record and writes the chosen cases out
as one project file each.

The selection objective is DISTINCT PASSES, which is the receipt (INC.18) is graded
by: on tsc's own 78 sources exactly ONE pass (`checkSpine`) nets anything, so the
partition gate compares an essentially empty population there.

Every emitted file is made a MODULE (`export {}` when it declares nothing exported),
because the fixture is ONE program: two script-scope files both declaring `class C`
would collide and the fixture's rows would be duplicate-identifier noise rather than
the walkers it was selected for.
"""
import os, re, sys, hashlib

MINE = sys.argv[1]
CASES = sys.argv[2]
OUT = sys.argv[3]
MAX_FILES = int(sys.argv[4]) if len(sys.argv) > 4 else 120
# `checkSpine` fires on every program that has any diagnostic at all; selecting for
# it would rank cases by nothing.
UBIQUITOUS = {"checkSpine"}

rows = []
for line in open(MINE):
    parts = line.rstrip("\n").split("\t")
    if len(parts) != 4 or parts[0] != "CASE":
        continue
    rel, ndiag, passes = parts[1], int(parts[2]), set(parts[3].split(","))
    rows.append((rel, ndiag, passes))

# Rank: fewest diagnostics first among equal coverage — a case that nets one row
# from one walker is a cleaner fixture file than one that nets forty from a cascade.
covered = set()
chosen = []
while len(chosen) < MAX_FILES:
    best = None
    for rel, ndiag, passes in rows:
        gain = len((passes - UBIQUITOUS) - covered)
        if gain == 0:
            continue
        key = (-gain, ndiag, len(rel), rel)
        if best is None or key < best[0]:
            best = (key, rel, ndiag, passes)
    if best is None:
        break
    _, rel, ndiag, passes = best
    chosen.append((rel, ndiag, sorted(passes - UBIQUITOUS)))
    covered |= (passes - UBIQUITOUS)
    rows = [r for r in rows if r[0] != rel]

os.makedirs(OUT, exist_ok=True)
MODULE_RE = re.compile(r"^\s*(export|import)\b", re.M)
DIRECTIVE_RE = re.compile(r"^\s*//\s*@", re.M)

manifest = []
for i, (rel, ndiag, passes) in enumerate(chosen):
    raw = open(os.path.join(CASES, rel), encoding="utf-8", errors="replace").read()
    body = "\n".join(
        l for l in raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        if not DIRECTIVE_RE.match(l)
    ).strip("\n")
    stem = re.sub(r"[^A-Za-z0-9]", "_", os.path.splitext(os.path.basename(rel))[0])
    name = "c%03d_%s.ts" % (i, stem[:48])
    text = ("// FIXTURE SOURCE: tests/cases/%s\n"
            "// selected for: %s\n" % (rel, ", ".join(passes))) + body + "\n"
    if not MODULE_RE.search(body):
        text += "\nexport {};\n"
    open(os.path.join(OUT, name), "w", encoding="utf-8").write(text)
    manifest.append((name, rel, passes))

print("selected %d files covering %d distinct non-ubiquitous passes"
      % (len(chosen), len(covered)))
for name, rel, passes in manifest:
    print("FILE %s <- %s : %s" % (name, rel, ",".join(passes)))
