import mitt from './mitt'

type DemoEvents = {
	greet: string
	count: number
}

const emitter = mitt<DemoEvents>()

emitter.on('greet', (name) => {
	console.log('hello ' + name)
})
emitter.on('count', (n) => {
	console.log('count ' + n)
})
emitter.on('*', (type) => {
	console.log('* ' + type)
})

emitter.emit('greet', 'world')
emitter.emit('count', 7)

emitter.off('greet')
emitter.emit('greet', 'ignored')
emitter.emit('count', 8)
