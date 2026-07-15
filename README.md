# AI Gateway

[![CI](https://github.com/cyson21/ai-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/cyson21/ai-gateway/actions/workflows/ci.yml)

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
- exact cache는 tenant와 정규화 prompt뿐 아니라 model alias, `max_tokens`, tools, `tool_choice`가 같은 요청만 재사용합니다.
- 유사도 cache는 결정론적 token vector로 prompt 유사도를 계산하되, tenant와 응답 계약은 별도 key로 엄격히 분리합니다.
- routing과 fallback을 분리해 모델 선택 정책과 장애 시 후보 전환을 각각 검증합니다.

## 실패 조건

| 조건 | 보호 규칙 |
|---|---|
| 누락·비활성 API key | tenant context 없이 pipeline에 진입하지 않아야 함 |
| 잘못된 role/content·token 범위·tool 조합 | 제공자 호출 전에 400으로 거절해야 함 |
| 요청·token·로컬 비용 예산 초과 | 허용량을 차감하거나 모델을 호출하지 않아야 함 |
| 1차 provider 실패 | circuit와 retry budget 안에서 다음 후보만 호출해야 함 |
| 같은 prompt의 가중 분배 | 동일한 hash bucket과 후보 순서를 재현해야 함 |
| cache 재사용 | tenant, model alias, token 한도, tool 계약이 다르면 결과를 공유하지 않아야 함 |
| cache 정책 변경 | 저장 당시 통과한 응답도 반환 직전에 output guardrail을 다시 통과해야 함 |

## 검증 결과

| 검증 | 확인 결과 |
|---|---|
| 기본 Maven 회귀 | pipeline 순서, request/auth 경계, cache hit, quota, guardrail, fallback, SSE 재조합과 tool 전달을 Docker 없이 확인 |
| Redis Testcontainers | rate limit·budget store contract와 동시 갱신을 실제 Redis에서 확인 |
| 인증 경계 | `ApiKeyAuthFilterTest`로 누락·오류·정상 API key의 요청 차단과 tenant context 전달을 확인 |
| cache 격리 | exact·semantic cache 모두 tenant와 응답 계약 기준 분리를 테스트로 확인 |
| fallback 제한 | `FallbackChainTest`로 재시도·후보 소진·null provider 응답 경계를 검증 |
| 공개 CI | PR과 `main` push에서 Java 21 전체 테스트, Redis Testcontainers, 정적 웹 검증을 실행 |
| 테스트 증거 | Surefire XML을 정렬·합산한 `ai-gateway-tests.json`을 CI artifact로 보존 |

## 대표 코드와 테스트

- 코드: [FallbackChain](backend/src/main/java/com/example/gateway/resilience/FallbackChain.java) - provider 실패를 분류하고 허용된 후보 안에서만 다음 호출을 선택합니다.
- 테스트: [FallbackChainTest](backend/src/test/java/com/example/gateway/resilience/FallbackChainTest.java) - 재시도·중단 조건과 후보 소진 경계를 검증합니다.

## 실행

기본 회귀는 Java 21과 Maven으로 실행하며 로컬 Docker 탐지와 분리합니다.

```bash
cd backend
mvn -B test -Dtest='!Redis*'
```

응답 가능한 Docker가 준비된 환경에서는 Redis 통합 테스트를 분리해 실행하고 `Skipped: 0`을 확인합니다.

```bash
cd backend
mvn -B test -Dtest='Redis*'
```

정적 웹 콘솔은 외부 패키지 없이 Node.js 내장 기능과 fixture만 사용합니다.

```bash
cd web
npm run build
npm test
```

기계 판독 가능한 테스트 요약은 고정된 commit과 시각을 입력해 생성합니다.

```bash
SOURCE_DATE_EPOCH="$(git show -s --format=%ct HEAD)" \
EVIDENCE_COMMIT="$(git rev-parse HEAD)" \
python3 tools/portfolio-evidence/generate_report.py \
  --reports backend/target/surefire-reports \
  --output evidence/ai-gateway-tests.json
```

## 제한 사항

- 기본 provider와 embedding은 결정론적 테스트 구현이며 실제 OpenAI·Anthropic 호출이나 모델 품질을 나타내지 않습니다.
- 지원 범위는 제한된 Chat Completions 필드와 메모리 batch API이며 구조화된 멀티턴 상태를 보존하지 않습니다.
- SSE는 완료 응답을 chunk로 투영한 결과로 실제 provider token streaming이 아닙니다.
- 기본 cache, quota, batch, request log는 메모리 구현이라 프로세스 재시작 시 유지되지 않습니다.
- PostgreSQL·Redis·Nginx Compose는 구성 검사 범위이며 운영 부하, 고가용성, 실제 과금 연동을 검증하지 않았습니다.
- CI의 JSON artifact는 테스트 pass/fail 합계와 suite 목록이며 처리량·지연시간 같은 운영 성능 증거가 아닙니다.
