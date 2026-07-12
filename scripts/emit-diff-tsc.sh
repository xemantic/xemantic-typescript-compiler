#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
# Emit-parity dashboard gate: diff xtsc's emitted JS against a reference tsc's,
# on the SAME tsc-source self-compile profile that bench-compile-tsc.sh builds.
#
# The 10,155-test corpus pins JS-emit byte-parity for SMALL/single-file shapes,
# but cross-module const-enum inlining, multi-line expression formatting, and
# sub-ES2021 operator downleveling at whole-program scale are shapes the corpus
# never exercises. This script measures that gap and breaks it down by the three
# known divergence families (see PLAN-PHASE-5.md "Emit parity (EP)"):
#
#   1. const-enum member inlining   (xtsc keeps `mod.Enum.Member`; tsc inlines
#                                     `VALUE /* Enum.Member */`)  -- cross-module
#   2. multi-line expression format (operator / `:` at line-end vs line-start)
#   3. logical/nullish assignment   (`||=`/`&&=`/`??=` downlevel below ES2021)
#
# Usage: scripts/emit-diff-tsc.sh [--project NAME] [--ref-tsc PATH] [--keep]
#   --project NAME   tsc subproject profile (default compiler; see bench-compile-tsc.sh)
#   --ref-tsc PATH   reference `tsc` binary. Ideal reference is a tsc BUILT AT THE
#                    PINNED COMMIT the corpus tracks (typeScriptCommit / typescript-repo
#                    HEAD) -- an npm `typescript` of a different version adds version
#                    noise to the small residual tail (esp. emitHelpers.js helper
#                    bodies). Auto-detects `tsc` on PATH when omitted.
#   --keep           keep the two emit dirs under build/bench/emit-diff/ for manual diff
#
# NOTE (macOS): pretty tsc output uses ANSI color; this script strips it. The three
# family counts are version-STABLE tsc behaviors, so they are trustworthy even when
# the reference tsc version differs from the pinned commit.

set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

PROJECT=compiler
REF_TSC=""
KEEP=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --project) PROJECT="$2"; shift 2 ;;
        --ref-tsc) REF_TSC="$2"; shift 2 ;;
        --keep)    KEEP=1; shift ;;
        --help|-h) sed -n '/^# Emit-parity/,/^set -euo/p' "$0" | sed '$d;s/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
    esac
done

if [[ -z "$REF_TSC" ]]; then
    REF_TSC="$(command -v tsc || true)"
    [[ -n "$REF_TSC" ]] || { echo "error: no reference tsc found; pass --ref-tsc PATH" >&2; exit 1; }
fi
echo "reference tsc: $REF_TSC ($("$REF_TSC" --version 2>/dev/null | tr -d '\n'))"

OUT_DIR="$REPO_ROOT/build/bench/emit-diff"
XTSC_OUT="$OUT_DIR/emit-xtsc"
TSC_OUT="$OUT_DIR/emit-tsc"
rm -rf "$OUT_DIR"; mkdir -p "$OUT_DIR"

# 1. xtsc: run the bench (builds the compiler + emits into the project's dist),
#    then snapshot dist BEFORE the reference tsc can touch it.
echo "== xtsc emit (via bench-compile-tsc.sh --project $PROJECT) =="
# stderr suppressed: the bench's stat parsing uses GNU `grep -oP`, which is noisy on
# macOS BSD grep (a documented artifact); a real bench failure still exits non-zero
# under its own `set -e`, which propagates here.
"$REPO_ROOT/scripts/bench-compile-tsc.sh" --project "$PROJECT" --no-log >/dev/null 2>&1
# Locate the materialized project dir (compiler profile keeps the historical name).
TS_COMMIT8="$(git -C "$REPO_ROOT/typescript-repo" rev-parse HEAD | cut -c1-8)"
if [[ "$PROJECT" == compiler ]]; then PROJ_DIR="$REPO_ROOT/build/bench/tsc-project-$TS_COMMIT8"
else PROJ_DIR="$REPO_ROOT/build/bench/tsc-$PROJECT-$TS_COMMIT8"; fi
cp -R "$PROJ_DIR/dist" "$XTSC_OUT"

# 2. reference tsc into a separate outDir (never clobbers xtsc's).
echo "== reference tsc emit =="
"$REF_TSC" -p "$PROJ_DIR/tsconfig.json" --outDir "$TSC_OUT" >/dev/null 2>&1 || true

# 3. Diff.
echo
echo "=== emit-parity diff (xtsc vs reference tsc, '$PROJECT' profile) ==="
same=0; diff=0
while IFS= read -r rel; do
    [[ -f "$TSC_OUT/$rel" ]] || continue
    if diff -q "$XTSC_OUT/$rel" "$TSC_OUT/$rel" >/dev/null 2>&1; then same=$((same+1)); else diff=$((diff+1)); fi
done < <(cd "$XTSC_OUT" && find . -name '*.js' | sort)
total=$((same+diff))
echo "byte-identical files: $same / $total"
echo "differing files     : $diff / $total"
echo
echo "--- divergence family signals ---"
enum_x=$(grep -rhoE '[0-9]+ /\* [A-Za-z_]+\.[A-Za-z_]+ \*/' "$XTSC_OUT" 2>/dev/null | wc -l | tr -d ' ')
enum_t=$(grep -rhoE '[0-9]+ /\* [A-Za-z_]+\.[A-Za-z_]+ \*/' "$TSC_OUT"  2>/dev/null | wc -l | tr -d ' ')
echo "1. const-enum inlined reads   xtsc $enum_x  vs  tsc $enum_t   (gap = cross-module enums not inlined)"
la_x=$(grep -rhoE '(\|\||&&|\?\?)=' "$XTSC_OUT" 2>/dev/null | wc -l | tr -d ' ')
la_t=$(grep -rhoE '(\|\||&&|\?\?)=' "$TSC_OUT"  2>/dev/null | wc -l | tr -d ' ')
echo "3. logical-assign operators   xtsc $la_x  vs  tsc $la_t   (xtsc surplus = not downleveled)"
echo
echo "(family 2 — multi-line expression formatting — shows up as the residual after"
echo " normalizing 1 and 3; inspect with: diff $XTSC_OUT/compiler/utilities.js $TSC_OUT/compiler/utilities.js)"

if [[ $KEEP -eq 0 ]]; then rm -rf "$OUT_DIR"; else echo; echo "emit dirs kept under: $OUT_DIR"; fi
