import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const dist = resolve(root, "dist");

const [html, css, app, fixtures] = await Promise.all([
  readFile(resolve(dist, "index.html"), "utf8"),
  readFile(resolve(dist, "styles.css"), "utf8"),
  readFile(resolve(dist, "app.js"), "utf8"),
  readFile(resolve(dist, "fixtures.js"), "utf8")
]);

for (const label of ["Gateway Console", "Usage & Cost", "Request Trace"]) {
  assert.match(html, new RegExp(label.replace("&", "&amp;|&")), `missing route label ${label}`);
}

for (const id of ["route-console", "route-usage", "route-trace", "prompt-input", "send-request"]) {
  assert.match(html, new RegExp(`id="${id}"`), `missing required element #${id}`);
}

assert.match(app, /fetch\(/, "backend connection path must use fetch");
assert.match(app, /localStorage/, "backend settings should persist locally");
assert.match(fixtures, /requestTraceRows/, "request trace fixture missing");
assert.match(css, /@media \(max-width: 760px\)/, "mobile breakpoint missing");

console.log("Static demo verification passed");
