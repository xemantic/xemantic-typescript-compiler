export class Prot {
    protected value = 1;
    protected static sv = 2;
}
export class SubProt extends Prot {
    read(other: Prot) { return other.value; }
}
export const outside = new Prot().value;
export function viaThis(this: Prot) { this.value = 2; }
