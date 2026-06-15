export const metrics = [
  { label: "Requests", value: "12,842", delta: "+18.4%", tone: "info" },
  { label: "Cache hit rate", value: "42.8%", delta: "+6.1%", tone: "good" },
  { label: "Cost", value: "$184.22", delta: "-9.7%", tone: "good" },
  { label: "p95 latency", value: "812 ms", delta: "-104 ms", tone: "info" },
  { label: "Fallback rate", value: "3.6%", delta: "+0.8%", tone: "warn" }
];

export const usageSeries = [
  { model: "gpt-4o", requests: 4280, cost: 82.14, cache: 37, latency: 780 },
  { model: "cheap", requests: 3124, cost: 24.88, cache: 56, latency: 940 },
  { model: "fast", requests: 2860, cost: 61.77, cache: 28, latency: 430 },
  { model: "resilient", requests: 1472, cost: 15.43, cache: 44, latency: 690 }
];

export const requestTraceRows = [
  {
    id: "req_9a12",
    tenant: "tenant-1",
    alias: "gpt-4o",
    mode: "ROUTED_RESILIENT",
    provider: "fake-primary",
    model: "gpt-4o",
    cache: "NONE",
    guardrail: "PASS",
    quota: "ALLOWED",
    latency: 612,
    status: "ok"
  },
  {
    id: "req_9a13",
    tenant: "tenant-1",
    alias: "gpt-4o",
    mode: "ROUTED_RESILIENT",
    provider: "cache",
    model: "gpt-4o",
    cache: "EXACT",
    guardrail: "PASS",
    quota: "ALLOWED",
    latency: 12,
    status: "cache hit"
  },
  {
    id: "req_9a14",
    tenant: "tenant-2",
    alias: "fast",
    mode: "ROUTED",
    provider: "fake-primary",
    model: "fast-model",
    cache: "NONE",
    guardrail: "PASS",
    quota: "ALLOWED",
    latency: 438,
    status: "ok"
  },
  {
    id: "req_9a15",
    tenant: "tenant-1",
    alias: "resilient",
    mode: "ROUTED_RESILIENT",
    provider: "fake-fallback",
    model: "resilient-fallback",
    cache: "NONE",
    guardrail: "PASS",
    quota: "ALLOWED",
    latency: 1180,
    status: "fallback"
  },
  {
    id: "req_9a16",
    tenant: "tenant-3",
    alias: "gpt-4o",
    mode: "ROUTED_RESILIENT",
    provider: "-",
    model: "-",
    cache: "NONE",
    guardrail: "BLOCKED_INPUT",
    quota: "ALLOWED",
    latency: 8,
    status: "blocked"
  }
];

export const fixtureResponse = {
  id: "chatcmpl-demo",
  object: "chat.completion",
  model: "gpt-4o",
  mode: "ROUTED_RESILIENT",
  status: "ok",
  content:
    "The gateway accepted the request, resolved the tenant, checked quota, skipped unsafe input, missed cache, routed to fake-primary/gpt-4o, and recorded token, latency, cost, cache, fallback, and guardrail metadata.",
  usage: { promptTokens: 18, completionTokens: 42, totalTokens: 60 },
  latencyMs: 612,
  cost: 30,
  cacheType: "NONE",
  fallbackCount: 0
};
