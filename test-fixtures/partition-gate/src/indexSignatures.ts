export interface Bag {
    [key: string]: number;
    named: string;
}
export interface Both {
    [k: number]: string;
    [k: string]: boolean;
}
export type Rec = Record<string, number>;
const r: Rec = { a: "no" };
export { r };
