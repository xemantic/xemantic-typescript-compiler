const name: string = "TypeScript"
console.log(name.length)
console.log(name.charAt(0))
console.log(name.indexOf("Script"))
console.log(name.slice(4))
console.log(name.slice(0, 4))
console.log(name.slice(-6))
console.log(name.substring(4, 0))
console.log(name.toUpperCase())
console.log(name.toLowerCase())
console.log(name.startsWith("Type"))
console.log(name.includes("peSc"))
console.log("  padded  ".trim())
console.log("ab".repeat(3))
console.log("a-b-c".split("-").join("+"))
console.log("a-b-c".replace("-", "+"))

const count = 3
console.log(`there are ${count} items in ${name}`)
console.log(`${count + 1} next`)
const plain = `no substitutions`
console.log(plain)
