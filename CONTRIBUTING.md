# 기여 규칙

## 작업 흐름

```text
이슈 작성 → 브랜치 생성 → 커밋 → PR → CI 통과 → Squash merge
```

- 레포 기본 세팅 이후 `main`에 직접 커밋하지 않는다. 모든 작업은 브랜치 → PR로 올린다.
- 예외: 문서(`docs/`와 `*.md`)만 바꾸는 작업은 PR 없이 `main`에 바로 커밋한다. 검증할 것이 없기 때문이다. 코드와 함께 바뀌는 문서는 그 코드 PR에 넣는다.
- PR 하나 = 이슈 하나. Squash merge로 `main`에는 PR당 커밋 하나만 남긴다.

## 브랜치

GitHub Flow. `main` + 작업 브랜치.

```text
<type>/#<이슈 번호>-<짧은 설명>
feat/#12-login
fix/#18-transfer-balance
```

| type | 용도 |
|---|---|
| `feat` | 기능 추가 |
| `fix` | 버그 수정 |
| `docs` | 문서 |
| `refactor` | 동작 변화 없는 구조 개선 |
| `test` | 테스트·측정 |
| `chore` | 빌드·설정·CI |

## 커밋

Conventional Commits + 한글 제목 + 이슈 번호.

```text
<type>(<범위>): <무엇을 했는지> (#<이슈 번호>)

feat(auth): 로그인 JWT 발급 (#12)
fix(transfer): 동시 송금 시 잔액 음수 방지 (#18)
```

- 요구사항 ID(FR·NFR)는 커밋이 아니라 이슈에 적는다.
- 커밋·PR·이슈 어디에도 도구 서명(`Co-Authored-By`, "Generated with ..." 등)을 넣지 않는다.

## 이슈

`.github/ISSUE_TEMPLATE/task.md` 순서대로 쓴다.

```text
목적 → 관련 요구사항(FR·NFR) → 영향 범위 → 완료 조건 → 참고사항
```

## PR

`.github/pull_request_template.md` 순서대로 쓴다.

```text
목적(연결 이슈) → 변경 내용 → 영향 범위 → 검증(테스트·측정 수치) → 완료 조건 체크 → 참고사항
```

- 검증에는 실행한 테스트와 측정 수치를 적는다. 수치로 설명하는 것이 이 프로젝트의 원칙이다.

## 코드 스타일

포매터가 스타일을 정한다. CI에서 검사한다.

| 대상 | 도구 |
|---|---|
| Java | Spotless (google-java-format) |
| Python | ruff (lint + format) |

- 설정 파일은 뼈대 PR에서 추가한다.
