# AI Gateway

AI Gateway는 여러 LLM 연동 흐름을 하나의 OpenAI 호환 인터페이스로 묶어 테스트하기 위한 로컬 우선 게이트웨이 프로젝트입니다. 기본 실행은 `fake provider` + `in-memory/local-first deterministic runtime`으로 동작하며, 비용 없는 재현형 데모를 우선합니다.

프로젝트는 구현 범위를 좁혀 문서화하고 있어, 실제 동작 API는 아래 4개입니다.

- `POST /v1/chat/completions`
- `POST /v1/batches/chat/completions`
- `POST /v1/batches/{id}/process`
- `GET /v1/batches/{id}`

## 상태

MVP는 M6 Demo 완성 기준으로 정리되어 있고, 2026-06-17에 local extension 4개(Model A/B routing, cache invalidation policy, tool-call passthrough, async batch endpoint)도 확인했습니다.
기본 런타임은 `fake provider`, `in-memory store`, deterministic embedding으로 고정되어 있어 외부 의존 없이 재현 가능합니다.

검증 기준:

- Backend: `cd backend && MAVEN_OPTS="-Xmx512m" JAVA_HOME=$(/usr/libexec/java_home -v 23) mvn -B -o test -DargLine="-Xmx384m" -Dtest='!Redis*'`
- Redis/Testcontainers: `cd backend && TESTCONTAINERS_DOCKERCONFIG_SOURCE=autoIgnoringUserProperties TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock DOCKER_HOST=unix:///Users/chanyang.son/.colima/default/docker.sock env 'api.version=1.44' MAVEN_OPTS="-Xmx512m" JAVA_HOME=$(/usr/libexec/java_home -v 23) mvn -B -o test -DargLine="-Xmx384m" -Dtest='Redis*'`
- Web demo: `cd web && npm run build && npm test`
- Local infra draft: `docker compose -f infra/local/docker-compose.yml config`

## 파이프라인

```text
요청 수신 (/v1/chat/completions)
→ Auth (API key → tenant)
→ Quota (rate limit + 토큰/비용 예산)
→ Guardrail(in)
→ Cache (exact → semantic)
→ Router (모델 별칭 → provider/모델)
→ Dispatch (provider 호출 + streaming)
→ Fallback (retry budget + circuit breaker)
→ Guardrail(out) + usage 집계
→ Record (token/latency/cost/cache/fallback)
```

## 비교 모드

```text
PASSTHROUGH        단일 provider 직통
CACHE_ONLY         semantic cache만 적용
ROUTED             정책 라우팅 적용
ROUTED_RESILIENT   라우팅 + 폴백 + retry budget
```

## Phase 2 Local Extensions

- `WEIGHTED_SPLIT` routing: `ab-test` alias uses deterministic prompt-bucketed A/B ordering while keeping fallback candidates.
- Cache invalidation: tenant/alias versioning makes old exact/semantic entries unreachable after invalidation.
- Tool-call passthrough: OpenAI-style `tools`/`tool_choice` requests reach the provider and return `tool_calls`.
- Async batch endpoint: `/v1/batches/chat/completions`, `/v1/batches/{id}/process`, and `/v1/batches/{id}` provide a local queued batch path.

## 기술 스택

- Java 21, Maven, Spring Boot 3 (WebFlux)
- PostgreSQL + pgvector, Flyway
- Dependency-free static web console (Gateway Console / Usage & Cost / Request Trace)
- Docker Compose
- 기본 runtime은 fake provider와 deterministic embedding + in-memory cache/지표 store
- Redis/Testcontainers는 기본 runtime 밖의 선택형 검증 경로
- 외부 provider 연동, 외부 비용 정산 연동은 범위 밖

## 레이아웃

```text
backend/   Spring Boot WebFlux gateway
web/       static demo console (Gateway Console / Usage & Cost / Request Trace)
infra/     local Docker Compose (PostgreSQL+pgvector, Redis, Nginx)
docs/      design, plans, ADR, project tracking
```

## 문서

- 포트폴리오 요약: `docs/portfolio-one-pager.md`

## 범위 제외

- 자체 모델 서빙·추론 엔진 운영, 실제 과금·정산
- 복잡한 MSA 분리, 멀티 리전·HA, 처음부터 Kubernetes
- 로컬 Docker 수치로 운영 규모 성능을 주장하는 방식
- 01/02/03 레포 수정
