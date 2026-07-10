# AI Gateway

AI Gateway는 여러 LLM 연동 흐름을 하나의 OpenAI 호환 API로 제공하는 Spring Boot gateway 프로젝트입니다. 기본 실행은 테스트용 provider, 메모리 저장소, 고정된 embedding 결과를 사용해 외부 비용 없이 라우팅, 캐시, 폴백, 예산 제어를 재현합니다.

## 프로젝트 요약

| 항목 | 내용 |
|---|---|
| 해결하려는 문제 | 애플리케이션마다 provider SDK, 폴백, 비용·token 예산, 캐시, 가드레일을 따로 구현하면 운영 기준이 흩어짐 |
| 주요 기술 | Java 21, Spring Boot WebFlux, OpenAI 호환 API, 라우팅·폴백, semantic cache, quota, 정적 데모 |
| 주요 기능 | OpenAI 호환 API, 요청 처리 단계, semantic cache, quota·폴백, 요청 기록 |
| 확인 방법 | 백엔드 로컬 회귀 테스트, Redis/Testcontainers 선택 테스트, 정적 웹 데모 테스트 |
| 빠른 실행 | web은 `cd web && npm run build && npm test`, backend는 Maven 로컬 테스트 실행 |
| 제한 사항 | 실제 LLM provider, 관리자 정책 API, AWS 운영 환경, 실제 과금 기능은 포함하지 않음 |

## 주요 내용

| 주제 | 관련 문서와 코드 | 설명 |
|---|---|---|
| OpenAI 호환 API | [구현 API](#구현-api), [ChatCompletionControllerTest](backend/src/test/java/com/example/gateway/web/ChatCompletionControllerTest.java) | `/v1/chat/completions`와 batch API의 요청 형식 |
| 캐시·예산·폴백 | [비교 모드](#비교-모드), 관련 테스트 | 요청 처리 단계에서 캐시, 예산, 폴백이 각각 어떻게 작동하는지 설명 |
| 정적 데모 | [정적 데모 검사 도구](web/scripts/verify-static-demo.mjs) | 외부 provider 없이 콘솔 화면과 요청 기록 예제가 빌드되는지 확인 |

## 주요 코드와 테스트

| 구분 | 코드 또는 기능 | 테스트 또는 실행 방법 |
|---|---|---|
| OpenAI 호환 API와 batch | `/v1/chat/completions`, `/v1/batches/*` | [ChatCompletionControllerTest](backend/src/test/java/com/example/gateway/web/ChatCompletionControllerTest.java) |
| 두 단계 캐시와 tenant 예산 | exact·semantic cache, quota guard | [TwoStageCacheTest](backend/src/test/java/com/example/gateway/cache/TwoStageCacheTest.java), [SlidingWindowQuotaGuardBudgetTest](backend/src/test/java/com/example/gateway/quota/SlidingWindowQuotaGuardBudgetTest.java) |
| 실패 복구 | retry budget, circuit breaker, 후보 provider 폴백 | [FallbackChainTest](backend/src/test/java/com/example/gateway/resilience/FallbackChainTest.java), `ROUTED_RESILIENT` 모드 |

## 실행 환경

| 구분 | 준비 사항 | 확인할 내용 |
|---|---|---|
| 기본 백엔드 | JDK와 Maven cache | 테스트용 provider 기반 로컬 회귀 테스트 |
| 기본 web | Node와 npm | 정적 콘솔 빌드와 테스트 |
| Redis 연동 | Docker 또는 Colima, Redis Testcontainers | Redis 캐시 통합 테스트이며 실제 provider 과금·운영 연동은 아님 |

## 구현 결과

| 구현 내용 | 결과 | 확인 방법 |
|---|---|---|
| OpenAI 호환 API | `/v1/chat/completions`와 batch API 3종 구현 | 백엔드 로컬 테스트 |
| 요청 처리 단계 | auth, quota, guardrail, cache, router, dispatch, fallback, record 순서 구성 | 처리 단계 테스트 |
| 캐시와 라우팅 비교 | exact·semantic cache와 고정된 A/B·폴백 모드 제공 | Phase 2 로컬 확장 테스트 |
| 비용 없는 데모 | 테스트용 provider와 정적 웹 콘솔로 라우팅과 요청 기록 재현 | `web/scripts/verify-static-demo.mjs` |

## 프로젝트 배경

Enterprise Policy RAG가 하나의 RAG 애플리케이션이라면, AI Gateway는 여러 AI 애플리케이션이 공통으로 사용하는 LLM 연결 계층입니다. 클라이언트는 OpenAI 호환 API만 호출하고, gateway가 인증, 라우팅, 캐시, 폴백, 비용·token 예산, 가드레일, 요청 기록을 처리합니다.

## 주요 설계

| 설계 | 선택 이유 | 구현과 테스트 |
|---|---|---|
| OpenAI 호환 API | 클라이언트 변경 없이 gateway의 공통 정책을 적용하기 위함 | `/v1/chat/completions` API 테스트 |
| 테스트용 provider 기본값 | 실제 LLM 비용 없이 요청 처리 흐름을 반복 테스트하기 위함 | 백엔드 로컬 회귀 테스트 |
| semantic cache | 유사 요청의 비용과 지연을 줄이기 위함 | 캐시 테스트, 정적 데모 |
| 폴백과 retry budget | provider 실패를 애플리케이션마다 따로 처리하지 않기 위함 | `ROUTED_RESILIENT` 모드 테스트 |

## 아키텍처

```text
요청 수신 (/v1/chat/completions)
-> Auth (API key -> tenant)
-> Quota (rate limit + token/cost budget)
-> Guardrail(in)
-> Cache (exact -> semantic)
-> Router (model alias -> provider/model candidates)
-> Dispatch (테스트용 provider 기본)
-> Fallback (retry budget + circuit breaker)
-> Guardrail(out) + usage aggregation
-> Record (token/latency/cost/cache/fallback)
```

기본 provider와 embedding은 항상 같은 결과를 반환하는 테스트용 구현입니다. 요청 처리 단계와 기록 기능을 비용 없이 테스트하며, 실제 provider 호출은 별도 연동 작업으로 남겨 둡니다.

## 구현 API

| Method | Path | 목적 |
|---|---|---|
| `POST` | `/v1/chat/completions` | OpenAI 호환 chat completion 요청 |
| `POST` | `/v1/batches/chat/completions` | 로컬 batch 요청 접수 |
| `POST` | `/v1/batches/{id}/process` | batch 작업 실행 |
| `GET` | `/v1/batches/{id}` | batch 상태와 결과 조회 |

## 구현 범위

| 영역 | 구현 내용 | 확인 방법 |
|---|---|---|
| 요청 처리 | auth, quota, guardrail, cache, router, dispatch, fallback, record 단계 | 백엔드 로컬 테스트 |
| Routing | model alias, 고정된 weighted A/B 순서, 폴백 후보 | Phase 2 로컬 확장 테스트 |
| Cache | exact·semantic cache, tenant·alias version 무효화 | 백엔드 테스트 |
| Batch | 로컬 batch 접수, 실행, 상태 조회 | API 테스트, 정적 데모 |
| Tool calls | OpenAI 방식의 `tools`·`tool_choice` 전달 | provider 분리 테스트 |
| Web demo | Gateway Console, Usage & Cost, Request Trace | `web/scripts/verify-static-demo.mjs` |
| 로컬 인프라 초안 | PostgreSQL+pgvector, Redis, Nginx Compose | `docker compose -f infra/local/docker-compose.yml config` |

## 비교 모드

| 모드 | 설명 |
|---|---|
| `PASSTHROUGH` | 단일 provider 직통 |
| `CACHE_ONLY` | semantic cache만 적용 |
| `ROUTED` | 정책 라우팅 적용 |
| `ROUTED_RESILIENT` | 라우팅 + 폴백 + retry budget |
| `WEIGHTED_SPLIT` | prompt 기준을 고정한 A/B 라우팅 |

## 기술 선택과 문제 해결

| 주제 | 고민한 점 | 적용 내용 | 확인 방법과 남은 과제 |
|---|---|---|---|
| 실제 provider 연동 보류 | 실제 provider를 먼저 붙이면 테스트가 비용·시크릿·네트워크에 의존함 | 테스트용 provider와 고정된 embedding 결과를 기본값으로 사용 | 로컬 회귀 테스트, 정적 데모 |
| WebFlux gateway | 외부 API 호환성과 비동기 IO 처리가 중요함 | Spring Boot WebFlux | 백엔드 테스트 |
| Redis 캐시 선택 테스트 | semantic cache의 Redis 통합과 기본 테스트를 분리해야 함 | Redis 테스트를 별도 실행 | Colima 환경의 `Redis*` 테스트 |

## 빠른 실행

Web 데모:

```bash
cd web
npm run build
npm test
```

백엔드 로컬 회귀 테스트:

```bash
cd backend
MAVEN_OPTS="-Xmx512m" \
JAVA_HOME=$(/usr/libexec/java_home -v 23) \
mvn -B -o test -DargLine="-Xmx384m" -Dtest='!Redis*'
```

Redis/Testcontainers 선택 테스트:

```bash
cd backend
TESTCONTAINERS_DOCKERCONFIG_SOURCE=autoIgnoringUserProperties \
TESTCONTAINERS_RYUK_DISABLED=true \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock" \
env 'api.version=1.44' \
MAVEN_OPTS="-Xmx512m" \
JAVA_HOME=$(/usr/libexec/java_home -v 23) \
mvn -B -o test -DargLine="-Xmx384m" -Dtest='Redis*'
```

## 테스트

| 구분 | 명령 또는 결과 | 설명 |
|---|---|---|
| 기본 백엔드 | `mvn -B -o test -Dtest='!Redis*'` | Redis/Testcontainers를 제외한 로컬 회귀 테스트 |
| Redis/Testcontainers | `mvn -B -o test -Dtest='Redis*'`와 Colima 환경 변수 | Docker 필요 |
| Web 데모 | `cd web && npm run build && npm test` | 정적 콘솔 빌드와 테스트 |
| 로컬 인프라 초안 | `docker compose -f infra/local/docker-compose.yml config` | Compose 설정 확인 |
| 문서 | `docs/portfolio-one-pager.md`, `README.md` | 테스트용 provider, Redis 연동, 실제 provider 연동 범위 구분 |

## 운영/배포

| 항목 | 내용 | 확인 방법 |
|---|---|---|
| 기본 로컬 | 테스트용 provider, 메모리 저장소, 고정된 결과 | 백엔드 로컬 회귀 테스트 |
| Redis 연동 | semantic cache Redis 테스트 | `Redis*` Testcontainers 테스트 |
| 정적 데모 | 외부 서비스가 필요 없는 web 콘솔 | `npm run build`, `npm test` |
| 로컬 인프라 초안 | PostgreSQL+pgvector, Redis, Nginx Compose | `docker compose ... config` |

## 담당 범위

개인 프로젝트이며, 직접 구현하고 테스트한 범위는 다음과 같습니다.

| 분야 | 구현 내용 | 확인 방법 |
|---|---|---|
| Gateway API | OpenAI 호환 API와 batch 흐름 | 구현 API 4개와 controller 테스트 |
| 요청 처리 | auth, quota, cache, router, fallback, trace 구성 | 백엔드 회귀 테스트 |
| 로컬 테스트 | 비용 없는 테스트용 provider와 정적 데모 | web 테스트, 백엔드 로컬 테스트 |

## 프로젝트 구조

```text
backend/   Spring Boot WebFlux gateway
web/       외부 서비스가 필요 없는 정적 데모 콘솔
infra/     로컬 Docker Compose 초안
docs/      공개 문서와 실행 안내
```

## 참고 문서

| 순서 | 문서 | 내용 |
|---|---|---|
| 1 | [Portfolio One-Pager](docs/portfolio-one-pager.md) | 프로젝트 요약과 제한 사항 |
| 2 | [README](README.md) | 실행 방법, 테스트, 구현 API |

## 제한 사항

- 자체 모델 서빙·추론 엔진 운영
- OpenAI와 Anthropic 실제 호출 연동
- 관리자 정책 CRUD API와 운영 대시보드 제품화
- 실제 과금·정산·결제 흐름
- 복잡한 MSA 분리, 멀티 리전·HA, 운영 Kubernetes
- 로컬 Docker 결과를 운영 규모 성능으로 설명하지 않음
