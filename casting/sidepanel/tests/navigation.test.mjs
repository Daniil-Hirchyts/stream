import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { test } from "node:test";
import vm from "node:vm";
import { build } from "esbuild";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

const bundle = await build({ stdin: { contents: 'export { App } from "./src/App"; export * from "./src/state";',
  resolveDir: process.cwd(), loader: "tsx" }, bundle: true, write: false,
  platform: "node", format: "cjs", packages: "external", jsx: "automatic" });

function app(savedTab = null) {
  const context = { module: { exports: {} }, require: createRequire(import.meta.url),
    setTimeout, clearTimeout, console,
    localStorage: { getItem() { return savedTab; }, setItem() {} },
    chrome: { runtime: { getManifest() { return { version: "0.3.0" }; } } } };
  vm.runInNewContext(bundle.outputFiles[0].text, context);
  const state = context.module.exports;
  const device = { id: "simulator", name: "Local simulator", selected: true, address: "127.0.0.1", port: 8765 };
  state.helper.set({ state: "ready", devices: [device], version: "0.4.0", conversion: true });
  state.tv.set({ at: Date.now(), result: { online: true, device, decoders: { video: ["H.264"], audio: ["AAC"] },
    conversion: { active: true, sourceCodec: "AV1" }, status: { phase: "playing", version: "0.3.1",
      title: "A local video", source: { convertedFrom: "AV1" }, videoFormat: { width: 640, height: 360 },
      audioTracks: [], textTracks: [], qualities: [], quality: "auto", position: 85000, duration: 150000,
      playWhenReady: true, speed: 1, episode: null, catalog: null } } });
  return { ...state, render(tab) { if (tab) state.showTab(tab); return renderToStaticMarkup(createElement(state.App)); } };
}

test("TV and Remote contain no header badge, versions or developer diagnostics", () => {
  const state = app();
  for (const tab of ["tv", "remote"]) {
    const html = state.render(tab);
    assert.doesNotMatch(html, /<header|class="pill"|127\.0\.0\.1|0\.4\.0|0\.3\.1|FFmpeg|AV1|Copy diagnostics|Reload extension/);
  }
});

test("Developer collects versions, connection details and diagnostics", () => {
  const html = app().render("developer");
  for (const detail of ["0.4.0", "0.3.1", "0.3.0", "127.0.0.1:8765", "FFmpeg", "AV1", "Copy diagnostics", "Activity"]) {
    assert.ok(html.includes(detail), detail);
  }
});

test("the saved Activity tab migrates to Developer", () => {
  assert.equal(app("activity").ui.get().tab, "developer");
});
