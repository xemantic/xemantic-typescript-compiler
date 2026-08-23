export function bs() {
    {
        let l = 1;
        let l = 2;
    }
    if (true) function inBlock() {}
    for (let i of [1, 2]) { i = 1; }
    try { } catch (e) { let e = 1; }
    const later = readEarly;
    let readEarly = 3;
    return later;
}
