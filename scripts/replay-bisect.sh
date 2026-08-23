#!/usr/bin/env bash
# (INC.19) THE REPLAY BISECTION — which `init` passes, ADDED to the 207 the
# re-entrant replay re-enters, repair the capture divergence?
#
# (INC.17) landed the replay at 3.06x with `DIVERGED: 8 of 75 file(s)` on the
# capture channel and a silent diagnostics channel. Two hypotheses, and they call
# for opposite work:
#
#   (a) the replay SET is too small — the classification measures *reads the
#       partition* where soundness wants *its OUTPUT depends on the partition*;
#   (b) replaying at all is non-idempotent — a replayed pass appends to a side
#       table or re-emits (the evidence: the all-passes arm burned 53 min of CPU
#       over 7 targets without finishing, ~100x the 205-pass replay over 75).
#
# So this does NOT re-run the all-passes arm. It sweeps the candidate universe
# (`all - replayed`) in GROUPS, each run pointed at the DIVERGING FILES ONLY so a
# draw costs seconds, and each run under a wall-clock CAP so a group containing a
# non-idempotent pass kills its own run instead of the sweep. Monotonicity is NOT
# assumed: under (b) adding passes makes things worse, and a group that TIMES OUT
# is itself the (b) signal.
#
#   scripts/replay-bisect.sh dump                    — write the candidate universe
#   scripts/replay-bisect.sh sweep [group] [files]   — group-by-group
#   scripts/replay-bisect.sh try <spec> [files]      — one explicit set (a,b,c or @file)
#   scripts/replay-bisect.sh narrow <@file> [files]  — halve a repairing group down
#
# <files> is a comma-separated list of file-name suffixes to COMPARE (never what
# the seed walked); it defaults to the eight (INC.17) reported.
#
# REFUSES (exit 2) rather than skipping when an input is missing — round 853: a
# gate that passes quietly where its subject is absent is worth nothing.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"

MODE="${1:-sweep}"
OUT="$ROOT/build/bench/replay-bisect"
PASSES="$OUT/passes.txt"
# The eight files (INC.17) measured as divergent, so a draw compares ~44 rows
# instead of 373,879 and costs one seed build plus 2N narrowed builds.
DEFAULT_FILES="checker.ts,debug.ts,program.ts,tsbuildPublic.ts,visitorPublic.ts,watch.ts,watchPublic.ts,destructuring.ts"
# Per-run wall cap. A 9-file draw is ~1 min; a group that blows past this is
# hypothesis (b) caught in the act, which is a RESULT and not a failure.
CAP="${REPLAY_BISECT_CAP:-600}"

PROFILE=""
shopt -s nullglob
for candidate in build/bench/tsc-project-*; do
  [[ -f "$candidate/tsconfig.json" ]] && PROFILE="$candidate"
done
shopt -u nullglob
[[ -n "$PROFILE" ]] || {
  echo "REFUSED: no tsc profile at build/bench/tsc-project-* — create it with" \
       "scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log" >&2; exit 2; }

CLASSES="$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/test"
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/ReplayDifferentialMainKt.class" ]] || {
  echo "REFUSED: runner not built — ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2; }

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS"
mkdir -p "$OUT"

java_run() { java -Xmx6g -cp "$CP" com.xemantic.typescript.compiler.project.ReplayDifferentialMainKt "$@"; }

dump() {
  echo "== dumping the candidate universe from $PROFILE"
  java_run --dump-passes "$PROFILE" > "$PASSES"
  local all replayed cand
  all=$(grep -c '^all	' "$PASSES" || true)
  replayed=$(grep -c '^replayed	' "$PASSES" || true)
  cand=$(grep -c '^candidate	' "$PASSES" || true)
  # A universe that does not contain the replayed set names a bisection that
  # cannot close, so check the arithmetic rather than trusting the run.
  [[ "$all" -gt 300 && "$replayed" -gt 100 && "$cand" -gt 0 ]] || {
    echo "REFUSED: implausible universe all=$all replayed=$replayed candidates=$cand" >&2; exit 2; }
  echo "all=$all replayed=$replayed candidates=$cand  ->  $PASSES"
}

candidates() {
  [[ -f "$PASSES" ]] || { echo "REFUSED: no $PASSES — run 'scripts/replay-bisect.sh dump' first" >&2; exit 2; }
  grep '^candidate	' "$PASSES" | cut -f2
}

# one draw: $1 = a file holding the extra pass names, $2 = the compared files
draw() {
  local list="$1" files="$2" log="$3"
  local t0 t1 rc=0
  t0=$(date +%s)
  timeout "$CAP" java -Xmx6g -cp "$CP" \
    com.xemantic.typescript.compiler.project.ReplayDifferentialMainKt \
    "$PROFILE" 0 '' "$files" "@$list" > "$log" 2>&1 || rc=$?
  t1=$(date +%s)
  local n
  if [[ $rc -eq 124 ]]; then
    echo "TIMEOUT after $((t1-t0))s  (hypothesis (b): a replayed pass does not terminate in budget)"
    return 0
  fi
  n=$(grep -a -o 'DIVERGED: [0-9]*' "$log" | tail -1 | awk '{print $2}' || true)
  if grep -qa 'EQUIVALENT' "$log"; then n=0; fi
  if [[ -z "$n" ]]; then
    echo "ERROR rc=$rc after $((t1-t0))s — $(tail -3 "$log" | tr '\n' ' ' | cut -c1-200)"
    return 0
  fi
  echo "diverged=$n  wall=$((t1-t0))s  $(grep -a -o 'divergentFiles=.*' "$log" | tail -1)"
}

case "$MODE" in
  dump) dump ;;
  try)
    SPEC="${2:?usage: try <a,b,c|@file> [files]}"
    FILES="${3:-$DEFAULT_FILES}"
    LIST="$OUT/try.txt"
    if [[ "$SPEC" == @* ]]; then cp "${SPEC#@}" "$LIST"; else tr ',' '\n' <<<"$SPEC" > "$LIST"; fi
    echo "== try $(wc -l < "$LIST") pass(es) over [$FILES]"
    draw "$LIST" "$FILES" "$OUT/try.log"
    ;;
  sweep)
    GROUP="${2:-24}"
    FILES="${3:-$DEFAULT_FILES}"
    mapfile -t CAND < <(candidates)
    echo "== sweep ${#CAND[@]} candidates in groups of $GROUP over [$FILES]  (cap ${CAP}s/draw)"
    i=0; g=0
    while [[ $i -lt ${#CAND[@]} ]]; do
      g=$((g+1))
      printf '%s\n' "${CAND[@]:$i:$GROUP}" > "$OUT/group-$g.txt"
      printf 'group %-3s [%3s..%3s] ' "$g" "$i" "$((i+GROUP-1))"
      draw "$OUT/group-$g.txt" "$FILES" "$OUT/group-$g.log"
      i=$((i+GROUP))
    done
    ;;
  narrow)
    SPEC="${2:?usage: narrow <@file> [files]}"
    FILES="${3:-$DEFAULT_FILES}"
    SRC="${SPEC#@}"
    mapfile -t SET < <(grep -v '^[[:space:]]*$' "$SRC")
    echo "== narrow ${#SET[@]} pass(es) over [$FILES]"
    while [[ ${#SET[@]} -gt 1 ]]; do
      half=$(( (${#SET[@]} + 1) / 2 ))
      printf '%s\n' "${SET[@]:0:$half}" > "$OUT/narrow-lo.txt"
      printf '%s\n' "${SET[@]:$half}" > "$OUT/narrow-hi.txt"
      printf 'lo(%s) ' "$half"; LO=$(draw "$OUT/narrow-lo.txt" "$FILES" "$OUT/narrow-lo.log"); echo "$LO"
      printf 'hi(%s) ' "$(( ${#SET[@]} - half ))"; HI=$(draw "$OUT/narrow-hi.txt" "$FILES" "$OUT/narrow-hi.log"); echo "$HI"
      lon=$(grep -o 'diverged=[0-9]*' <<<"$LO" | cut -d= -f2 || true)
      hin=$(grep -o 'diverged=[0-9]*' <<<"$HI" | cut -d= -f2 || true)
      # Prefer the half that repairs MORE; a tie means the effect is split and the
      # bisection cannot continue as a bisection — say so rather than guessing.
      if [[ -n "$lon" && -n "$hin" && "$lon" -lt "$hin" ]]; then
        mapfile -t SET < "$OUT/narrow-lo.txt"
      elif [[ -n "$lon" && -n "$hin" && "$hin" -lt "$lon" ]]; then
        mapfile -t SET < "$OUT/narrow-hi.txt"
      else
        echo "STOP: neither half alone reproduces the repair (lo=$lon hi=$hin) — the" \
             "effect is split across the halves, or neither is the cause."
        printf '%s\n' "${SET[@]}"
        exit 0
      fi
    done
    echo "NARROWED TO: ${SET[0]}"
    ;;
  *) echo "usage: $0 dump|sweep [group] [files]|try <spec> [files]|narrow <@file> [files]" >&2; exit 2 ;;
esac
