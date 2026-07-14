# AI Gateway

AI Gateway는 LLM 요청 앞단의 인증, 사용량 제한, 캐시, 라우팅, 폴백, 가드레일, 요청 기록을 하나의 파이프라인으로 구성한 Java 21·Spring Boot WebFlux 프로젝트입니다. HTTP 계층은 OpenAI Chat Completions의 일부 요청·응답 형식과 로컬 배치 API를 구현합니다. 기본 런타임은 테스트용 제공자와 메모리 저장소를 사용하며 실제 OpenAI·Anthropic 호출이나 실제 과금 연동은 포함하지 않습니다.

## 한눈에 보기

| 항목 | 내용 |
|---|---|
| 문제 | 애플리케이션마다 흩어지는 인증, 사용량 제한, 캐시, 라우팅, 장애 복구 기준을 공통 처리 단계에 모음 |
| 지원 요청 필드 | `model`, `messages[].role/content`, `max_tokens`, `stream`, `tools`, `tool_choice` |
| 구현 API | Chat Completions 1개, 로컬 배치 접수·실행·조회 3개 |
| 기본 실행 | Java 21, 테스트용 LLM 제공자, 결정론적 임베딩, 메모리 사용량 제한·캐시·요청 기록 |
| 선택 검증 | Redis Testcontainers, PostgreSQL·Redis·Nginx Compose 설정 검사 |
| 검증 방식 | Maven 단위·슬라이스 테스트, Redis 통합 테스트, 정적 웹 콘솔 빌드·검사 |
| 주요 경계 | 실제 LLM, 실제 모델 임베딩, 통화 단위 비용, 운영 배포와 성능 수치는 구현하지 않음 |

## 아키텍처

```text
HTTP 요청
  -> Bearer API key 해시 조회 -> tenant 식별
  -> Chat Completions 일부 형식 -> 내부 CompletionRequest
  -> 사용량 제한: 요청 수 + 토큰/로컬 비용 단위 예산
  -> 입력 가드레일
  -> 2단계 캐시
     - exact: tenant + model alias + 정규화 프롬프트의 SHA-256
     - similarity: 결정론적 영문·숫자 토큰 벡터 + cosine threshold
  -> model alias 해석
  -> fixed / least-cost / least-latency / 프롬프트 해시 기반 가중 분배
  -> 테스트용 제공자 호출
  -> circuit breaker + retry budget + 후보 폴백
  -> 출력 가드레일
  -> 사용량·지연·캐시·폴백 결과 기록
  -> JSON 응답 또는 완료 응답을 SSE 청크로 투영
```

[기본 파이프라인 구성](backend/src/main/java/com/example/gateway/config/GatewayPipelineConfig.java)은 네트워크 없이 같은 처리 순서를 반복 검증하도록 메모리 구현과 테스트용 제공자를 연결합니다. PostgreSQL·Redis·Nginx 구성은 별도 Compose 파일에 있으나, 현재 README에서 보장하는 범위는 설정 구문 검사까지입니다.

## 구현 API 범위

| Method | Path | 범위 |
|---|---|---|
| `POST` | `/v1/chat/completions` | 제한된 Chat Completions 요청, JSON 응답 또는 `stream=true` SSE 응답 |
| `POST` | `/v1/batches/chat/completions` | 메모리 배치 작업 접수 |
| `POST` | `/v1/batches/{id}/process` | 접수된 메모리 배치 실행 |
| `GET` | `/v1/batches/{id}` | tenant 범위 안에서 배치 상태·결과 조회 |

`messages`는 구조화된 대화 상태로 유지되지 않고 `role: content` 줄 단위의 단일 prompt로 평탄화됩니다. 따라서 전체 OpenAI API 호환이나 모든 Chat Completions 옵션 지원을 의미하지 않습니다.

- 요청 변환: [ChatCompletionRequest](backend/src/main/java/com/example/gateway/web/ChatCompletionRequest.java)
- 단건 API: [ChatCompletionController](backend/src/main/java/com/example/gateway/web/ChatCompletionController.java)
- 배치 API: [BatchController](backend/src/main/java/com/example/gateway/web/BatchController.java)
- 검증: [ChatCompletionControllerTest](backend/src/test/java/com/example/gateway/web/ChatCompletionControllerTest.java), [BatchControllerTest](backend/src/test/java/com/example/gateway/web/BatchControllerTest.java)

## 핵심 설계 판단

### 1. 인증에서 tenant를 확정한 뒤 파이프라인에 전달

원문 API key는 저장하지 않고 SHA-256 해시로 조회합니다. 활성 key와 tenant가 확인된 경우에만 tenant ID를 요청 속성과 Reactor context에 넣습니다. 컨트롤러 슬라이스 테스트는 데이터베이스 대신 이 경계를 대체하는 테스트 필터를 사용합니다.

- 구현: [API key 필터](backend/src/main/java/com/example/gateway/auth/ApiKeyAuthFilter.java), [R2DBC 저장소](backend/src/main/java/com/example/gateway/auth/R2dbcApiKeyRepository.java)
- 검증: [ApiKeyAuthFilterTest](backend/src/test/java/com/example/gateway/auth/ApiKeyAuthFilterTest.java), [ChatCompletionControllerTest](backend/src/test/java/com/example/gateway/web/ChatCompletionControllerTest.java)

### 2. 정확 일치 캐시와 유사도 캐시를 분리

1단계 정확 일치 캐시는 tenant·model alias·정규화된 프롬프트의 SHA-256으로 동일 요청을 식별합니다. 2단계 유사도 캐시의 기본 임베딩은 외부 모델이 아니라 **영문 소문자와 숫자 토큰을 고정 차원에 해시하는 결정론적 테스트 구현**입니다. 한국어 의미 검색이나 실제 임베딩 품질을 나타내지 않습니다.

- 구현: [ExactCache](backend/src/main/java/com/example/gateway/cache/ExactCache.java), [TwoStageCache](backend/src/main/java/com/example/gateway/cache/TwoStageCache.java), [DeterministicEmbeddingModel](backend/src/main/java/com/example/gateway/cache/DeterministicEmbeddingModel.java)
- 검증: [ExactCacheTest](backend/src/test/java/com/example/gateway/cache/ExactCacheTest.java), [TwoStageCacheTest](backend/src/test/java/com/example/gateway/cache/TwoStageCacheTest.java), [PgVectorSemanticCacheTest](backend/src/test/java/com/example/gateway/cache/PgVectorSemanticCacheTest.java)

### 3. 사용량 제한 비용은 통화가 아닌 로컬 정수 단위

예산 선검사에서는 예상 토큰 수에 `microsPerToken` 정수를 곱한 값을 사용합니다. 요청 기록의 비용도 후보 모델에 지정된 `costPerKTokens`와 응답 토큰 수로 계산합니다. 두 값은 로컬 정책 비교를 위한 단위이며 실제 provider 가격, 청구액, 절감액이 아닙니다.

- 구현: [FlatRateCostEstimator](backend/src/main/java/com/example/gateway/quota/FlatRateCostEstimator.java), [SlidingWindowQuotaGuard](backend/src/main/java/com/example/gateway/quota/SlidingWindowQuotaGuard.java), [GatewayPipeline](backend/src/main/java/com/example/gateway/api/GatewayPipeline.java)
- 검증: [SlidingWindowQuotaGuardBudgetTest](backend/src/test/java/com/example/gateway/quota/SlidingWindowQuotaGuardBudgetTest.java), [InMemoryBudgetStoreTest](backend/src/test/java/com/example/gateway/quota/InMemoryBudgetStoreTest.java)

### 4. 가중 분배는 프롬프트 문자열의 고정 버킷

`WEIGHTED_SPLIT`은 프롬프트 문자열의 Java hash를 후보 가중치 합계로 나눈 버킷으로 1차 후보를 선택합니다. 같은 프롬프트는 같은 순서를 얻는 결정론적 분배이며, 사용자 단위 실험 배정이나 통계적 A/B 분석 기능은 아닙니다.

- 구현: [PolicyRouter](backend/src/main/java/com/example/gateway/router/PolicyRouter.java), [RoutingStrategy](backend/src/main/java/com/example/gateway/router/RoutingStrategy.java)
- 검증: [WeightedRoutingTest](backend/src/test/java/com/example/gateway/router/WeightedRoutingTest.java)

### 5. 폴백과 재시도 예산을 요청 처리 단계에 포함

`ROUTED_RESILIENT` 모드만 후보 수와 최대 시도 횟수 안에서 다음 제공자를 호출합니다. circuit breaker가 열린 후보는 제외하고, retry budget이 소진되면 추가 시도를 중단합니다.

- 구현: [FallbackChain](backend/src/main/java/com/example/gateway/resilience/FallbackChain.java), [CircuitBreaker](backend/src/main/java/com/example/gateway/resilience/CircuitBreaker.java), [RetryBudget](backend/src/main/java/com/example/gateway/resilience/RetryBudget.java)
- 검증: [FallbackChainTest](backend/src/test/java/com/example/gateway/resilience/FallbackChainTest.java), [CircuitBreakerTest](backend/src/test/java/com/example/gateway/resilience/CircuitBreakerTest.java), [RetryBudgetTest](backend/src/test/java/com/example/gateway/resilience/RetryBudgetTest.java)

### 6. SSE는 완료 응답을 청크로 변환

`stream=true`이면 제공자가 토큰을 생성하는 즉시 중계하는 것이 아니라, 이미 완료된 `CompletionResponse`를 role·content·finish·`[DONE]` 이벤트로 나눕니다. 스트리밍 응답 형식과 재조합 규칙을 검증하기 위한 구현입니다.

- 구현: [StreamChunkProjector](backend/src/main/java/com/example/gateway/streaming/StreamChunkProjector.java), [ChatCompletionController](backend/src/main/java/com/example/gateway/web/ChatCompletionController.java)
- 검증: [StreamChunkProjectorTest](backend/src/test/java/com/example/gateway/streaming/StreamChunkProjectorTest.java), [StreamingUsageAccumulatorTest](backend/src/test/java/com/example/gateway/streaming/StreamingUsageAccumulatorTest.java)

## 코드와 테스트 근거

| 기능 | 운영 코드 | 검증 코드 |
|---|---|---|
| 파이프라인 순서와 기본 구성 | [GatewayPipeline](backend/src/main/java/com/example/gateway/api/GatewayPipeline.java), [GatewayPipelineConfig](backend/src/main/java/com/example/gateway/config/GatewayPipelineConfig.java) | [GatewayPipelineConfigTest](backend/src/test/java/com/example/gateway/config/GatewayPipelineConfigTest.java) |
| 요청·응답 일부 형식 | [ChatCompletionRequest](backend/src/main/java/com/example/gateway/web/ChatCompletionRequest.java), [ChatCompletionResponse](backend/src/main/java/com/example/gateway/web/ChatCompletionResponse.java) | [ChatCompletionControllerTest](backend/src/test/java/com/example/gateway/web/ChatCompletionControllerTest.java) |
| tool 전달 | [CompletionRequest](backend/src/main/java/com/example/gateway/provider/CompletionRequest.java), [FakeLlmProvider](backend/src/main/java/com/example/gateway/provider/FakeLlmProvider.java) | [ToolCallPassthroughTest](backend/src/test/java/com/example/gateway/web/ToolCallPassthroughTest.java) |
| 입력·출력 가드레일 | [RuleBasedGuardrail](backend/src/main/java/com/example/gateway/guardrail/RuleBasedGuardrail.java) | [RuleBasedGuardrailTest](backend/src/test/java/com/example/gateway/guardrail/RuleBasedGuardrailTest.java) |
| 사용량 제한과 예산 | [SlidingWindowQuotaGuard](backend/src/main/java/com/example/gateway/quota/SlidingWindowQuotaGuard.java) | [SlidingWindowQuotaGuardTest](backend/src/test/java/com/example/gateway/quota/SlidingWindowQuotaGuardTest.java), [SlidingWindowQuotaGuardBudgetTest](backend/src/test/java/com/example/gateway/quota/SlidingWindowQuotaGuardBudgetTest.java) |
| Redis 사용량 제한 저장소 | [RedisRateLimitStore](backend/src/main/java/com/example/gateway/quota/RedisRateLimitStore.java), [RedisBudgetStore](backend/src/main/java/com/example/gateway/quota/RedisBudgetStore.java) | [RedisQuotaStoreContractTest](backend/src/test/java/com/example/gateway/quota/RedisQuotaStoreContractTest.java), [RedisQuotaStoreConcurrencyTest](backend/src/test/java/com/example/gateway/quota/RedisQuotaStoreConcurrencyTest.java) |
| 캐시 | [TwoStageCache](backend/src/main/java/com/example/gateway/cache/TwoStageCache.java) | [TwoStageCacheTest](backend/src/test/java/com/example/gateway/cache/TwoStageCacheTest.java), [VersionedSemanticCacheTest](backend/src/test/java/com/example/gateway/cache/VersionedSemanticCacheTest.java) |
| 라우팅 | [PolicyRouter](backend/src/main/java/com/example/gateway/router/PolicyRouter.java) | [WeightedRoutingTest](backend/src/test/java/com/example/gateway/router/WeightedRoutingTest.java) |
| 장애 복구 | [FallbackChain](backend/src/main/java/com/example/gateway/resilience/FallbackChain.java) | [FallbackChainTest](backend/src/test/java/com/example/gateway/resilience/FallbackChainTest.java) |
| 요청 기록·집계 | [InMemoryRequestLogStore](backend/src/main/java/com/example/gateway/observability/InMemoryRequestLogStore.java), [UsageRollupAggregator](backend/src/main/java/com/example/gateway/observability/UsageRollupAggregator.java) | [RequestLogStoreTest](backend/src/test/java/com/example/gateway/observability/RequestLogStoreTest.java), [UsageRollupAggregatorTest](backend/src/test/java/com/example/gateway/observability/UsageRollupAggregatorTest.java) |
| 고정 평가 실행 | [EvalRunner](backend/src/main/java/com/example/gateway/eval/EvalRunner.java), [GoldenRequests](backend/src/main/java/com/example/gateway/eval/GoldenRequests.java) | [EvalRunnerTest](backend/src/test/java/com/example/gateway/eval/EvalRunnerTest.java), [EvalReportTest](backend/src/test/java/com/example/gateway/eval/EvalReportTest.java) |

## 검증 시나리오

| 시나리오 | 관찰 기준 | 근거 |
|---|---|---|
| 인증 누락 | tenant가 없으면 Chat Completions 요청을 `401`로 거절 | [ChatCompletionControllerTest](backend/src/test/java/com/example/gateway/web/ChatCompletionControllerTest.java) |
| 정확 일치 캐시 | 동일 요청의 두 번째 실행에서 `EXACT` 캐시와 비용 `0` 기록 | [GatewayPipelineConfigTest](backend/src/test/java/com/example/gateway/config/GatewayPipelineConfigTest.java) |
| 입력 차단 | 금지어가 포함된 요청은 제공자를 선택하지 않고 차단 | [RuleBasedGuardrailTest](backend/src/test/java/com/example/gateway/guardrail/RuleBasedGuardrailTest.java) |
| 예산 초과 | 토큰·로컬 비용 단위 한도를 넘으면 파이프라인 진입 전 거절 | [SlidingWindowQuotaGuardBudgetTest](backend/src/test/java/com/example/gateway/quota/SlidingWindowQuotaGuardBudgetTest.java) |
| 프롬프트 고정 분배 | 같은 프롬프트가 같은 가중 버킷 순서를 선택 | [WeightedRoutingTest](backend/src/test/java/com/example/gateway/router/WeightedRoutingTest.java) |
| 후보 폴백 | 1차 제공자 실패 후 허용된 시도 안에서 다음 후보 호출 | [FallbackChainTest](backend/src/test/java/com/example/gateway/resilience/FallbackChainTest.java) |
| SSE 재조합 | 분할된 content 청크를 합치면 완료 응답 원문과 일치 | [StreamChunkProjectorTest](backend/src/test/java/com/example/gateway/streaming/StreamChunkProjectorTest.java) |
| tool call 반환 | `tools`와 `tool_choice`가 내부 요청을 거쳐 응답의 `tool_calls`로 반환 | [ToolCallPassthroughTest](backend/src/test/java/com/example/gateway/web/ToolCallPassthroughTest.java) |
| 고정 평가 | 정상·cache hit·가드레일·실패 경로를 고정 입력으로 반복 실행 | [EvalRunnerTest](backend/src/test/java/com/example/gateway/eval/EvalRunnerTest.java) |

## 재현 방법

### 기본 백엔드: Java 21

최초 실행은 Maven이 의존성을 내려받을 수 있는 네트워크가 필요합니다. Redis/Testcontainers 테스트를 제외한 기본 회귀 검사는 Docker 없이 실행합니다.

```bash
cd backend
java -version  # 21 확인
mvn -B test -Dtest='!Redis*'
```

### Redis/Testcontainers: Docker

Docker daemon이 접근 가능한 환경에서 실행합니다. 최초 실행은 `redis:7-alpine` 이미지를 받을 네트워크 또는 미리 준비된 이미지가 필요합니다. 테스트가 조용히 건너뛰지 않았는지 Maven 요약에서 `RedisQuotaStoreContractTest`와 `RedisQuotaStoreConcurrencyTest`가 모두 실행되고 `Skipped: 0`인지 확인해야 합니다.

```bash
cd backend
mvn -B test -Dtest='Redis*'
```

Colima를 사용하는 macOS에서 Docker socket 자동 탐지가 실패할 때만 다음 보정을 추가합니다.

```bash
cd backend
TESTCONTAINERS_DOCKERCONFIG_SOURCE=autoIgnoringUserProperties \
TESTCONTAINERS_RYUK_DISABLED=true \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock" \
env 'api.version=1.44' mvn -B test -Dtest='Redis*'
```

### 정적 웹 콘솔: Node.js 20.11 이상

외부 제공자나 backend 없이 fixture 화면을 빌드하고 필수 UI 요소를 검사합니다.

```bash
cd web
npm ci
npm run build
npm test
```

### 로컬 Compose 설정

아래 명령은 PostgreSQL, Redis, gateway, Nginx 정의의 **구문과 병합 결과만** 검사합니다. 서비스 정상 기동, API key seed, API 성공, 성능을 검증하지 않습니다.

```bash
docker compose -f infra/local/docker-compose.yml config -q
```

## 담당 범위

개인 프로젝트로 다음 영역을 직접 설계·구현·테스트했습니다.

- Java 21·Spring Boot WebFlux 기반 요청 처리 파이프라인
- API key 해시 인증과 tenant 범위 전달
- 제한된 Chat Completions 형식과 메모리 배치 API
- 정확 일치·유사도 캐시, 사용량 제한·예산, 라우팅, 폴백, 가드레일
- JSON 응답과 완료 응답 기반 SSE 청크 투영
- 요청 기록, 사용량 집계, 고정 평가 실행기
- Redis Testcontainers 검사와 정적 웹 콘솔

## 제한 사항

- 구현 범위는 명시된 Chat Completions 필드와 네 개 엔드포인트까지이며 구조화된 멀티턴 상태나 실제 토큰 스트리밍은 포함하지 않습니다.
- 기본 제공자와 임베딩은 결정론적 테스트 구현입니다. 실제 LLM 품질, 의미 검색 품질, 통화 단위 비용, 실험 분석을 나타내지 않습니다.
- 배치 작업, 기본 캐시, 사용량 제한, 요청 기록은 메모리 구현이며 프로세스 재시작 시 유지되지 않습니다.
- Redis는 선택형 통합 테스트로 확인하며 PostgreSQL·Redis·Nginx Compose는 설정 구문만 검사했습니다.
- 멀티 리전, 고가용성, 실제 운영 부하와 성능 수치는 구현·검증 범위에 포함하지 않습니다.

## 관련 문서

| 자료 | 내용 |
|---|---|
| [기술 개요](docs/portfolio-one-pager.md) | 구현 단계와 현재 범위 요약 |
| [초기 데이터 스키마](backend/src/main/resources/db/migration/V1__init.sql) | tenant, API key, quota, cache, 요청 기록 테이블 |
| [로컬 인프라 구성](infra/local/docker-compose.yml) | PostgreSQL, Redis, gateway, Nginx 설정 |
| [정적 데모 검사](web/scripts/verify-static-demo.mjs) | fixture 기반 웹 콘솔 검증 기준 |
