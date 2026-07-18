# AI Gateway

[![CI](https://github.com/cyson21/ai-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/cyson21/ai-gateway/actions/workflows/ci.yml)

여러 애플리케이션의 LLM 요청에 조직 인증, 사용량 제한, 캐시, 모델 선택, 장애 복구와 입력·출력 검사를 공통으로 적용하는 Java 21·Spring WebFlux 프로젝트입니다.

개인 프로젝트로 요청 파이프라인, 인증·할당량·캐시·모델 선택·실패 복구 정책과 정적 운영 콘솔을 직접 설계·구현했습니다.

[웹 사례](https://cyson21.github.io/projects/ai-gateway/) · [전체 포트폴리오 PDF](https://github.com/cyson21/portfolio-hub/releases/download/latest/portfolio-complete.pdf) · [최신 이력서](https://github.com/cyson21/portfolio-hub/releases/download/latest/resume.pdf)

## 문제

애플리케이션마다 인증과 모델 선택, 사용량 제한, 장애 복구를 따로 구현하면 조직별 격리와 비용 정책이 달라집니다. 요청이 모델 제공자에 도달하기 전에 공통 정책을 적용하고, 실패 시 허용된 후보와 재시도 횟수 안에서만 복구해야 합니다.

## 설계

```text
Bearer API 키 -> SHA-256 조회 -> 조직 식별
채팅 요청     -> 요청·토큰 예산 -> 입력 검사
              -> 정확 일치 캐시 -> 유사도 캐시
              -> 고정·비용·지연·가중치 기반 모델 선택
              -> 테스트 모델 -> 회로 차단·재시도·후보 전환
              -> 출력 검사 -> 사용량 기록 -> JSON 또는 SSE 응답
```

- 원문 API 키를 저장하지 않고 해시 조회 결과로 조직을 식별합니다.
- 정확 일치 캐시는 조직, 정규화한 입력문, 모델 별칭, `max_tokens`, 도구 설정이 모두 같은 요청만 재사용합니다.
- 유사도 캐시는 입력문 유사도를 계산하되 조직과 응답 조건이 다른 결과는 공유하지 않습니다.
- 모델 선택과 장애 복구를 분리해 평상시 선택 기준과 실패 시 후보 전환을 각각 검증합니다.

## 실패 조건

| 조건 | 보호 규칙 |
|---|---|
| 누락·비활성 API 키 | 조직 정보 없이 요청 처리 단계에 진입하지 않아야 함 |
| 잘못된 역할·본문·토큰 범위·도구 조합 | 모델 호출 전에 400으로 거절해야 함 |
| 요청·토큰·비용 한도 초과 | 허용량을 차감하거나 모델을 호출하지 않아야 함 |
| 1차 모델 호출 실패 | 회로 차단기와 재시도 횟수 안에서 다음 후보만 호출해야 함 |
| 같은 입력의 가중 분배 | 동일한 해시 구간과 후보 순서를 유지해야 함 |
| 캐시 재사용 | 조직, 모델 별칭, 토큰 한도, 도구 설정이 다르면 결과를 공유하지 않아야 함 |
| 캐시 정책 변경 | 저장 당시 통과한 응답도 반환 직전에 출력 검사를 다시 통과해야 함 |

## 검증 결과

| 검증 | 확인 결과 |
|---|---|
| 기본 회귀 테스트 | 정책 적용 순서, 요청·인증 경계, 캐시 적중, 사용량 제한, 입출력 검사, 장애 복구와 SSE 응답 조립을 Docker 없이 확인 |
| Redis 통합 테스트 | 사용량 제한과 예산 저장 규칙, 동시 갱신을 실제 Redis에서 확인 |
| 인증 경계 | `ApiKeyAuthFilterTest`로 누락·오류·정상 API 키의 요청 차단과 조직 정보 전달을 확인 |
| 캐시 격리 | 정확 일치·유사도 캐시 모두 조직과 응답 조건에 따라 분리되는지 확인 |
| 복구 범위 | `FallbackChainTest`로 재시도 중단, 후보 소진과 빈 모델 응답 처리를 검증 |
| 공개 CI | PR과 `main` push에서 Java 21 전체 테스트, Redis Testcontainers, 정적 웹 검증을 실행 |

## 대표 코드와 테스트

- 코드: [FallbackChain](backend/src/main/java/com/example/gateway/resilience/FallbackChain.java) - 모델 호출 실패를 분류하고 허용된 후보 안에서만 다음 호출을 선택합니다.
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

## 제한 사항

- 기본 모델 제공자와 임베딩은 테스트용 구현이며 실제 OpenAI·Anthropic 호출이나 모델 품질을 나타내지 않습니다.
- 지원 범위는 제한된 Chat Completions 필드와 메모리 배치 API이며 구조화된 다중 대화 상태를 보존하지 않습니다.
- SSE는 완료 응답을 나누어 전달한 결과로 실제 모델의 토큰 단위 실시간 전송이 아닙니다.
- 기본 캐시, 사용량 제한, 배치와 요청 기록은 메모리 구현이라 프로세스 재시작 시 유지되지 않습니다.
- PostgreSQL·Redis·Nginx Compose는 구성 검사 범위이며 운영 부하, 고가용성, 실제 과금 연동을 검증하지 않았습니다.
