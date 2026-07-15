# AI Gateway

LLM 요청 앞단의 tenant 인증, quota, cache, routing, fallback, guardrail을 하나의 Java 21·Spring WebFlux 파이프라인으로 구성한 프로젝트입니다. [웹 사례](https://cyson21.github.io/projects/ai-gateway/)

## 포트폴리오 링크

- [웹 사례](https://cyson21.github.io/projects/ai-gateway/) · [전체 포트폴리오 PDF](https://github.com/cyson21/portfolio-hub/releases/download/latest/portfolio-complete.pdf) · [최신 이력서](https://github.com/cyson21/portfolio-hub/releases/download/latest/resume.pdf)

## 문제

애플리케이션마다 인증과 모델 선택, 사용량 제한, 장애 복구를 따로 구현하면 tenant 격리와 비용 정책이 달라집니다. 요청이 모델 제공자에 도달하기 전에 공통 정책을 적용하고, 실패 시 허용된 후보와 재시도 예산 안에서만 복구해야 합니다.

## 설계

```text
Bearer API key -> SHA-256 lookup -> tenant context
Chat request   -> request/token budget -> input guardrail
               -> exact cache -> deterministic similarity cache
               -> fixed/least-cost/least-latency/weighted routing
               -> fake provider -> circuit breaker/retry/fallback
               -> output guardrail -> usage log -> JSON or SSE projection
```

- 원문 API key를 저장하지 않고 hash 조회 결과로 tenant를 확정합니다.
- exact cache key에 tenant, model alias, 정규화 prompt를 포함하고 유사도 cache는 결정론적 token vector를 사용합니다.
- routing과 fallback을 분리해 모델 선택 정책과 장애 시 후보 전환을 각각 검증합니다.

## 실패 조건

| 조건 | 보호 규칙 |
|---|---|
| 누락·비활성 API key | tenant context 없이 pipeline에 진입하지 않아야 함 |
| 잘못된 message·빈 prompt | 제공자 호출 전에 요청을 거절해야 함 |
| 요청·token·로컬 비용 예산 초과 | 허용량을 차감하거나 모델을 호출하지 않아야 함 |
| 1차 provider 실패 | circuit와 retry budget 안에서 다음 후보만 호출해야 함 |
| 같은 prompt의 가중 분배 | 동일한 hash bucket과 후보 순서를 재현해야 함 |
| cache 재사용 | tenant나 model alias가 다른 요청과 결과를 공유하지 않아야 함 |

## 검증 결과

| 검증 | 확인 결과 |
|---|---|
| 기본 Maven 회귀 | pipeline 순서, cache hit, quota, guardrail, fallback, SSE 재조합과 tool 전달을 Docker 없이 확인 |
| Redis Testcontainers | rate limit·budget store contract와 동시 갱신을 실제 Redis에서 확인 |
| 인증 경계 | `ApiKeyAuthFilterTest`로 누락·오류·정상 API key의 요청 차단과 tenant context 전달을 확인 |
| cache 격리 | `TwoStageCacheTest`로 exact·semantic cache 순서와 tenant·model 기준 분리를 확인 |
| fallback 제한 | `FallbackChainTest`로 재시도 가능한 실패와 즉시 중단해야 할 실패를 구분 |

## 대표 코드와 테스트

- 코드: [FallbackChain](backend/src/main/java/com/example/gateway/resilience/FallbackChain.java) - provider 실패를 분류하고 허용된 후보 안에서만 다음 호출을 선택합니다.
- 테스트: [FallbackChainTest](backend/src/test/java/com/example/gateway/resilience/FallbackChainTest.java) - 재시도·중단 조건과 후보 소진 경계를 검증합니다.

## 실행

기본 회귀는 Java 21과 Maven으로 실행합니다.

```bash
cd backend
mvn -B test -Dtest='!Redis*'
```

Docker가 준비된 환경에서는 Redis 통합 테스트를 분리해 실행하고 `Skipped: 0`을 확인합니다.

```bash
cd backend
mvn -B test -Dtest='Redis*'
```

정적 웹 콘솔은 fixture만 사용합니다.

```bash
cd web
npm ci
npm run build
npm test
```

## 제한 사항

- 기본 provider와 embedding은 결정론적 테스트 구현이며 실제 OpenAI·Anthropic 호출이나 모델 품질을 나타내지 않습니다.
- 지원 범위는 제한된 Chat Completions 필드와 메모리 batch API이며 구조화된 멀티턴 상태를 보존하지 않습니다.
- SSE는 완료 응답을 chunk로 투영한 결과로 실제 provider token streaming이 아닙니다.
- 기본 cache, quota, batch, request log는 메모리 구현이라 프로세스 재시작 시 유지되지 않습니다.
- PostgreSQL·Redis·Nginx Compose는 구성 검사 범위이며 운영 부하, 고가용성, 실제 과금 연동을 검증하지 않았습니다.
