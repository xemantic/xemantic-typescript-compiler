export type Homo<T> = { [K in keyof T]: T[K] };
export type AsClause<T> = { [K in keyof T as `get${string & K}`]: () => T[K] };
export const ac: AsClause<{ a: number }> = { getA: () => "s" };
export type Removed<T> = { -readonly [K in keyof T]-?: T[K] };
export const rem: Removed<{ readonly a?: number }> = {};
export type IndexedConstraint<T extends object> = { [K in keyof T]: T[K]["nope"] };
