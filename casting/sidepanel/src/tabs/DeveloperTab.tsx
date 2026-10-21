import { IconButton } from "../ui";
import { helper, page, refreshHelper, selectedDevice, tv } from "../state";
import { useStore } from "../store";
import { ActivityTab } from "./ActivityTab";
import { ManualPairing } from "./TvTab";

/** Versions, network details and diagnostics have one home, away from playback. */
export function DeveloperTab() {
  const helperState = useStore(helper);
  const result = useStore(tv).result;
  const scan = useStore(page).scan;
  const device = selectedDevice();
  const status = result?.status;
  const phase = status?.phase ? status.phase[0].toUpperCase() + status.phase.slice(1) : "Ready";
  return (
    <div className="stack">
      <section className="card">
        <div className="card-head">
          <h2>Developer</h2>
          <IconButton icon="refresh" label="Refresh diagnostics" onClick={() => void refreshHelper()} />
        </div>
        <p className="muted">Technical information and troubleshooting tools.</p>
        <ul className="facts">
          <li><span>Extension</span><b>v{chrome.runtime.getManifest().version}</b></li>
          <li><span>Desktop helper</span><b>{helperState.state === "ready" ? "Connected" : helperState.state}</b></li>
          <li><span>Helper version</span><b>{helperState.version ? `v${helperState.version}` : "Unavailable"}</b></li>
          <li><span>FFmpeg conversion</span><b>{helperState.conversion ? "Ready" : "Unavailable"}</b></li>
          {device && <>
            <li><span>Selected TV</span><b>{device.name}</b></li>
            <li><span>Address</span><b>{device.address}:{device.port}</b></li>
            <li><span>Model</span><b>{device.model || "Unknown"}</b></li>
            <li><span>Receiver version</span><b>{status?.version ? `v${status.version}` : "Unavailable"}</b></li>
            <li><span>Player state</span><b>{result?.online ? phase : "Offline"}</b></li>
          </>}
          {result?.decoders && <li><span>Video decoders</span><b>{result.decoders.video.join(", ")}</b></li>}
          {status?.source?.convertedFrom && <li><span>Converted from</span><b>{status.source.convertedFrom}</b></li>}
          {result?.conversion.active && <li><span>Converter</span><b>Active · {result.conversion.sourceCodec?.toUpperCase()}</b></li>}
          {scan?.candidates.map((source, index) => <li key={source.url}><span>Page source {index + 1}</span>
            <b>{[source.info?.codec, source.mime].filter(Boolean).join(" · ") || "Unknown format"}</b></li>)}
        </ul>
        <button type="button" className="link small" onClick={() => chrome.runtime.reload()}>Reload extension</button>
        {helperState.problem && <p className="muted">{helperState.problem.detail || helperState.problem.title}</p>}
      </section>
      <ManualPairing />
      <ActivityTab />
    </div>
  );
}
