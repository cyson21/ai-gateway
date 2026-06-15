# Slice-start prompt template

매 micro-slice 세션을 `/clear` 직후 이 템플릿으로 시작한다. **고정부는 그대로 두고**,

- 진입 컨텍스트는 README/plan 전체가 아니라 그 슬라이스가 실제로 여는 파일만 적는다.
- 직전 슬라이스 산출물을 소비해야 하면 "이전 슬라이스 산출물" 줄에 1줄로 명시한다(없으면 삭제).

---

```text
ai-gateway(Project 04, repos/ai-gateway)의 {{슬라이스 ID}} 슬라이스를 진행한다.

[작업 방식]
- "1세션 = 1 micro-slice"로 진행한다. 이 슬라이스 범위 밖은 건드리지 않는다.
- 진입 컨텍스트(아래 파일)만 열고 시작한다. README/plan 전체를 다시 읽지 마라.

[빌드 환경 — 매번 적용]
- 기본 셸 JDK는 8이다. backend 명령은 반드시 앞에 JAVA_HOME을 핀 고정한다:
  cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B test
- mvnw wrapper는 없다. 시스템 mvn(3.9.11)을 쓴다.

[{{슬라이스 ID}} 목표]

[진입 컨텍스트]
- 이전 슬라이스 산출물(소비할 때만): {{예: auth/ApiKeyAuthFilter.java의 TENANT_ID_ATTRIBUTE}}

[검증 기준]
- DB 의존은 fake/in-memory로 추상화해 단위 테스트로 검증하는 것을 우선한다
  (실DB 마이그레이션/통합 검증은 Testcontainers 슬라이스 몫).
- 검증 그린 기준: JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B test.

[마무리]
  (커맨드·결과·설계 메모·다음 슬라이스).
- 범위 밖 슬라이스/프론트/Phase 2는 손대지 않는다. 끝나면 결과만 보고한다.
```

---

## 채우는 순서 (30초)

2. 그 줄의 **ID / 목표 / 검증 / 진입** 을 위 4개 `{{ }}` 에 옮긴다.
3. 직전 슬라이스 출력을 쓰면 "이전 슬라이스 산출물" 줄을 채우고, 아니면 지운다.
4. `/clear` 후 붙여넣고 시작.

## 고정부 변경이 필요한 순간

다음이 바뀌면 이 템플릿(또는 빌드 환경 메모)을 갱신한다 — 슬라이스 프롬프트에 매번
임시로 적지 말 것:

- JDK 핀 버전 / `mvnw` wrapper 도입 / 시스템 mvn 버전
- 검증 커맨드 형태(예: 프론트 추가로 `npm` 검증 병행)
- tracking 기록 위치
