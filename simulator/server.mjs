// Stream's local fixture service on 127.0.0.1:58133, reached from the emulator through adb reverse. It serves the
// generated torrents and their expected contents; the real BitTorrent seeder (dtv-fixture seed) runs separately on
// 58134. Nothing here runs in the app's process, so it never counts toward the app's memory.
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { readFile, stat } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { join, resolve, sep, extname } from 'node:path';
import { addonFixtures } from './addon-fixtures.mjs';
import { liveRoom, inputCommand } from './live-room.mjs';

const run = promisify(execFile);
const [adb, serial, avd, fixtureTool] = process.argv.slice(2);
if (!/^emulator-\d+$/.test(serial || '')) throw Error('An emulator serial is required');
const here = fileURLToPath(new URL('.', import.meta.url));
const cache = join(here, '.cache');
const media = join(cache, 'media');
const origin = 'http://127.0.0.1:58133';
const port = 58133;

async function verify() {
  const { stdout } = await run(adb, ['-s', serial, 'shell', 'getprop', 'ro.kernel.qemu']);
  if (stdout.trim() !== '1') throw Error('Refusing a non-emulator device');
  const { stdout: name } = await run(adb, ['-s', serial, 'emu', 'avd', 'name']);
  if (name.trim().split(/\r?\n/)[0] !== avd) throw Error('Unexpected emulator at this port');
}

async function sha256(file) {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(file)) hash.update(chunk);
  return hash.digest('hex');
}

// What each torrent holds, with every file's hash, so tests prove the bytes they read.
async function buildIndex() {
  const fixtures = JSON.parse(await readFile(join(here, 'fixtures.json'), 'utf8'));
  const index = [];
  for (const f of fixtures) {
    const torrent = join(cache, 'torrents', f.torrent);
    const { stdout } = await run(fixtureTool, ['info', torrent]);
    const info = JSON.parse(stdout);
    const files = [];
    for (const file of info.files) {
      const path = join(media, f.save, file.path);
      files.push({ ...file, sha256: await sha256(path) });
    }
    index.push({ name: f.name, about: f.about, torrent: `${origin}/torrents/${encodeURIComponent(f.torrent)}`,
      v1: info.v1, v2: info.v2, pieceLength: info.pieceLength, pieces: info.pieces, files });
  }
  return index;
}

await verify();
const index = await buildIndex();
const serveAddon = addonFixtures(index, origin);
let capture;
const types = { '.m3u8': 'application/x-mpegURL', '.mpd': 'application/dash+xml', '.ts': 'video/mp2t', '.m4s': 'video/iso.segment', '.vtt': 'text/vtt', '.jpg': 'image/jpeg', '.mp4': 'video/mp4', '.mkv': 'video/x-matroska', '.torrent': 'application/x-bittorrent' };

function serveFile(req, res, root, relative) {
  const file = resolve(root, '.' + relative);
  if (!file.startsWith(root.endsWith(sep) ? root : root + sep)) { res.writeHead(403).end(); return; }
  return stat(file).then(info => {
    let start = 0, end = info.size - 1;
    res.setHeader('Content-Type', types[extname(file)] || 'application/octet-stream');
    res.setHeader('Accept-Ranges', 'bytes');
    if (req.headers.range) {
      const m = /^bytes=(\d+)-(\d*)$/.exec(req.headers.range);
      if (!m) { res.writeHead(416, { 'Content-Range': `bytes */${info.size}` }).end(); return; }
      start = Number(m[1]);
      end = m[2] ? Math.min(Number(m[2]), end) : end;
      if (start > end) { res.writeHead(416, { 'Content-Range': `bytes */${info.size}` }).end(); return; }
      res.statusCode = 206;
      res.setHeader('Content-Range', `bytes ${start}-${end}/${info.size}`);
    }
    res.setHeader('Content-Length', end - start + 1);
    if (req.method === 'HEAD') { res.end(); return; }
    createReadStream(file, { start, end }).on('error', () => res.destroy()).pipe(res);
  });
}

const server = createServer(async (req, res) => {
  try {
    if (req.method === 'POST' && req.url === '/input') {
      if (req.headers.origin !== origin || req.headers['content-type'] !== 'application/json') { res.writeHead(403).end('Same-origin JSON required'); return; }
      let body = ''; for await (const chunk of req) { body += chunk; if (body.length > 1024) { res.writeHead(413).end(); return; } }
      const input = JSON.parse(body); await verify();
      const { stdout: size } = await run(adb, ['-s', serial, 'shell', 'wm', 'size']);
      const command = inputCommand(input, size); await verify(); await run(adb, ['-s', serial, 'shell', 'input', ...command]);
      res.writeHead(200, { 'Content-Type': 'application/json' }).end('{"ok":true}'); return;
    }
    if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405).end(); return; }
    const path = decodeURIComponent(new URL(req.url, origin).pathname);
    res.setHeader('Cache-Control', 'no-store');
    if (path === '/') { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }).end(liveRoom(serial, avd)); return; }
    if (path === '/screen.png') {
      capture ??= verify().then(() => run(adb, ['-s', serial, 'exec-out', 'screencap', '-p'], { encoding: 'buffer', maxBuffer: 12 << 20 })).finally(() => { capture = null; });
      const { stdout } = await capture; res.writeHead(200, { 'Content-Type': 'image/png' }).end(stdout); return;
    }
    if (path === '/addon/subtitle.vtt') {
      res.writeHead(200, { 'Content-Type': 'text/vtt' }).end('WEBVTT\n\n00:00:00.000 --> 00:00:30.000\nGenerated subtitle\n');
      return;
    }
    if (await serveAddon(req, res)) return;
    if (path === '/health') {
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ app: 'Stream fixtures', serial, avd }));
      return;
    }
    if (path === '/index.json') {
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify(index));
      return;
    }
    if (path.startsWith('/torrents/')) return await serveFile(req, res, join(cache, 'torrents'), path.slice('/torrents'.length));
    // The same media over plain HTTP, for direct-link sources.
    if (path.startsWith('/media/')) return await serveFile(req, res, media, path.slice('/media'.length));
    res.writeHead(404).end();
  } catch (error) {
    res.writeHead(503, { 'Content-Type': 'text/plain' }).end(error.message);
  }
});
server.listen(port, '127.0.0.1', () => console.log(`Stream fixtures: ${origin} (${serial}, ${index.length} torrents)`));
