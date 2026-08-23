export function assertions<T>(v: unknown) {
    const a = <T>v;
    return a;
}
export const big = 123n;
export const invalidName = 1;
export type IsStr = (v: unknown) => v is string;
export const guard: IsStr = (v) => 1;
