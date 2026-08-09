"""(WARM.16) round 869 — inject ONE deliberate mistake into AnnScopeStack.kt.

Each arm is a COMPILING mistake (round 808: a compile error makes the driver
print `ran 0, failed 0`, which is indistinguishable from "the mistake changed
nothing"). Exits non-zero if the text it expects is not there, so an arm that
silently stopped applying is an error rather than a green.
"""
import sys

P = 'xemantic-typescript-compiler-core/src/commonMain/kotlin/AnnScopeStack.kt'

RESTORE = "            if (v == null) live.remove(k) else live[k] = v"

POP_BODY = """        var i = undoKeys.size - 1
        while (i >= mark) {
            val k = undoKeys.removeAt(i)
            val v = undoVals.removeAt(i)
            if (v == null) live.remove(k) else live[k] = v
            i--
        }"""

ARMS = {
    # pop() drops the frame's slice without restoring anything it shadowed.
    'A1': (POP_BODY, """        var i = undoKeys.size - 1
        while (i >= mark) {
            undoKeys.removeAt(i)
            undoVals.removeAt(i)
            i--
        }"""),
    # pop() replays this frame's slice FORWARD instead of in reverse.
    'A2': (POP_BODY, """        var i = mark
        while (i < undoKeys.size) {
            val k = undoKeys[i]
            val v = undoVals[i]
            if (v == null) live.remove(k) else live[k] = v
            i++
        }
        while (undoKeys.size > mark) {
            undoKeys.removeAt(undoKeys.size - 1)
            undoVals.removeAt(undoVals.size - 1)
        }"""),
    # put() records the NEW value, so a pop restores the inner scope's binding.
    'A3': ("        undoVals.add(live[name])", "        undoVals.add(type)"),
    # put() persists with no frame open, instead of dropping the write.
    'A4': ("""        if (owners.isEmpty()) return
        undoKeys.add(name)""",
           """        if (owners.isEmpty()) { live[name] = type; return }
        undoKeys.add(name)"""),
    # push() records mark 0, so a pop unwinds the WHOLE log, not its own slice.
    'A5': ("        marks.add(undoKeys.size)", "        marks.add(0)"),
    # pop() leaves a key the frame INTRODUCED in the live map.
    'A6': (RESTORE, "            if (v != null) live[k] = v"),
    # topOwner() answers the OUTERMOST frame.
    'A7': ("    fun topOwner(): Node? = if (owners.isEmpty()) null else owners[owners.size - 1]",
           "    fun topOwner(): Node? = if (owners.isEmpty()) null else owners[0]"),
}

arm = sys.argv[1]
if arm not in ARMS:
    sys.exit(f"unknown arm: {arm}")
old, new = ARMS[arm]
s = open(P, encoding='utf-8').read()
if s.count(old) != 1:
    sys.exit(f"{arm}: expected exactly one occurrence, found {s.count(old)}")
open(P, 'w', encoding='utf-8').write(s.replace(old, new, 1))
