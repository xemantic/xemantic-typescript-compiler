export async function ops(p: Promise<number[]>) {
    return p.length;
}
export function notAwaited(p: Promise<number>) {
    return p.toFixed(2);
}
export async function arith(p: Promise<number>) { return p * 2; }
export const sync: number = ops(Promise.resolve([]));
