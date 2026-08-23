export function selfConstraint<T extends T>(x: T): number { return x; }
export function mappedConstraint<T extends { [P in T]: number }, K extends keyof T>(n: number, v: T, k: K) {
    n += v[k];
}
export function stricter<T, S extends T>(x: T, y: S): void { x = y; }
export const loose = function <T, S>(x: T, y: S): void {};
export const assigned: typeof loose = stricter;
