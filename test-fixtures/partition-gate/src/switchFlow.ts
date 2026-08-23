export type Disc = { k: "a"; v: number } | { k: "b"; s: string };
export function exhaust(d: Disc) {
    switch (d.k) {
        case "a": return d.v;
        case "b": return d.s;
        case "c": return 0;
    }
}
export function fall(n: number) {
    switch (n) {
        case 1: {
            const inCase = 1;
        }
        case 2:
            return inCase;
    }
}
