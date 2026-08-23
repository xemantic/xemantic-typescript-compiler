export class HasPrivate2 { private secret = 1 }
export function reachesPrivate(h: HasPrivate2) { return h.secret; }
export type KeyofPrim = string["length"];
export const kp: KeyofPrim = "no";
export function tpIndex<T extends { a: number }>(v: T) {
    const s: string = v["a"];
    return s;
}
export interface IdxOnly { [k: string]: number }
export const fromIdx: string = (null as unknown as IdxOnly)["anything"];
