const $ = id => document.getElementById(id);
const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
let candidates = [], pagePosition = 0, drm = false, busy = false, polling = false;
let pendingSeek = null, seeking = false;
$("pageTitle").textContent = tab?.title || "Open a page with a video";

async function message(payload) {
  const result = await chrome.runtime.sendMessage(payload);
  if (!result?.ok) throw new Error(result?.error || "Cast command failed");
  return result;
}
const native = command => message({ type: "native", command });
function playbackError(status) {
  if (status.errorMessage) return `${status.errorMessage} (${status.error})`;
  if (/HTTP (410|404)/.test(status.error)) return `The video server rejected the stream URL (${status.error}). Reload the video page, then Find videos and Cast again.`;
  return `TV player: ${status.error}`;
}
function error(exception) { $("error").textContent = exception?.message || String(exception); }
async function perform(task) {
  if (busy) return;
  busy = true;
  $("error").textContent = "";
  try { await task(); } catch (exception) { error(exception); }
  finally { busy = false; }
}
function time(ms) {
  const total = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
}
function sourceLabel(candidate) {
  const url = new URL(candidate.url);
  const type = /mpegurl/i.test(candidate.mime) ? "HLS" : /dash/i.test(candidate.mime) ? "DASH" : candidate.mime || "Video";
  let filename = url.pathname.split("/").pop() || "/";
  try { filename = decodeURIComponent(filename); } catch {}
  return `${type}${candidate.playlist === "master" ? " master" : ""} · ${url.hostname} · ${filename.slice(0, 65)}`;
}
function selected() {
  const source = candidates[Number($("sources").value)];
  $("cast").disabled = !source || drm;
  $("sourceInfo").textContent = source ? `Found in ${source.source}.` : "";
}
async function scan() {
  if (!tab?.id || !/^https?:/.test(tab.url || "")) throw new Error("Open an HTTP(S) video page in Chrome first.");
  $("hint").textContent = "Checking the active player and its stream types…";
  $("cast").disabled = true;
  const result = await message({ type: "scan", tabId: tab.id });
  candidates = result.candidates;
  const score = candidate => candidate.playlist === "master" ? 200
    : candidate.playlist === "trick" ? -100
    : /mpegurl|dash\+xml/i.test(candidate.mime) ? 100
    : candidate.source === "video element" ? 90
    : /video\/mp4/i.test(candidate.mime) || /\.mp4(?:$|[?#])/i.test(candidate.url) ? 80
    : /webm/i.test(candidate.mime) ? 60 : 0;
  candidates.sort((a, b) => score(b) - score(a));
  pagePosition = result.position;
  drm = result.drm;
  $("sources").replaceChildren();
  if (!candidates.length) $("sources").append(new Option("No direct stream found yet", ""));
  candidates.forEach((candidate, index) => $("sources").append(new Option(sourceLabel(candidate), String(index))));
  $("hint").textContent = drm ? "This player uses protected media. Use the service’s TV app."
    : candidates.length ? `${candidates.length} stream${candidates.length === 1 ? "" : "s"} found. Choose the main playlist or the video you want.`
    : result.blob ? "This video uses a browser-only blob URL. Click Find embedded streams, reload the page, start playback, then Find videos."
    : "Start the video first. For an embedded player, click Find embedded streams, reload the page, then Find videos.";
  selected();
}
async function cast(source) {
  if (drm) throw new Error("This player uses protected media; it needs its supported TV app.");
  const position = $("resume").checked ? source.position ?? pagePosition : 0;
  await native({ action: "load", url: source.url, mime: source.mime || "", title: (tab?.title || "Video from Chrome").slice(0, 250), headers: source.headers || {}, position });
  $("nowPlaying").textContent = "Opening on your TV…";
  // Pause the page only after the receiver reports successful playback.
  for (let attempt = 0; attempt < 12; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 700));
    const response = await native({ action: "status" });
    const status = response.status;
    render(status);
    if (status.error) throw new Error(playbackError(status));
    if (status.playing && Date.now() - status.updatedAt < 5000) {
      try { await message({ type: "pauseBrowser", tabId: tab.id }); } catch {}
      return;
    }
  }
  throw new Error("The stream was sent, but playback has not started yet. Check the TV and try another source if it fails.");
}
function render(status) {
  const fresh = status.updatedAt && Date.now() - status.updatedAt < 5000;
  const label = status.playing ? "Playing" : status.state === "ready" ? "Paused" : status.state;
  $("nowPlaying").textContent = status.title ? `${status.title} · ${fresh ? label : "Stopped"}` : "Ready for a video";
  $("time").textContent = `${time(status.position || 0)} / ${time(status.duration || 0)}`;
  if (document.activeElement !== $("seek")) {
    $("seek").max = status.duration || 0;
    $("seek").value = status.position || 0;
  }
  $("seek").disabled = !fresh || !(status.duration > 0);
  if (status.error) $("error").textContent = playbackError(status);
}
async function refresh() {
  if (polling || busy) return;
  polling = true;
  try { render((await native({ action: "status" })).status); } catch (exception) { error(exception); }
  finally { polling = false; }
}
$("sources").addEventListener("change", selected);
$("scan").addEventListener("click", () => perform(scan));
$("cast").addEventListener("click", () => perform(() => cast(candidates[Number($("sources").value)])));
$("embedded").addEventListener("click", async () => {
  // Chrome requires a user gesture for this optional grant. No capture starts until this tab is armed.
  try {
    const allowed = await chrome.permissions.request({ origins: ["http://*/*", "https://*/*"] });
    if (allowed) await perform(scan);
    else error(new Error("Site access was declined. Direct video elements and pasted URLs still work."));
  } catch (exception) { error(exception); }
});
$("manual").addEventListener("click", () => perform(() => {
  const url = $("url").value.trim();
  if (!/^https?:\/\//i.test(url)) throw new Error("Paste a direct HTTP(S) video URL, not the page URL or a blob URL.");
  return cast({ url, position: 0 });
}));
for (const action of ["play", "pause", "stop"]) $(action).addEventListener("click", () => perform(async () => {
  await native({ action });
  render((await native({ action: "status" })).status);
}));
$("seek").addEventListener("change", async () => {
  pendingSeek = Number($("seek").value);
  if (seeking) return;
  seeking = true;
  try {
    while (pendingSeek !== null) {
      const position = pendingSeek;
      pendingSeek = null;
      await native({ action: "seek", position });
    }
  } catch (exception) { error(exception); }
  finally { seeking = false; }
});
$("reloadExtension").addEventListener("click", () => chrome.runtime.reload());
try {
  const info = await native({ action: "info" });
  $("connection").textContent = info.installed ? `● ${info.name} · ${info.host}` : "Receiver isn’t installed. Build and sideload Daniil Cast first.";
} catch (exception) { $("connection").textContent = "Fire TV disconnected"; error(exception); }
try { await scan(); } catch (exception) { $("hint").textContent = exception.message; }
if (await chrome.permissions.contains({ origins: ["http://*/*", "https://*/*"] })) {
  $("embedded").textContent = "Embedded detection enabled";
}
await refresh();
setInterval(refresh, 3000);
