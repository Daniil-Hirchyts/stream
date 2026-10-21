import type { Problem } from "./types";

const HOST = "tv.daniil.cast";

export const HELPER_MISSING: Problem = {
  code: "helper-missing",
  title: "The Mac helper isn’t installed",
  message: "Run the install command once, then reopen this panel."
};

export class HelperError extends Error {
  constructor(readonly problem: Problem) {
    super(problem.title);
  }
}

/** The problem behind any failure, ready to show. */
export function problemOf(error: unknown): Problem {
  if (error instanceof HelperError) return error.problem;
  return { code: "panel", title: "Something went wrong", detail: error instanceof Error ? error.message : String(error) };
}

interface Pending {
  resolve(value: unknown): void;
  reject(error: unknown): void;
  timer: ReturnType<typeof setTimeout>;
}

/**
 * One long-lived connection to the Mac helper. Requests carry a number and may be answered in any order, so a cast
 * that waits for the TV never holds up the status reads that drive the Remote tab.
 */
class NativeConnection {
  private port: chrome.runtime.Port | null = null;
  private pending = new Map<number, Pending>();
  private next = 1;
  missing = false;

  private connect(): chrome.runtime.Port {
    const port = chrome.runtime.connectNative(HOST);
    port.onMessage.addListener((message: { request?: number; ok?: boolean; error?: Problem }) => {
      const waiting = message.request === undefined ? undefined : this.pending.get(message.request);
      if (!waiting || message.request === undefined) return;
      this.pending.delete(message.request);
      clearTimeout(waiting.timer);
      if (message.ok === false) waiting.reject(new HelperError(message.error || { code: "helper", title: "The Mac helper failed" }));
      else waiting.resolve(message);
    });
    port.onDisconnect.addListener(() => {
      const reason = chrome.runtime.lastError?.message || "";
      this.port = null;
      this.missing = /not found|forbidden|not allowed/i.test(reason);
      const problem = this.missing ? HELPER_MISSING : { code: "helper", title: "The Mac helper stopped", detail: reason };
      for (const waiting of this.pending.values()) {
        clearTimeout(waiting.timer);
        waiting.reject(new HelperError(problem));
      }
      this.pending.clear();
    });
    this.port = port;
    return port;
  }

  request<T = Record<string, unknown>>(message: Record<string, unknown>, timeout = 20000): Promise<T> {
    const port = this.port || this.connect();
    const id = this.next++;
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new HelperError({ code: "timeout", title: "The Mac helper didn’t answer in time" }));
      }, timeout);
      this.pending.set(id, { resolve: resolve as (value: unknown) => void, reject, timer });
      try {
        port.postMessage({ ...message, request: id });
      } catch (error) {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(new HelperError({ code: "helper", title: "The Mac helper stopped", detail: String(error) }));
      }
    });
  }
}

export const native = new NativeConnection();
