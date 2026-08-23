export class NoArg<T> {
    foo(): NoArg { return null as never; }
}
export interface IdName {}
declare const idn: IdName;
export class ShadowsTp<IdName> {
    x: IdName;
    fn() { this.x = idn; }
}
