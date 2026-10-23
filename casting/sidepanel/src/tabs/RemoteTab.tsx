import { useEffect, useRef, useState, type CSSProperties } from "react";
import { lines, qualityLabel, time } from "../format";
import { Icon } from "../icons";
import { casting, command, dismissCastProblem, helper, selectedDevice, showTab, tv } from "../state";
import { useStore } from "../store";
import type { Problem, StatusResult, SubtitleStyle, TvStatus } from "../types";
import { Empty, Field, IconButton, ProblemNote, Segmented, Select, Spinner } from "../ui";
import { HelperSetup } from "./HelperSetup";

// Subtitle style choices shared with the TV app. The fractions mirror SubtitleStyle.java, so the preview is to scale.
const SIZE = { small: .042, medium: .0533, large: .066, xlarge: .082 };
const GAP = { low: .04, standard: .08, raised: .16 };
const COLOR = {
  white: "#ffffff", yellow: "#ffe15a", cyan: "#8be9ff",
  green: "#a8f387", magenta: "#ff80df", blue: "#82b1ff", amber: "#ffb84d", red: "#ff6e6e"
};
const SPEEDS = [0.75, 1, 1.25, 1.5, 1.75, 2];
const DEFAULT_STYLE: SubtitleStyle = { size: "medium", color: "white", edge: "box", position: "standard" };

export function RemoteTab() {
  const castState = useStore(casting);
  return (
    <div className="stack">
      {castState.problem && <ProblemNote problem={castState.problem} onDismiss={dismissCastProblem} />}
      <RemoteBody />
    </div>
  );
}

function RemoteBody() {
  const tvState = useStore(tv);
  const helperState = useStore(helper);
  const device = selectedDevice();
  if (helperState.state === "missing") return <HelperSetup />;
  if (helperState.state === "ready" && !device) {
    return <Empty icon="tv" title="No TV yet" action={<button type="button" className="button secondary"
      onClick={() => showTab("tv")}><Icon name="plus" />Add a TV</button>}>Pair your TV to control it from here.</Empty>;
  }
  const result = tvState.result;
  if (!result) return <div className="loading"><Spinner />Connecting to {device?.name || "the TV"}…</div>;
  if (!result.online) {
    return <Empty icon="wifi" title={result.problem?.title || "The TV isn’t answering"}>{result.problem?.message}</Empty>;
  }
  const status = result.status!;
  if (status.phase === "closed" || status.phase === "idle") {
    return <Empty icon="tv" title="Nothing playing" action={<button type="button" className="button secondary"
      onClick={() => showTab("cast")}><Icon name="cast" />Cast a video</button>}>
      {result.device.name} is ready. {status.phase === "closed" ? "Daniil Cast opens on the TV when you cast." : ""}
    </Empty>;
  }
  return <NowPlaying result={result} receivedAt={tvState.at} />;
}

/** A value the viewer just chose wins over status reads that may predate it, for a few seconds. */
function usePending() {
  const [pending, setPending] = useState<Record<string, { value: string; until: number }>>({});
  const value = (key: string, actual: string) => {
    const item = pending[key];
    return item && item.until > Date.now() ? item.value : actual;
  };
  const set = (key: string, next: string) => setPending(state => ({ ...state, [key]: { value: next, until: Date.now() + 4000 } }));
  return { value, set };
}

function NowPlaying({ result, receivedAt }: { result: StatusResult; receivedAt: number }) {
  const status = result.status!;
  const pending = usePending();
  const [problem, setProblem] = useState<Problem>();
  const send = (message: Record<string, unknown>) => void command(message, setProblem);
  const active = status.playWhenReady && status.phase !== "ended";
  const audioLabel = status.audioTracks.find(track => track.id === status.audioTrack)?.label;
  const playing = lines(status.videoFormat.width, status.videoFormat.height);
  const details = [status.episode?.label, status.audioTracks.length > 1 ? audioLabel : "", qualityLabel(playing)].filter(Boolean);
  const catalog = status.catalog;
  const hasNext = Boolean(catalog?.next);
  const hasPrevious = Boolean(catalog && status.episode && catalog.seasons.flatMap(season => season.episodes)
    .findIndex(episode => episode.id === status.episode!.id) > 0);

  return (
    <>
      <section className="card now">
        <div className="card-head">
          <div className="grow">
            <p className="eyebrow">{result.device.name}</p>
            <h1 className="page-title">{status.title || "Video"}</h1>
            {details.length > 0 && <p className="sub">{details.join(" · ")}</p>}
          </div>
          <StateChip status={status} />
        </div>
        {status.phase === "preparing" && <Preparing status={status} />}
        {status.phase === "error" && status.error && (
          <div className="problem inline">
            <Icon name="alert" />
            <div className="grow">
              <strong>{status.error.title}</strong>
              <p>{status.error.message}</p>
              {status.error.retry && (
                <button type="button" className="button secondary small" onClick={() => send({ action: "retry" })}>
                  <Icon name="refresh" />Try again
                </button>
              )}
            </div>
          </div>
        )}
        {status.phase !== "preparing" && status.phase !== "error" && (
          <>
            <Seek status={status} receivedAt={receivedAt} onSeek={position => send({ action: "seek", position })} />
            <div className="transport">
              {catalog && <IconButton icon="previous" label="Previous episode" disabled={!hasPrevious}
                onClick={() => send({ action: "previous" })} />}
              <IconButton icon="back10" label="Back 10 seconds" onClick={() => send({ action: "seek", position: Math.max(0, status.position - 10000) })} />
              <button type="button" className="play" aria-label={active ? "Pause" : "Play"}
                onClick={() => send({ action: active ? "pause" : "play" })}>
                <Icon name={active ? "pause" : "play"} />
              </button>
              <IconButton icon="forward10" label="Forward 10 seconds" onClick={() => send({ action: "seek", position: status.position + 10000 })} />
              {catalog && <IconButton icon="next" label="Next episode" disabled={!hasNext} onClick={() => send({ action: "next" })} />}
            </div>
          </>
        )}
        <div className="card-foot">
          <button type="button" className="link small danger-hover" onClick={() => send({ action: "stop" })}>
            <Icon name="stop" />Stop on TV
          </button>
        </div>
      </section>
      {problem && <ProblemNote problem={problem} onDismiss={() => setProblem(undefined)} />}

      <section className="card form">
        {catalog && status.episode && <EpisodePicker status={status} onPick={id => send({ action: "episode", id })} />}
        {status.audioTracks.length > 1 && (
          <Field icon="voice" label="Voice-over">
            <Select label="Voice-over" value={pending.value("audio", status.audioTrack)}
              options={status.audioTracks.map(track => ({ value: track.id, label: track.label }))}
              onChange={value => { pending.set("audio", value); send({ action: "audio", track: value }); }} />
          </Field>
        )}
        {status.textTracks.length > 0 && (
          <Field icon="cc" label="Subtitles">
            <Select label="Subtitles" value={pending.value("subtitles", status.textTrack)}
              options={[...status.textTracks.map(track => ({ value: track.id, label: track.label })), { value: "off", label: "Off" }]}
              onChange={value => { pending.set("subtitles", value); send({ action: "subtitles", track: value }); }} />
          </Field>
        )}
        {status.qualities.length > 1 && (
          <Field icon="quality" label="Quality">
            <Select label="Quality" value={pending.value("quality", status.quality)}
              options={[{ value: "auto", label: playing ? `Auto · ${qualityLabel(playing)}` : "Auto" },
                ...status.qualities.map(quality => ({ value: quality.id, label: quality.label }))]}
              onChange={value => { pending.set("quality", value); send({ action: "quality", id: value }); }} />
          </Field>
        )}
        <Field icon="speed" label="Speed">
          <Select label="Speed" value={pending.value("speed", String(status.speed))}
            options={SPEEDS.map(speed => ({ value: String(speed), label: speed === 1 ? "Normal" : `${speed}×` }))}
            onChange={value => { pending.set("speed", value); send({ action: "speed", value: Number(value) }); }} />
        </Field>
        <StyleEditor style={status.subtitleStyle || DEFAULT_STYLE} onChange={change => send({ action: "subtitles", ...change })} />
      </section>
    </>
  );
}

function StateChip({ status }: { status: TvStatus }) {
  const label = { preparing: "Preparing", buffering: "Loading", playing: "Playing", paused: "Paused", ended: "Ended",
    error: "Stopped", idle: "Ready", closed: "Ready" }[status.phase];
  return <span className="chip" data-state={status.phase}>{label}</span>;
}

/** Preparing is a normal step, not a failure: opening, or converting on this Mac. */
function Preparing({ status }: { status: TvStatus }) {
  const text = status.step === "converting" || status.step === "preparing"
    ? "Preparing this video for your TV…" : "Opening on the TV…";
  return <div className="preparing"><Spinner /><p>{text}</p></div>;
}

function Seek({ status, receivedAt, onSeek }: { status: TvStatus; receivedAt: number; onSeek: (position: number) => void }) {
  const [now, setNow] = useState(Date.now());
  const [dragging, setDragging] = useState<number | null>(null);
  const target = useRef<number | null>(null);
  const commitTimer = useRef<ReturnType<typeof setTimeout>>();
  const resetTimer = useRef<ReturnType<typeof setTimeout>>();
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 500);
    return () => clearInterval(timer);
  }, []);
  useEffect(() => () => {
    clearTimeout(commitTimer.current);
    clearTimeout(resetTimer.current);
  }, []);
  // Coalesce rapid keyboard seeks so older network replies cannot win over
  // the final position. Pointer release uses the same commit path.
  const commit = () => {
    if (target.current === null) return;
    const next = target.current;
    target.current = null;
    clearTimeout(commitTimer.current);
    clearTimeout(resetTimer.current);
    commitTimer.current = setTimeout(() => {
      onSeek(next);
      resetTimer.current = setTimeout(() => setDragging(null), 1500);
    }, 150);
  };
  // Between status reads a playing video's position is estimated, so the bar moves smoothly.
  const moving = status.playing ? (now - receivedAt) * (status.speed || 1) : 0;
  const position = Math.min(dragging ?? (status.position + moving), status.duration || Infinity);
  const duration = status.duration;
  const fill = duration ? `${(position / duration) * 100}%` : "0%";
  return (
    <div className="seek">
      <input type="range" min={0} max={duration || 1} step={1000} value={position} disabled={!duration}
        style={{ "--fill": fill } as CSSProperties} aria-label="Position"
        onChange={event => { clearTimeout(commitTimer.current); clearTimeout(resetTimer.current);
          target.current = Number(event.target.value); setDragging(target.current); }}
        onPointerUp={commit} onKeyUp={commit} />
      <div className="times"><span>{time(position)}</span><span>{duration ? `−${time(duration - position)}` : "Live"}</span></div>
    </div>
  );
}

function EpisodePicker({ status, onPick }: { status: TvStatus; onPick: (id: string) => void }) {
  const catalog = status.catalog!;
  const current = status.episode!;
  const seasonOf = (id: string) => catalog.seasons.find(season => season.episodes.some(episode => episode.id === id))?.id;
  const [seasonId, setSeasonId] = useState(seasonOf(current.id));
  // Browsing another season stays put between status reads; a new episode on the TV moves to its season.
  useEffect(() => setSeasonId(seasonOf(current.id)), [current.id]);
  const season = catalog.seasons.find(item => item.id === seasonId) || catalog.seasons[0];
  const inSeason = season.episodes.some(episode => episode.id === current.id);
  return (
    <Field icon="episodes" label="Episode">
      <span className="pair">
        {catalog.seasons.length > 1 && (
          <Select label="Season" value={season.id} onChange={setSeasonId}
            options={catalog.seasons.map(item => ({ value: item.id, label: `Season ${item.number}` }))} />
        )}
        <Select label="Episode" value={inSeason ? current.id : ""} onChange={value => value && onPick(value)}
          options={[...(inSeason ? [] : [{ value: "", label: "Choose…" }]),
            ...season.episodes.map(episode => ({ value: episode.id, label: `Episode ${episode.number}` }))]} />
      </span>
    </Field>
  );
}

function StyleEditor({ style, onChange }: { style: SubtitleStyle; onChange: (change: Partial<SubtitleStyle>) => void }) {
  const [local, setLocal] = useState(style);
  const [touched, setTouched] = useState(0);
  // A change sent from here wins over a status read that may predate it.
  useEffect(() => { if (Date.now() - touched > 4000) setLocal(style); }, [style, touched]);
  const change = (next: Partial<SubtitleStyle>) => {
    setLocal({ ...local, ...next });
    setTouched(Date.now());
    onChange(next);
  };
  return (
    <details className="style">
      <summary><span className="field-label"><Icon name="style" />Subtitle style</span><Icon name="chevron" /></summary>
      <div className="preview" data-edge={local.edge} style={{ "--size": SIZE[local.size], "--gap": GAP[local.position],
        "--color": COLOR[local.color] } as CSSProperties}>
        <span>Subtitles will look like this</span>
      </div>
      <div className="style-row"><span>Size</span>
        <Segmented label="Size" value={local.size} onChange={size => change({ size })} options={[
          { value: "small", label: <span style={{ fontSize: 11 }}>A</span>, title: "Small" },
          { value: "medium", label: <span style={{ fontSize: 13 }}>A</span>, title: "Medium" },
          { value: "large", label: <span style={{ fontSize: 15 }}>A</span>, title: "Large" },
          { value: "xlarge", label: <span style={{ fontSize: 17 }}>A</span>, title: "Extra large" }]} />
      </div>
      <div className="style-row"><span>Color</span>
        <Segmented label="Color" value={local.color} onChange={color => change({ color })} options={(["white", "yellow", "cyan", "green", "magenta", "blue", "amber", "red"] as const)
          .map(color => ({ value: color, title: color, label: <i className="swatch" style={{ "--swatch": COLOR[color] } as CSSProperties} /> }))} />
      </div>
      <div className="style-row"><span>Back</span>
        <Segmented label="Background" value={local.edge} onChange={edge => change({ edge })} options={[
          { value: "box", label: "Box" }, { value: "outline", label: "Outline" }, { value: "shadow", label: "Shadow" }]} />
      </div>
      <div className="style-row"><span>Position</span>
        <Segmented label="Position" value={local.position} onChange={position => change({ position })} options={[
          { value: "low", label: "Low" }, { value: "standard", label: "Middle" }, { value: "raised", label: "High" }]} />
      </div>
    </details>
  );
}
