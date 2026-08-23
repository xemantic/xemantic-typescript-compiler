export const tup: [number, string] = [1, "a"];
const [t1, t2, t3] = tup;
export const ro: readonly [number] = [1];
const mut: [number] = ro;
export function spreadInto(a: number, b: number) {}
const args: number[] = [1, 2];
spreadInto(...args);
export { t1, t2, t3, mut };
