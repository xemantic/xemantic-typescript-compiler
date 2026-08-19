#!/usr/bin/env bash
# Round 938 — the 8-profile `--listAll` output grid for (CHK.5)(b): a FIRST-WINS
# duplicate member map plus the duplicate scans' computed-key namer.
#
# TWO BINARIES, not two flags. This change has no in-binary arm (it is one `when`
# guard in a member loop plus one namer), so the grid rebuilds the PRE-938
# source, captures, and rebuilds the fixed one — the shape round 813 asks for: a whole-output diff of the
# ablated binary against the committed one over a family of real inputs.
#
# Both source states are taken from sha256-VERIFIED on-disk snapshots (never `git
# checkout` — round 851: an ablation's own revert destroys every uncommitted edit in
# the file it touches, and CLAUDE.md's round-805 trap is a killed script leaving the
# ablated source in the tree with no marker). Every rewrite runs in the FOREGROUND
# and the script verifies the tree is restored before it exits.
#
# ALL EIGHT PROFILES. `bench-compile-tsc.sh` names the compiler profile
# `tsc-project-<commit8>` and the other seven `tsc-<name>-<commit8>`, so the
# `build/bench/tsc-project-*` glob every pre-895 "grid" here used matched ONE dir.
# A profile is identified by carrying a tsconfig.json; below 8 we refuse.
#
# RUNTIME ~13 MIN (two ~1.5-min builds plus 16 whole-project compiles), i.e. OVER a
# 10-minute agent tool timeout — run it detached and block on it, never in a call that
# can be killed mid-flight. If it IS killed, the tree is left at whichever source the
# last `cp` wrote: check `sha256sum` against both arms before believing any binary
# (round 805).
# Usage: scripts/round938-grid.sh <before-Checker.kt> <after-Checker.kt>
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler

BEFORE_SRC="${1:?usage: round938-grid.sh <before-Checker.kt> <after-Checker.kt>}"
AFTER_SRC="${2:?usage: round938-grid.sh <before-Checker.kt> <after-Checker.kt>}"
CK=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
OUT=build/bench/round938-grid
MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
mkdir -p "$OUT"

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

capture() { # $1 = arm name
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
    # round 811: a truncated capture reports a regression that does not exist.
    grep -qa 'more error(s)' "$OUT/$name.$arm.raw" && { echo "REFUSED $name/$arm: truncated"; st=1; }
    # round 804: an empty capture is not a clean run.
    [[ -s "$OUT/$name.$arm.txt" ]] || { echo "REFUSED $name/$arm: empty"; st=1; }
  done
  return $st
}

build() {
  ./gradlew :xemantic-typescript-compiler-core:compileKotlinJvm > "$OUT/build.$1.log" 2>&1
  grep -qa 'BUILD SUCCESSFUL' "$OUT/build.$1.log" || { echo "BUILD FAILED ($1)"; return 1; }
}

status=0
echo "=== arm: before (pre-938) ==="
cp "$BEFORE_SRC" "$CK" || exit 1
build before || { cp "$AFTER_SRC" "$CK"; exit 1; }
capture before || status=1

echo "=== arm: after (round 938) ==="
cp "$AFTER_SRC" "$CK" || exit 1
build after || exit 1
capture after || status=1

# The tree MUST be the fixed source when this exits.
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
exit $status
