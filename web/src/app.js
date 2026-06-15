import { fixtureResponse, metrics, requestTraceRows, usageSeries } from "./fixtures.js";

const state = {
  route: "console",
  mode: "ROUTED_RESILIENT",
  traceRows: [...requestTraceRows],
  settings: loadSettings(),
  requestSeq: 100
};

const routeMeta = {
  console: {
    title: "Gateway Console",
    subtitle: "Route, cache, stream, and inspect requests through the MVP pipeline."
  },
  usage: {
    title: "Usage & Cost",
    subtitle: "Compare token, cost, cache, latency, and fallback behavior by model."
  },
  trace: {
    title: "Request Trace",
    subtitle: "Inspect the per-request record emitted by the gateway pipeline."
  }
};

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));

function loadSettings() {
  try {
    return JSON.parse(localStorage.getItem("ai-gateway-demo-settings") || "{}");
  } catch {
    return {};
  }
}

function saveSettings() {
  const settings = {
    backendUrl: $("#backend-url").value.trim(),
    apiKey: $("#api-key").value.trim(),
    backendEnabled: $("#backend-toggle").checked
  };
  localStorage.setItem("ai-gateway-demo-settings", JSON.stringify(settings));
}

function renderMetrics() {
  $("#metric-strip").innerHTML = metrics
    .map(
      (metric) => `
        <article class="metric">
          <span>${metric.label}</span>
          <strong>${metric.value}</strong>
          <em class="tone-${metric.tone}">${metric.delta}</em>
        </article>`
    )
    .join("");
}

function renderUsage() {
  const maxRequests = Math.max(...usageSeries.map((row) => row.requests));
  $("#usage-bars").innerHTML = usageSeries
    .map((row) => {
      const width = Math.round((row.requests / maxRequests) * 100);
      return `
        <article class="usage-row">
          <div>
            <strong>${row.model}</strong>
            <span>${row.requests.toLocaleString()} requests · ${row.cache}% cache · ${row.latency} ms p95</span>
          </div>
          <div class="bar-track" aria-label="${row.model} request share">
            <span style="width:${width}%"></span>
          </div>
          <em>$${row.cost.toFixed(2)}</em>
        </article>`;
    })
    .join("");
}

function renderTrace() {
  $("#trace-body").innerHTML = state.traceRows
    .map(
      (row) => `
        <tr>
          <td><code>${row.id}</code></td>
          <td>${statusBadge(row.status)}</td>
          <td>${row.tenant}</td>
          <td>${row.alias}</td>
          <td>${row.provider}<br><small>${row.model}</small></td>
          <td>${cacheBadge(row.cache)}</td>
          <td>${guardrailBadge(row.guardrail)}</td>
          <td>${row.quota}</td>
          <td>${row.latency} ms</td>
        </tr>`
    )
    .join("");
}

function statusBadge(status) {
  const tone = status.includes("blocked") ? "bad" : status.includes("fallback") ? "warn" : "good";
  return `<span class="badge badge-${tone}">${status}</span>`;
}

function cacheBadge(cache) {
  const tone = cache === "NONE" ? "neutral" : "info";
  return `<span class="badge badge-${tone}">${cache}</span>`;
}

function guardrailBadge(value) {
  const tone = value === "PASS" ? "good" : "bad";
  return `<span class="badge badge-${tone}">${value}</span>`;
}

function setRoute(route) {
  state.route = route;
  $$(".nav-item").forEach((button) => button.classList.toggle("is-active", button.dataset.route === route));
  $$(".route-panel").forEach((panel) => panel.classList.remove("is-visible"));
  $(`#route-${route}`).classList.add("is-visible");
  $("#page-title").textContent = routeMeta[route].title;
  $("#page-subtitle").textContent = routeMeta[route].subtitle;
}

function setMode(mode) {
  state.mode = mode;
  $$(".mode-control button").forEach((button) => button.classList.toggle("is-active", button.dataset.mode === mode));
}

async function sendRequest() {
  saveSettings();
  const backendEnabled = $("#backend-toggle").checked;
  clearResponse(backendEnabled ? "backend" : "fixture");
  if (backendEnabled) {
    await sendBackendRequest();
    return;
  }
  await streamFixtureResponse();
}

function clearResponse(source) {
  $("#response-state").textContent = source;
  $("#response-state").className = `badge ${source === "backend" ? "badge-good" : "badge-info"}`;
  $("#response-summary").innerHTML = "";
  $("#stream-list").innerHTML = "";
}

async function streamFixtureResponse(options = {}) {
  const words = fixtureResponse.content.split(" ");
  for (const word of words) {
    appendChunk(word + " ");
    if (!options.instant) {
      await delay(18);
    }
  }
  appendSummary(fixtureResponse);
  appendDone();
  addTraceFromResponse(fixtureResponse, "ok");
}

async function sendBackendRequest() {
  const baseUrl = $("#backend-url").value.trim().replace(/\/$/, "");
  const apiKey = $("#api-key").value.trim();
  if (!baseUrl || !apiKey) {
    appendChunk("Backend URL and API key are required for live mode.");
    appendDone("blocked");
    return;
  }
  const body = {
    model: $("#alias-select").value,
    messages: [{ role: "user", content: $("#prompt-input").value }],
    max_tokens: 256,
    stream: $("#stream-toggle").checked
  };
  const response = await fetch(`${baseUrl}/v1/chat/completions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${apiKey}`,
      "X-Gateway-Mode": state.mode
    },
    body: JSON.stringify(body)
  });
  if (!response.ok) {
    appendChunk(`HTTP ${response.status}: ${await response.text()}`);
    appendDone("error");
    return;
  }
  if (body.stream && response.body) {
    await readEventStream(response.body);
    return;
  }
  const json = await response.json();
  appendChunk(json.choices?.[0]?.message?.content || json.status || "request complete");
  appendSummary(json);
  appendDone();
  addTraceFromResponse(json, json.status || "ok");
}

async function readEventStream(body) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (true) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    for (const event of buffer.split("\n\n")) {
      if (!event.trim()) {
        continue;
      }
      const payload = event.replace(/^data:\s?/gm, "").trim();
      if (payload === "[DONE]") {
        appendDone();
      } else {
        appendChunk(payload);
      }
    }
    buffer = "";
  }
}

function appendChunk(text) {
  const item = document.createElement("li");
  item.textContent = text;
  $("#stream-list").append(item);
}

function appendDone(tone = "done") {
  const item = document.createElement("li");
  item.className = "done-line";
  item.textContent = `[${tone.toUpperCase()}]`;
  $("#stream-list").append(item);
}

function appendSummary(response) {
  $("#response-summary").innerHTML = `
    <span><strong>${response.model || "model"}</strong> · ${response.mode || state.mode}</span>
    <span>${response.usage?.totalTokens ?? 0} tokens</span>
    <span>${response.latencyMs ?? 0} ms</span>
    <span>${response.cacheType || "NONE"} cache</span>`;
}

function addTraceFromResponse(response, status) {
  const id = `req_${state.requestSeq++}`;
  state.traceRows = [
    {
      id,
      tenant: $("#tenant-input").value,
      alias: $("#alias-select").value,
      mode: state.mode,
      provider: status === "ok" ? "fake-primary" : "-",
      model: response.model || $("#alias-select").value,
      cache: response.cacheType || "NONE",
      guardrail: "PASS",
      quota: "ALLOWED",
      latency: response.latencyMs || 0,
      status
    },
    ...state.traceRows
  ].slice(0, 10);
  renderTrace();
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function restoreSettings() {
  $("#backend-url").value = state.settings.backendUrl || "";
  $("#api-key").value = state.settings.apiKey || "";
  $("#backend-toggle").checked = Boolean(state.settings.backendEnabled);
}

function bindEvents() {
  $$(".nav-item").forEach((button) => button.addEventListener("click", () => setRoute(button.dataset.route)));
  $$(".mode-control button").forEach((button) => button.addEventListener("click", () => setMode(button.dataset.mode)));
  $("#send-request").addEventListener("click", () => sendRequest().catch((error) => {
    appendChunk(error.message);
    appendDone("error");
  }));
  ["backend-url", "api-key", "backend-toggle"].forEach((id) => {
    $(`#${id}`).addEventListener("change", saveSettings);
  });
}

renderMetrics();
renderUsage();
renderTrace();
restoreSettings();
bindEvents();
streamFixtureResponse({ instant: true });
