#!/usr/bin/env bash
# Builds the tsgo-oracle dev tool (docs/goport-oracle.md).
#
# main.go imports typescript-go's internal/ packages, which Go only permits from inside the
# github.com/microsoft/typescript-go module. Instead of writing into typescript-go-repo (a
# read-only reference checkout), we compile main.go as the VIRTUAL package
# <typescript-go-repo>/cmd/xtsc-oracle via `go build -overlay`: the overlay maps that
# non-existent path to this directory's main.go, so the build happens inside the tsgo module
# (its go.mod, go.sum and module cache) while no file is created there.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
tsgo="$root/typescript-go-repo"
go="$root/tools/go/bin/go"
out="${1:-$root/build/goport/bin/tsgo-oracle}"
[[ -x "$go" ]] || { echo "build.sh: no Go toolchain at $go (see docs/tsgo-port-plan.md D1)" >&2; exit 2; }
[[ -f "$tsgo/go.mod" ]] || { echo "build.sh: no typescript-go checkout at $tsgo" >&2; exit 2; }
tag="$(git -C "$tsgo" describe --tags --exact-match 2>/dev/null || true)"
[[ "$tag" == "typescript/v7.0.2" ]] || echo "build.sh: WARNING typescript-go-repo is at '${tag:-untagged}', expected typescript/v7.0.2" >&2
work="$root/build/goport/oracle-go-build"
mkdir -p "$work" "$(dirname "$out")"
# cmd/xtsc-oracle/*.go is the tool; overlay/<pkg>/*.go are ADDED (never replacing an upstream file)
# to tsgo packages whose unexported runner/harness code the diagnostics oracle must call
# (docs/goport-diag-oracle.md).
cat > "$work/overlay.json" <<JSON
{"Replace": {
  "$tsgo/cmd/xtsc-oracle/main.go": "$here/main.go",
  "$tsgo/cmd/xtsc-oracle/diags.go": "$here/diags.go",
  "$tsgo/internal/testrunner/zz_xtsc_export.go": "$here/overlay/testrunner/xtsc_export.go",
  "$tsgo/internal/testutil/harnessutil/zz_xtsc_export.go": "$here/overlay/harnessutil/xtsc_export.go",
  "$tsgo/internal/repo/zz_xtsc_submodule.go": "$here/overlay/repo/xtsc_submodule.go"
}}
JSON
for f in internal/testrunner/zz_xtsc_export.go internal/testutil/harnessutil/zz_xtsc_export.go internal/repo/zz_xtsc_submodule.go; do
  [[ ! -e "$tsgo/$f" ]] || { echo "build.sh: $tsgo/$f exists on disk; the overlay must only ADD files" >&2; exit 2; }
done
cd "$tsgo"
GOTOOLCHAIN=local "$go" build -overlay "$work/overlay.json" -o "$out" ./cmd/xtsc-oracle
echo "built $out"
