const names: string[] = ["ada", "grace"]
names.push("alan")
console.log(names.length)
console.log(names[0])
console.log(names[2])
console.log(names[7])
names[1] = "hopper"
console.log(names.join("-"))
console.log(names.indexOf("hopper"))

const numbers: number[] = [3, 1, 2]
let total = 0
for (let i = 0; i < numbers.length; i = i + 1) {
    total = total + numbers[i]
}
console.log(total)
const popped = numbers.pop()
console.log(popped)
console.log(numbers.length)
