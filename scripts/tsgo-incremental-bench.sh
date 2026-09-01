#!/usr/bin/env bash
# Times tsgo's `--incremental --noEmit` loop for the two edit shapes (INC.46)'s gate
# distinguishes: a BODY-ONLY edit (no exported signature moves) and a SIGNATURE edit
# (one does). The mirror of `scripts/inc46-vs-tsgo.sh`, which measures the same four
# cells on our side over the same tree and the same edit variants.
#
# Each run is a FRESH PROCESS, because that is tsgo's model: the incremental state
# lives in `.tsbuildinfo` on disk, not in a session. That asymmetry against our
# in-session API is inherent, and the write-up must say so rather than hide it.
#
# Every cell also reports its DIAGNOSTIC ROW COUNT. `kir-bench.sh`'s law: a wall-clock
# harness reads a program that does LESS work as the fastest arm, so a cell whose row
# count differs from the full build's is answering a different question and its
# milliseconds are not quotable.
#
# usage: tsgo-incremental-bench.sh <projectDir> <editFileRelative> <editsDir> [reps]
#   <editsDir> holds orig.ts / body.ts / sig.ts (or the legacy binder.<v>.ts names).
set -euo pipefail
cd "$(dirname "$0")/.."

P="${1:-build/bench/tsgo-bench}"
EDIT_REL="${2:-src/compiler/binder.ts}"
EDITS="${3:-/tmp/claude-1000}"
REPS="${4:-3}"
TSGO=tools/tsgo-7.0.2/lib/tsc
F="$P/$EDIT_REL"

[[ -x $TSGO ]] || { echo "REFUSED: no tsgo binary at $TSGO" >&2; exit 2; }
[[ -f $P/tsconfig.json ]] || { echo "REFUSED: no project at $P" >&2; exit 2; }
[[ -f $F ]] || { echo "REFUSED: no file to edit at $F" >&2; exit 2; }

variant() { # $1 = orig|body|sig
  if   [[ -f "$EDITS/$1.ts"        ]]; then echo "$EDITS/$1.ts"
  elif [[ -f "$EDITS/binder.$1.ts" ]]; then echo "$EDITS/binder.$1.ts"
  else echo "REFUSED: no '$1' variant in $EDITS" >&2; exit 2; fi
}
ORIG="$(variant orig)"; BODY="$(variant body)"; SIG="$(variant sig)"
cmp -s "$ORIG" "$BODY" && { echo "REFUSED: body variant is identical to orig" >&2; exit 2; }
cmp -s "$ORIG" "$SIG"  && { echo "REFUSED: sig variant is identical to orig" >&2; exit 2; }
cmp -s "$F" "$ORIG"    || { echo "REFUSED: $F does not hold the recorded original" >&2; exit 2; }

OUT="${TMPDIR:-/tmp}/tsgo-run.out"
# Prints "<elapsed ms> <diagnostic rows>" on one line. It must PRINT both rather than
# set a variable: every call site is a command substitution, i.e. a subshell, so an
# assignment made here would never reach the caller — and the row counts would then be
# whichever run last executed OUTSIDE a substitution, which is a stale-but-plausible
# number and exactly the kind an equivalence gate must not be built on.
run() {
  local t0 t1 rows
  t0=$(date +%s%3N)
  "$TSGO" --incremental --noEmit -p "$P" > "$OUT" 2>&1 || true
  t1=$(date +%s%3N)
  rows=$(grep -ac "error TS" "$OUT" || true)
  echo "$(( t1 - t0 )) ${rows:-0}"
}
med() { printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}'; }

echo "project : $P"
echo "edited  : $EDIT_REL"
echo "edits   : $EDITS"
echo "reps    : $REPS"
echo

cold=(); coldrows=()
for ((i=0;i<REPS;i++)); do rm -f "$P"/*.tsbuildinfo; read -r m r <<<"$(run)"; cold+=("$m"); coldrows+=("$r"); done
echo "cold (no tsbuildinfo)   : ${cold[*]}  median=$(med "${cold[@]}") ms  rows=${coldrows[*]}"

noop=(); nooprows=()
for ((i=0;i<REPS;i++)); do read -r m r <<<"$(run)"; noop+=("$m"); nooprows+=("$r"); done
echo "no-op (nothing changed) : ${noop[*]}  median=$(med "${noop[@]}") ms  rows=${nooprows[*]}"

body=(); bodyrows=()
for ((i=0;i<REPS;i++)); do
  cp "$ORIG" "$F"; run > /dev/null
  cp "$BODY" "$F"; read -r m r <<<"$(run)"; body+=("$m"); bodyrows+=("$r")
done
echo "after BODY-ONLY edit    : ${body[*]}  median=$(med "${body[@]}") ms  rows=${bodyrows[*]}"

sig=(); sigrows=()
for ((i=0;i<REPS;i++)); do
  cp "$ORIG" "$F"; run > /dev/null
  cp "$SIG" "$F"; read -r m r <<<"$(run)"; sig+=("$m"); sigrows+=("$r")
done
echo "after SIGNATURE edit    : ${sig[*]}  median=$(med "${sig[@]}") ms  rows=${sigrows[*]}"

cp "$ORIG" "$F"
rm -f "$P"/*.tsbuildinfo
