#!/usr/bin/env python3
"""(HASH.1)(b) round 890 — bucket distribution of every packed-Long key population.

Reads a `HASHKEYCENSUS` dump (the throwaway census patch writes one raw packed key
per line) and models `java.util.HashMap`'s bucket occupancy exactly:

    Long.hashCode(v) = (int)(v ^ (v >>> 32))
    HashMap.hash(h)  = h ^ (h >>> 16)
    index            = hash & (capacity - 1)
    a bucket TREEIFIES at 8 entries once the table has grown to 64

for the population as packed, and for the same population after the bijective
golden-ratio finalizer `key * 0x9E3779B97F4A7C15` that round 889 applied to
`nodeKey`. Multiplication by an ODD constant modulo 2^64 is a bijection, so the
key stays exact; only its bit pattern moves.

Usage: round890_bucket_model.py <census-dump> [more dumps...]
"""
import sys
from collections import defaultdict

MASK64 = (1 << 64) - 1
MIX = 0x9E3779B97F4A7C15


def to_signed64(v):
    v &= MASK64
    return v - (1 << 64) if v >= (1 << 63) else v


def long_hash(v):
    """java.lang.Long.hashCode — (int)(v ^ (v >>> 32)) over the UNSIGNED shift."""
    u = v & MASK64
    h = (u ^ (u >> 32)) & 0xFFFFFFFF
    return h


def bucket(key, capacity):
    h = long_hash(key)
    h ^= (h >> 16)
    return h & (capacity - 1)


def capacity_for(n):
    """The table a default HashMap ends at after n successive puts (load factor .75)."""
    cap = 16
    while n > cap * 3 // 4:
        cap <<= 1
    return cap


def stats(keys, capacity):
    counts = defaultdict(int)
    for k in keys:
        counts[bucket(k, capacity)] += 1
    used = len(counts)
    worst = max(counts.values()) if counts else 0
    treeified_keys = sum(c for c in counts.values() if c >= 8)
    return used, worst, treeified_keys


def main(paths):
    pops = defaultdict(set)
    live = {}
    for path in paths:
        with open(path) as f:
            for line in f:
                if line.startswith("K\t"):
                    _, name, k = line.rstrip("\n").split("\t")
                    pops[name].add(int(k))
                elif line.startswith("LIVE\t"):
                    _, name, v = line.rstrip("\n").split("\t")
                    live[name] = max(live.get(name, 0), int(v))

    rows = []
    for name, keys in pops.items():
        keys = list(keys)
        n = len(keys)
        cap = capacity_for(n)
        u_raw, w_raw, t_raw = stats(keys, cap)
        mixed = [to_signed64(k * MIX) for k in keys]
        u_mix, w_mix, t_mix = stats(mixed, cap)
        rows.append((name, n, cap, u_raw, w_raw, t_raw / n if n else 0,
                     u_mix, w_mix, t_mix / n if n else 0, live.get(name)))

    rows.sort(key=lambda r: -r[5])
    hdr = ("population", "keys", "cap", "used", "max", "tree%",
           "used'", "max'", "tree%'", "maxLive")
    print(f"{hdr[0]:<34}{hdr[1]:>9}{hdr[2]:>9}{hdr[3]:>9}{hdr[4]:>8}{hdr[5]:>8}"
          f"{hdr[6]:>9}{hdr[7]:>7}{hdr[8]:>8}{hdr[9]:>9}")
    print("-" * 110)
    for (name, n, cap, ur, wr, tr, um, wm, tm, lv) in rows:
        print(f"{name:<34}{n:>9}{cap:>9}{ur:>9}{wr:>8}{tr * 100:>7.1f}%"
              f"{um:>9}{wm:>7}{tm * 100:>7.1f}%{(lv if lv is not None else '-'):>9}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1:])
