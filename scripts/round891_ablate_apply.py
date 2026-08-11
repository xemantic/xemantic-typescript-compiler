#!/usr/bin/env python3
"""(WARM.18) round 891 — apply ONE deliberate mistake to VarScopeStack.kt.

Round 807's law: a combined ablation cannot attribute, so each arm is a single
edit and the driver reverts between arms. Every arm must COMPILE (round 808: a
build failure reports `ran 0, failed 0`, which reads exactly like "the mistake
changed nothing").
"""
import sys

SRC = "xemantic-typescript-compiler-core/src/commonMain/kotlin/VarScopeStack.kt"

ARMS = {
    # The mistake the whole scheme turns on: the last restore applied to a key
    # must be its FIRST record, i.e. the value the frame inherited.
    "A1": (
        """        var i = undoKeys.size - 1
        while (i >= mark) {
            val k = undoKeys.removeAt(i)
            val v = undoVals.removeAt(i)
            if (v == null) live.remove(k) else live[k] = v
            i--
        }""",
        """        var i = mark
        while (i < undoKeys.size) {
            val k = undoKeys[i]
            val v = undoVals[i]
            if (v == null) live.remove(k) else live[k] = v
            i++
        }
        while (undoKeys.size > mark) {
            undoKeys.removeAt(undoKeys.size - 1)
            undoVals.removeAt(undoVals.size - 1)
        }""",
    ),
    # The scope closes and restores nothing — every write leaks outward.
    "A2": (
        """            val k = undoKeys.removeAt(i)
            val v = undoVals.removeAt(i)
            if (v == null) live.remove(k) else live[k] = v
            i--""",
        """            undoKeys.removeAt(i)
            undoVals.removeAt(i)
            i--""",
    ),
    # The pre-write state is recorded as ABSENT, so a SHADOWED key is dropped at
    # the pop instead of coming back with its outer value.
    "A3": (
        "        undoVals.add(live[key])",
        "        undoVals.add(null)",
    ),
    # `putAll` bypasses the log — the `extraVarTypes` (`this.X`) seeds of a
    # method/ctor frame then outlive the member.
    "A4": (
        """            for ((k, v) in from) {
                record(k)
                live[k] = v
            }""",
        """            for ((k, v) in from) {
                live[k] = v
            }""",
    ),
    # Every scope claims the whole log, so one pop unwinds every open scope.
    "A5": (
        "        marks.add(undoKeys.size)",
        "        marks.add(0)",
    ),
    # A write made at file-root scope is recorded into a log nothing replays.
    "A6": (
        """        if (marks.isEmpty()) return
        undoKeys.add(key)""",
        """        undoKeys.add(key)""",
    ),
    # A removal inside a scope is not undone at the pop.
    "A7": (
        """        override fun remove(key: String): String? {
            record(key)
            return live.remove(key)
        }""",
        """        override fun remove(key: String): String? {
            return live.remove(key)
        }""",
    ),
    # The file boundary keeps the entries — a cross-FILE annotation leak.
    "A8": (
        """    fun reset() {
        live.clear()""",
        """    fun reset() {""",
    ),
}


def main() -> int:
    arm = sys.argv[1]
    if arm not in ARMS:
        print(f"unknown arm: {arm}", file=sys.stderr)
        return 2
    old, new = ARMS[arm]
    with open(SRC, encoding="utf-8") as f:
        s = f.read()
    if s.count(old) != 1:
        print(f"{arm}: anchor matched {s.count(old)} times", file=sys.stderr)
        return 3
    out = s.replace(old, new)
    with open(SRC, "w", encoding="utf-8") as f:
        f.write(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
