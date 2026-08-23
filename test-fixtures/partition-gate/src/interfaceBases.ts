export interface IA { m(): number }
export interface IB { m(): string }
export interface IC extends IA, IB {}
export interface Extending extends IA { m(): boolean }
export class Impl implements IA, IB {
    m(): number { return 1; }
}
