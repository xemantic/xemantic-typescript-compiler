#!/usr/bin/env bash
#
# (TSGO.6) The -tsgo native-image gate from a FRESH checkout, as the `tsgo-native-image` job of
# .github/workflows/native.yml runs it (it also runs locally):
#
#   1. tsgo 7.0.2 at tools/tsgo-7.0.2 — the official `@typescript/typescript-linux-x64` tarball from the npm
#      registry (no Node needed), its sha1 pinned below; the tree is byte-identical to the local reference.
#   2. the 8 tsc profiles under build/bench (`bench-compile-tsc.sh --materialize-only`).
#   3. the PGO image (`scripts/tsgo-native-image-pgo.sh`, Oracle GraalVM — trained on tsc's compiler profile).
#   4. tsgo's own output recorded (`scripts/tsgo-cli-oracle.py`) for the cases a fresh checkout can reproduce —
#      the 8 profiles (check-only and emit), the command-line cases and the `-b` / `--incremental` fixtures; the census libraries, cronstrue, marked
#      and the type-oracle projects are local data — then replayed through the image
#      (`scripts/tsgo-cli-native-replay.py`), which FAILS on any difference in stdout, exit status or written files.
#
# Usage: scripts/tsgo-native-ci.sh --graalvm <ORACLE_GRAALVM_HOME> [--workers N]
set -euo pipefail
R="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
GRAAL=""; WORKERS=2
while [[ $# -gt 0 ]]; do
  case "$1" in
    --graalvm) GRAAL=$2; shift 2 ;;
    --workers) WORKERS=$2; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[[ -n $GRAAL ]] || { echo "usage: $0 --graalvm <ORACLE_GRAALVM_HOME> [--workers N]" >&2; exit 2; }
cd "$R"

TSGO_URL=https://registry.npmjs.org/@typescript/typescript-linux-x64/-/typescript-linux-x64-7.0.2.tgz
TSGO_SHA1=73269cb0baba50aea0ca060445a6b88e583f1ce2
if [[ ! -x tools/tsgo-7.0.2/lib/tsc ]]; then
  tmp=$(mktemp -d)
  curl -sSfL -o "$tmp/tsgo.tgz" "$TSGO_URL"
  echo "$TSGO_SHA1  $tmp/tsgo.tgz" | sha1sum -c --quiet
  tar xzf "$tmp/tsgo.tgz" -C "$tmp"
  mkdir -p tools
  mv "$tmp/package" tools/tsgo-7.0.2
  rm -r -- "$tmp"
fi
tools/tsgo-7.0.2/lib/tsc --version

# Only when absent: a dev tree's typescript-repo may have moved past its profiles' commit, and a second set
# (other suffix) would double the recorder's profile cases and change which one PGO trains on.
if (( $(find build/bench -maxdepth 2 -path 'build/bench/tsc-*/tsconfig.json' 2>/dev/null | wc -l) < 8 )); then
  scripts/bench-compile-tsc.sh --project all --materialize-only
fi

scripts/tsgo-native-image-pgo.sh --graalvm "$GRAAL"

CASES=$(python3 scripts/tsgo-cli-oracle.py --list | grep -E '^(profile|cli|build|incremental)-' | paste -sd, -)
[[ $(tr ',' '\n' <<<"$CASES" | grep -c '^profile-') -ge 16 ]] || { echo "REFUSED: fewer than 16 profile cases: $CASES" >&2; exit 1; }
python3 scripts/tsgo-cli-oracle.py --only "$CASES" --workers "$WORKERS"
python3 scripts/tsgo-cli-native-replay.py --bin xemantic-typescript-compiler-tsgo/build/native/xtsc-tsgo-pgo --only "$CASES"
