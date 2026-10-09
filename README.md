# AI Gateway

[![CI](https://github.com/cyson21/ai-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/cyson21/ai-gateway/actions/workflows/ci.yml)

여러 서비스가 OpenAI나 Anthropic을 각자 호출하는 대신, 이 게이트웨이 하나를 거쳐 호출하게 만든 Java 21, Spring WebFlux 프로젝트입니다. 인증, 사용량 제한, 비용, 캐시, 장애 대응을 한곳에서 처리합니다. 설계부터 구현, 테스트까지 혼자 진행한 개인 프로젝트입니다.

[포트폴리오](https://cyson21.github.io/projects/ai-gateway/) · [이력서](https://cyson21.github.io/downloads/resume.pdf)

## 왜 만들었나

서비스마다 모델 선택, 대체 모델 전환, 할당량, 비용 제한, 캐시, 입출력 검사, 사용량 기록을 따로 만들면 규칙이 서비스마다 조금씩 달라지고, 문제가 생겼을 때 볼 곳도 흩어집니다. 앞에 게이트웨이를 두면 모든 요청에 같은 규칙이 적용되고, 모델을 바꾸거나 정책을 고칠 때도 한곳만 고치면 됩니다.

조직별로 LLM 사용량과 비용을 제한해야 하거나, 비용, 속도, 장애 여부에 따라 모델을 골라 써야 하거나, 입출력 검사와 호출 기록을 한곳에 모아야 하는 경우를 생각하고 만들었습니다.

## 요청 흐름

요청이 들어오면 인증으로 조직을 확인하고, 할당량과 입력을 검사한 뒤, 캐시를 보고, 모델을 골라 호출합니다. 실패하면 정해진 재시도 횟수와 후보 안에서만 다른 모델로 넘어갑니다. 마지막으로 출력을 검사하고 사용량과 비용을 기록합니다.

```text
Auth → Quota → Guardrail → Cache → Router → Dispatch → Fallback → Guardrail → Record
```

## 기능

- 모델 선택과 대체: 비용, 지연, 고정 가중치로 모델을 고르고, 실패하면 남은 후보로 넘어갑니다.
- 조직별 할당량: 조직마다 요청 수, 토큰, 비용 한도를 따로 둡니다.
- 캐시: 똑같은 요청은 바로 재사용하고, 비슷한 질문은 유사도로 찾습니다. 조직이 다르면 공유하지 않습니다.
- 입출력 검사: 모델을 부르기 전에 입력을, 응답을 돌려주기 전에 출력을 검사합니다.
- 사용량 기록: 요청, 토큰, 지연 시간, 추정 비용을 남깁니다.
- 그 밖에: JSON, SSE 스트리밍, 서킷 브레이커, A/B 라우팅, 캐시 무효화, 도구 호출 전달, 비동기 배치

## 기술 구성

기본 실행과 테스트는 Java 21, Spring Boot WebFlux, 메모리 저장소, 가짜 모델과 가짜 임베딩으로 돌아갑니다. 네트워크 없이 항상 같은 결과가 나옵니다.

Redis, PostgreSQL, pgvector, 실제 모델 연동은 설정을 켜면 동작하도록 따로 분리해 두었습니다. 운영 환경에서 쓴 것은 아닙니다.

## 구조

```text
Bearer API 키 -> SHA-256 조회 -> 조직 식별
채팅 요청     -> 요청·토큰 예산 -> 입력 검사
              -> 정확 일치 캐시 -> 유사도 캐시
              -> 고정·비용·지연·가중치 기반 모델 선택
              -> 테스트 모델 -> 회로 차단·재시도·후보 전환
              -> 출력 검사 -> 사용량 기록 -> JSON 또는 SSE 응답
```

- API 키 원문은 저장하지 않고, 해시로 찾아서 조직을 확인합니다.
- 똑같은 요청 캐시는 조직, 정규화한 입력, 모델 별칭, `max_tokens`, 도구 설정이 모두 같을 때만 재사용합니다.
- 유사도 캐시도 조직이나 응답 조건이 다르면 결과를 나누지 않습니다.
- 평소 모델을 고르는 규칙과 실패했을 때 넘어가는 규칙을 따로 둬서, 한쪽을 바꿔도 다른 쪽에 영향이 없게 했습니다.

## 실패 상황별 결과

| 상황 | 결과 |
|---|---|
| API 키가 없거나 비활성 | 조직을 확인하지 못해 요청 처리로 넘어가지 않습니다 |
| 역할, 본문, 토큰 범위, 도구 조합이 잘못됨 | 모델을 부르기 전에 400으로 거절합니다 |
| 요청 수, 토큰, 비용 한도 초과 | 할당량을 깎지도, 모델을 부르지도 않습니다 |
| 기본 모델 호출 실패 | 서킷 브레이커와 재시도 한도 안에서 다음 후보만 호출합니다 |
| 같은 입력의 가중치 분배 | 항상 같은 구간, 같은 후보 순서로 갑니다 |
| 캐시 재사용 | 조직, 모델 별칭, 토큰 한도, 도구 설정이 다르면 공유하지 않습니다 |
| 캐시 정책이 바뀜 | 저장할 때 통과한 응답도 돌려주기 직전에 출력 검사를 다시 거칩니다 |

## 확인한 방법

| 검증 | 확인한 내용 |
|---|---|
| 기본 테스트 | 정책 순서, 요청과 인증 처리, 캐시 적중, 사용량 제한, 입출력 검사, 장애 대응, SSE 응답을 Docker 없이 확인 |
| Redis 통합 테스트 | 사용량 제한과 예산 저장, 동시 갱신을 실제 Redis에서 확인 |
| 인증 | `ApiKeyAuthFilterTest`로 키가 없거나 틀리거나 맞을 때 각각 막히거나 조직 정보가 넘어가는지 확인 |
| 캐시 분리 | 두 캐시 모두 조직과 응답 조건별로 나뉘는지 확인 |
| 장애 대응 범위 | `FallbackChainTest`로 재시도 중단, 후보 소진, 빈 응답 처리를 확인 |
| CI | PR과 `main` push마다 Java 21 전체 테스트, Redis Testcontainers, 정적 웹 검사를 실행 |

## 대표 코드와 테스트

- 코드: [FallbackChain](backend/src/main/java/com/example/gateway/resilience/FallbackChain.java) - 모델 호출 실패를 종류별로 나누고, 허용된 후보 안에서만 다음 모델을 고릅니다.
- 테스트: [FallbackChainTest](backend/src/test/java/com/example/gateway/resilience/FallbackChainTest.java) - 재시도를 멈추는 조건과 후보를 다 썼을 때를 확인합니다.

## 실행

기본 테스트는 Java 21과 Maven으로 돌리고, Docker가 필요한 테스트는 뺍니다.

```bash
cd backend
mvn -B test -Dtest='!Redis*'
```

Docker가 있으면 Redis 통합 테스트를 따로 돌리고 `Skipped: 0`인지 확인합니다.

```bash
cd backend
mvn -B test -Dtest='Redis*'
```

관리 화면은 외부 패키지 없이 Node.js 기본 기능과 고정 데이터만 씁니다.

```bash
cd web
npm run build
npm test
```

## 해 보지 않은 것

- 기본 모델과 임베딩은 테스트용 가짜 구현입니다. 실제 OpenAI, Anthropic을 호출하거나 모델 품질을 본 것은 아닙니다.
- Chat Completions 필드 일부와 메모리 배치 API만 지원하고, 여러 턴 대화 상태는 따로 저장하지 않습니다.
- SSE는 완성된 응답을 잘라서 보내는 방식이라, 실제 모델 토큰을 실시간으로 중계한 건 아닙니다.
- 기본 캐시, 사용량 제한, 배치, 요청 기록은 메모리에 있어서 재시작하면 사라집니다.
- PostgreSQL, Redis, Nginx Compose는 구성이 맞는지만 확인했습니다. 대규모 부하, 고가용성, 실제 과금 연동은 해 보지 않았습니다.

## 관련 프로젝트와 공개 자료

이 저장소의 코드·실행·테스트는 이 저장소에서 관리합니다. [웹 포트폴리오의 프로젝트 설명](https://cyson21.github.io/projects/ai-gateway/)과 [공개 자료 안내](https://github.com/cyson21/portfolio-hub)는 외부에서 구현 근거를 찾는 진입점입니다.

- 관련 주제: [enterprise-policy-rag](https://github.com/cyson21/enterprise-policy-rag) — LLM 호출 정책과 권한 기반 검색 비교.
- 최신 제출 파일: [이력서 PDF](https://cyson21.github.io/downloads/resume.pdf) · [경력기술서 PDF](https://cyson21.github.io/downloads/career-description.pdf).

위 관련 저장소는 별도로 실행하는 개인 프로젝트입니다. 서로의 서비스를 순서대로 띄우거나 실제 API·메시지로 연결한 E2E 체인이 구현됐다는 의미는 아닙니다. 구현·검증 범위가 바뀌면 이 README와 웹 프로젝트 문안을 함께 확인합니다.
