#!/usr/bin/env bash
# Build the GraalVM native image with PROFILE-GUIDED OPTIMIZATION.
#
# WHY THIS EXISTS AS A SCRIPT AND NOT AS WORKFLOW YAML: the PGO cycle is three
# builds and two training runs, and it is used by BOTH `native.yml` (which
# publishes the artifact) and `bench.yml` (which publishes the number). Two
# hand-maintained copies of a five-step recipe drift, and the drift is silent —
# a bench measuring a non-PGO image while the release ships a PGO one is exactly
# the class of "instrument is not measuring the shipped thing" defect that
# rounds 853 / 857 / 858 each cost a session.
#
# MEASURED (2026-08-10, dev box, 78-file `compiler` profile, 5 rotated reps):
#     Oracle base   9,803 ms check-only / 11,684 ms emit
#     Oracle + PGO  8,441 ms            /  9,400 ms      = -13.9% / -19.5%
# and against a cold JVM one-shot on the same commit (27,996 / 32,168 ms) the
# PGO image is 3.32x / 3.42x faster. All three images answered 46 diagnostics
# with ZERO differing lines against the JVM's `--listAll` — the load-bearing
# check, because PGO rewrites codegen.
#
# TRAIN ON BOTH MODES — the one trap in the recipe. Round 840(c) found a JDK AOT
# cache trained with `--noEmit` carried no emitter profile at all, and adding one
# bought -932 ms; the same applies here, because round 738's `skipEmitOutputs`
# gate makes `--noEmit` skip `Transformer.transform` and `Emitter.emit` ENTIRELY.
# A check-only-only profile therefore leaves the whole emit path on static
# heuristics while looking perfectly healthy.
#
# ORACLE GraalVM ONLY. Community Edition has no PGO: `--pgo-instrument` is
# rejected by the CE builder and the word does not appear in its `--help`. That
# is the documented detector and this script uses it, up front, rather than
# letting a CE run fail three minutes into a build.
#
# Usage:
#   scripts/build-native-pgo.sh [--profile <dir>] [--graalvm-home <dir>]
#                               [--output <name>] [--allow-no-pgo]
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

PROFILE=""
GRAAL_HOME="${GRAALVM_HOME:-}"
OUTPUT="xtsc"
ALLOW_NO_PGO=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --profile)       PROFILE="$2"; shift 2 ;;
        --graalvm-home)  GRAAL_HOME="$2"; shift 2 ;;
        --output)        OUTPUT="$2"; shift 2 ;;
        # Degrade to a plain (non-PGO) image instead of failing. Deliberately
        # OPT-IN: a silent downgrade would publish a slower binary under the same
        # name and nothing downstream could tell.
        --allow-no-pgo)  ALLOW_NO_PGO=1; shift ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

NATIVE_DIR="$REPO_ROOT/xemantic-typescript-compiler-cli/build/native"
GRADLE_ARGS=(:xemantic-typescript-compiler-cli:nativeImage --no-configuration-cache)
[[ -n "$GRAAL_HOME" ]] && GRADLE_ARGS+=(-PgraalvmHome="$GRAAL_HOME")

# --- is this Oracle GraalVM? ------------------------------------------------
NI="${GRAAL_HOME:+$GRAAL_HOME/bin/native-image}"
[[ -z "$NI" ]] && NI="$(command -v native-image || true)"
[[ -x "$NI" ]] || { echo "error: native-image not found (GRAALVM_HOME=$GRAAL_HOME)" >&2; exit 1; }

echo "native-image: $NI"
"$NI" --version 2>&1 | head -2 || true

if "$NI" --help 2>&1 | grep -qi -- "--pgo"; then
    HAS_PGO=1
else
    HAS_PGO=0
    if [[ "$ALLOW_NO_PGO" -eq 0 ]]; then
        echo "error: this native-image has no PGO support — it is GraalVM Community." >&2
        echo "       Use Oracle GraalVM (setup-graalvm 'distribution: graalvm'), or pass" >&2
        echo "       --allow-no-pgo to build a plain image on purpose." >&2
        exit 1
    fi
    echo "::warning::no PGO on this toolchain — building a PLAIN image (--allow-no-pgo)"
fi

if [[ "$HAS_PGO" -eq 0 ]]; then
    ./gradlew "${GRADLE_ARGS[@]}" -PnativeImageOutput="$OUTPUT"
    ls -la "$NATIVE_DIR/$OUTPUT"
    exit 0
fi

# --- training workload ------------------------------------------------------
# The profile is what the image is optimised FOR, so it must be a real compile.
# `bench-compile-tsc.sh` materialises the pinned 78-file tsc `compiler` profile.
if [[ -z "$PROFILE" ]]; then
    PROFILE="$(ls -d "$REPO_ROOT"/build/bench/tsc-project-* 2>/dev/null | head -1 || true)"
fi
if [[ -z "$PROFILE" || ! -d "$PROFILE" ]]; then
    echo "--- materialising the training profile"
    scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log --iterations 1
    PROFILE="$(ls -d "$REPO_ROOT"/build/bench/tsc-project-* | head -1)"
fi
[[ -d "$PROFILE" ]] || { echo "error: no training profile at '$PROFILE'" >&2; exit 1; }
echo "training profile: $PROFILE"

# --- 1. instrumented image --------------------------------------------------
echo "--- $(date -Is) building instrumented image"
./gradlew "${GRADLE_ARGS[@]}" \
    -PnativeImageOutput="$OUTPUT-instrumented" \
    -PnativeImageArgs="--pgo-instrument"

INSTR="$NATIVE_DIR/$OUTPUT-instrumented"
[[ -x "$INSTR" ]] || { echo "error: instrumented image missing at $INSTR" >&2; exit 1; }

# --- 2. train, BOTH modes, one directory each -------------------------------
# `default.iprof` is written to the CWD, so the two runs need separate dirs or
# the second silently overwrites the first and the emit profile is lost.
TRAIN="$(mktemp -d)"
trap 'rm -rf "$TRAIN"' EXIT
mkdir -p "$TRAIN/noemit" "$TRAIN/emit"

# The compiler EXITS 1 on a project that has diagnostics (tsc semantics) and this
# profile has 46 — expected, so it must not be read as a failure under `set -e`.
echo "--- $(date -Is) training: check-only"
( cd "$TRAIN/noemit" && "$INSTR" --noEmit "$PROFILE" >/dev/null 2>&1 || true )
echo "--- $(date -Is) training: emit"
( cd "$TRAIN/emit"   && "$INSTR"          "$PROFILE" >/dev/null 2>&1 || true )

for f in "$TRAIN/noemit/default.iprof" "$TRAIN/emit/default.iprof"; do
    [[ -s "$f" ]] || { echo "error: no profile written at $f — did the training run crash?" >&2; exit 1; }
    echo "iprof: $f ($(stat -c%s "$f") bytes)"
done

# --- 3. final image ---------------------------------------------------------
echo "--- $(date -Is) building PGO image"
./gradlew "${GRADLE_ARGS[@]}" \
    -PnativeImageOutput="$OUTPUT" \
    -PnativeImageArgs="--pgo=$TRAIN/noemit/default.iprof,$TRAIN/emit/default.iprof"

BIN="$NATIVE_DIR/$OUTPUT"
[[ -x "$BIN" ]] || { echo "error: final image missing at $BIN" >&2; exit 1; }

# The instrumented image is ~2.3x the size of the final one and is a build
# intermediate; leaving it beside the artifact invites uploading the wrong file.
rm -f "$INSTR"

echo "=== $(date -Is) done"
ls -la "$BIN"
