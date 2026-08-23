export function tooMany<T>(a: T): T { return a; }
const t = tooMany<number, string>(1);
export interface Constrained<T extends string> { v: T }
export type Bad = Constrained<number>;
export type Defaults<T = number, U extends T = string> = [T, U];
export type SelfConstraint<T extends SelfConstraint<T>> = T;
export { t };
