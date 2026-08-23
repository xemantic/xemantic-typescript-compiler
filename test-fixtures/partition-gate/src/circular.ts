export type Alias1 = Alias2;
export type Alias2 = Alias1;
export interface Rec1 extends Rec2 {}
export interface Rec2 extends Rec1 {}
export class Circ1 extends Circ2 {}
export class Circ2 extends Circ1 {}
export type Mapped1<T> = { [K in keyof Mapped1<T>]: T };
