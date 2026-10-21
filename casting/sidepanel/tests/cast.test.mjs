import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { test } from "node:test";
import vm from "node:vm";
import { build } from "esbuild";

const bundle = await build({ entryPoints: ["src/state.ts"], bundle: true, write: false,
  platform: "node", format: "cjs", packages: "external" });

function controller() {
  let receive;
  const requests = [], browserMessages = [];
  const port = {
    onMessage: { addListener(listener) { receive = listener; } },
    onDisconnect: { addListener() {} },
    postMessage(message) { requests.push(message); }
  };
  const context = { module: { exports: {} }, require: createRequire(import.meta.url),
    setTimeout, clearTimeout, console,
    localStorage: { getItem() { return null; }, setItem() {} },
    chrome: { runtime: {
      connectNative() { return port; },
      sendMessage(message) { browserMessages.push(message); return Promise.resolve({ ok: true }); }
    } } };
  vm.runInNewContext(bundle.outputFiles[0].text, context);
  return { ...context.module.exports, browserMessages,
    finish(state) { receive({ request: requests.at(-1).request, ok: true, state }); } };
}

test("a slow cast pauses its source tab after the viewer switches tabs", async () => {
  const app = controller();
  app.page.set({ tabId: 10, scanning: false });
  const casting = app.cast({ action: "load" }, true);
  app.page.set({ tabId: 20, scanning: false });
  app.finish("playing");
  await casting;
  assert.equal(app.browserMessages.length, 1);
  assert.equal(app.browserMessages[0].type, "pauseBrowser");
  assert.equal(app.browserMessages[0].tabId, 10);
});

test("pending playback and manual links leave browser playback alone", async () => {
  const app = controller();
  app.page.set({ tabId: 10, scanning: false });
  const pending = app.cast({ action: "load" }, true);
  app.finish("pending");
  await pending;
  const manual = app.cast({ action: "load" }, false);
  app.finish("playing");
  await manual;
  assert.equal(app.browserMessages.length, 0);
});
