// A minimal HTTP/HTTPS file server with byte ranges, written on Node's own http stack: an independent web seed
// (BEP 19) for native/eval/interop.sh. Loopback only.
//
//   node webseed.mjs <root-dir> [key.pem cert.pem]
// Prints "listening on <port>" once ready.
import { createReadStream, statSync } from 'node:fs';
import { readFileSync } from 'node:fs';
import http from 'node:http';
import https from 'node:https';
import path from 'node:path';

const [root, key, cert] = process.argv.slice(2);
if (!root) {
  console.error('usage: node webseed.mjs <root-dir> [key.pem cert.pem]');
  process.exit(2);
}
const base = path.resolve(root);

function handle(req, res) {
  let file;
  try {
    file = path.resolve(base, '.' + decodeURIComponent(new URL(req.url, 'http://x').pathname));
  } catch {
    res.writeHead(400).end();
    return;
  }
  if (!file.startsWith(base + path.sep)) {
    res.writeHead(403).end();
    return;
  }
  let size;
  try {
    const st = statSync(file);
    if (!st.isFile()) throw new Error('not a file');
    size = st.size;
  } catch {
    res.writeHead(404).end();
    return;
  }
  const range = /^bytes=(\d+)-(\d*)$/.exec(req.headers.range || '');
  if (!range) {
    res.writeHead(200, { 'Content-Length': size, 'Accept-Ranges': 'bytes' });
    if (req.method === 'HEAD') return res.end();
    createReadStream(file).pipe(res);
    return;
  }
  const start = Number(range[1]);
  const end = range[2] === '' ? size - 1 : Math.min(Number(range[2]), size - 1);
  if (start > end || start >= size) {
    res.writeHead(416, { 'Content-Range': `bytes */${size}` }).end();
    return;
  }
  res.writeHead(206, { 'Content-Length': end - start + 1, 'Content-Range': `bytes ${start}-${end}/${size}`, 'Accept-Ranges': 'bytes' });
  if (req.method === 'HEAD') return res.end();
  createReadStream(file, { start, end }).pipe(res);
}

const server = key ? https.createServer({ key: readFileSync(key), cert: readFileSync(cert) }, handle) : http.createServer(handle);
server.listen(0, '127.0.0.1', () => console.log(`listening on ${server.address().port}`));
