#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
# Three-way compile bench: compile the pinned TypeScript compiler sources with
# xtsc, the reference JS tsc, and the native tsgo, then write a per-run Markdown
# report under bench-history/runs/ and prepend a row to bench-history/README.md
# so improvements are observable across runs (see .github/workflows/bench.yml).
#
# All three compile the SAME materialized project (the `compiler` profile that
# scripts/bench-compile-tsc.sh builds from typescript-repo @ typeScriptCommit),
# with emit, cold single process. Wall-clock is the comparable metric (xtsc also
# reports its self-time, which excludes JVM startup/JIT).
#
# Usage: scripts/bench-3way.sh --tsc PATH --tsgo PATH [options]
#   --tsc PATH        reference JS `tsc` binary (required)
#   --tsgo PATH       native `tsgo` binary (required)
#   --project NAME    tsc subproject profile (default compiler)
#   --xtsc-iters N    measured xtsc runs, median (default 1; ~30s each in CI)
#   --ref-iters N     measured tsc/tsgo runs, median (default 3; cheap)
#   --out-dir DIR     bench-history root (default bench-history)
#   --label TEXT      free-text note stored in the report
#   --help            show this help

set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TSC_BIN=""; TSGO_BIN=""; PROJECT=compiler
XTSC_ITERS=1; REF_ITERS=3
OUT_DIR="$REPO_ROOT/bench-history"; LABEL=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --tsc)        TSC_BIN="$2"; shift 2 ;;
        --tsgo)       TSGO_BIN="$2"; shift 2 ;;
        --project)    PROJECT="$2"; shift 2 ;;
        --xtsc-iters) XTSC_ITERS="$2"; shift 2 ;;
        --ref-iters)  REF_ITERS="$2"; shift 2 ;;
        --out-dir)    OUT_DIR="$2"; shift 2 ;;
        --label)      LABEL="$2"; shift 2 ;;
        --help|-h)    sed -n '/^# Three-way/,/^set -euo/p' "$0" | sed '$d;s/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
    esac
done
[[ -n "$TSC_BIN"  ]] || { echo "error: --tsc PATH is required" >&2; exit 2; }
[[ -n "$TSGO_BIN" ]] || { echo "error: --tsgo PATH is required" >&2; exit 2; }
command -v python3 >/dev/null || { echo "error: python3 not found" >&2; exit 1; }

# `tsc --version` prints "Version 6.0.3"; tsgo prints "Version 7.0.0-dev.NNN".
TSC_VER="$("$TSC_BIN"  --version 2>/dev/null | awk '{print $NF}' || echo '?')"
TSGO_VER="$("$TSGO_BIN" --version 2>/dev/null | awk '{print $NF}' || echo '?')"
[[ -n "$TSC_VER"  ]] || TSC_VER='?'
[[ -n "$TSGO_VER" ]] || TSGO_VER='?'
strip_ansi() { sed -E $'s/\x1b\\[[0-9;]*m//g'; }

median() { printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END{ if(NR%2) print a[(NR+1)/2]; else print int((a[NR/2]+a[NR/2+1])/2) }'; }

# --------------------------------------------------------------------------
# 1. xtsc: reuse bench-compile-tsc.sh (materializes the project, builds the
#    compiler, resolves the classpath, runs MainKt). Parse its summary.
# --------------------------------------------------------------------------
echo "== xtsc =="
XTSC_LOG="$(mktemp)"
"$REPO_ROOT/scripts/bench-compile-tsc.sh" --project "$PROJECT" --iterations "$XTSC_ITERS" --no-log \
    | tee "$XTSC_LOG" >&2 || { echo "xtsc bench failed" >&2; exit 1; }

TS_COMMIT8="$(git -C "$REPO_ROOT/typescript-repo" rev-parse HEAD | cut -c1-8)"
if [[ "$PROJECT" == compiler ]]; then PROJ_DIR="$REPO_ROOT/build/bench/tsc-project-$TS_COMMIT8"
else PROJ_DIR="$REPO_ROOT/build/bench/tsc-$PROJECT-$TS_COMMIT8"; fi

# xtsc self/errors/files come from the MainKt RUN LOG (printed by the compiler
# itself, so correct on any OS — the bench summary's own stats are 0 on macOS BSD
# grep). wall and LOC come from the summary (computed with date / wc, both fine).
xtsc_wall=$(grep -oE '[0-9]+ ms wall' "$XTSC_LOG" | grep -oE '[0-9]+' | head -1 || echo 0)
LOC=$(grep -oE '[0-9]+ LOC' "$XTSC_LOG" | grep -oE '[0-9]+' | tail -1 || echo 0)
RUNLOG=$(grep -oE 'full output: .*' "$XTSC_LOG" | head -1 | sed 's/^full output: //' || true)
if [[ -n "$RUNLOG" && -f "$RUNLOG" ]]; then
    xtsc_self=$(grep -oE '^time:\s+[0-9]+' "$RUNLOG" | grep -oE '[0-9]+' | head -1 || echo 0)
    xtsc_err=$(grep -oE 'diagnostics:\s+[0-9]+ error' "$RUNLOG" | grep -oE '[0-9]+' | head -1 || echo 0)
    xtsc_files=$(grep -oE '[0-9]+ in program' "$RUNLOG" | grep -oE '[0-9]+' | head -1 || echo 0)
else
    xtsc_self=0; xtsc_err=0; xtsc_files=0
fi
rm -f "$XTSC_LOG"

# --------------------------------------------------------------------------
# 2. tsc / tsgo on the SAME materialized project, separate outDirs.
# --------------------------------------------------------------------------
run_ref() { # $1=bin  $2=out-tag ; echoes "wall_ms err"
    local bin="$1" tag="$2" walls=() out i start end
    for ((i=1;i<=REF_ITERS;i++)); do
        out="$(mktemp)"
        start=$(date +%s%N)
        "$bin" -p "$PROJ_DIR/tsconfig.json" --outDir "$(mktemp -d)" >"$out" 2>&1 || true
        end=$(date +%s%N)
        walls+=($(( (end-start)/1000000 )))
        local err; err=$(strip_ansi <"$out" | grep -cE 'error TS[0-9]+' || true)
        rm -f "$out"
    done
    echo "$(median "${walls[@]}") ${err:-0}"
}
echo "== tsc ($TSC_VER) ==";  read -r tsc_wall  tsc_err  < <(run_ref "$TSC_BIN"  tsc)
echo "== tsgo ($TSGO_VER) =="; read -r tsgo_wall tsgo_err < <(run_ref "$TSGO_BIN" tsgo)

# --------------------------------------------------------------------------
# 3. Render the per-run report + update the index.
# --------------------------------------------------------------------------
XTSC_REV="$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD)"
XTSC_REV_FULL="$(git -C "$REPO_ROOT" rev-parse HEAD)"
RUN_STAMP="${BENCH_RUN_STAMP:-$(date -u +%Y%m%dT%H%M%SZ)}"
RUNNER="${BENCH_RUNNER:-$(uname -s)-$(uname -m)}"
REPO_SLUG="${GITHUB_REPOSITORY:-xemantic/xemantic-typescript-compiler}"

# All values are passed via the environment (NOT string-interpolated into the
# heredoc) — the report text contains backticks/`$` that an unquoted heredoc would
# run as command substitution. The delimiter is quoted so bash leaves the body verbatim.
export B_OUT_DIR="$OUT_DIR" B_STAMP="$RUN_STAMP" B_REV="$XTSC_REV" B_REV_FULL="$XTSC_REV_FULL"
export B_PROJECT="$PROJECT" B_RUNNER="$RUNNER" B_SLUG="$REPO_SLUG" B_LABEL="$LABEL"
export B_TS_COMMIT="$TS_COMMIT8" B_FILES="$xtsc_files" B_LOC="$LOC"
export B_XSELF="$xtsc_self" B_XWALL="$xtsc_wall" B_XERR="$xtsc_err"
export B_TSC_VER="$TSC_VER"  B_TWALL="$tsc_wall"  B_TERR="$tsc_err"
export B_TSGO_VER="$TSGO_VER" B_GWALL="$tsgo_wall" B_GERR="$tsgo_err"

python3 - <<'PYEOF'
import os, re
g = lambda k, d="": os.environ.get(k, d)
i = lambda k: int(g(k) or 0)

out_dir  = g("B_OUT_DIR")
runs_dir = os.path.join(out_dir, "runs")
os.makedirs(runs_dir, exist_ok=True)

stamp, rev, rev_full = g("B_STAMP"), g("B_REV"), g("B_REV_FULL")
project, runner, slug, label = g("B_PROJECT"), g("B_RUNNER"), g("B_SLUG"), g("B_LABEL")
ts_commit, files, loc = g("B_TS_COMMIT"), i("B_FILES"), i("B_LOC")
rows = [  # name, version, wall_ms, errors
    ("xtsc", rev,           i("B_XWALL"), i("B_XERR")),
    ("tsc",  g("B_TSC_VER"),  i("B_TWALL"), i("B_TERR")),
    ("tsgo", g("B_TSGO_VER"), i("B_GWALL"), i("B_GERR")),
]
xtsc_self = i("B_XSELF")

def sec(ms): return f"{ms/1000:.2f}s" if ms else "n/a"
def tput(ms): return f"{loc*1000//ms:,}" if ms else "n/a"
xw, tw, gw = rows[0][2], rows[1][2], rows[2][2]
def ratio(a, b): return f"{a/b:.1f}×" if b else "n/a"

date_h = f"{stamp[0:4]}-{stamp[4:6]}-{stamp[6:8]}"
commit_url = f"https://github.com/{slug}/commit/{rev_full}"

# ---- per-run report ----
lines = [
    f"# Bench run {stamp}",
    "",
    f"- **xtsc revision**: [`{rev}`]({commit_url})",
    f"- **TypeScript pin**: `{ts_commit}` (typeScriptCommit)",
    f"- **Profile**: `{project}` — {files} files, {loc:,} LOC",
    f"- **Runner**: {runner}",
    f"- **Mode**: cold single process, with emit; wall-clock is the comparable metric",
]
if label:
    lines.append(f"- **Label**: {label}")
lines += [
    "",
    "| Compiler | Version | Wall | Throughput | Errors |",
    "|---|---|---:|---:|---:|",
]
for name, ver, ms, err in rows:
    lines.append(f"| {name} | {ver} | {sec(ms)} | {tput(ms)} LOC/s | {err} |")
lines += [
    "",
    f"xtsc self-reported time (excl. JVM startup/JIT): **{sec(xtsc_self)}**.",
    "",
    f"Relative (wall): xtsc is **{ratio(xw,tw)}** tsc and **{ratio(xw,gw)}** tsgo; "
    f"tsc is **{ratio(tw,gw)}** tsgo.",
    "",
    "> Errors are all env-legit offline `@types/node` artifacts (no `node_modules/@types` "
    "in CI); real tsc reports 0 on its own source. Counts differ slightly by how each "
    "compiler models the missing ambient env, not by real diagnostics.",
    "",
]
run_name = f"{stamp}-{rev}.md"
with open(os.path.join(runs_dir, run_name), "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(lines))

# ---- index (prepend newest row) ----
index = os.path.join(out_dir, "README.md")
header = (
    "# Bench history\n\n"
    "Three-way compile bench — **xtsc** vs the reference JS **tsc** vs the native "
    "**tsgo** — on the pinned TypeScript `compiler` profile "
    "(`src/compiler` @ typeScriptCommit). Generated by "
    "[`scripts/bench-3way.sh`](../scripts/bench-3way.sh) via "
    "[`.github/workflows/bench.yml`](../.github/workflows/bench.yml). One row per run, "
    "newest first. Wall-clock, cold, with emit — CI runners are shared so absolute "
    "numbers drift; the **ratios** and the **trend** are the signal.\n\n"
)
col = ("| Date | xtsc rev | xtsc | tsc | tsgo | xtsc/tsc | xtsc/tsgo | xtsc err | Run |\n"
       "|---|---|---:|---:|---:|---:|---:|---:|---|\n")
new_row = (f"| {date_h} | [`{rev}`]({commit_url}) | {sec(xw)} | {sec(tw)} | {sec(gw)} "
           f"| {ratio(xw,tw)} | {ratio(xw,gw)} | {rows[0][3]} | [report](runs/{run_name}) |\n")

existing_rows = ""
if os.path.exists(index):
    txt = open(index, encoding="utf-8").read()
    m = re.search(r"\|---.*?---\|\n((?:\|.*\n)*)", txt)
    if m:
        existing_rows = m.group(1)
with open(index, "w", encoding="utf-8", newline="\n") as f:
    f.write(header + col + new_row + existing_rows)

print(f"wrote {os.path.join('runs', run_name)} and updated index")
PYEOF

echo
echo "=== 3-way bench ($PROJECT @ $TS_COMMIT8) ==="
printf '  %-6s %8s ms wall  %6s errors\n' xtsc "$xtsc_wall" "$xtsc_err"
printf '  %-6s %8s ms wall  %6s errors  (%s)\n' tsc  "$tsc_wall"  "$tsc_err"  "$TSC_VER"
printf '  %-6s %8s ms wall  %6s errors  (%s)\n' tsgo "$tsgo_wall" "$tsgo_err" "$TSGO_VER"
