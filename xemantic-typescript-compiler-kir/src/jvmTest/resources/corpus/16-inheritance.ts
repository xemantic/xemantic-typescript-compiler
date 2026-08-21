class Shape {
    name: string = "shape"
    sides: number = 0
    describe(): string {
        return this.name + " with " + this.sides + " sides"
    }
    area(): number {
        return 0
    }
}

class Square extends Shape {
    side: number
    constructor(side: number) {
        super()
        this.name = "square"
        this.sides = 4
        this.side = side
    }
    area(): number {
        return this.side * this.side
    }
    describe(): string {
        return super.describe() + ", area " + this.area()
    }
}

class Counter {
    static total: number = 0
    private count: number = 0
    static reset(): void {
        Counter.total = 0
    }
    bump(): number {
        this.count = this.count + 1
        Counter.total = Counter.total + 1
        return this.count
    }
    get doubled(): number {
        return this.count * 2
    }
    set value(next: number) {
        this.count = next
    }
}

const square = new Square(3)
console.log(square.describe())
console.log(square.area())

const shape: Shape = square
console.log(shape.area())
console.log(shape instanceof Square)
console.log(new Shape() instanceof Square)

const counter = new Counter()
counter.bump()
counter.bump()
console.log(counter.doubled)
counter.value = 10
console.log(counter.doubled)
console.log(Counter.total)
Counter.reset()
console.log(Counter.total)
