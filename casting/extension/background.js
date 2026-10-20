const HOST = "tv.daniil.cast";
const MEDIA_PATH = /\.(m3u8|mpd|mp4|webm|m4v|mov)(?:$|[?#])/i;
const PLAYLIST_MIME = /(?:mpegurl|dash\+xml)/i;
const MP4_FRAGMENT = /(?:[-_/](?:s\d+|init|segment[-_]?\d+|chunk[-_]?\d+))\.mp4(?:$|[?#])/i;
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
  });
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
  const isSegment = /\.(?:ts|m4s|aac)(?:$|[?#])/i.test(details.url) || MP4_FRAGMENT.test(details.url) || /mp2t/i.test(mime);
  if (!isSegment && (MEDIA_PATH.test(details.url) || PLAYLIST_MIME.test(mime) || /^video\//i.test(mime))) {
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
        await chrome.storage.session.set({ [key]: { ...old, page: new URL(change.url).pathname + new URL(change.url).search, candidates: [] } });
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
      candidates: old?.origin === origin ? old.candidates || [] : [] } });
  });
  const frames = await chrome.scripting.executeScript({
    target: { tabId, allFrames: true },
    func: () => {
      const videos = [...document.querySelectorAll("video")].map(video => ({
        url: video.currentSrc || video.src,
        sources: [...video.querySelectorAll("source")].map(source => ({ url: source.src, mime: source.type })),
        position: Number.isFinite(video.currentTime) ? video.currentTime * 1000 : 0,
        playing: !video.paused, drm: !!video.mediaKeys,
        area: video.clientWidth * video.clientHeight
      })).sort((a, b) => Number(b.playing) - Number(a.playing) || b.area - a.area);
      const resources = performance.getEntriesByType("resource")
        .map(entry => ({ url: entry.name, mime: entry.contentType || "", initiator: entry.initiatorType }))
        .filter(entry => /\.(m3u8|mpd|mp4|webm|m4v|mov)(?:$|[?#])/i.test(entry.url)
          || /mpegurl|dash\+xml|^video\//i.test(entry.mime)
          || (videos.length && /fetch|xmlhttprequest|video/.test(entry.initiator)));
      return { videos, resources, referer: location.href, userAgent: navigator.userAgent };
    }
  });
  let drm = false, blob = false, position = 0, foundPosition = false;
  const probes = [];
  const liveFrameIds = new Set(frames.map(frame => frame.frameId));
  for (const frame of frames) {
    const result = frame.result;
    if (!result) continue;
    const headers = { Referer: result.referer, "User-Agent": result.userAgent };
    for (const video of result.videos) {
      drm ||= video.drm;
      blob ||= video.url?.startsWith("blob:");
      if (!foundPosition && video.area > 0) { position = video.position; foundPosition = true; }
      if (video.url) await addCandidate(tabId, { url: video.url, source: "video element", frameId: frame.frameId, headers, position: video.position });
      for (const source of video.sources) await addCandidate(tabId, { ...source, source: "video source", frameId: frame.frameId, headers, position: video.position });
    }
    for (const resource of result.resources) {
      const { url, mime } = resource;
      if (!validUrl(url) || MP4_FRAGMENT.test(url) || /\.(?:m4s|ts|aac)(?:$|[?#])/i.test(url)) continue;
      const resourceHeaders = { ...headers };
      if (/fetch|xmlhttprequest/.test(resource.initiator) && new URL(url).origin !== new URL(result.referer).origin) {
        resourceHeaders.Origin = new URL(result.referer).origin;
      }
      const key = `tab-${tabId}`;
      const state = (await chrome.storage.session.get(key))[key];
      // Preserve actual network request headers when available.
      if (state?.candidates.some(item => item.url === url)) continue;
      const candidate = { url, mime, source: "page resource", frameId: frame.frameId, headers: resourceHeaders };
      if (MEDIA_PATH.test(url) || PLAYLIST_MIME.test(mime) || /^video\//i.test(mime)) await addCandidate(tabId, candidate);
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
      if (identified) await addCandidate(tabId, { ...candidate, ...identified, source: "response type" });
    }));
  }
  const state = (await chrome.storage.session.get(`tab-${tabId}`))[`tab-${tabId}`];
  const candidates = (state?.candidates || [])
    .filter(c => !MP4_FRAGMENT.test(c.url) && (c.frameId === undefined || c.frameId < 0 || liveFrameIds.has(c.frameId)))
    .map(c => ({ ...c, mime: mimeFor(c.url, c.mime) }));
  await Promise.allSettled(candidates.filter(c => /mpegurl/i.test(c.mime)).map(async candidate => {
    // Inspect only playlist text, never download video segments or credentials.
    const origin = new URL(candidate.url).origin + "/*";
    if (!await chrome.permissions.contains({ origins: [origin] })) return;
    try {
      const response = await fetch(candidate.url, { credentials: "omit", signal: AbortSignal.timeout(3000) });
      if (!response.ok || !response.body) return;
      const reader = response.body.getReader();
      let text = "", bytes = 0;
      const decoder = new TextDecoder();
      try {
        while (bytes < 262144) {
          const part = await reader.read();
          if (part.done) break;
          bytes += part.value.byteLength;
          text += decoder.decode(part.value, { stream: true });
        }
      } finally { await reader.cancel(); }
      if (/#EXT-X-STREAM-INF:/i.test(text)) candidate.playlist = "master";
      else if (/#EXT-X-I-FRAMES-ONLY/i.test(text)) candidate.playlist = "trick";
      else if (/#EXTINF:/i.test(text)) candidate.playlist = "media";
    } catch { /* Servers requiring page-specific access keep their unclassified source. */ }
  }));
  candidates.sort((a, b) => Number(PLAYLIST_MIME.test(b.mime)) - Number(PLAYLIST_MIME.test(a.mime)) || b.seenAt - a.seenAt);
  return { ok: true, candidates, drm, blob, position };
}

async function identifyMedia(url) {
  if (!await chrome.permissions.contains({ origins: [new URL(url).origin + "/*"] })) return null;
  try {
    const response = await fetch(url, { method: "HEAD", credentials: "omit", signal: AbortSignal.timeout(1800) });
    const mime = response.headers.get("content-type") || "";
    if (response.ok && (PLAYLIST_MIME.test(mime) || /^video\//i.test(mime))) return { mime: mimeFor(url, mime) };
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
        if (prefix.includes("#EXTM3U") || /<(?:\w+:)?MPD(?:\s|>)/.test(prefix)) break;
      }
    } finally { await reader.cancel(); }
    if (prefix.trimStart().startsWith("#EXTM3U")) return { mime: "application/x-mpegURL" };
    if (/<(?:\w+:)?MPD(?:\s|>)/.test(prefix)) return { mime: "application/dash+xml" };
  } catch { /* A failed metadata request must not stop playback or other detection. */ }
  return null;
}

async function native(command) {
  try { return await chrome.runtime.sendNativeMessage(HOST, command); }
  catch (error) {
    return { ok: false, error: `Can’t reach the local Cast bridge. Run casting/native/install.py with the project’s Python environment. ${error.message}` };
  }
}

chrome.runtime.onMessage.addListener((message, sender, respond) => {
  // Only our extension's own pages may invoke the native bridge.
  if (sender.id !== chrome.runtime.id || !sender.url?.startsWith(chrome.runtime.getURL("")) || sender.tab) return false;
  (async () => {
    if (message.type === "scan") return scan(message.tabId);
    if (message.type === "native") return native(message.command);
    if (message.type === "pauseBrowser") {
      await chrome.scripting.executeScript({ target: { tabId: message.tabId, allFrames: true },
        func: () => { for (const video of document.querySelectorAll("video")) video.pause(); } });
      return { ok: true };
    }
    return { ok: false, error: "Unknown extension command" };
  })().then(respond, error => respond({ ok: false, error: error.message }));
  return true;
});

chrome.runtime.onInstalled.addListener(() => {
  chrome.storage.session.setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" });
  chrome.contextMenus.create({ id: "cast-media", title: "Cast this video to Fire TV", contexts: ["video"] });
});
chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  if (info.menuItemId !== "cast-media" || !validUrl(info.srcUrl)) return;
  const result = await native({ action: "load", url: info.srcUrl, title: tab.title?.slice(0, 250) || "Video from Chrome",
    mime: mimeFor(info.srcUrl), headers: { Referer: info.frameUrl || info.pageUrl }, position: 0 });
  await chrome.action.setBadgeText({ text: result.ok ? "TV" : "!", tabId: tab.id });
});
