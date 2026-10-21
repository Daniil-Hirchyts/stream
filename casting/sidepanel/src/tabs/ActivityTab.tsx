import { useEffect, useState } from "react";
import { ago } from "../format";
import { Icon, type IconName } from "../icons";
import { activity, clearLog, helper, refreshLog, tv } from "../state";
import { useStore } from "../store";
import type { LogEntry } from "../types";
import { Empty, IconButton } from "../ui";

const LEVEL_ICON: Record<LogEntry["level"], IconName> = { info: "info", success: "success", warning: "alert", error: "alert" };

/** What happened, newest first, with the technical details kept out of the other tabs. */
export function ActivityTab() {
  const state = useStore(activity);
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    void refreshLog();
    const timer = setInterval(() => void refreshLog(), 4000);
    return () => clearInterval(timer);
  }, []);
  const entries = [...state.local, ...state.entries].sort((a, b) => b.at - a.at);

  async function copy() {
    const diagnostics = {
      extension: chrome.runtime.getManifest().version, helper: helper.get().version, userAgent: navigator.userAgent,
      tv: tv.get().result, activity: entries.slice(0, 60)
    };
    await navigator.clipboard.writeText(JSON.stringify(diagnostics, null, 2));
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  return (
    <div className="stack">
      <section className="card">
        <div className="card-head">
          <h2>Activity</h2>
          <span className="actions">
            <IconButton icon={copied ? "check" : "copy"} label="Copy diagnostics" onClick={() => void copy()} />
            <IconButton icon="trash" label="Clear" disabled={!entries.length} onClick={() => void clearLog()} />
          </span>
        </div>
        {entries.length === 0
          ? <Empty icon="activity" title="Nothing yet">Casts, conversions and problems appear here.</Empty>
          : <ul className="log">{entries.map(entry => <Entry key={`${entry.at}-${entry.message}`} entry={entry} />)}</ul>}
      </section>
    </div>
  );
}

function Entry({ entry }: { entry: LogEntry }) {
  const [open, setOpen] = useState(false);
  const detail = entry.detail && Object.entries(entry.detail).filter(([, value]) => value !== undefined && value !== "");
  return (
    <li className={`entry ${entry.level}`}>
      <Icon name={LEVEL_ICON[entry.level]} />
      <div className="grow">
        <button type="button" className="entry-text" disabled={!detail?.length} onClick={() => setOpen(!open)}>
          <span>{entry.message}</span>
          {detail?.length ? <Icon name="chevron" className={open ? "flip" : ""} /> : null}
        </button>
        {open && detail && (
          <dl>{detail.map(([key, value]) => (
            <div key={key}><dt>{key}</dt><dd>{typeof value === "string" ? value : JSON.stringify(value)}</dd></div>
          ))}</dl>
        )}
      </div>
      <time>{ago(entry.at)}</time>
    </li>
  );
}
