#!/usr/bin/env bash
# Round 942 — the 8-profile `--listAll` grid AND the PRISTINE 630-fixture sweep, from
# sha256-VERIFIED on-disk snapshots. Modelled on `scripts/round941-grid.sh`.
#
# The BEFORE arm's sweep is REUSED rather than re-run: it was produced by this same HEAD
# `Checker.kt` (the sha256 printed below is the one that produced it) and a sweep is a
# deterministic function of (binary, fixture set). Pass its JSON as the third argument;
# omit it and the before sweep runs here as in round 941.
#
# NEVER `git checkout` to swap an arm (round 851). Every rewrite is in the FOREGROUND and
# the tree is verified restored to the AFTER source before exit.
# RUNTIME ~70 MIN. Run it detached and BLOCK on its marker file.
# Usage: scripts/round942-grid.sh <before-Checker.kt> <after-Checker.kt>
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler

BEFORE_SRC="${1:?usage: round942-grid.sh <before-Checker.kt> <after-Checker.kt>}"
AFTER_SRC="${2:?usage: round942-grid.sh <before-Checker.kt> <after-Checker.kt> [before-sweep.json]}"
BEFORE_SWEEP="${3:-}"
CK=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
OUT=build/bench/round942-grid
MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
mkdir -p "$OUT"
rm -f "$OUT/DONE"

BEFORE_SHA="$(sha256sum "$BEFORE_SRC" | cut -d' ' -f1)"
AFTER_SHA="$(sha256sum "$AFTER_SRC" | cut -d' ' -f1)"
echo "before sha256 $BEFORE_SHA"
echo "after  sha256 $AFTER_SHA"
[[ "$BEFORE_SHA" != "$AFTER_SHA" ]] || { echo "REFUSED: the two arms are the same file"; exit 1; }

shopt -s nullglob
profiles=()
for d in build/bench/tsc-*; do
  [[ -d "$d" && -f "$d/tsconfig.json" ]] && profiles+=("$d")
done
if [[ "${#profiles[@]}" -lt 8 ]]; then
  echo "REFUSED: only ${#profiles[@]} profile(s)"; exit 1
fi
echo "profiles: ${#profiles[@]}"

capture() {
  local arm="$1"
  DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; return 1; }
  local CP="$MAIN:$DEPS"
  [[ -f "$MAIN/com/xemantic/typescript/compiler/MainKt.class" ]] || {
    echo "REFUSED: no MainKt in $MAIN"; return 1; }
  local st=0
  for proj in "${profiles[@]}"; do
    local name; name="$(basename "$proj")"
    java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
      --noEmit --listAll "$proj" > "$OUT/$name.$arm.raw" 2>&1
    grep -a 'error TS' "$OUT/$name.$arm.raw" | sort > "$OUT/$name.$arm.txt"
    grep -qa 'more error(s)' "$OUT/$name.$arm.raw" && { echo "REFUSED $name/$arm: truncated"; st=1; }
    [[ -s "$OUT/$name.$arm.txt" ]] || { echo "REFUSED $name/$arm: empty"; st=1; }
  done
  return $st
}

sweep() {
  python3 scripts/pristine_sweep.py --classes "$MAIN" \
      --out "$OUT/sweep.$1.json" --work "build/bench/round942-sweep-$1" \
      > "$OUT/sweep.$1.txt" 2>&1
  echo "sweep($1): $(tail -1 "$OUT/sweep.$1.txt")"
}

build() {
  ./gradlew :xemantic-typescript-compiler-core:compileKotlinJvm > "$OUT/build.$1.log" 2>&1
  grep -qa 'BUILD SUCCESSFUL' "$OUT/build.$1.log" || { echo "BUILD FAILED ($1)"; return 1; }
}

status=0
echo "=== arm: before (HEAD) ==="
cp "$BEFORE_SRC" "$CK" || exit 1
build before || { cp "$AFTER_SRC" "$CK"; exit 1; }
capture before || status=1
if [[ -n "$BEFORE_SWEEP" && -f "$BEFORE_SWEEP" ]]; then
  cp "$BEFORE_SWEEP" "$OUT/sweep.before.json"
  echo "sweep(before): REUSED $BEFORE_SWEEP"
else
  sweep before
fi

echo "=== arm: after (round 942) ==="
cp "$AFTER_SRC" "$CK" || exit 1
build after || exit 1
capture after || status=1
sweep after

TREE_SHA="$(sha256sum "$CK" | cut -d' ' -f1)"
[[ "$TREE_SHA" == "$AFTER_SHA" ]] || { echo "REFUSED: tree not restored to AFTER"; status=1; }

echo "=== diff ==="
for proj in "${profiles[@]}"; do
  name="$(basename "$proj")"
  added=$(comm -13 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | wc -l)
  removed=$(comm -23 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | wc -l)
  n=$(wc -l < "$OUT/$name.after.txt")
  echo "$name: added=$added removed=$removed total=$n"
done
python3 - "$OUT/sweep.before.json" "$OUT/sweep.after.json" <<'EOF'
import json, sys
b = json.load(open(sys.argv[1])); a = json.load(open(sys.argv[2]))
print(f"sweep ours-only rows: {b['total_ours_only_rows']} -> {a['total_ours_only_rows']}")
print(f"sweep fixtures with ours-only: {b['fixtures_with_ours_only']} -> {a['fixtures_with_ours_only']}")
pb = sum(v['pristine_only'] for v in b['results'].values())
pa = sum(v['pristine_only'] for v in a['results'].values())
print(f"sweep pristine-only rows: {pb} -> {pa}")
worse = [k for k in a['results']
         if len(a['results'][k]['ours_only']) > len(b['results'].get(k, {'ours_only': []})['ours_only'])]
print("FIXTURES REGRESSED (ours-only up):", worse or "none")
gone = [k for k in b['results']
        if len(b['results'][k]['ours_only']) > len(a['results'].get(k, {'ours_only': []})['ours_only'])]
for k in sorted(gone):
    print(f"  improved {k}: {len(b['results'][k]['ours_only'])} -> {len(a['results'][k]['ours_only'])}")
up = [k for k in a['results'] if a['results'][k]['pristine_only'] > b['results'].get(k, {'pristine_only': 0})['pristine_only']]
print("PRISTINE-ONLY UP (a true positive LOST):", up or "none")
EOF
echo "$status" > "$OUT/DONE"
exit $status
