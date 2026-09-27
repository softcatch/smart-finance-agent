# 이슈 #8 — 내 계좌·잔액 조회 (FR-006) 개발 계획

## Context

이슈 #7(PR #9)까지 만든 `Account`/`AccountRepository`/`AccountService`/
`AccountController`(`server/src/main/java/com/softcatch/smart/account/`)를 그대로
이어서 쓴다 — 새 엔티티·마이그레이션·에러 코드 없음.

`docs/smart-spec.xlsx` API 상세 시트의 E-04 스펙:
- 요청: `Header Authorization`만.
- 응답: `data.accounts[].{accountId, accountNumber, balance, primary}`.
- 에러: `AUTH_INVALID`뿐(이미 `/api/**` 인증 필터가 공통 처리).

이슈 #8 참고사항에 이미 적혀 있듯 읽기 전용이라 동시성 이슈가 없다 — 이슈 #5·#7처럼
잠금이나 원자적 UPDATE를 고민할 필요가 없는, 계좌 카테고리에서 가장 단순한 이슈다.

## 설계

- 응답 항목(`accountId`·`accountNumber`·`balance`·`primary`)이 이미 있는
  `AccountController.AccountResponse` 레코드와 완전히 같은 모양이라 그대로
  재사용한다(계좌 개설 응답에도 쓰고 있음). 새 DTO를 또 안 만든다.
- `data`가 배열이 아니라 `{accounts: [...]}` 객체이므로 감싸는 레코드 하나만
  추가한다: `record AccountListResponse(List<AccountResponse> accounts) {}`.
- 목록 순서는 스펙에 명시가 없지만, 테스트에서 "몇 번째 계좌가 대표인지"를
  안정적으로 검증하려면 순서가 결정적이어야 한다 — `id` 오름차순(개설 순서)으로
  정렬해서 반환한다.

## 개발 계획 (완료 조건 그대로)

1. **`AccountRepository`에 조회 쿼리 추가** —
   `List<Account> findByMemberIdOrderByIdAsc(Long memberId);`
   예상 결과: 회원의 계좌를 개설 순서대로 가져올 수 있음.
2. **조회 API (FR-006, E-04)** — `AccountService.findAll(memberId)`가 리포지토리
   호출을 그대로 감싸고, `AccountController`에 `GET /api/v1/accounts`(Bearer)를
   추가해 `AccountResponse` 목록을 `AccountListResponse`로 감싸 반환한다.
   예상 결과: 로그인한 회원의 계좌 목록·잔액·대표 여부가 배열로 나옴.
3. **통합 테스트** — 계좌 여러 개(대표 1개 + 비대표 1~2개) 개설 후 목록 조회 →
   개수·각 필드·대표 계좌 위치 검증. 남의 계좌가 안 섞이는지도 확인(다른 회원으로
   개설 후 내 목록에 안 나오는지).
   예상 결과: `./gradlew test`(gradle 루트 `server/`) 전체 통과.

## 실행 순서

이슈 #7(PR #9)이 아직 병합 대기 중이다. PR #9 위에 `feat/#8-accounts`를 바로
얹으면(스택 브랜치) PR #9가 병합될 때까지 이 PR 리뷰에 #7의 커밋까지 같이 보여
리뷰가 섞인다 — 권장하지 않는다. **PR #9가 병합될 때까지 기다렸다가, `main`을
받아서 그 위에 `feat/#8-accounts`를 딴다.** (코드 자체는 이미 다 구상돼 있어서
기다리는 동안 막히는 건 없다 — PR #9 CI·병합 상태만 확인하면 된다.)

## 검증

- 각 단계 커밋 전 `./gradlew test`로 전체 테스트 통과 확인.
- 동시성 테스트는 필요 없음(읽기 전용).
