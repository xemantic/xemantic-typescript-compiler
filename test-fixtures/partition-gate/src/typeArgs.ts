export class Holder<T> {
    static staticUse: T;
    instanceUse: T = null as unknown as T;
}
export type Idx<T> = T["missing"];
export type KeyofAccess<T extends object> = T[keyof T];
const k: KeyofAccess<{ a: number }> = "s";
export { k };
