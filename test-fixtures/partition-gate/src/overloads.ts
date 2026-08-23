export class Ov {
    m(a: number): void;
    m(a: string, b: number): void;
    m(a: number): void {}
}
export declare function amb(a: number): void;
export declare function amb(a: string): void;
export interface Iface { (a: number): void; new (a: string): Iface }
const made: Iface = null as unknown as Iface;
made("no");
