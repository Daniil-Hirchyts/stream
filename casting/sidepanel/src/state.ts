import { native, problemOf } from "./native";
import { DEFAULT_DECODERS } from "./page";
import { createStore } from "./store";
import type { Device, Hello, LoadResult, LogEntry, Problem, Scan, StatusResult } from "./types";

export type TabName = "cast" | "remote" | "tv" | "developer";

function remembered(): TabName {
  try {
    const value = localStorage.getItem("tab");
    if (value === "activity") return "developer";
    if (value === "cast" || value === "remote" || value === "tv" || value === "developer") return value;
  } catch { /* Private storage may be unavailable; start on Cast. */ }
  return "cast";
}

export const ui = createStore<{ tab: TabName }>({ tab: remembered() });

export function showTab(tab: TabName) {
  ui.set({ tab });
  if (tab === "developer") activity.set(state => ({ ...state, unseen: false }));
  try {
    localStorage.setItem("tab", tab);
  } catch { /* Not remembered then. */ }
}

// The Mac helper and paired TVs

interface HelperState {
  state: "connecting" | "ready" | "missing" | "error";
  version?: string;
  conversion?: boolean;
  devices: Device[];
  problem?: Problem;
}

export const helper = createStore<HelperState>({ state: "connecting", devices: [] });

export const selectedDevice = () => helper.get().devices.find(device => device.selected) || helper.get().devices[0];

export async function refreshHelper() {
  try {
    const hello = await native.request<Hello>({ action: "hello" }, 8000);
    helper.set({ state: "ready", version: hello.version, conversion: hello.conversion, devices: hello.devices });
  } catch (error) {
    const problem = problemOf(error);
    helper.set(state => ({ ...state, state: problem.code === "helper-missing" ? "missing" : "error", problem }));
  }
}

export function setDevices(devices: Device[]) {
  helper.set(state => ({ ...state, devices }));
  tv.set({ at: 0 });
  void pollTv();
}

// What the TV is doing

interface TvState {
  result?: StatusResult;
  problem?: Problem;
  at: number;
}

export const tv = createStore<TvState>({ at: 0 });
let polling = false;

export async function pollTv() {
  if (polling || helper.get().state !== "ready" || !selectedDevice()) return;
  polling = true;
  try {
    const result = await native.request<StatusResult>({ action: "status" }, 8000);
    tv.set({ result, at: Date.now() });
  } catch (error) {
    tv.set(state => ({ ...state, problem: problemOf(error), at: Date.now() }));
  } finally {
    polling = false;
  }
}

export const decoders = () => tv.get().result?.decoders?.video || DEFAULT_DECODERS;

/** Sends a player command; failures go to the activity log and the given setter. */
export async function command(message: Record<string, unknown>, onProblem?: (problem: Problem) => void): Promise<boolean> {
  try {
    await native.request(message, 10000);
    void pollTv();
    return true;
  } catch (error) {
    const problem = problemOf(error);
    onProblem?.(problem);
    noteProblem(problem);
    return false;
  }
}

// The page in the active tab

interface PageState {
  tabId?: number;
  url?: string;
  title?: string;
  scanning: boolean;
  scan?: Scan;
  problem?: Problem;
}

export const page = createStore<PageState>({ scanning: false });
let scanTimer: ReturnType<typeof setTimeout> | undefined;

export async function scanActiveTab() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.id || !/^https?:/.test(tab.url || "")) {
    page.set({ tabId: tab?.id, url: tab?.url, title: tab?.title, scanning: false });
    return;
  }
  const same = page.get().tabId === tab.id && page.get().url === tab.url;
  page.set(state => ({ tabId: tab.id, url: tab.url, title: tab.title, scanning: true, scan: same ? state.scan : undefined }));
  try {
    const result = await chrome.runtime.sendMessage({ type: "scan", tabId: tab.id });
    if (!result?.ok) throw new Error(result?.error || "The page couldn't be read");
    if (page.get().tabId === tab.id) page.set(state => ({ ...state, scanning: false, scan: result as Scan }));
  } catch (error) {
    // Chrome's own pages and the Web Store can't be scripted; nothing to cast there.
    page.set(state => ({ ...state, scanning: false, problem: { code: "scan", title: "Couldn’t look at this page",
      message: "Reload the page, then look again.", detail: error instanceof Error ? error.message : String(error) } }));
  }
}

/** Looks again a moment after the page changes, once its player has settled. */
export function scanSoon(delay = 700) {
  clearTimeout(scanTimer);
  scanTimer = setTimeout(() => void scanActiveTab(), delay);
}

// Casting

export const casting = createStore<{ busy: boolean; problem?: Problem }>({ busy: false });

export async function cast(message: Record<string, unknown>, fromPage: boolean) {
  // Conversion can finish after the viewer has switched to another tab.
  const tabId = fromPage ? page.get().tabId : undefined;
  casting.set({ busy: true });
  // A conversion takes a while; the Remote tab explains what the TV is waiting for.
  const reveal = setTimeout(() => { if (casting.get().busy) showTab("remote"); }, 2000);
  try {
    const result = await native.request<LoadResult>(message, 150000);
    clearTimeout(reveal);
    casting.set({ busy: false });
    // Pause the page only once the TV shows a picture: sound alone isn't success.
    if (result.state === "playing" && fromPage && tabId !== undefined) {
      chrome.runtime.sendMessage({ type: "pauseBrowser", tabId }).catch(() => {});
    }
    showTab("remote");
    void pollTv();
  } catch (error) {
    clearTimeout(reveal);
    const problem = problemOf(error);
    casting.set({ busy: false, problem });
    noteProblem(problem);
  }
}

export function dismissCastProblem() {
  casting.set(state => ({ ...state, problem: undefined }));
}

// Activity

interface ActivityState {
  entries: LogEntry[];
  /** Entries the helper couldn't store, such as "the helper isn't installed". */
  local: LogEntry[];
  unseen: boolean;
}

export const activity = createStore<ActivityState>({ entries: [], local: [], unseen: false });

export async function refreshLog() {
  try {
    const result = await native.request<{ entries: LogEntry[] }>({ action: "log", limit: 150 }, 8000);
    activity.set(state => ({ ...state, entries: result.entries }));
  } catch { /* The local entries still show. */ }
}

export async function clearLog() {
  activity.set(state => ({ ...state, local: [], entries: [] }));
  await native.request({ action: "clearLog" }).catch(() => {});
}

/** A failure the viewer saw; its details wait in the Activity tab. */
function noteProblem(problem: Problem) {
  if (ui.get().tab !== "developer") activity.set(state => ({ ...state, unseen: true }));
  if (helper.get().state !== "ready") {
    const entry: LogEntry = { at: Date.now(), level: "error", message: problem.title,
      detail: problem.detail ? { detail: problem.detail } : undefined };
    activity.set(state => ({ ...state, local: [entry, ...state.local].slice(0, 50) }));
  }
}
