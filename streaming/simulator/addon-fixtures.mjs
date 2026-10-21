// Test-only protocol services. The application never contains these catalogue entries or fake network paths.
import { gzipSync } from 'node:zlib';

export function addonFixtures(index, origin) {
  const attempts = new Map();
  let active = 0, maximum = 0;
  const film = index.find(t => t.name === 'film-hybrid');
  const episodeId = 'fixture:episode/custom?part=2';
  const title = { id: 'fixture:film', type: 'movie', name: 'Generated Film', description: 'A generated local test film.' };
  const series = { id: 'fixture:series', type: 'series', name: 'Generated Series', videos: [
    { id: 'fixture:episode/custom?part=1', title: 'One', season: 1, episode: 1 },
    { id: episodeId, title: 'Two', season: 1, episode: 2 },
  ] };
  const manifest = {
    id: 'tv.daniil.fixtures', version: '1.0.0', name: 'Local fixtures', types: ['movie', 'series'],
    resources: ['catalog', { name: 'meta', types: ['movie', 'series'], idPrefixes: ['fixture:'] },
      { name: 'stream', types: ['movie', 'series'], idPrefixes: ['fixture:'] }, 'subtitles'],
    catalogs: [ { type: 'movie', id: 'films/list', name: 'Generated films', extra: [
      { name: 'search' }, { name: 'skip' }, { name: 'genre', options: ['Generated'] },
    ] }, { type: 'series', id: 'shows', name: 'Generated series' } ],
  };
  const json = (res, object, status = 200) => res.writeHead(status, { 'Content-Type': 'application/json' }).end(JSON.stringify(object));
  return async function handle(req, res) {
    const url = new URL(req.url, origin);
    if (url.pathname === '/addon/stats.json') { json(res, { active, maximum }); return true; }
    const match = /^\/addon\/([^/]+)\/([^/]+)\/(.*)$/.exec(url.pathname);
    if (!match) return false;
    const [, variant, configuration, route] = match;
    if (route === 'manifest.json') { json(res, manifest); return true; }
    if (variant === 'broken') { json(res, { error: 'Deliberate fixture failure' }, 503); return true; }
    if (variant === 'malformed') { res.writeHead(200).end('{bad'); return true; }
    if (variant === 'oversized') {
      res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Encoding': 'gzip' });
      res.end(gzipSync(JSON.stringify({ value: 'x'.repeat(3 << 20) })));
      return true;
    }
    if (variant === 'flaky') {
      const key = configuration + ':' + route;
      const count = attempts.get(key) || 0;
      if (attempts.size > 1024) attempts.clear();
      attempts.set(key, count + 1);
      if (count > 0) { json(res, {}, 503); return true; }
    }
    if (variant === 'slow') await new Promise(resolve => setTimeout(resolve, 12_000));
    if (variant === 'concurrency') {
      active++; maximum = Math.max(active, maximum);
      await new Promise(resolve => setTimeout(resolve, 250));
      active--;
    }
    const [resource, rawType, rawId, extra] = route.replace(/\.json$/, '').split('/');
    const type = decodeURIComponent(rawType || ''), id = decodeURIComponent(rawId || '');
    const extras = new URLSearchParams(extra || '');
    if (resource === 'catalog') {
      const meta = type === 'series' ? series : { ...title, id: Number(extras.get('skip')) > 0 ? 'fixture:next' : title.id };
      json(res, { metas: [null, 5, { wrong: true }, meta], request: { id, extras: Object.fromEntries(extras), rawPath: url.pathname } });
    } else if (resource === 'meta') json(res, { meta: id === series.id ? series : title });
    else if (resource === 'stream') json(res, { streams: [
      { name: 'Local torrent', infoHash: film.v1, fileIdx: 0, sources: ['tracker:udp://127.0.0.1:1', 'dht:' + film.v1] },
      { name: 'Local magnet', url: `magnet:?xt=urn:btih:${film.v1}&x.pe=127.0.0.1:58134` },
      { name: 'Direct media', url: `${origin}/streaming/media/film/film.mp4`, behaviorHints: {
        notWebReady: true, filename: 'film.mp4', videoHash: '0123456789abcdef', videoSize: film.files[0].size,
        proxyHeaders: { request: { 'X-Fixture': 'media-only' } },
      } },
      { name: 'Browser', externalUrl: 'https://example.com' },
      { name: 'Invalid index', infoHash: film.v1, fileIdx: -1 },
    ], request: { id, type, extras: Object.fromEntries(extras), headers: req.headers } });
    else if (resource === 'subtitles') json(res, { subtitles: [
      { id: 'en', lang: 'eng', url: origin + '/addon/subtitle.vtt' }, { bad: true },
    ], request: { id, extras: Object.fromEntries(extras), headers: req.headers } });
    else json(res, {}, 404);
    return true;
  };
}
