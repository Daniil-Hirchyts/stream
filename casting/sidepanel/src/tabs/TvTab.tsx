import { useEffect, useRef, useState } from "react";
import { Icon } from "../icons";
import { native, problemOf } from "../native";
import { helper, refreshHelper, selectedDevice, setDevices, showTab, tv } from "../state";
import { useStore } from "../store";
import type { Device, FoundTv, Problem } from "../types";
import { Empty, IconButton, ProblemNote, Spinner } from "../ui";
import { HelperSetup } from "./HelperSetup";

type Flow =
  | { step: "closed" }
  | { step: "searching" }
  | { step: "list"; tvs: FoundTv[] }
  | { step: "code"; tv: { address: string; port: number; name: string } };

export function TvTab() {
  const helperState = useStore(helper);
  const [flow, setFlow] = useState<Flow>({ step: "closed" });
  const [problem, setProblem] = useState<Problem>();
  if (helperState.state === "missing") return <HelperSetup />;
  const devices = helperState.devices;

  async function search() {
    setProblem(undefined);
    setFlow({ step: "searching" });
    try {
      const result = await native.request<{ tvs: FoundTv[] }>({ action: "discover" }, 20000);
      setFlow({ step: "list", tvs: result.tvs });
    } catch (error) {
      setProblem(problemOf(error));
      setFlow({ step: "list", tvs: [] });
    }
  }

  return (
    <div className="stack">
      {(devices.length > 0 || flow.step === "closed") && <section className="card">
        <div className="card-head">
          <h2>Your TVs</h2>
          {devices.length > 0 && flow.step === "closed" && (
            <IconButton icon="plus" label="Add a TV" onClick={() => void search()} />
          )}
        </div>
        {devices.length === 0 && flow.step === "closed" && (
          <Empty icon="tv" title="No TV yet" action={<button type="button" className="button primary"
            onClick={() => void search()}><Icon name="plus" />Add a TV</button>}>
            Open Daniil Cast on the TV. It shows a code to type here.
          </Empty>
        )}
        <ul className="devices">
          {devices.map(device => <DeviceRow key={device.id} device={device} several={devices.length > 1} />)}
        </ul>
      </section>}

      {flow.step !== "closed" && (
        <section className="card">
          <div className="card-head">
            <h2>{flow.step === "code" ? `Pair ${flow.tv.name}` : "Add a TV"}</h2>
            <IconButton icon="close" label="Cancel" className="small" onClick={() => { setFlow({ step: "closed" }); setProblem(undefined); }} />
          </div>
          {flow.step === "searching" && <div className="loading"><Spinner />Looking for TVs on this network…</div>}
          {flow.step === "list" && (
            <FoundList tvs={flow.tvs} onSearch={() => void search()} onPick={found => setFlow({ step: "code", tv: found })}
              onProblem={setProblem} />
          )}
          {flow.step === "code" && (
            <CodeEntry target={flow.tv} onProblem={setProblem} onPaired={devicesNow => {
              setDevices(devicesNow);
              setFlow({ step: "closed" });
            }} />
          )}
        </section>
      )}
      {problem && <ProblemNote problem={problem} onDismiss={() => setProblem(undefined)} />}
    </div>
  );
}

function DeviceRow({ device, several }: { device: Device; several: boolean }) {
  const tvState = useStore(tv);
  const [confirming, setConfirming] = useState(false);
  const chosen = selectedDevice()?.id === device.id;
  const result = chosen ? tvState.result : undefined;
  const state = !chosen ? "" : !result ? "Checking…" : !result.online ? "Offline"
    : result.status?.phase === "playing" ? "Playing" : "Online";
  return (
    <li className={`device${chosen ? " chosen" : ""}`}>
      <span className="device-icon"><Icon name="tv" /></span>
      <div className="grow">
        <strong>{device.name}</strong>
      </div>
      {state && <span className="chip" data-state={state === "Offline" ? "error" : state === "Playing" ? "playing" : "ready"}>{state}</span>}
      {!chosen && several && (
        <button type="button" className="button secondary small" onClick={async () => {
          const result = await native.request<{ devices: Device[] }>({ action: "select", id: device.id }).catch(() => null);
          if (result) setDevices(result.devices);
        }}>Use</button>
      )}
      {confirming ? (
        <span className="confirm">
          <button type="button" className="link small danger" onClick={async () => {
            const result = await native.request<{ devices: Device[] }>({ action: "forget", id: device.id }).catch(() => null);
            if (result) setDevices(result.devices);
          }}>Remove</button>
          <button type="button" className="link small" onClick={() => setConfirming(false)}>Keep</button>
        </span>
      ) : <IconButton icon="trash" label={`Remove ${device.name}`} className="small" onClick={() => setConfirming(true)} />}
    </li>
  );
}

function FoundList({ tvs, onSearch, onPick, onProblem }: {
  tvs: FoundTv[]; onSearch: () => void; onPick: (tv: { address: string; port: number; name: string }) => void;
  onProblem: (problem: Problem) => void;
}) {
  const [asking, setAsking] = useState(false);

  /** Asks the TV to show a code, then moves on to typing it. */
  async function ask(target: { address: string; port: number; name?: string }) {
    setAsking(true);
    try {
      const result = await native.request<{ tv: { name: string } }>({ action: "pairRequest", address: target.address, port: target.port });
      onPick({ address: target.address, port: target.port, name: result.tv.name || target.name || "TV" });
    } catch (error) {
      onProblem(problemOf(error));
    } finally {
      setAsking(false);
    }
  }

  return (
    <div className="found">
      {tvs.length === 0 && <p className="muted">No TV answered. Check that Daniil Cast is open on the TV and that both are on the same Wi-Fi.</p>}
      <ul className="devices">
        {tvs.map(found => (
          <li key={found.id} className="device">
            <span className="device-icon"><Icon name="tv" /></span>
            <div className="grow">
              <strong>{found.name}</strong>
            </div>
            {found.paired ? <span className="chip" data-state="ready">Paired</span>
              : <button type="button" className="button secondary small" disabled={asking} onClick={() => void ask(found)}>Pair</button>}
          </li>
        ))}
      </ul>
      <button type="button" className="link small" onClick={onSearch}><Icon name="refresh" />Search again</button>
      <button type="button" className="link small" onClick={() => showTab("developer")}>Connect by address</button>
    </div>
  );
}

function CodeEntry({ target, onPaired, onProblem }: {
  target: { address: string; port: number; name: string }; onPaired: (devices: Device[]) => void; onProblem: (problem: Problem) => void;
}) {
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => input.current?.focus(), []);

  async function pair(value: string) {
    setBusy(true);
    try {
      await native.request({ action: "pair", address: target.address, port: target.port, code: value });
      await refreshHelper();
      onPaired(helper.get().devices);
    } catch (error) {
      onProblem(problemOf(error));
      setCode("");
      input.current?.focus();
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="code-entry" onSubmit={event => { event.preventDefault(); if (code.length === 6) void pair(code); }}>
      <p className="muted">Enter the six digits shown on the TV.</p>
      <input ref={input} className="code-input" inputMode="numeric" autoComplete="one-time-code" maxLength={7}
        placeholder="000 000" value={code.length > 3 ? `${code.slice(0, 3)} ${code.slice(3)}` : code} disabled={busy}
        aria-label="Pairing code" onChange={event => {
          const digits = event.target.value.replace(/\D/g, "").slice(0, 6);
          setCode(digits);
          if (digits.length === 6) void pair(digits);
        }} />
      <button type="button" className="link small" disabled={busy} onClick={async () => {
        await native.request({ action: "pairRequest", address: target.address, port: target.port }).catch(() => {});
      }}>Show the code on the TV again</button>
    </form>
  );
}

/** Manual network setup stays with the other advanced tools. */
export function ManualPairing() {
  const [address, setAddress] = useState("");
  const [target, setTarget] = useState<{ address: string; port: number; name: string }>();
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem>();

  async function connect() {
    setBusy(true);
    setProblem(undefined);
    try {
      const result = await native.request<{ tv: { name: string } }>({ action: "pairRequest", address: address.trim(), port: 8765 });
      setTarget({ address: address.trim(), port: 8765, name: result.tv.name || "TV" });
    } catch (error) { setProblem(problemOf(error)); }
    finally { setBusy(false); }
  }

  return (
    <section className="card">
      <div className="card-head"><h2>Connect by address</h2></div>
      <p className="muted">For the local emulator on this Mac, use <strong>127.0.0.1</strong>.</p>
      {target ? <CodeEntry target={target} onProblem={setProblem} onPaired={devices => {
        setDevices(devices); setTarget(undefined); showTab("tv");
      }} /> : <form className="manual" onSubmit={event => { event.preventDefault(); if (address.trim()) void connect(); }}>
        <input aria-label="TV address" placeholder="TV address, e.g. 192.168.1.20" value={address} inputMode="decimal"
          onChange={event => setAddress(event.target.value)} />
        <button type="submit" className="button secondary" disabled={!address.trim() || busy}>Pair</button>
      </form>}
      {target && <button type="button" className="link small" onClick={() => setTarget(undefined)}>Cancel</button>}
      {problem && <ProblemNote problem={problem} onDismiss={() => setProblem(undefined)} />}
    </section>
  );
}
