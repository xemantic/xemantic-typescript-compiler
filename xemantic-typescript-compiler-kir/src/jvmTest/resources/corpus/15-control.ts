const words: string[] = ["alpha", "beta", "gamma"]
for (const word of words) {
    console.log(word.toUpperCase())
}

let seen = 0
for (const n of [1, 2, 3, 4]) {
    if (n === 2) continue
    if (n === 4) break
    seen += n
}
console.log(seen)

function classify(value: number): string {
    switch (value) {
        case 0:
            return "zero"
        case 1:
        case 2:
            return "small"
        default:
            return "large"
    }
}
console.log(classify(0))
console.log(classify(1))
console.log(classify(2))
console.log(classify(9))

function label(kind: string): string {
    let out = ""
    switch (kind) {
        case "a":
            out += "A"
        case "b":
            out += "B"
            break
        case "c":
            out += "C"
            break
    }
    return out
}
console.log(label("a"))
console.log(label("b"))
console.log(label("c"))
console.log(label("z"))

let countdown = 3
do {
    countdown -= 1
} while (countdown > 0)
console.log(countdown)

function risky(fail: boolean): string {
    try {
        if (fail) {
            throw "boom"
        }
        return "fine"
    } catch (e) {
        return "caught " + e
    } finally {
        console.log("cleanup")
    }
}
console.log(risky(false))
console.log(risky(true))
