#!/usr/bin/env bash
# Round 940 — the 8-profile `--listAll` output grid AND the PRISTINE 630-fixture sweep,
# both arms, from sha256-VERIFIED on-disk snapshots.
#
# Same contract as `scripts/round937-grid.sh` (which this is modelled on) with one
# addition: each arm also runs `scripts/round940_pristine_sweep.py`, because the sweep —
# our binary against PRISTINE tsc over fixtures NO corpus baseline gates — is the
# instrument (CHK.7) is judged by, and a fix must move its OURS-ONLY count DOWN and
# nothing else.
#
# NEVER `git checkout` to swap an arm (round 851: the revert destroys every uncommitted
# edit in the file). Every rewrite is in the FOREGROUND and the tree is verified restored
# to the AFTER source before exit; if this script is KILLED, `sha256sum` the tree against
# both snapshots before believing any binary (round 805).
#
# RUNTIME ~40 MIN. Run it detached and BLOCK on its marker file.
# Usage: scripts/round940-grid.sh <before-Checker.kt> <after-Checker.kt>
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler

BEFORE_SRC="${1:?usage: round940-grid.sh <before-Checker.kt> <after-Checker.kt>}"
AFTER_SRC="${2:?usage: round940-grid.sh <before-Checker.kt> <after-Checker.kt>}"
CK=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
OUT=build/bench/round940-grid
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
  echo "REFUSED: only ${#profiles[@]} profile(s) — materialize with" \
       "scripts/bench-compile-tsc.sh --project all --no-emit --no-log"
  exit 1
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
  python3 scripts/round940_pristine_sweep.py --classes "$MAIN" \
      --out "$OUT/sweep.$1.json" --work "build/bench/round940-sweep-$1" \
      > "$OUT/sweep.$1.txt" 2>&1
  echo "sweep($1): $(cat "$OUT/sweep.$1.txt" | tail -1)"
}

build() {
  ./gradlew :xemantic-typescript-compiler-core:compileKotlinJvm > "$OUT/build.$1.log" 2>&1
  grep -qa 'BUILD SUCCESSFUL' "$OUT/build.$1.log" || { echo "BUILD FAILED ($1)"; return 1; }
}

status=0
echo "=== arm: before (HEAD, pre-940) ==="
cp "$BEFORE_SRC" "$CK" || exit 1
build before || { cp "$AFTER_SRC" "$CK"; exit 1; }
capture before || status=1
sweep before

echo "=== arm: after (round 940) ==="
cp "$AFTER_SRC" "$CK" || exit 1
build after || exit 1
capture after || status=1
sweep after

if [[ "$(sha256sum "$CK" | cut -d' ' -f1)" != "$AFTER_SHA" ]]; then
  echo "FATAL: tree not restored to the fixed source"; exit 1
fi
echo "tree restored to the fixed source (sha256 $AFTER_SHA)"

for proj in "${profiles[@]}"; do
  name="$(basename "$proj")"
  added=$(comm -13 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | wc -l)
  removed=$(comm -23 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | wc -l)
  n=$(wc -l < "$OUT/$name.after.txt")
  echo "$name: $n diagnostics  added=$added removed=$removed"
  if [[ "$added" -ne 0 || "$removed" -ne 0 ]]; then
    comm -13 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | sed 's/^/    + /'
    comm -23 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | sed 's/^/    - /'
  fi
done
echo "grid exit=$status"
echo "$status" > "$OUT/DONE"
exit $status
