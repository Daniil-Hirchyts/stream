// Bundles the side panel into the extension folder, which Chrome loads unpacked.
import { build, context } from "esbuild";

const options = {
  entryPoints: ["src/main.tsx"],
  bundle: true,
  outfile: "../extension/sidepanel/app.js",
  format: "esm",
  target: "chrome116",
  jsx: "automatic",
  minify: true,
  sourcemap: false,
  legalComments: "none",
  define: { "process.env.NODE_ENV": '"production"' },
  logLevel: "info"
};

if (process.argv.includes("--watch")) {
  await (await context({ ...options, minify: false })).watch();
} else {
  await build(options);
}
