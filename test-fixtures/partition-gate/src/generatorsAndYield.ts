export function* g1(): Generator<number> {
    yield;
    yield "no";
}
export async function* g2() { yield 1; }
export function* g3<T>(v: T) { yield v; }
const consumed: string = g3(1).next().value;
export { consumed };
