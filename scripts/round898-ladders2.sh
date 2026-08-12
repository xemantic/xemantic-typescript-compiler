#!/usr/bin/env bash
# (WARM.25) round 898 batch 2 — the CONFIRMATION ladder.
#
# Batch 1 (round898-ladders.sh) ran three arms x 4 draws and its two mirrored
# rotations disagreed by 2x on `em` and 4x on `al`, with `es` reading pure noise.
# The reason is visible in the raw draws: an `r = 0` arm ranges 5,100-5,869 ms in
# ONE binary (a 15% spread — the ladder is still warming at rebuild 14, exactly
# as the 2026-08-10 warm-up note says), so a 350 ms effect sits at SNR ~ 1.
#
# Two changes, both aimed at that and nothing else:
#
#   * TWO arms, not three. The slope is a straight difference of two arm means,
#     so every draw buys signal instead of a third of the ladder buying a
#     mid-point nobody differences against.
#   * r = 64 instead of 32, and EIGHT draws per process in a balanced palindrome
#     (`64,0,0,64,64,0,0,64`), which cancels a LINEAR drift exactly by
#     construction and gives 4 draws per arm per process.
#
# Two processes per family, so 8 draws per arm. Nonlinearity is the price of a
# large `r` and is stated rather than assumed: at r = 64 the `em` arm allocates
# ~22 M extra map entries per rebuild, so if anything it over-reads the per-copy
# cost through GC — which is the SAFE direction for this round's question, since
# what is being tested is whether the true cost is 11 ms or round 894's 38.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round898
rm -f "$OUT/ladders2.done"

run() { bash scripts/round898-copies.sh tier "$1" "$2" 2; }

EM="copyampem64,copyampem0,copyampem0,copyampem64,copyampem64,copyampem0,copyampem0,copyampem64"
AL="copyampal64,copyampal0,copyampal0,copyampal64,copyampal64,copyampal0,copyampal0,copyampal64"

run "$EM" em2A
run "$AL" al2A
run "$EM" em2B
run "$AL" al2B

date > "$OUT/ladders2.done"
