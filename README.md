# AI Gateway

여러 LLM provider 앞단에 서서 인증, 라우팅, 폴백, semantic cache, 테넌트별 토큰 예산, rate limit, 가드레일, 비용·지연 관측을 책임지는 LLM 오케스트레이션 인프라 백엔드입니다.

애플리케이션은 provider SDK 대신 gateway의 OpenAI 호환 인터페이스 하나만 바라보고, provider 선택·폴백·캐시·예산·관측은 gateway가 책임집니다.

## 상태

MVP 범위는 M6 Demo까지 완료했고, 2026-06-17에 비-AWS Phase 2 local extension 4개(Model A/B routing, cache invalidation policy, tool-call passthrough, async batch endpoint)도 완료했습니다. 기본 실행은 fake provider, in-memory store, deterministic embedding을 사용해 비용 없이 재현됩니다.

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
- Redis (rate limit window, exact cache, 예산 원자 차감)
- Dependency-free static web console (Gateway Console / Usage & Cost / Request Trace)
- Docker Compose, Testcontainers
- 기본 provider/embedding은 fake, 실 provider는 opt-in

## 레이아웃

```text
backend/   Spring Boot WebFlux gateway
web/       static demo console (Gateway Console / Usage & Cost / Request Trace)
infra/     local Docker Compose (PostgreSQL+pgvector, Redis, Nginx)
docs/      public portfolio docs and runbooks
```

## 문서

- 포트폴리오 요약: `docs/portfolio-one-pager.md`

## 범위 제외

- 자체 모델 서빙·추론 엔진 운영, 실제 과금·정산
- 복잡한 MSA 분리, 멀티 리전·HA, 처음부터 Kubernetes
- 로컬 Docker 수치로 운영 규모 성능을 주장하는 방식
- 01/02/03 레포 수정
