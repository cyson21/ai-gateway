# AI Gateway Portfolio One-Pager

## What It Shows

AI Gateway is a multi-tenant LLM infrastructure backend with an OpenAI-compatible
local runtime focused on four implemented endpoints:

- `POST /v1/chat/completions`
- `POST /v1/batches/chat/completions`
- `POST /v1/batches/{id}/process`
- `GET  /v1/batches/{id}`

It demonstrates deterministic routing, bounded fallback, exact/semantic cache behavior,
tenant quota and budget control, rule-based guardrails, streaming response projection,
request observability, and deterministic evaluation under a fake provider runtime.

The project runs without paid provider calls by default. Fake providers and deterministic
embeddings keep core behaviors reproducible in local tests and in the static demo console.

## Evidence

- Backend runtime: Spring Boot WebFlux endpoint wired to real deterministic gateway components.
- Resilience: fallback chain, retry budget, and circuit breaker are covered by unit tests.
- Governance: API key filter, sliding-window rate limit, token/cost budget, and guardrails are covered.
- Cache: exact cache plus deterministic semantic cache are covered, including cache-hit provider bypass.
- Streaming: OpenAI-compatible SSE chunk projection with final usage reconciliation is covered.
- Observability: request log rows and usage rollups are covered by deterministic aggregation tests.
- Eval: golden request set and mode-comparison report generation are covered.
- Phase 2 local extensions: weighted A/B routing, cache invalidation, tool-call passthrough, and async
  batch processing are covered.
- Demo: dependency-free web console with Gateway Console, Usage & Cost, and Request Trace views.
- Implementation boundary: only the above four API paths are in scope. Models-list, admin 영역, and paid-provider workflows are deferred.

## How To Demo Locally

Backend unit and context verification:

```bash
cd backend
MAVEN_OPTS="-Xmx512m" JAVA_HOME=$(/usr/libexec/java_home -v 23) \
  mvn -B -o test -DargLine="-Xmx384m" -Dtest='!Redis*'
```

Static console verification:

```bash
cd web
npm run build
npm test
```

Open `web/dist/index.html` through a local static server. The console can run from fixtures,
or send requests to a local backend by enabling the backend toggle and setting the base URL.

## Current Boundaries

- Default runtime uses fake providers, in-memory stores, and deterministic embeddings.
- Redis-backed store tests pass with local Colima/Testcontainers when Docker is running.
- 외부 provider 연동, 관리자 기능 API, AWS 연계, and payment flow are out-of-scope for local completion.
