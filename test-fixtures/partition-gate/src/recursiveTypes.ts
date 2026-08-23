export var recLit = { self: recLit };
declare var unionSelf: { prop: number } | { prop: TSelf };
export type TSelf = typeof unionSelf;
declare var readBack: TSelf;
export const asString: string = readBack;
export type Expanding<T, K extends string> = T | { [P in K]: Expanding<T, K> }[K];
export type Expanded = Expanding<number, "M">;
