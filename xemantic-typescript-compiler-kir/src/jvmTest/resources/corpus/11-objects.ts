interface Point {
    x: number
    label: string
    show(prefix: string): string
}

function makePoint(x: number, label: string): Point {
    return {
        x: x,
        label,
        show(prefix: string): string {
            return prefix + label + "@" + x
        }
    }
}

const p = makePoint(3, "origin")
console.log(p.x)
console.log(p.label)
console.log(p.show(">"))
p.x = 4
console.log(p.x)

const inline = { a: 1, b: "two" }
console.log(inline.a)
console.log(inline.b)
