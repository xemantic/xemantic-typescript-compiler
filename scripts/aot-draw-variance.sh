#!/usr/bin/env bash
#
# aot-draw-variance.sh - how much does the TRAINING DRAW move a cached run?
#
# Round 842, (AOT.5)(f). Every AOT result recorded before this round - round
# 840(c)'s shipped emit win, round 840(d)'s rejected --workers 4 arm - was
# measured with ONE trained cache per arm, so the training run's own variance
# was never sampled. Two batches of runs replicate the MEASUREMENT draw; they
# cannot replicate the TRAINING draw, because they re-use the same cache files.
# This harness samples it: N caches trained by an IDENTICAL command, then run
# against each other paired and rotated.
#
#   scripts/aot-draw-variance.sh train <n> <project>      train n caches
#   scripts/aot-draw-variance.sh check <batch> <reps> <off> <project> <arms...>
#   scripts/aot-draw-variance.sh emit  <batch> <reps> <off> <project> <arms...>
#
# `check` runs `--noEmit --listAll` (the standard workload, and the one round
# 840(d) measured); `emit` runs `--listAll --outDir <throwaway>` (the workload
# round 840(c)'s shipped win lives on) and additionally digests the emitted tree.
# An arm is a draw index: `1 3 5` runs caches 1, 3 and 5. The literal arm `p`
# runs with NO cache, as an anchor.
#
# WHAT MAKES A RESULT QUOTABLE, and it is not the medians:
#   - Only WITHIN-REP PAIRED differences compare arms; absolute ms drift between
#     batches (~400 ms was seen between two batches an hour apart) and the
#     standing rule is that no absolute figure crosses a round boundary.
#   - Run at least TWO batches with different --off rotations. Round 840(c)
#     caught a 5/5 sign-consistent FALSE POSITIVE in its own first batch, and
#     round 842's batch A did the same: a 3.5% spread of medians that vanished
#     on replication.
#   - Every run's diagnostics are captured and digested here; a batch whose runs
#     do not all share ONE md5 at the expected error count is not a measurement,
#     it is a bug report. `--listAll` is mandatory for that reason (round 811: a
#     truncated capture reads as a regression), and the harness records whether
#     the capture contained the `more error(s)` tell.
#
# Results go to $XTSC_DV_DIR (default: a tmp dir printed on the first run) as
# TSV: batch, rep, arm, wall_ms, errors, diagnostics-md5, more-tell[, files,
# tree-digest]. Analysis is deliberately NOT in here - pair it in whatever tool
# you like, and print the sign counts next to the medians.
#
# The box must be QUIET (round 774: watching a benchmark is part of the
# benchmark - start it, then leave it alone) and must have no Gradle or Kotlin
# daemon resident (round 800: a build's daemon still in memory inflated every
# row by ~270x).
set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DV_DIR="${XTSC_DV_DIR:-${TMPDIR:-/tmp}/xtsc-draw-variance}"
mkdir -p "$DV_DIR"

die() { printf 'aot-draw-variance: %s\n' "$*" >&2; exit 2; }

# ROUND 858. This used to be `<core jar>:$(cat build/bench/cp.txt)`, which the
# module split made doubly wrong: cp.txt was a hand-frozen Jul-8 file naming
# kotlin-stdlib 2.4.0 after the build had moved to 2.4.10, AND the main class
# measured here (`XtscMainKt`) lives in the DAEMON module, which is not on that
# classpath at all - verified, the script died `ClassNotFoundException`. It was
# fail-safe (no number rather than a wrong one) but it had been dead since the
# split, exactly like `scripts/xtsc` was until round 857 found it.
#
# The fix is round 857's: read the STAGED lib dir with the launcher's own
# `find | LC_ALL=C sort`, never a hand-built list. That ordering is load-bearing
# here beyond mere freshness - the classpath and its ORDER are hashed into the
# AOT fingerprint, so a tail assembled any other way names a cache the launcher
# can never look up, and every draw would silently measure an uncached run.
resolve_cp() {
  local lib cp
  lib="$ROOT/xemantic-typescript-compiler-daemon/build/install/lib"
  [ -d "$lib" ] || die "no $lib - run ./gradlew assemble first"
  cp="$(find "$lib" -maxdepth 1 -name '*.jar' | LC_ALL=C sort | tr '\n' ':')"
  cp="${cp%:}"
  [ -n "$cp" ] || die "no jars in $lib - run ./gradlew assemble first"
  printf '%s' "$cp"
}

# The same main class the launcher and the trainer name - it is the `mainclass`
# field of the AOT fingerprint, so measuring through a different one would
# measure a cache the launcher can never look up.
MAIN=com.xemantic.typescript.compiler.server.XtscMainKt

cache_of() {  # cache_of <draw-index>
  ls "$DV_DIR/cache$1"/xtsc-*.aot 2>/dev/null | head -1
}

cmd="${1:-}"; shift || die "usage: see the header of $0"

case "$cmd" in

  train)
    n="${1:?usage: train <n> <project>}"; project="${2:?usage: train <n> <project>}"
    [ -d "$project" ] || die "not a directory: $project"
    for i in $(seq 1 "$n"); do
      d="$DV_DIR/cache$i"
      rm -rf "$d"; mkdir -p "$d"
      s=$(date +%s%3N)
      # The SHIPPED trainer, invoked exactly as a packager would - deliberately
      # not a hand-rolled java line, so that what is sampled is the variance of
      # the command users actually run.
      XTSC_AOT_DIR="$d" "$ROOT/scripts/xtsc-aot" train "$project" >"$DV_DIR/train$i.log" 2>&1 \
        || die "training draw $i failed - see $DV_DIR/train$i.log"
      e=$(date +%s%3N)
      f="$(cache_of "$i")"
      printf 'draw %s  train %s ms  size %s  sha %s\n' \
        "$i" "$((e - s))" "$(stat -c %s "$f")" "$(sha256sum "$f" | cut -c1-16)"
    done
    ;;

  check|emit)
    batch="${1:?batch label}"; reps="${2:?reps}"; off="${3:?rotation offset}"; project="${4:?project}"
    shift 4
    [ "$#" -ge 2 ] || die "give at least two arms (draw indices, or 'p' for uncached)"
    arms=("$@"); n=${#arms[@]}
    cp="$(resolve_cp)"
    tsv="$DV_DIR/$cmd-$batch.tsv"; : > "$tsv"
    printf 'results -> %s\n' "$tsv"
    date -Is
    for r in $(seq 1 "$reps"); do
      for k in $(seq 0 $((n - 1))); do
        arm="${arms[$(( (k + r + off) % n ))]}"
        out="$DV_DIR/out-$cmd-$batch-$r-$arm.txt"
        flags=()
        if [ "$arm" != p ]; then
          c="$(cache_of "$arm")"
          [ -n "$c" ] || die "no cache for draw $arm - run the 'train' subcommand first"
          # The launcher's own flag set. -Xlog:aot*=off:stdout is load-bearing:
          # the JVM's AOT warnings go to STDOUT, interleaved with diagnostics.
          flags=(-XX:AOTCache="$c" -Xlog:aot*=off:stdout -Xlog:aot*=error:stderr)
        fi
        wl=(--noEmit --listAll)
        od=""
        if [ "$cmd" = emit ]; then
          od="$(mktemp -d "${TMPDIR:-/tmp}/xtsc-dv-emit.XXXXXX")"
          wl=(--listAll --outDir "$od")   # never writes into the project
        fi
        s=$(date +%s%3N)
        java "${flags[@]}" -Xmx4g -cp "$cp" "$MAIN" "${wl[@]}" "$project" >"$out" 2>"$out.err"
        e=$(date +%s%3N)
        errs=$(grep -c 'error TS' "$out")
        md5=$(grep 'error TS' "$out" | sort | md5sum | cut -d' ' -f1)
        more=$(grep -c 'more error(s)' "$out")
        extra=""
        if [ "$cmd" = emit ]; then
          extra=$(printf '\t%s\t%s' \
            "$(find "$od" -type f | wc -l)" \
            "$(cd "$od" && find . -type f | sort | xargs sha256sum | md5sum | cut -d' ' -f1)")
          rm -rf "$od"
        fi
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s%s\n' \
          "$batch" "$r" "$arm" "$((e - s))" "$errs" "$md5" "$more" "$extra" >> "$tsv"
        printf '%s %s rep %s arm %-2s %6s ms  errors %s  md5 %s  more %s%s\n' \
          "$cmd" "$batch" "$r" "$arm" "$((e - s))" "$errs" "${md5:0:8}" "$more" "$extra"
      done
    done
    date -Is
    printf 'DONE %s %s\n' "$cmd" "$batch"
    ;;

  *) die "unknown command: $cmd (try: train, check, emit)" ;;
esac
