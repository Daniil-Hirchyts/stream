import { cleanTitle, fileName, guessLanguage, host, LANGUAGE, languageName, lines, preferredLanguages, qualityLabel, seriesTitle } from "./format";
import type { Candidate, PageSubtitle, Scan, SeriesEpisode } from "./types";

/** The Fire TV Stick's decoders, until the TV says otherwise. */
export const DEFAULT_DECODERS = ["H.264", "HEVC", "VP9"];

export interface SubtitleFile {
  url: string;
  mime: string;
  language: string;
  label: string;
  showing: boolean;
}

/** One version of a video: a quality and codec, as one playlist or file. */
export interface Version {
  key: string;
  kind: string;
  url: string;
  mime: string;
  codec: string;
  lines: number;
  headers: Record<string, string>;
  label: string;
  voices: string[];
}

export interface Episode {
  id: string;
  season: string;
  number: string;
  title: string;
  duration: number;
  versions: Version[];
  voices: Voice[];
  subtitles: SubtitleFile[];
}

/** A voice-over: its number is the player's own index, which the TV maps back to the stream's tracks. */
export interface Voice {
  number: number;
  label: string;
}

/** Everything the Cast tab offers for the current page. */
export interface Plan {
  title: string;
  host: string;
  seasons: { id: string; number: string; episodes: Episode[] }[];
  /** The episode the page plays, when it is a series. */
  current: string | null;
  seriesHeaders: Record<string, string>;
  versions: Version[];
  voices: Voice[];
  selectedVoice: number;
  subtitles: SubtitleFile[];
  textTrack: Scan["textTrack"];
  position: number;
  drm: boolean;
  blob: boolean;
}

export interface Choice {
  episode: string;
  version: string;
  voice: string;
  subtitle: string;
  resume: boolean;
}

const mimeOf = (key: string) =>
  key.startsWith("hls") ? "application/x-mpegURL" : key.startsWith("dash") ? "application/dash+xml" : "video/mp4";

function streamType(candidate: Candidate): string {
  if (/mpegurl/i.test(candidate.mime)) return "HLS";
  if (/dash/i.test(candidate.mime)) return "DASH";
  if (/webm/i.test(candidate.mime) || /\.webm(?:$|[?#])/i.test(candidate.url)) return "WebM";
  return "MP4";
}

function versionLabel(codec: string, value: number, fallback: string): string {
  return [qualityLabel(value), codec].filter(Boolean).join(" · ") || fallback;
}

/** Readable, unique names for subtitle files, and the language the TV can match on. */
export function describeSubtitles(list: PageSubtitle[]): SubtitleFile[] {
  const seen = new Map<string, number>();
  let size = 0;
  return list.filter(item => item.url.length <= 8000 && (size += item.url.length) < 24000).slice(0, 12).map(item => {
    const base = fileName(item.url).replace(/\.[a-z0-9]+$/i, "");
    // Players number their files: "Eng. full - 2".
    const given = (item.label || "").replace(/\s*-\s*\d+$/, "").trim();
    const language = LANGUAGE.test(item.language || "") ? item.language! : guessLanguage(`${given} ${base}`);
    let label = given || (language ? languageName(language) : "") || base.replace(/[-_.]+/g, " ").trim().slice(0, 40) || "Subtitles";
    if (/forced|форс/i.test(`${base} ${item.label || ""}`) && !/forced/i.test(label)) label += " (forced)";
    const count = (seen.get(label) || 0) + 1;
    seen.set(label, count);
    return { url: item.url, mime: item.mime || "", language, label: (count > 1 ? `${label} ${count}` : label).slice(0, 100),
      showing: Boolean(item.showing) };
  });
}

/** Voice-overs by the player's numbering; "order" is the player's menu order. */
function voicesOf(names: string[], order: number[]): Voice[] {
  if (names.length < 2) return [];
  const sequence = order.length === names.length ? order : names.map((_, index) => index);
  return sequence.filter(number => number < names.length).map(number => ({ number, label: names[number] }));
}

/** Voice-overs named only by the stream: rus0, rus1, eng2. */
function streamVoices(candidate?: Candidate): Voice[] {
  const tracks = candidate?.info?.audio || [];
  if (tracks.length < 2) return [];
  const tags = tracks.map(track => track.name || track.language || "");
  const numbered = tags.every(tag => /^[a-z]{2,3}\d{1,2}$/i.test(tag));
  const seen = new Map<string, number>();
  return tracks.map((track, index) => {
    let label = track.name && !/^[a-z]{2,3}\d{1,2}$/i.test(track.name) ? track.name : "";
    label ||= languageName((track.language || "").replace(/\d+$/, "")) || `Audio ${index + 1}`;
    const count = (seen.get(label) || 0) + 1;
    seen.set(label, count);
    return { number: numbered ? Number(tags[index].match(/\d+$/)![0]) : index, label: count > 1 ? `${label} ${count}` : label };
  });
}

/** Versions the TV plays itself first, then sharper ones. */
export function rank(versions: Version[], decoders: string[]): Version[] {
  const score = (version: Version) => (!version.codec || decoders.includes(version.codec) ? 10000 : 0) + version.lines
    + (/dash|mpegurl/i.test(version.mime) ? 5 : 0);
  return [...versions].sort((a, b) => score(b) - score(a));
}

export function needsMac(version: Version | undefined, decoders: string[]): boolean {
  return Boolean(version?.codec) && !decoders.includes(version!.codec);
}

function candidateVersion(candidate: Candidate, index: number): Version {
  const info = candidate.info;
  const value = lines(info?.width, info?.height);
  const type = streamType(candidate);
  const file = fileName(candidate.url);
  const readable = file.length <= 40 && !/[A-Za-z0-9_-]{24,}/.test(file);
  const fallback = candidate.playlist === "master" ? "All qualities" : candidate.playlist === "media" ? "Single quality"
    : type === "HLS" || type === "DASH" ? `${type} stream` : readable ? file : "Video file";
  return { key: `c${index}`, kind: type.toLowerCase(), url: candidate.url, mime: candidate.mime, codec: info?.codec || "",
    lines: value, headers: candidate.headers || {}, label: versionLabel(info?.codec || "", value, fallback),
    voices: (info?.audio || []).map(track => track.name || track.language) };
}

/** What can be cast from this page, from what the background script found on it. */
export function buildPlan(scan: Scan, tabTitle: string, tabUrl: string, decoders: string[]): Plan {
  const candidates = scan.candidates.filter(candidate => candidate.playlist !== "trick");
  const byUrl = new Map(candidates.map(candidate => [candidate.url, candidate]));
  const title = cleanTitle(tabTitle);
  const plan: Plan = {
    title, host: host(tabUrl), seasons: [], current: null, seriesHeaders: {}, versions: [], voices: [], selectedVoice: -1,
    subtitles: describeSubtitles(scan.subtitles), textTrack: scan.textTrack, position: scan.position, drm: scan.drm,
    blob: scan.blob
  };

  const series = scan.series;
  if (series?.seasons.length) {
    plan.title = seriesTitle(title);
    plan.seriesHeaders = series.headers;
    // Codec and quality are known for the page's episode; other episodes come the same way, by kind.
    const kinds = new Map<string, { codec: string; lines: number }>();
    for (const season of series.seasons) {
      for (const episode of season.episodes) {
        for (const { key, url } of episode.versions) {
          const info = byUrl.get(url)?.info;
          if (info) kinds.set(key, { codec: info.codec, lines: lines(info.width, info.height) });
        }
      }
    }
    const toEpisode = (season: string, item: SeriesEpisode): Episode => ({
      id: `${season}:${item.episode}`, season, number: item.episode, title: item.title, duration: item.duration,
      versions: rank(item.versions.map(({ key, url }) => {
        const known = kinds.get(key) || { codec: "", lines: 0 };
        return { key, kind: key, url, mime: mimeOf(key), codec: known.codec, lines: known.lines, headers: series.headers,
          label: versionLabel(known.codec, known.lines, key.toUpperCase()), voices: item.audio?.names || [] };
      }), decoders),
      voices: voicesOf(item.audio?.names || [], item.audio?.order || []),
      subtitles: describeSubtitles(item.cc.map(cc => ({ url: cc.url, mime: /\.srt(?:$|[?#])/i.test(cc.url) ? "application/x-subrip" : "text/vtt", label: cc.name })))
    });
    plan.seasons = [...series.seasons]
      .sort((a, b) => Number(a.season) - Number(b.season))
      .map(season => ({ id: season.season, number: season.season,
        episodes: season.episodes.map(item => toEpisode(season.season, item))
          .sort((a, b) => Number(a.number) - Number(b.number)) }))
      .filter(season => season.episodes.length);
    const all = plan.seasons.flatMap(season => season.episodes);
    const playing = all.find(episode => episode.title && episode.title === series.playing)
      || all.find(episode => episode.season === series.current?.season && episode.number === series.current?.episode)
      || all[0];
    plan.current = playing?.id || null;
  } else {
    plan.versions = rank(candidates.map(candidateVersion), decoders);
    plan.voices = voicesOf(scan.audio?.names || [], scan.audio?.order || []);
    if (!plan.voices.length) plan.voices = streamVoices(candidates.find(candidate => candidate.url === plan.versions[0]?.url));
  }
  plan.selectedVoice = scan.audio?.selected ?? -1;
  return plan;
}

export function episodeOf(plan: Plan, id: string): Episode | undefined {
  for (const season of plan.seasons) for (const episode of season.episodes) if (episode.id === id) return episode;
  return undefined;
}

/** The choices to start with: the page's episode, best version, voice-over and the subtitles it shows. */
export function initialChoice(plan: Plan, previous?: Choice): Choice {
  const episode = plan.current ? episodeOf(plan, previous?.episode || "") || episodeOf(plan, plan.current) : undefined;
  const versions = episode?.versions || plan.versions;
  const voices = episode?.voices || plan.voices;
  const subtitles = episode?.subtitles || plan.subtitles;
  const keep = <T,>(value: string | undefined, options: T[], id: (option: T) => string) =>
    value !== undefined && options.some(option => id(option) === value) ? value : undefined;
  const voice = keep(previous?.voice, voices, option => String(option.number))
    ?? (voices.some(option => option.number === plan.selectedVoice) ? String(plan.selectedVoice) : String(voices[0]?.number ?? ""));
  return {
    episode: episode?.id || "",
    version: keep(previous?.version, versions, option => option.key) ?? versions[0]?.key ?? "",
    voice,
    subtitle: keep(previous?.subtitle, [...subtitles.map((_, index) => String(index)), "off"], option => option)
      ?? defaultSubtitle(subtitles),
    resume: previous?.resume ?? true
  };
}

/** The one the page shows, else the first in a language Chrome is set to, else the player's first. */
function defaultSubtitle(subtitles: SubtitleFile[]): string {
  if (!subtitles.length) return "off";
  const showing = subtitles.findIndex(subtitle => subtitle.showing);
  if (showing >= 0) return String(showing);
  const languages = preferredLanguages();
  const ranked = subtitles.map((subtitle, index) => [languages.indexOf(subtitle.language), index])
    .filter(([position]) => position >= 0).sort((a, b) => a[0] - b[0]);
  return String(ranked.length ? ranked[0][1] : 0);
}

/** The helper's load command for these choices. */
export function loadCommand(plan: Plan, choice: Choice): Record<string, unknown> {
  const episode = plan.current ? episodeOf(plan, choice.episode) : undefined;
  const versions = episode?.versions || plan.versions;
  const version = versions.find(option => option.key === choice.version) || versions[0];
  const voices = episode?.voices || plan.voices;
  const subtitles = episode?.subtitles || plan.subtitles;
  // The page's position belongs to the episode it plays.
  const resumable = choice.resume && plan.position > 3000 && (!episode || episode.id === plan.current);
  const command: Record<string, unknown> = {
    action: "load", url: version.url, mime: version.mime, title: plan.title.slice(0, 250), headers: version.headers,
    kind: version.kind, codec: version.codec, position: resumable ? Math.round(plan.position) : 0
  };
  if (subtitles.length) {
    command.subtitles = subtitles.map(({ url, mime, language, label }) => ({ url, mime, language, label }));
    command.subtitle = choice.subtitle;
  }
  if (voices.length > 1 && choice.voice !== "") {
    command.audio = Number(choice.voice);
    command.audioNames = namesByNumber(voices);
  }
  // Subtitles a player built from its playlist: the TV picks the same language from the stream.
  if (!episode && plan.textTrack) {
    if (LANGUAGE.test(plan.textTrack.language || "")) command.textLanguage = plan.textTrack.language;
    if (plan.textTrack.label) command.textLabel = plan.textTrack.label.slice(0, 100);
  }
  if (episode) {
    command.episode = episode.id;
    command.catalog = {
      title: plan.title.slice(0, 250), headers: plan.seriesHeaders,
      seasons: plan.seasons.map(season => ({ id: season.id, number: season.number, episodes: season.episodes.map(item => ({
        id: item.id, number: item.number, title: item.title.slice(0, 200), duration: item.duration,
        versions: item.versions.slice(0, 6).map(({ kind, url, mime, codec }) => ({ kind, url, mime, codec })),
        audioNames: namesByNumber(item.voices),
        subtitles: item.subtitles.map(({ url, mime, language, label }) => ({ url, mime, language, label }))
      })) }))
    };
  }
  return command;
}

/** Names indexed by the player's numbers, as the TV expects them. */
function namesByNumber(voices: Voice[]): string[] {
  const names: string[] = [];
  for (const voice of voices) names[voice.number] = voice.label.slice(0, 80);
  return Array.from(names, name => name || "").slice(0, 24);
}
