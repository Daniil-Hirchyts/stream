/** A failure explained for people: code for the panel's logic, title and message for the viewer. */
export interface Problem {
  code: string;
  title: string;
  message?: string;
  detail?: string;
}

export interface Device {
  id: string;
  name: string;
  model?: string;
  address: string;
  port: number;
  pairedAt?: number;
  selected?: boolean;
}

export interface FoundTv {
  id: string;
  name: string;
  model?: string;
  address: string;
  port: number;
  version?: string;
  paired: boolean;
}

export interface Hello {
  version: string;
  conversion: boolean;
  devices: Device[];
}

export interface Track {
  id: string;
  label: string;
  language?: string;
}

export interface SubtitleStyle {
  size: "small" | "medium" | "large" | "xlarge";
  color: "white" | "yellow" | "cyan" | "green" | "magenta" | "blue" | "amber" | "red";
  edge: "outline" | "shadow" | "box";
  position: "low" | "standard" | "raised";
}

export interface CatalogSummary {
  seasons: { id: string; number: string; episodes: { id: string; number: string; title: string; duration: number }[] }[];
  current: string;
  next: string;
}

export type Phase = "closed" | "idle" | "preparing" | "buffering" | "playing" | "paused" | "ended" | "error";

export interface TvStatus {
  phase: Phase;
  version?: string;
  title: string;
  step: "" | "opening" | "converting" | "preparing";
  stepFormat: string;
  needsConversion: boolean;
  unsupportedFormat: string;
  error: (Problem & { retry: boolean; httpStatus: number }) | null;
  renderedVideo: boolean;
  source: { kind: string; codec: string; convertedFrom: string } | null;
  videoFormat: { width?: number; height?: number; mime?: string };
  playing: boolean;
  playWhenReady: boolean;
  position: number;
  duration: number;
  buffered: number;
  textTracks: Track[];
  textTrack: string;
  audioTracks: Track[];
  audioTrack: string;
  qualities: Track[];
  quality: string;
  speed: number;
  episode: { id: string; season: string; number: string; label: string } | null;
  catalog: CatalogSummary | null;
  subtitleStyle?: SubtitleStyle;
  updatedAt: number;
}

export interface StatusResult {
  online: boolean;
  device: Device;
  status?: TvStatus;
  problem?: Problem;
  decoders?: { video: string[]; audio: string[] };
  conversion: { active?: boolean; sourceCodec?: string; error?: string };
}

export interface LoadResult {
  route: "direct" | "converted";
  state: "playing" | "pending";
  format: string;
  status?: TvStatus;
}

export interface LogEntry {
  at: number;
  level: "info" | "success" | "warning" | "error";
  message: string;
  detail?: Record<string, unknown>;
}

// What the background script finds on a page.

export interface Candidate {
  url: string;
  mime: string;
  source: string;
  headers: Record<string, string>;
  position?: number;
  playlist?: "master" | "media" | "trick";
  info?: { codec: string; width: number; height: number; audio: { name: string; language: string }[] };
}

export interface PageSubtitle {
  url: string;
  mime: string;
  language?: string;
  label?: string;
  showing?: boolean;
}

export interface SeriesEpisode {
  episode: string;
  title: string;
  duration: number;
  versions: { key: string; url: string }[];
  audio: { names: string[]; order: number[] } | null;
  cc: { url: string; name: string }[];
}

export interface Series {
  seasons: { season: string; episodes: SeriesEpisode[] }[];
  current: { season: string; episode: string } | null;
  playing: string;
  headers: Record<string, string>;
}

export interface Scan {
  candidates: Candidate[];
  subtitles: PageSubtitle[];
  textTrack: { language: string; label: string } | null;
  audio: { names: string[]; order: number[]; selected: number } | null;
  series: Series | null;
  drm: boolean;
  blob: boolean;
  position: number;
}
