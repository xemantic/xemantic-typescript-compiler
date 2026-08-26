#!/usr/bin/env bash
# (CHK.45) diff two captures produced by chk45-capture.sh
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
A="build/bench/chk45/$1"; B="build/bench/chk45/$2"
status=0
for f in "$A"/*.txt; do
  name="$(basename "$f")"
  [[ -f "$B/$name" ]] || { echo "MISSING $B/$name"; status=1; continue; }
  added=$(comm -13 "$f" "$B/$name" | wc -l)
  removed=$(comm -23 "$f" "$B/$name" | wc -l)
  echo "${name%.txt}: added=$added removed=$removed"
  if [[ "$added" -ne 0 || "$removed" -ne 0 ]]; then
    status=1
    comm -13 "$f" "$B/$name" | sed 's/^/  + /'
    comm -23 "$f" "$B/$name" | sed 's/^/  - /'
  fi
done
echo "diff exit=$status"
exit $status
