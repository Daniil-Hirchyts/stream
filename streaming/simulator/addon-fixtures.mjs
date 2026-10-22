// Test-only protocol services. The application never contains these catalogue entries or fake network paths.
import { gzipSync } from 'node:zlib';

export function addonFixtures(index, origin) {
  const attempts = new Map();
  let active = 0, maximum = 0, storeRequests = 0;
  const film = index.find(t => t.name === 'film-hybrid');
  const show = index.find(t => t.name === 'series');
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
    behaviorHints: { p2p: true },
  };
  const setup = { ...manifest, id: 'tv.daniil.fixtures.setup', name: 'Setup fixture', behaviorHints: { p2p: true, configurable: true, configurationRequired: true } };
  const community = { ...manifest, id: 'tv.daniil.fixtures.community', name: 'Community fixture', resources: ['subtitles'], catalogs: [], behaviorHints: {} };
  // A store directory, as Cinemeta publishes Stremio's lists through addon_catalog.
  const directory = {
    id: 'tv.daniil.fixtures.directory', version: '1.0.0', name: 'Fixture directory', types: ['movie', 'series'], resources: ['addon_catalog'],
    catalogs: [], addonCatalogs: [{ type: 'all', id: 'official', name: 'Fixture list' }, { type: 'movie', id: 'official', name: 'Fixture films' }],
  };
  const listing = (path, extra = {}) => ({ transportUrl: origin + path, transportName: 'http', manifest: { ...manifest, ...extra } });
  const lists = {
    official: [
      listing('/addon/good/store-with-a-longer-configuration/manifest.json'),
      listing('/addon/good/store/manifest.json'),
      listing('/addon/good/needs-setup/manifest.json', setup),
      listing('/addon/good/adult/manifest.json', { id: 'tv.daniil.fixtures.adult', name: 'Adult fixture', behaviorHints: { adult: true } }),
      { transportUrl: 'http://127.0.0.2:11470/local-addon/manifest.json', transportName: 'http', manifest: { ...manifest, id: 'other-host', name: 'Other host' } },
      { transportUrl: origin + '/legacy/stremio/v1', transportName: 'http', manifest: { ...manifest, id: 'legacy', name: 'Legacy' } },
      null, 5,
    ],
    community: [listing('/addon/good/store/manifest.json'), listing('/addon/good/community/manifest.json', community)],
  };
  const json = (res, object, status = 200) => res.writeHead(status, { 'Content-Type': 'application/json' }).end(JSON.stringify(object));
  return async function handle(req, res) {
    const url = new URL(req.url, origin);
    if (url.pathname === '/addon/stats.json') { json(res, { active, maximum, storeRequests }); return true; }
    const match = /^\/addon\/([^/]+)\/([^/]+)\/(.*)$/.exec(url.pathname);
    if (!match) return false;
    const [, variant, configuration, route] = match;
    if (route === 'manifest.json') {
      json(res, configuration === 'directory' ? directory : configuration === 'needs-setup' ? setup
        : configuration === 'empty-setup' ? { ...setup, resources: [], catalogs: [], behaviorHints: { configurable: true, configurationRequired: false } }
        : configuration.startsWith('configured-setup') ? { ...setup, behaviorHints: { p2p: true, configurable: true, configurationRequired: false } }
        : configuration === 'community' ? community : manifest);
      return true;
    }
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
    else if (resource === 'stream') {
      if (configuration.startsWith('http-') || configuration === 'failover' || configuration === 'failover-all') {
        const format = configuration.slice(5), mkv = index.find(t => t.name === 'matroska');
        const file = format === 'mkv' ? mkv.files[0].path : film.files[0].path;
        const address = format === 'hls' || format === 'dash' ? `${format}/stream.${format === 'hls' ? 'm3u8' : 'mpd'}` : file.split('/').map(encodeURIComponent).join('/');
        const good = { name: 'Direct ' + format, url: origin + '/streaming/media/' + address };
        const bad = { name: 'Unavailable source', url: origin + '/missing.mp4' };
        json(res, { streams: configuration === 'failover' ? [bad, good] : configuration === 'failover-all' ? [bad, bad, bad, good] : [good] }); return true;
      }
      if (configuration === 'multiple-files') {
        json(res, { streams: [{ name: 'Choose an episode', infoHash: show.v1 }] }); return true;
      }
      const payload = type === 'series' ? show : film;
      const episode = id.endsWith('part=2') ? 2 : 1;
      const selectedFile = type === 'series' ? show.files.find(f => f.path.endsWith(`S01E0${episode}.mkv`)) : film.files[0];
      json(res, { streams: [
      { name: 'Local torrent', infoHash: payload.v1, fileIdx: selectedFile.index, sources: ['tracker:udp://127.0.0.1:1', 'dht:' + payload.v1] },
      { name: 'Local magnet', url: `magnet:?xt=urn:btih:${payload.v1}&x.pe=127.0.0.1:58134`, fileIdx: selectedFile.index },
      { name: 'Direct media', url: `${origin}/streaming/media/${selectedFile.path.split('/').map(encodeURIComponent).join('/')}`, behaviorHints: {
        notWebReady: true, filename: 'film.mp4', videoHash: '0123456789abcdef', videoSize: film.files[0].size,
        proxyHeaders: { request: { 'X-Fixture': 'media-only' } },
      } },
      { name: 'Browser', externalUrl: 'https://example.com' },
      { name: 'Invalid index', infoHash: film.v1, fileIdx: -1 },
    ], request: { id, type, extras: Object.fromEntries(extras), headers: req.headers } });
    }
    else if (resource === 'addon_catalog') {
      storeRequests++;
      if (lists[id]) json(res, { addons: lists[id], request: { type, id } }); else json(res, {}, 404);
    }
    else if (resource === 'subtitles') json(res, { subtitles: [
      { id: 'en', lang: 'eng', url: origin + '/addon/subtitle.vtt' }, { bad: true },
    ], request: { id, extras: Object.fromEntries(extras), headers: req.headers } });
    else json(res, {}, 404);
    return true;
  };
}
