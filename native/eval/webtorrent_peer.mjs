// WebTorrent as an independent BitTorrent peer for interop.sh. A test peer only. No DHT, local discovery, port mapping,
// trackers or uTP; it reaches only the peer it is given, and its listening port is never announced anywhere.
//
//   webtorrent_peer.mjs <node_modules directory> fetch <file.torrent> <host:port> <directory>
//   webtorrent_peer.mjs <node_modules directory> seed <file.torrent> <directory>
import { createHash } from 'node:crypto'
import { createReadStream, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import net from 'node:net'

const [modules, command, torrentFile, ...rest] = process.argv.slice(2)
const { default: WebTorrent } = await import(pathToFileURL(join(modules, 'webtorrent', 'index.js')).href)
if (command === 'probe') process.exit(0)
if (!['fetch', 'seed'].includes(command)) throw new Error('expected fetch or seed')
// WebTorrent 3 has no listen-host option. Restrict its synchronous TCP pool creation to loopback, then restore Node.
const listen = net.Server.prototype.listen
net.Server.prototype.listen = function (port, callback) {
  return listen.call(this, { port, host: '127.0.0.1' }, callback)
}
let client
try {
  client = new WebTorrent({ dht: false, lsd: false, utPex: false, tracker: false, natUpnp: false, natPmp: false, utp: false, webSeeds: false })
} finally {
  net.Server.prototype.listen = listen
}
// A stalled peer or an unready seed cannot hang the matrix forever.
setTimeout(() => {
  console.error('Error: WebTorrent transfer timed out after 60 seconds')
  process.exit(1)
}, 60_000).unref()
client.on('error', e => {
  console.log(`Error: ${e.message}`)
  process.exit(1)
})
const started = Date.now()
const directory = command === 'fetch' ? rest[1] : rest[0]
const torrent = client.add(readFileSync(torrentFile), { path: directory, announce: [] })
torrent.on('error', e => {
  console.log(`Error: ${e.message}`)
  process.exit(1)
})
if (command === 'fetch') {
  const peer = rest[0]
  if (!/^127\.0\.0\.1:\d+$/.test(peer)) throw new Error('peer must be on loopback')
  torrent.on('ready', () => torrent.addPeer(peer))
  torrent.on('done', () => {
    const hash = createHash('sha256')
    const path = join(directory, torrent.files[0].path)
    createReadStream(path)
      .on('data', d => hash.update(d))
      .on('end', () => {
        const seconds = ((Date.now() - started) / 1000).toFixed(1)
        console.log(`${hash.digest('hex')} ${torrent.files[0].length} bytes in ${seconds} s`)
        process.exit(0)
      })
  })
} else {
  torrent.on('done', () => console.log(`seeding on 127.0.0.1:${client.torrentPort}`))
}
