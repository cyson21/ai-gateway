# AGENTS.md

## 공통 작업 규칙

- 이 파일은 저장소 전체에 적용한다.
- 더 하위 경로에 별도 `AGENTS.md`가 생기면 해당 범위에서는 하위 파일이 우선한다.
- `side-projects` 작업공간에서 브랜치·PR·Git worktree를 다룰 때는 [공통 운영 규칙](../../docs/standards/git-branch-worktree-operations.md)을 따른다.
- 요청과 관련 없는 모듈, 문서, 인프라 파일은 함께 정리하거나 재작성하지 않는다.

## 저장소 구조

- `backend/`: Java 21, Spring Boot 3.3.6, WebFlux 기반 API와 핵심 게이트웨이 로직
- `web/`: Node.js 기반 웹 UI
- `infra/local/`: 로컬 PostgreSQL·Redis 등 Docker Compose 환경
- `tools/portfolio-evidence/`: 포트폴리오 증거 보고서 생성기와 Python 테스트
- `docs/`: 설계, 운영, 포트폴리오 문서
- `.github/workflows/ci.yml`: 기준 CI 절차

## 기준 환경

- 백엔드: Java 21과 Maven
- 웹: CI 기준 Node.js 22와 npm
- 증거 도구: Python 3
- Redis 통합 테스트: Docker 필요
- 비밀값과 실제 공급자 API 키를 저장소에 기록하지 않는다. 예제에는 명백한 더미값만 쓴다.

## 자주 쓰는 명령

저장소 루트에서 실행한다.

```bash
# Docker 없이 실행하는 기본 백엔드 회귀 테스트
cd backend
mvn -B --no-transfer-progress test -Dtest='!Redis*'

# Docker 사용이 가능할 때 전체 백엔드 테스트
cd backend
mvn -B --no-transfer-progress test

# Redis 통합 테스트만 실행
cd backend
mvn -B --no-transfer-progress test -Dtest='Redis*'

# 웹 검증
cd web
npm run build
npm test

# 포트폴리오 증거 도구 검증
python3 -m unittest discover -s tools/portfolio-evidence/tests -p 'test_*.py'
```

Windows 전용 도구를 써야 할 때도 같은 버전과 인수를 유지한다. Docker를 사용할 수 없다면 Redis 테스트를 통과했다고 표현하지 말고 미실행 사유를 보고한다.

## 아키텍처 불변 조건

- 인증 필터가 테넌트를 확정한 뒤에만 게이트웨이 파이프라인으로 진입한다.
- 핵심 처리 순서를 유지한다: 할당량 확인 → 입력 가드레일 → 캐시 → 라우팅 → 디스패치/폴백 → 출력 가드레일 → 요청 기록.
- `PASSTHROUGH` 경로는 캐시를 사용하지 않는다.
- 성공, 캐시 적중, 거부, 실패를 포함한 모든 처리 경로에서 `RequestRecord`에 필요한 정보가 남아야 한다.
- 캐시 적중 결과도 출력 가드레일을 거친다.
- 동기적이고 결정 가능한 핵심 파이프라인과 리액티브 웹 경계를 분리한다.
- WebFlux 요청 경로에 블로킹 I/O나 Reactor `.block()`을 추가하지 않는다.
- Redis 기반 할당량·예산 연산은 원자성을 보존하고 테넌트 키를 서로 격리한다.
- 스트리밍 응답은 OpenAI 호환 청크 형식과 종료 표식 `[DONE]`을 보존한다.

## 구현 규칙

- 기존 패키지 경계와 역할을 따른다. 새 추상화는 실제 중복이나 테스트 가능한 경계를 해결할 때만 추가한다.
- 의존성은 명시적 생성자 주입을 사용한다.
- 값과 계약에는 불변 객체와 Java `record`를 우선한다.
- 시간 의존 로직은 주입 가능한 `Clock` 또는 기존 테스트 대역을 사용해 결정 가능하게 만든다.
- 공개 오류 코드, HTTP 상태, 헤더와 응답 형태를 바꾸면 대응 테스트를 함께 수정한다.
- `/v1/chat/completions` 계약과 기존 인증·테넌트 헤더는 요청받지 않은 한 호환성을 유지한다.
- 테스트는 변경한 동작의 정상 경로뿐 아니라 거부, 폴백, 경계값을 포함한다.

## 변경 범위와 금지 사항

- 백엔드 변경에 필요하지 않은 `web/`, `docs/`, `infra/` 파일을 수정하지 않는다.
- 생성물, 빌드 출력, IDE 설정, 로컬 환경 파일, 비밀값을 커밋하지 않는다.
- 테스트 실패를 우회하려고 테스트를 비활성화하거나 검증 강도를 낮추지 않는다.
- Docker 또는 외부 서비스가 없어서 실행하지 못한 검증을 성공으로 기록하지 않는다.
- 사용자 요청 없이 공개 API 계약이나 데이터 마이그레이션을 파괴적으로 변경하지 않는다.

## 완료 기준

- 백엔드 핵심 로직 변경: 최소한 Docker 제외 백엔드 회귀 테스트를 실행한다.
- Redis, 할당량, 예산 변경: Docker 환경에서 Redis 테스트를 실행하고 건너뛴 테스트가 없는지 확인한다.
- 웹 변경: `npm run build`와 `npm test`를 모두 실행한다.
- 증거 생성기 변경: Python 단위 테스트를 실행한다.
- 여러 영역을 건드린 변경: `.github/workflows/ci.yml`과 동등한 전체 검증을 우선한다.
- 문서만 변경한 경우에도 문서의 명령, 경로, 주장과 실제 저장소가 일치하는지 확인한다.
- 완료 보고에는 변경 파일, 실행한 정확한 명령, 성공·실패·건너뜀 결과, 실행하지 못한 항목과 이유를 포함한다.
