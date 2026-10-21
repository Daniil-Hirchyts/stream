const HOST = "tv.daniil.cast";
const MEDIA_PATH = /\.(m3u8|mpd|mp4|webm|m4v|mov)(?:$|[?#])/i;
const PLAYLIST_MIME = /(?:mpegurl|dash\+xml)/i;
const MP4_FRAGMENT = /(?:[-_/](?:s\d+|init|segment[-_]?\d+|chunk[-_]?\d+))\.mp4(?:$|[?#])/i;
const SUBTITLE_PATH = /\.(?:vtt|webvtt|srt|ass|ssa|ttml|dfxp)(?:$|[?#])/i;
const SUBTITLE_MIME = /^(?:text\/vtt|application\/x-subrip|text\/x-ssa|text\/x-ass|application\/ttml\+xml)/i;
// WebVTT pieces of an HLS or DASH playlist; the TV gets those from the playlist itself.
const SUBTITLE_SEGMENT = /(?:seg(?:ment)?|chunk|frag(?:ment)?|part|sequence)[-_]?\d+\.(?:web)?vtt(?:$|[?#])|\/\d+\.(?:web)?vtt(?:$|[?#])/i;
const requests = new Map();
const armedTabs = new Set();
let queue = Promise.resolve();
void chrome.storage.session.get(null).then(values => {
  for (const [key, value] of Object.entries(values)) {
    if (key.startsWith("tab-") && value.armed) armedTabs.add(Number(key.slice(4)));
  }
});

function serial(task) {
  const next = queue.then(task, task);
  queue = next.catch(() => {});
  return next;
}

function mimeFor(url, mime = "") {
  if (/mpegurl/i.test(mime) || /\.m3u8(?:$|[?#])/i.test(url)) return "application/x-mpegURL";
  if (/dash\+xml/i.test(mime) || /\.mpd(?:$|[?#])/i.test(url)) return "application/dash+xml";
  return mime.split(";")[0].trim();
}

function subtitleMime(url, mime = "") {
  if (/\.(?:web)?vtt(?:$|[?#])/i.test(url) || /text\/vtt/i.test(mime)) return "text/vtt";
  if (/\.srt(?:$|[?#])/i.test(url) || /subrip/i.test(mime)) return "application/x-subrip";
  if (/\.(?:ass|ssa)(?:$|[?#])/i.test(url) || /x-ss?a/i.test(mime)) return "text/x-ssa";
  if (/\.(?:ttml|dfxp)(?:$|[?#])/i.test(url) || /ttml/i.test(mime)) return "application/ttml+xml";
  return "";
}

function validUrl(url) {
  try { return ["http:", "https:"].includes(new URL(url).protocol); } catch { return false; }
}

async function addCandidate(tabId, candidate) {
  if (tabId < 0 || !validUrl(candidate.url)) return;
  await serial(async () => {
    const key = `tab-${tabId}`;
    const state = (await chrome.storage.session.get(key))[key];
    if (!state?.armed) return;
    const old = state.candidates.find(item => item.url === candidate.url);
    const value = { ...old, ...candidate,
      source: old?.source === "network" ? old.source : candidate.source,
      headers: old?.source === "network" && candidate.source !== "network" ? old.headers : candidate.headers || old?.headers || {},
      mime: mimeFor(candidate.url, candidate.mime || old?.mime), seenAt: Date.now() };
    state.candidates = [value, ...state.candidates.filter(item => item.url !== value.url)]
      .sort((a, b) => Number(PLAYLIST_MIME.test(b.mime)) - Number(PLAYLIST_MIME.test(a.mime)) || b.seenAt - a.seenAt)
      .slice(0, 40);
    await chrome.storage.session.set({ [key]: state });
    if (!old && candidate.source === "network") detected(tabId);
  });
}

/** Tells an open side panel that the page started a new stream, such as another episode, so it looks again. */
function detected(tabId) {
  chrome.runtime.sendMessage({ type: "detected", tabId }).catch(() => { /* No panel is open. */ });
}

// Subtitle files of the page's player. <track> elements keep the player's order; a file fetched over the
// network moves to the front, since custom players draw subtitles themselves and fetch the one in use.
async function addSubtitle(tabId, subtitle) {
  if (tabId < 0 || !validUrl(subtitle.url) || SUBTITLE_SEGMENT.test(subtitle.url)) return;
  await serial(async () => {
    const key = `tab-${tabId}`;
    const state = (await chrome.storage.session.get(key))[key];
    if (!state?.armed) return;
    const list = state.subtitles || [];
    const old = list.find(item => item.url === subtitle.url);
    const value = { ...old, ...subtitle,
      label: subtitle.label || old?.label || "", language: subtitle.language || old?.language || "",
      mime: subtitleMime(subtitle.url, subtitle.mime || old?.mime) };
    const others = list.filter(item => item.url !== value.url);
    state.subtitles = subtitle.source === "network" ? [value, ...others].slice(0, 24)
      : old ? list.map(item => item.url === value.url ? value : item) : [...others, value].slice(-24);
    await chrome.storage.session.set({ [key]: state });
  });
}

/** Live subtitle files of the frames still on the page, without numbered playlist pieces. */
function pageSubtitles(list, liveFrameIds) {
  const live = list.filter(item => item.frameId === undefined || item.frameId < 0 || liveFrameIds.has(item.frameId));
  // Three or more files that differ only by a number are segments, e.g. sub_1.vtt, sub_2.vtt…
  const pattern = item => { const url = new URL(item.url); return url.origin + url.pathname.replace(/\d+/g, "#"); };
  const counts = new Map();
  for (const item of live) counts.set(pattern(item), (counts.get(pattern(item)) || 0) + 1);
  return live.filter(item => counts.get(pattern(item)) < 3)
    .sort((a, b) => Number(b.showing || false) - Number(a.showing || false))
    .slice(0, 8);
}

chrome.webRequest.onBeforeSendHeaders.addListener(details => {
  if (!armedTabs.has(details.tabId)) return;
  // No cookies or Authorization headers are collected or sent to the stick.
  const headers = {};
  for (const header of details.requestHeaders || []) {
    if (["referer", "origin", "user-agent"].includes(header.name.toLowerCase())) headers[header.name] = header.value;
  }
  requests.set(details.requestId, { headers, at: Date.now() });
  if (requests.size > 250) requests.delete(requests.keys().next().value);
}, { urls: ["http://*/*", "https://*/*"], types: ["media", "xmlhttprequest", "other"] }, ["requestHeaders", "extraHeaders"]);

chrome.webRequest.onHeadersReceived.addListener(details => {
  if (!armedTabs.has(details.tabId)) return;
  const mime = details.responseHeaders?.find(h => h.name.toLowerCase() === "content-type")?.value || "";
  if (SUBTITLE_PATH.test(details.url) || SUBTITLE_MIME.test(mime)) {
    void addSubtitle(details.tabId, { url: details.url, mime, source: "network", frameId: details.frameId });
    return;
  }
  const isSegment = /\.(?:ts|m4s|aac)(?:$|[?#])/i.test(details.url) || MP4_FRAGMENT.test(details.url) || /mp2t/i.test(mime);
  const playlist = PLAYLIST_MIME.test(mime) || /\.(?:m3u8|mpd)(?:$|[?#])/i.test(details.url);
  // Media Source players fetch video in pieces; only a <video> element's own request is a whole file.
  const file = details.type === "media" && (MEDIA_PATH.test(details.url) || /^video\//i.test(mime));
  if (!isSegment && (playlist || file)) {
    void addCandidate(details.tabId, { url: details.url, mime, source: "network", frameId: details.frameId,
      headers: requests.get(details.requestId)?.headers || {} });
  }
}, { urls: ["http://*/*", "https://*/*"], types: ["media", "xmlhttprequest", "other"] }, ["responseHeaders"]);

const forgetRequest = details => requests.delete(details.requestId);
chrome.webRequest.onCompleted.addListener(forgetRequest, { urls: ["http://*/*", "https://*/*"] });
chrome.webRequest.onErrorOccurred.addListener(forgetRequest, { urls: ["http://*/*", "https://*/*"] });
chrome.tabs.onRemoved.addListener(tabId => { armedTabs.delete(tabId); void chrome.storage.session.remove(`tab-${tabId}`); });
chrome.tabs.onUpdated.addListener((tabId, change) => {
  if (change.url) {
    void serial(async () => {
      const key = `tab-${tabId}`;
      const old = (await chrome.storage.session.get(key))[key];
      if (change.url && old?.origin && new URL(change.url).origin !== old.origin) {
        armedTabs.delete(tabId);
        await chrome.storage.session.remove(key);
        return;
      }
      // Hash updates in single-page players may arrive after their manifest.
      // Keep that manifest through these updates and through ordinary reloads.
      if (old?.armed && new URL(change.url).pathname + new URL(change.url).search !== old.page) {
        await chrome.storage.session.set({ [key]: { ...old, page: new URL(change.url).pathname + new URL(change.url).search, candidates: [], subtitles: [] } });
      }
    });
  }
});

async function scan(tabId) {
  const tab = await chrome.tabs.get(tabId);
  const origin = new URL(tab.url).origin;
  armedTabs.add(tabId);
  await serial(async () => {
    const key = `tab-${tabId}`;
    const old = (await chrome.storage.session.get(key))[key];
    await chrome.storage.session.set({ [key]: { armed: true, origin, page: new URL(tab.url).pathname + new URL(tab.url).search,
      candidates: old?.origin === origin ? old.candidates || [] : [],
      subtitles: old?.origin === origin ? old.subtitles || [] : [] } });
  });
  const frames = await chrome.scripting.executeScript({
    target: { tabId, allFrames: true },
    func: () => {
      const videos = [...document.querySelectorAll("video")].map(video => ({
        url: video.currentSrc || video.src,
        sources: [...video.querySelectorAll("source")].map(source => ({ url: source.src, mime: source.type })),
        position: Number.isFinite(video.currentTime) ? video.currentTime * 1000 : 0,
        playing: !video.paused, drm: !!video.mediaKeys,
        area: video.clientWidth * video.clientHeight,
        tracks: [...video.querySelectorAll("track")].filter(track => /subtitles|captions/.test(track.kind))
          .map(track => ({ url: track.src, language: track.srclang, label: track.label, showing: track.track?.mode === "showing" })),
        // The subtitles the viewer turned on, including ones a player created from its playlist.
        textTrack: [...video.textTracks].filter(track => track.mode === "showing" && /subtitles|captions/.test(track.kind))
          .map(track => ({ language: track.language, label: track.label }))[0] || null
      })).sort((a, b) => Number(b.playing) - Number(a.playing) || b.area - a.area);
      // VenomPlayer embeds list every version of the video (H.264 HLS, VP9 and AV1 DASH) and the names of its
      // voice-overs in the page's config, though the browser loads only one version. For a series the config
      // lists every season and episode the same way.
      const config = [...document.scripts].map(script => script.src ? "" : script.textContent)
        .find(text => /\b(?:source|seasons)\s*:\s*[[{]/.test(text) && /\b(?:hls|dash\w*|mp4)"?\s*:\s*"https?:/.test(text)) || "";
      const at = config.search(/source\s*:\s*\{/);
      const versions = at < 0 ? [] : [...config.slice(at, at + 8000).matchAll(/\b(hls|dash\w*|mp4)\s*:\s*"(https?:[^"]+)"/g)]
        .map(([, key, url]) => ({ key, url: url.replace(/\\\//g, "/") }));
      /** The JSON array or object starting at {@code start}, found by matching brackets outside strings. */
      const jsonAt = (text, start) => {
        let depth = 0, quoted = false, escaped = false;
        for (let i = start; i < text.length; i++) {
          const c = text[i];
          if (quoted) { if (escaped) escaped = false; else if (c === "\\") escaped = true; else if (c === '"') quoted = false; continue; }
          if (c === '"') quoted = true;
          else if (c === "[" || c === "{") depth++;
          else if ((c === "]" || c === "}") && --depth === 0) return text.slice(start, i + 1);
        }
        return "";
      };
      let series = null;
      const seasonsAt = config.search(/seasons\s*:\s*\[/);
      if (seasonsAt >= 0) {
        try {
          const seasons = JSON.parse(jsonAt(config, config.indexOf("[", seasonsAt)));
          const current = config.match(/current\s*:\s*\{\s*season\s*:\s*"?(\d+)"?\s*,\s*episode\s*:\s*"?([^",}\s]+)"?/);
          series = {
            seasons: seasons.map(season => ({ season: String(season.season), episodes: (season.episodes || []).map(episode => ({
              episode: String(episode.episode), title: String(episode.title || ""), duration: Number(episode.duration) || 0,
              versions: Object.entries(episode).filter(([key, url]) => /^(?:hls|dash\w*|mp4)$/.test(key) && /^https?:/.test(url))
                .map(([key, url]) => ({ key, url })),
              audio: Array.isArray(episode.audio?.names) ? { names: episode.audio.names.map(String),
                order: Array.isArray(episode.audio.order) ? episode.audio.order : [] } : null,
              cc: (Array.isArray(episode.cc) ? episode.cc : []).filter(item => /^https?:/.test(item?.url || ""))
                .map(item => ({ url: item.url, name: String(item.name || "") }))
            })) })),
            // The player retitles its page with the episode it plays.
            current: current ? { season: current[1], episode: current[2] } : null, playing: document.title
          };
        } catch { /* Another player's config: its versions are still found below. */ }
      }
      let voices = null;
      // A series lists them per episode; they are the same throughout, so the first will do here.
      try { voices = JSON.parse(config.match(/"?audio"?\s*:\s*(\{\s*"names"\s*:\s*\[[^\]]*\][^{}]*\})/)?.[1] || "null"); } catch {}
      const names = Array.isArray(voices?.names) ? voices.names.map(String) : [];
      // The voice-over in use: the player's highlighted menu item, else dash.js's remembered language ("ru2").
      const active = [...document.querySelectorAll('[class*="active"]')].map(element => element.textContent.trim())
        .find(text => names.includes(text));
      let selected = active ? names.indexOf(active) : -1;
      if (selected < 0 && names.length) {
        try { selected = Number(JSON.parse(localStorage.getItem("dashjs_audio_settings"))?.settings?.lang?.match(/(\d+)$/)?.[1] ?? -1); } catch {}
      }
      const resources = performance.getEntriesByType("resource")
        .map(entry => ({ url: entry.name, mime: entry.contentType || "", initiator: entry.initiatorType }))
        .filter(entry => /\.(m3u8|mpd|mp4|webm|m4v|mov|vtt|webvtt|srt|ass|ssa|ttml|dfxp)(?:$|[?#])/i.test(entry.url)
          || /mpegurl|dash\+xml|^video\/|text\/vtt|subrip|x-ssa|ttml/i.test(entry.mime)
          || (videos.length && /fetch|xmlhttprequest|video/.test(entry.initiator)));
      return { videos, resources, referer: location.href, userAgent: navigator.userAgent, versions, series,
        audio: names.length ? { names, order: Array.isArray(voices.order) ? voices.order : [], selected } : null };
    }
  });
  let drm = false, blob = false, position = 0, foundPosition = false, textTrack = null, audio = null, series = null;
  const probes = [];
  const liveFrameIds = new Set(frames.map(frame => frame.frameId));
  for (const frame of frames) {
    const result = frame.result;
    if (!result) continue;
    const headers = { Referer: result.referer, "User-Agent": result.userAgent };
    audio ||= result.audio;
    if (result.series && !series) {
      series = { ...result.series, headers };
      // The episode on the page is described like any other stream, so its versions show codec and quality.
      const playing = result.series.seasons.flatMap(season => season.episodes.map(episode => ({ season, episode })))
        .find(({ season, episode }) => episode.title && episode.title === result.series.playing
          || season.season === result.series.current?.season && episode.episode === result.series.current?.episode);
      for (const { key, url } of playing?.episode.versions || []) result.versions.push({ key, url });
    }
    for (const { key, url } of result.versions || []) {
      const mime = key.startsWith("hls") ? "application/x-mpegURL" : key.startsWith("dash") ? "application/dash+xml" : "video/mp4";
      await addCandidate(tabId, { url, mime, source: "player", frameId: frame.frameId, headers });
    }
    for (const video of result.videos) {
      drm ||= video.drm;
      blob ||= video.url?.startsWith("blob:");
      if (!foundPosition && video.area > 0) { position = video.position; foundPosition = true; }
      textTrack ||= video.textTrack;
      for (const track of video.tracks) {
        await addSubtitle(tabId, { ...track, source: "track element", frameId: frame.frameId });
      }
      if (video.url) await addCandidate(tabId, { url: video.url, source: "video element", frameId: frame.frameId, headers, position: video.position });
      for (const source of video.sources) await addCandidate(tabId, { ...source, source: "video source", frameId: frame.frameId, headers, position: video.position });
    }
    for (const resource of result.resources) {
      const { url, mime } = resource;
      if (!validUrl(url) || MP4_FRAGMENT.test(url) || /\.(?:m4s|ts|aac)(?:$|[?#])/i.test(url)) continue;
      if (SUBTITLE_PATH.test(url) || SUBTITLE_MIME.test(mime)) {
        await addSubtitle(tabId, { url, mime, source: "page resource", frameId: frame.frameId });
        continue;
      }
      const resourceHeaders = { ...headers };
      if (/fetch|xmlhttprequest/.test(resource.initiator) && new URL(url).origin !== new URL(result.referer).origin) {
        resourceHeaders.Origin = new URL(result.referer).origin;
      }
      const key = `tab-${tabId}`;
      const state = (await chrome.storage.session.get(key))[key];
      // Preserve actual network request headers when available.
      if (state?.candidates.some(item => item.url === url)) continue;
      const candidate = { url, mime, source: "page resource", frameId: frame.frameId, headers: resourceHeaders };
      const playlist = PLAYLIST_MIME.test(mime) || /\.(?:m3u8|mpd)(?:$|[?#])/i.test(url);
      // Pieces fetched by a Media Source player are not whole videos.
      const file = !/fetch|xmlhttprequest/.test(resource.initiator) && (MEDIA_PATH.test(url) || /^video\//i.test(mime));
      if (playlist || file) await addCandidate(tabId, candidate);
      else if (MEDIA_PATH.test(url) || /^video\//i.test(mime)) continue;
      else if (result.videos.length && !/\.(?:js|css|png|jpg|jpeg|gif|svg|woff2?|vtt)(?:$|[?#])/i.test(url)) probes.push(candidate);
    }
  }
  // Recover extensionless manifests that loaded before this popup was opened.
  // Probe only existing requests of a frame containing a video, with no cookies.
  const uniqueProbes = [...new Map(probes.map(candidate => [candidate.url, candidate])).values()];
  const selectedProbes = uniqueProbes.length > 16 ? [...uniqueProbes.slice(0, 10), ...uniqueProbes.slice(-6)] : uniqueProbes;
  for (let start = 0; start < selectedProbes.length; start += 4) {
    await Promise.allSettled(selectedProbes.slice(start, start + 4).map(async candidate => {
      const identified = await identifyMedia(candidate.url);
      if (identified?.subtitle) await addSubtitle(tabId, { url: candidate.url, mime: identified.mime, source: "response type", frameId: candidate.frameId });
      else if (identified) await addCandidate(tabId, { ...candidate, ...identified, source: "response type" });
    }));
  }
  const state = (await chrome.storage.session.get(`tab-${tabId}`))[`tab-${tabId}`];
  const candidates = (state?.candidates || [])
    .filter(c => !MP4_FRAGMENT.test(c.url) && (c.frameId === undefined || c.frameId < 0 || liveFrameIds.has(c.frameId)))
    .map(c => ({ ...c, mime: mimeFor(c.url, c.mime) }));
  await Promise.allSettled(candidates.filter(c => PLAYLIST_MIME.test(c.mime)).map(describePlaylist));
  candidates.sort((a, b) => Number(PLAYLIST_MIME.test(b.mime)) - Number(PLAYLIST_MIME.test(a.mime)) || b.seenAt - a.seenAt);
  const subtitles = pageSubtitles(state?.subtitles || [], liveFrameIds);
  return { ok: true, candidates, subtitles, textTrack, audio, series, drm, blob, position };
}

/** Reads the start of a playlist without credentials. Video segments are never downloaded. */
async function readText(url, limit) {
  if (!await chrome.permissions.contains({ origins: [new URL(url).origin + "/*"] })) return "";
  const response = await fetch(url, { credentials: "omit", signal: AbortSignal.timeout(4000) });
  if (!response.ok || !response.body) return "";
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let text = "", bytes = 0;
  try {
    while (bytes < limit) {
      const part = await reader.read();
      if (part.done) break;
      bytes += part.value.byteLength;
      text += decoder.decode(part.value, { stream: true });
    }
  } finally { await reader.cancel(); }
  return text;
}

// The TV decodes H.264 and HEVC itself; VP9 and AV1 may need the Mac's conversion.
const CODECS = [["H.264", /avc[13]/i], ["HEVC", /hvc1|hev1/i], ["VP9", /vp0?9/i], ["AV1", /av01/i]];
function videoCodec(codecs) {
  return CODECS.find(([, pattern]) => codecs.some(codec => pattern.test(codec)))?.[0] || "";
}

/** Adds what a viewer needs to choose: playlist kind, video codec, best height and voice-overs. */
async function describePlaylist(candidate) {
  try {
    const attribute = (tag, name) => tag.match(new RegExp(`\\b${name}=(?:"([^"]*)"|([^,\\s>]*))`, "i"))?.slice(1).find(Boolean) || "";
    if (/mpegurl/i.test(candidate.mime)) {
      const text = await readText(candidate.url, 262144);
      if (/#EXT-X-STREAM-INF:/i.test(text)) candidate.playlist = "master";
      else if (/#EXT-X-I-FRAMES-ONLY/i.test(text)) candidate.playlist = "trick";
      else if (/#EXTINF:/i.test(text)) candidate.playlist = "media";
      const variants = text.split("\n").filter(line => line.startsWith("#EXT-X-STREAM-INF:"));
      const audio = new Map();
      for (const line of text.split("\n").filter(line => /^#EXT-X-MEDIA:.*TYPE=AUDIO/.test(line))) {
        const name = attribute(line, "NAME");
        if (name && !audio.has(name)) audio.set(name, { name, language: attribute(line, "LANGUAGE") });
      }
      candidate.info = {
        codec: videoCodec(variants.flatMap(line => attribute(line, "CODECS").split(","))),
        width: Math.max(0, ...variants.map(line => Number(attribute(line, "RESOLUTION").split("x")[0]) || 0)),
        height: Math.max(0, ...variants.map(line => Number(attribute(line, "RESOLUTION").split("x")[1]) || 0)),
        audio: [...audio.values()]
      };
    } else {
      const text = await readText(candidate.url, 1048576);
      const sets = text.split(/<AdaptationSet\b/).slice(1).map(block => block.split(/<\/AdaptationSet>/)[0]);
      const kind = block => attribute(block, "contentType") || attribute(block, "mimeType").split("/")[0];
      const video = sets.filter(block => kind(block) === "video");
      candidate.info = {
        codec: videoCodec(video.flatMap(block => [...block.matchAll(/codecs="([^"]+)"/g)].map(match => match[1]))),
        width: Math.max(0, ...video.flatMap(block => [...block.matchAll(/\bwidth="(\d+)"/g)].map(match => Number(match[1])))),
        height: Math.max(0, ...video.flatMap(block => [...block.matchAll(/\bheight="(\d+)"/g)].map(match => Number(match[1])))),
        audio: sets.filter(block => kind(block) === "audio")
          .map(block => ({ name: block.match(/<Label[^>]*>([^<]*)</)?.[1] || "", language: attribute(block, "lang") }))
      };
    }
  } catch { /* Servers requiring page-specific access keep their undescribed source. */ }
}

async function identifyMedia(url) {
  if (!await chrome.permissions.contains({ origins: [new URL(url).origin + "/*"] })) return null;
  try {
    const response = await fetch(url, { method: "HEAD", credentials: "omit", signal: AbortSignal.timeout(1800) });
    const mime = response.headers.get("content-type") || "";
    if (response.ok && (PLAYLIST_MIME.test(mime) || /^video\//i.test(mime))) return { mime: mimeFor(url, mime) };
    if (response.ok && SUBTITLE_MIME.test(mime)) return { mime: subtitleMime(url, mime), subtitle: true };
    if (response.ok && !/octet-stream|text\/plain|text\/xml|application\/xml/i.test(mime)) return null;
    if (!response.ok && response.status !== 405 && response.status !== 501) return null;
    const body = await fetch(url, { credentials: "omit", signal: AbortSignal.timeout(1800) });
    if (!body.ok || !body.body) return null;
    const reader = body.body.getReader();
    let prefix = "", length = 0;
    const decoder = new TextDecoder();
    try {
      while (length < 16384) {
        const part = await reader.read();
        if (part.done) break;
        length += part.value.byteLength;
        prefix += decoder.decode(part.value, { stream: true });
        if (prefix.includes("#EXTM3U") || /<(?:\w+:)?MPD(?:\s|>)/.test(prefix) || prefix.includes("-->")) break;
      }
    } finally { await reader.cancel(); }
    if (prefix.trimStart().startsWith("#EXTM3U")) return { mime: "application/x-mpegURL" };
    if (/<(?:\w+:)?MPD(?:\s|>)/.test(prefix)) return { mime: "application/dash+xml" };
    const text = prefix.replace(/^\uFEFF/, "").trimStart();
    if (text.startsWith("WEBVTT")) return { mime: "text/vtt", subtitle: true };
    if (/^\d+\s*\r?\n\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->/.test(text)) return { mime: "application/x-subrip", subtitle: true };
  } catch { /* A failed metadata request must not stop playback or other detection. */ }
  return null;
}

async function native(command) {
  try { return await chrome.runtime.sendNativeMessage(HOST, command); }
  catch (error) {
    return { ok: false, error: { code: "helper-missing", title: "The Mac helper isn’t installed", detail: error.message } };
  }
}

chrome.runtime.onMessage.addListener((message, sender, respond) => {
  // Only our extension's own pages may invoke the native bridge.
  if (sender.id !== chrome.runtime.id || !sender.url?.startsWith(chrome.runtime.getURL("")) || sender.tab) return false;
  (async () => {
    if (message.type === "scan") return scan(message.tabId);
    if (message.type === "pauseBrowser") {
      await chrome.scripting.executeScript({ target: { tabId: message.tabId, allFrames: true },
        func: () => { for (const video of document.querySelectorAll("video")) video.pause(); } });
      return { ok: true };
    }
    return { ok: false, error: "Unknown extension command" };
  })().then(respond, error => respond({ ok: false, error: error.message }));
  return true;
});

// The toolbar button opens the side panel, which stays open while browsing.
chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {});

chrome.runtime.onInstalled.addListener(() => {
  chrome.storage.session.setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" });
  chrome.contextMenus.create({ id: "cast-media", title: "Cast this video to TV", contexts: ["video"] });
});
chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  if (info.menuItemId !== "cast-media" || !validUrl(info.srcUrl)) return;
  const result = await native({ action: "load", url: info.srcUrl, title: tab.title?.slice(0, 250) || "Video from Chrome",
    mime: mimeFor(info.srcUrl), headers: { Referer: info.frameUrl || info.pageUrl }, position: 0 });
  await chrome.action.setBadgeText({ text: result.ok ? "TV" : "!", tabId: tab.id });
});
