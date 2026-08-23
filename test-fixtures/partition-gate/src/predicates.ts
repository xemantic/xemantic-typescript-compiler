declare function pick<T>(predicate: (x: {}) => x is T): T;
export const picked = pick((n): n is number => true);
export const asString2: string = picked;
export function narrows(x: { a: "A"; b: number } | { a: "C"; e: number }) {
    switch (x.a) {
        case x:
            break;
    }
}
