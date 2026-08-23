export type Distribute<T> = T extends string ? "s" : "n";
export type Applied2 = Distribute<number | string>;
export const ap: Applied2 = "x";
export type Deferred<T, U> = T extends U ? true : false;
export function def<T, U>(v: Deferred<T, U>) { const b: true = v; return b; }
export type RecAlias<T> = T extends [infer H, ...infer R] ? [H, ...RecAlias<R>] : [];
export const ra: RecAlias<[1, 2]> = ["no"];
