# AI Gateway

AI Gateway는 여러 LLM 연동 흐름을 하나의 OpenAI 호환 인터페이스로 묶어 테스트하는 local-first Spring Boot gateway 프로젝트입니다. 기본 실행은 fake provider, in-memory store, deterministic embedding으로 고정되어 외부 비용 없이 라우팅, 캐시, 폴백, 예산 제어를 재현합니다.

## 한눈에 보기

| 항목 | 내용 |
|---|---|
| 문제 | 애플리케이션마다 provider SDK, fallback, 비용/토큰 예산, cache, guardrail을 따로 구현하면 운영 기준이 흩어짐 |
| 핵심 역량 | Java 21, Spring Boot WebFlux, OpenAI-compatible API, routing/fallback, semantic cache, quota, static demo |
| 대표 증거 | offline backend regression, Redis/Testcontainers 선택 검증, static web demo verification |
| 실행 기준 | `cd web && npm run build && npm test`, backend는 Maven offline regression |
| 범위 경계 | 실제 provider 연동, 관리자 정책 API, AWS/실운영 연계, 실제 과금은 local completion 범위 밖 |

## 왜 만들었나

Enterprise Policy RAG가 RAG 제품 애플리케이션이라면, AI Gateway는 그런 애플리케이션들이 공통으로 의존하는 LLM 인프라 레이어입니다. 클라이언트는 OpenAI 호환 endpoint만 바라보고, gateway가 인증, 라우팅, 캐시, 폴백, 비용/토큰 예산, 가드레일, 요청 관측을 일관되게 처리합니다.

## 핵심 설계

```text
요청 수신 (/v1/chat/completions)
-> Auth (API key -> tenant)
-> Quota (rate limit + token/cost budget)
-> Guardrail(in)
-> Cache (exact -> semantic)
-> Router (model alias -> provider/model candidates)
-> Dispatch (fake provider 기본)
-> Fallback (retry budget + circuit breaker)
-> Guardrail(out) + usage aggregation
-> Record (token/latency/cost/cache/fallback)
```

기본 provider와 embedding은 deterministic fake 구현입니다. 정책 pipeline과 관측 경계를 비용 없이 검증하고, 실제 provider transport는 명시적으로 선택한 후속 범위로 둡니다.

## 구현 API

| Method | Path | 목적 |
|---|---|---|
| `POST` | `/v1/chat/completions` | OpenAI 호환 chat completion 요청 |
| `POST` | `/v1/batches/chat/completions` | local queued batch 요청 수락 |
| `POST` | `/v1/batches/{id}/process` | batch job 처리 트리거 |
| `GET` | `/v1/batches/{id}` | batch 상태와 결과 조회 |

## 구현 범위

| 영역 | 구현 내용 | 증거 |
|---|---|---|
| Pipeline | auth, quota, guardrail, cache, router, dispatch, fallback, record 단계 | backend offline tests |
| Routing | model alias, deterministic weighted A/B ordering, fallback candidates | Phase 2 local extension tests |
| Cache | exact/semantic cache, tenant/alias version invalidation | backend tests |
| Batch | queued local batch endpoint, process trigger, status lookup | endpoint tests, static demo |
| Tool calls | OpenAI-style `tools`/`tool_choice` passthrough | provider boundary tests |
| Web demo | Gateway Console, Usage & Cost, Request Trace | `web/scripts/verify-static-demo.mjs` |
| Infra draft | PostgreSQL+pgvector, Redis, Nginx compose draft | `docker compose -f infra/local/docker-compose.yml config` |

## 비교 모드

| 모드 | 설명 |
|---|---|
| `PASSTHROUGH` | 단일 provider 직통 |
| `CACHE_ONLY` | semantic cache만 적용 |
| `ROUTED` | 정책 라우팅 적용 |
| `ROUTED_RESILIENT` | 라우팅 + 폴백 + retry budget |
| `WEIGHTED_SPLIT` | deterministic prompt-bucketed A/B routing |

## 빠른 실행

Web demo:

```bash
cd web
npm run build
npm test
```

Backend offline regression:

```bash
cd backend
MAVEN_OPTS="-Xmx512m" \
JAVA_HOME=$(/usr/libexec/java_home -v 23) \
mvn -B -o test -DargLine="-Xmx384m" -Dtest='!Redis*'
```

Redis/Testcontainers 선택 검증:

```bash
cd backend
TESTCONTAINERS_DOCKERCONFIG_SOURCE=autoIgnoringUserProperties \
TESTCONTAINERS_RYUK_DISABLED=true \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
DOCKER_HOST=unix:///Users/chanyang.son/.colima/default/docker.sock \
env 'api.version=1.44' \
MAVEN_OPTS="-Xmx512m" \
JAVA_HOME=$(/usr/libexec/java_home -v 23) \
mvn -B -o test -DargLine="-Xmx384m" -Dtest='Redis*'
```

## 검증

| 구분 | 명령/증거 | 비고 |
|---|---|---|
| Backend 기본 | `mvn -B -o test -Dtest='!Redis*'` | Redis/Testcontainers 제외, offline regression |
| Redis/Testcontainers | `mvn -B -o test -Dtest='Redis*'` with Colima env | Docker daemon 필요 |
| Web demo | `cd web && npm run build && npm test` | static console 검증 |
| Local infra draft | `docker compose -f infra/local/docker-compose.yml config` | compose 문법 검증 |

## 프로젝트 구조

```text
backend/   Spring Boot WebFlux gateway
web/       dependency-free static demo console
infra/     local Docker Compose draft
docs/      design, plans, ADR, project tracking
```

## 문서 읽는 순서

| 순서 | 문서 | 목적 |
|---|---|---|
| 1 | [Portfolio One-Pager](docs/portfolio-one-pager.md) | 포트폴리오 요약과 경계 |

## 범위 밖

- 자체 모델 서빙·추론 엔진 운영
- OpenAI/Anthropic live transport 완료 주장
- 관리자 정책 CRUD API와 운영 대시보드 제품화
- 실제 과금·정산, 결제 flow
- 복잡한 MSA 분리, 멀티 리전·HA, 운영 Kubernetes
- 로컬 Docker 수치를 운영 규모 성능으로 주장하는 방식
