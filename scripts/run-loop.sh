#!/usr/bin/env bash
# Continuous-progress loop for Phase 4 of the TypeScript compiler port.
#
# Each iteration starts a fresh `claude --dangerously-skip-permissions`
# session that reads SESSION-PROMPT.md and follows its protocol. Sessions
# are expected to commit + push their work and exit cleanly.
#
# Stop conditions (checked between iterations):
#   - touch STOP-LOOP            → exit before next iteration (file is
#                                  consumed when the loop sees it)
#   - Ctrl-C between iterations
#   - MAX_ITER env var, e.g.     MAX_ITER=3 ./scripts/run-loop.sh
#
# Safety: if a session exits with uncommitted changes the loop creates
# STOP-LOOP itself so a human can investigate before more sessions
# accumulate damage on top of partial state.
#
# Logs: logs/loop/session-<timestamp>.log

set -u

cd "$(git rev-parse --show-toplevel)"

LOG_DIR="logs/loop"
mkdir -p "$LOG_DIR"

iter=0
while true; do
    iter=$((iter + 1))

    if [ -f STOP-LOOP ]; then
        echo "STOP-LOOP file found — exiting before iteration $iter."
        rm -f STOP-LOOP
        exit 0
    fi

    if [ -n "${MAX_ITER:-}" ] && [ "$iter" -gt "$MAX_ITER" ]; then
        echo "MAX_ITER=$MAX_ITER reached — exiting."
        exit 0
    fi

    timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
    log="$LOG_DIR/session-$timestamp.log"

    {
        echo "=== Iteration $iter started at $timestamp ==="
        echo "--- pre-session git status ---"
        git status --short
        echo "--- pre-session HEAD ---"
        git log -1 --oneline
        echo "--- session output ---"
    } | tee "$log"

    claude --dangerously-skip-permissions \
        "Read @SESSION-PROMPT.md and follow the instructions in it." \
        2>&1 | tee -a "$log"

    {
        echo "--- post-session git status ---"
        git status --short
        echo "--- post-session HEAD ---"
        git log -1 --oneline
        echo "=== Iteration $iter ended at $(date -u +%Y%m%dT%H%M%SZ) ==="
    } | tee -a "$log"

    if ! git diff --quiet || ! git diff --cached --quiet; then
        echo "ABORT: session left uncommitted changes — see $log." | tee -a "$log"
        touch STOP-LOOP
    fi

    sleep 5
done
