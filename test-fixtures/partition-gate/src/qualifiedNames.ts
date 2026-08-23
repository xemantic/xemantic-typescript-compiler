export namespace Q { export interface T { v: number } }
export const q1: Q.Missing = 1;
export const q2: Q.T.Deeper = 1;
export type UsedAsNs = Q.T.Nope;
