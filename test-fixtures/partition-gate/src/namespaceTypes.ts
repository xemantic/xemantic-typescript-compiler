export namespace ValuesOnly { export var hidden = 1; }
export const usedAsType: ValuesOnly.hidden = 1;
export namespace HasTypes { export interface T { v: number } }
export const typeofNs: typeof HasTypes = 1;
export type AliasedNs = HasTypes.T;
export const aliasRef: AliasedNs = { v: "no" };
export import Aliased = HasTypes;
