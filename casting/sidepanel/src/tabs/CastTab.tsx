import { useEffect, useMemo, useRef, useState } from "react";
import { qualityLabel, time } from "../format";
import { Icon } from "../icons";
import { buildPlan, episodeOf, initialChoice, loadCommand, needsMac, type Choice, type Plan } from "../page";
import { cast, casting, decoders, dismissCastProblem, helper, page, scanActiveTab, selectedDevice, showTab, tv } from "../state";
import { useStore } from "../store";
import { Empty, Field, IconButton, ProblemNote, Select, Spinner, Switch } from "../ui";
import { HelperSetup } from "./HelperSetup";

export function CastTab() {
  const pageState = useStore(page);
  const helperState = useStore(helper);
  const castState = useStore(casting);
  useStore(tv);
  const [manual, setManual] = useState(false);
  const device = selectedDevice();
  const decoderList = decoders();
  const plan = useMemo(() => pageState.scan && pageState.title !== undefined
    ? buildPlan(pageState.scan, pageState.title, pageState.url || "", decoderList) : null,
  [pageState.scan, pageState.title, pageState.url, decoderList.join()]);

  if (helperState.state === "missing") return <HelperSetup />;
  const empty = !plan || (!plan.versions.length && !plan.seasons.length);

  return (
    <div className="stack">
      <section className="card page">
        <div className="card-head">
          <div className="grow">
            <p className="eyebrow">{pageState.url && /^https?:/.test(pageState.url) ? plan?.host || hostOf(pageState.url) : "This tab"}</p>
            <h1 className="page-title">{plan?.title || pageState.title || "No page"}</h1>
          </div>
          <IconButton icon="link" label="Cast a link" active={manual} onClick={() => setManual(!manual)} />
          <IconButton icon="refresh" label="Look again" className={pageState.scanning ? "turning" : ""}
            onClick={() => void scanActiveTab()} />
        </div>
        {manual && <ManualLink onDone={() => setManual(false)} />}
        {!device && helperState.state === "ready"
          ? <Empty icon="tv" title="Pair your TV first" action={<button type="button" className="button secondary"
              onClick={() => showTab("tv")}><Icon name="plus" />Add a TV</button>}>
              Open Daniil Cast on the TV, then add it here with the code it shows.
            </Empty>
          : pageState.url && !/^https?:/.test(pageState.url)
            ? <Empty icon="cast" title="Nothing to cast here">Open a page with a video.</Empty>
            : pageState.problem && !plan
              ? <Empty icon="alert" title={pageState.problem.title}>{pageState.problem.message}</Empty>
              : !plan
                ? <div className="loading"><Spinner />Looking for videos…</div>
                : plan.drm
                  ? <Empty icon="alert" tone="warning" title="Protected video">
                      This player uses DRM, so only the service’s own TV app can play it.
                    </Empty>
                  : empty
                    ? <Empty icon="cast" title="No video yet">
                        {plan.blob ? "Start the video on the page, then look again." : "Start the video on the page. It shows up here."}
                      </Empty>
                    : <CastForm plan={plan} pageKey={`${pageState.tabId}|${pageState.url}`} deviceName={device?.name || "TV"}
                        busy={castState.busy} />}
      </section>
      {castState.problem && <ProblemNote problem={castState.problem} onDismiss={dismissCastProblem} />}
    </div>
  );
}

function hostOf(url: string) {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return "";
  }
}

function CastForm({ plan, pageKey, deviceName, busy }: { plan: Plan; pageKey: string; deviceName: string; busy: boolean }) {
  const [choice, setChoice] = useState<Choice>(() => initialChoice(plan));
  const lastKey = useRef(pageKey);
  // A new scan of the same page keeps what the viewer picked; another page starts afresh.
  useEffect(() => {
    setChoice(previous => initialChoice(plan, lastKey.current === pageKey ? previous : undefined));
    lastKey.current = pageKey;
  }, [plan, pageKey]);

  const episode = plan.current ? episodeOf(plan, choice.episode) : undefined;
  const season = plan.seasons.find(item => item.id === episode?.season);
  const versions = episode?.versions || plan.versions;
  const qualityOptions = versions.map((item, index) => ({ value: item.key,
    label: (qualityLabel(item.lines) || "Original") + (versions.some((other, i) => i !== index && other.lines === item.lines)
      ? ` · Option ${index + 1}` : "") }));
  const version = versions.find(item => item.key === choice.version) || versions[0];
  const voices = episode?.voices || plan.voices;
  const subtitles = episode?.subtitles || plan.subtitles;
  const list = decoders();
  const converts = needsMac(version, list);
  const resumable = plan.position > 3000 && (!episode || episode.id === plan.current);

  /** Another episode keeps the viewer's voice-over and subtitles, matched by name. */
  function pickEpisode(id: string) {
    const next = episodeOf(plan, id);
    if (!next) return;
    const voiceName = voices.find(item => String(item.number) === choice.voice)?.label;
    const subtitleName = subtitles[Number(choice.subtitle)]?.label;
    const voice = next.voices.find(item => item.label === voiceName) || next.voices[0];
    const subtitle = next.subtitles.findIndex(item => item.label === subtitleName);
    setChoice({
      ...choice, episode: id,
      version: next.versions.some(item => item.key === choice.version) ? choice.version : next.versions[0]?.key || "",
      voice: voice ? String(voice.number) : "",
      subtitle: choice.subtitle === "off" || !next.subtitles.length ? "off" : String(Math.max(0, subtitle))
    });
  }

  const step = tv.get().result?.status?.step;
  const castLabel = !busy ? `Cast to ${deviceName}` : step === "converting" || converts ? "Preparing video…" : "Opening on the TV…";

  return (
    <div className="form">
      {plan.seasons.length > 0 && episode && (
        <Field icon="episodes" label="Episode">
          <span className="pair">
            {plan.seasons.length > 1 && (
              <Select label="Season" value={episode.season} onChange={value => {
                const first = plan.seasons.find(item => item.id === value)?.episodes[0];
                if (first) pickEpisode(first.id);
              }} options={plan.seasons.map(item => ({ value: item.id, label: `Season ${item.number}` }))} />
            )}
            <Select label="Episode" value={episode.id} onChange={pickEpisode}
              options={(season?.episodes || []).map(item => ({ value: item.id,
                label: `Episode ${item.number}` }))} />
          </span>
        </Field>
      )}
      {versions.length > 1 && (
        <Field icon="quality" label="Quality">
          <Select label="Quality" value={version.key} onChange={value => setChoice({ ...choice, version: value })}
            options={qualityOptions} />
        </Field>
      )}
      {voices.length > 1 && (
        <Field icon="voice" label="Voice-over">
          <Select label="Voice-over" value={choice.voice} onChange={value => setChoice({ ...choice, voice: value })}
            options={voices.map(item => ({ value: String(item.number), label: item.label }))} />
        </Field>
      )}
      {subtitles.length > 0 && (
        <Field icon="cc" label="Subtitles">
          <Select label="Subtitles" value={choice.subtitle} onChange={value => setChoice({ ...choice, subtitle: value })}
            options={[...subtitles.map((item, index) => ({ value: String(index), label: item.label })), { value: "off", label: "Off" }]} />
        </Field>
      )}
      {resumable && (
        <Field icon="resume" label={`Start at ${time(plan.position)}`}>
          <Switch label="Start where the page is" checked={choice.resume} onChange={resume => setChoice({ ...choice, resume })} />
        </Field>
      )}
      <button type="button" className="button primary" disabled={busy}
        onClick={() => void cast(loadCommand(plan, choice), !episode || episode.id === plan.current)}>
        {busy ? <Spinner /> : <Icon name="cast" />}<span className="label">{castLabel}</span>
      </button>
    </div>
  );
}

function ManualLink({ onDone }: { onDone: () => void }) {
  const [url, setUrl] = useState("");
  const valid = /^https?:\/\/\S+$/i.test(url.trim());
  return (
    <form className="manual" onSubmit={event => {
      event.preventDefault();
      if (!valid) return;
      void cast({ action: "load", url: url.trim(), title: "Video from Chrome", headers: {}, position: 0 }, false);
      onDone();
    }}>
      <input type="url" placeholder="Paste a video or playlist link" value={url} autoFocus
        onChange={event => setUrl(event.target.value)} />
      <button type="submit" className="button secondary" disabled={!valid}>Cast</button>
    </form>
  );
}
