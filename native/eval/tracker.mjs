// A loopback HTTP tracker for interop.sh, for clients that can't be given a peer directly (aria2, Transmission):
// GET /peer/<port>/announce answers that 127.0.0.1:<port> is the only peer, whatever the torrent.
import http from 'node:http'

const server = http.createServer((req, res) => {
  const match = /^\/peer\/(\d+)\/announce/.exec(req.url)
  if (!match) {
    res.writeHead(404).end()
    return
  }
  const port = Number(match[1])
  const peers = Buffer.from([127, 0, 0, 1, port >> 8, port & 255])
  res.writeHead(200, { 'Content-Type': 'text/plain' })
  res.end(Buffer.concat([Buffer.from('d8:intervali60e5:peers6:'), peers, Buffer.from('e')]))
})
server.listen(0, '127.0.0.1', () => console.log(`listening on ${server.address().port}`))
