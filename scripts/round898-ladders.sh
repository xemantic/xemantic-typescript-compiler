#!/usr/bin/env bash
# (WARM.25) round 898 — the six amplification ladders, one JVM each.
#
# Three families, two MIRRORED rotations each (round 891's law, which that round
# learned the hard way: one 6-draw ladder read 53.6 ms/rep and its mirror 14.1
# on the SAME binary, because the leading draw is worth up to 15% and lands
# wholly on whichever arm ran first).
#
#   em — `EpochMap(localTypes)`, round 894's candidate (8)
#   es — `EpochSet(paramBindings)`, the same family's per-CALL-dominated twin
#        (35,015 copies of mean 1.1 entries): it is the second equation that
#        splits a copy's cost into its per-call and per-entry halves, which is
#        the whole reason round 891's per-entry derivation could be wrong.
#   al — `spineArgListOverlay`, round 894's candidate (6)
#
# `es` runs at 0/32/64 rather than 0/16/32 because its entry volume is a tenth
# of the others' and a 16x ladder would put its slope inside the draw spread.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round898
rm -f "$OUT/ladders.done"

run() { bash scripts/round898-copies.sh tier "$1" "$2" 2; }

run "copyampem32,copyampem16,copyampem0,copyampem0,copyampem16,copyampem32" emA
run "copyampem0,copyampem16,copyampem32,copyampem32,copyampem16,copyampem0" emB
run "copyampal32,copyampal16,copyampal0,copyampal0,copyampal16,copyampal32" alA
run "copyampal0,copyampal16,copyampal32,copyampal32,copyampal16,copyampal0" alB
run "copyampes64,copyampes32,copyampes0,copyampes0,copyampes32,copyampes64" esA
run "copyampes0,copyampes32,copyampes64,copyampes64,copyampes32,copyampes0" esB

date > "$OUT/ladders.done"
