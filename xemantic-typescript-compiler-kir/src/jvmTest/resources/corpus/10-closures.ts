const twice = (x: number): number => x * 2
console.log(twice(21))

function apply(f: (x: number) => number, value: number): number {
    return f(value)
}
console.log(apply(twice, 5))

function adder(by: number): (x: number) => number {
    return (x: number): number => x + by
}
const addThree = adder(3)
console.log(addThree(4))

let count = 0
const bump = (): void => {
    count = count + 1
}
bump()
bump()
console.log(count)

const numbers: number[] = [1, 2, 3]
const doubled = numbers.map((n) => n * 2)
console.log(doubled.join(","))
