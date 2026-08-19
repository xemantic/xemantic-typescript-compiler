#!/usr/bin/env bash
# Round 943 — the 8-profile `--listAll` grid AND the PRISTINE 630-fixture sweep for the
# AFTER arm, differenced against round 942's own captures.
#
# WHY NO BEFORE BUILD: round 942's `after2` captures and `sweep.after2.json` were produced
# by a source file that is BYTE-IDENTICAL to this round's BEFORE (both sha256
# 6eda7d97e06bbe68b7dbd0e64e491cd5ac5a2d0510338e669778d4e1885ee793 -- asserted below, and
# the assertion is what makes the reuse sound: a capture is a deterministic function of
# (binary, input), so a hash-equal source is a stronger provenance claim than a rebuild).
# Round 942's own script reused its before sweep on exactly this argument.
#
# Profiles are enumerated by the presence of a `tsconfig.json` and the run REFUSES below 8
# (CLAUDE.md: every committed "8-profile grid" before round 895 was a ONE-profile grid).
# RUNTIME ~35 MIN.  Usage: scripts/round943-grid.sh
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler

CK=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
BEFORE_SRC=build/bench/round942/Checker.after.kt
BEFORE_DIR=build/bench/round942-grid
BEFORE_SWEEP=$BEFORE_DIR/sweep.after2.json
OUT=build/bench/round943-grid
MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
mkdir -p "$OUT"; rm -f "$OUT/DONE"

HEAD_SHA="$(git show HEAD:$CK | sha256sum | cut -d' ' -f1)"
BEFORE_SHA="$(sha256sum "$BEFORE_SRC" | cut -d' ' -f1)"
AFTER_SHA="$(sha256sum "$CK" | cut -d' ' -f1)"
echo "HEAD sha256   $HEAD_SHA"
echo "before sha256 $BEFORE_SHA  ($BEFORE_SRC, the source that produced the .after2 captures)"
echo "after  sha256 $AFTER_SHA   (the working tree)"
[[ "$HEAD_SHA" == "$BEFORE_SHA" ]] || { echo "REFUSED: the stored before-arm source is not HEAD"; exit 1; }
[[ "$AFTER_SHA" != "$BEFORE_SHA" ]] || { echo "REFUSED: the two arms are the same file"; exit 1; }

shopt -s nullglob
profiles=()
for d in build/bench/tsc-*; do [[ -d "$d" && -f "$d/tsconfig.json" ]] && profiles+=("$d"); done
[[ "${#profiles[@]}" -ge 8 ]] || { echo "REFUSED: only ${#profiles[@]} profile(s)"; exit 1; }
echo "profiles: ${#profiles[@]}"

DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
CP="$MAIN:$DEPS"
[[ -f "$MAIN/com/xemantic/typescript/compiler/MainKt.class" ]] || { echo "REFUSED: no MainKt"; exit 1; }

status=0
for proj in "${profiles[@]}"; do
  name="$(basename "$proj")"
  java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
    --noEmit --listAll "$proj" > "$OUT/$name.after.raw" 2>&1
  grep -a 'error TS' "$OUT/$name.after.raw" | sort > "$OUT/$name.after.txt"
  grep -qa 'more error(s)' "$OUT/$name.after.raw" && { echo "REFUSED $name: truncated"; status=1; }
  [[ -s "$OUT/$name.after.txt" ]] || { echo "REFUSED $name: empty"; status=1; }
  [[ -s "$BEFORE_DIR/$name.after2.txt" ]] || { echo "REFUSED $name: no before capture"; status=1; }
done

python3 scripts/pristine_sweep.py --classes "$MAIN" \
   --out "$OUT/sweep.after.json" --work build/bench/round943-sweep-after \
   > "$OUT/sweep.after.txt" 2>&1
echo "sweep(after): $(tail -1 "$OUT/sweep.after.txt")"

echo "=== 8-profile diff ==="
for proj in "${profiles[@]}"; do
  name="$(basename "$proj")"
  added=$(comm -13 "$BEFORE_DIR/$name.after2.txt" "$OUT/$name.after.txt" | wc -l)
  removed=$(comm -23 "$BEFORE_DIR/$name.after2.txt" "$OUT/$name.after.txt" | wc -l)
  echo "$name: added=$added removed=$removed total=$(wc -l < "$OUT/$name.after.txt")"
  comm -3 "$BEFORE_DIR/$name.after2.txt" "$OUT/$name.after.txt" | head -20
done

python3 - "$BEFORE_SWEEP" "$OUT/sweep.after.json" <<'EOF'
import json, sys
b = json.load(open(sys.argv[1])); a = json.load(open(sys.argv[2]))
print(f"sweep ours-only rows: {b['total_ours_only_rows']} -> {a['total_ours_only_rows']}")
print(f"sweep fixtures with ours-only: {b['fixtures_with_ours_only']} -> {a['fixtures_with_ours_only']}")
pb = sum(v['pristine_only'] for v in b['results'].values())
pa = sum(v['pristine_only'] for v in a['results'].values())
print(f"sweep pristine-only rows: {pb} -> {pa}")
worse = {k: (len(b['results'].get(k, {'ours_only': []})['ours_only']), len(a['results'][k]['ours_only']))
         for k in a['results']
         if len(a['results'][k]['ours_only']) > len(b['results'].get(k, {'ours_only': []})['ours_only'])}
print("FIXTURES REGRESSED (ours-only UP):", worse or "none")
for k, v in worse.items():
    old = {tuple(r) for r in b['results'].get(k, {'ours_only': []})['ours_only']}
    for r in a['results'][k]['ours_only']:
        if tuple(r) not in old: print("   NEW ROW", k, r)
gone = [k for k in b['results']
        if len(b['results'][k]['ours_only']) > len(a['results'].get(k, {'ours_only': []})['ours_only'])]
for k in sorted(gone):
    print(f"  improved {k}: {len(b['results'][k]['ours_only'])} -> {len(a['results'][k]['ours_only'])}")
up = [k for k in a['results'] if a['results'][k]['pristine_only'] > b['results'].get(k, {'pristine_only': 0})['pristine_only']]
print("PRISTINE-ONLY UP (a true positive LOST):", up or "none")
EOF
echo "$status" > "$OUT/DONE"
exit $status
