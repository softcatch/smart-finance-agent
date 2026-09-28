# 이슈 #13 — 별칭 등록·목록 (FR-007, FR-008)

## Context

계좌 카테고리(FR-003~006)가 전부 끝났다(이슈 #5·#7·#8). `docs/smart-spec.xlsx`
요구사항 시트 기준으로 로드맵상 다음은 **별칭** 카테고리(FR-007 별칭 등록,
FR-008 별칭 목록)다 — 그다음은 송금(FR-009~010)인데, 송금은 별칭·대표 계좌를
전제로 하므로 순서상 별칭이 먼저다.

스펙(API 상세 시트):
- **E-07** `POST /api/v1/aliases` — 요청 `alias`·`accountNumber`(둘 다 필수).
  응답 `data.{aliasId, alias, accountNumber, ownerName}`. 에러
  `AUTH_INVALID`·`ACCOUNT_NOT_FOUND`(가리키는 계좌번호가 없음).
- **E-08** `GET /api/v1/aliases` — 응답
  `data.aliases[].{aliasId, alias, accountNumber, ownerName}`. 에러
  `AUTH_INVALID`뿐.

핵심 포인트: **`ownerName`은 별칭을 등록하는 회원 자신의 이름이 아니라, 별칭이
가리키는 계좌번호를 실제로 소유한 다른 회원의 이름**이다("별칭 목록" 요구사항
설명: "등록한 별칭과 계좌·예금주 이름"). 즉 계좌번호 → 그 계좌를 가진 `Account.
memberId` → `Member.name`을 조회해야 한다 — `account`와 `auth` 두 도메인을 모두
참조하는 첫 번째 기능이다.

## 설계

### 새 도메인 `alias`

`account`가 `auth`에서 분리됐던 것과 같은 이유로, 별칭은 계좌·회원과는 다른
책임(연락처 관리)이라 새 도메인 패키지
`server/src/main/java/com/softcatch/smart/alias/`를 만든다. 이슈 #10에서 정한
DTO 분리 규칙을 처음부터 적용한다 — 컨트롤러에 DTO를 중첩시키지 않고 바로
`alias/dto/request`·`alias/dto/response`에 만든다.

- **`Alias` 엔티티**(`id`, `memberId`(별칭을 등록한 회원), `alias`, `accountNumber`).
  `Member`/`Account`와 같은 패턴 — `protected Alias() {}` + package-private
  생성자 + 필요한 것만 public getter.
- **`V3__alias.sql`** 마이그레이션:
  ```sql
  CREATE TABLE alias (
      id             BIGSERIAL PRIMARY KEY,
      member_id      BIGINT      NOT NULL REFERENCES member(id),
      alias          VARCHAR(50) NOT NULL,
      account_number VARCHAR(12) NOT NULL REFERENCES account(account_number),
      created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  ```
  `account_number`를 `account.account_number`(이미 UNIQUE)에 대한 실제 FK로
  걸어서, 존재하지 않는 계좌번호가 DB 레벨에서도 걸러지게 한다. 별칭 등록·목록
  둘 다 "다른 행을 보고 판단"하는 로직이 없어서 동시성 설계가 필요 없다(계좌
  목록 조회와 같은 성격).
- **`AliasResponse(Long aliasId, String alias, String accountNumber, String
  ownerName)`**를 `alias/dto/response/`에 만든다. 등록·목록 응답 모양이 완전히
  같아서 재사용한다. 목록은 `AliasListResponse(List<AliasResponse> aliases)`로
  감싼다(계좌 목록과 같은 패턴).
- **`AliasService`**가 `AccountRepository.findByAccountNumber`(신규 추가 —
  지금은 계좌를 id로만 찾음)로 대상 계좌를 찾고 없으면 `ACCOUNT_NOT_FOUND`,
  있으면 그 계좌의 `memberId`로 `MemberRepository.findById`(이미 있음)를 조회해
  `ownerName`을 얻는다. 서비스 메서드가 엔티티 대신 `AliasResponse`를 직접
  반환한다 — 등록·목록 둘 다 "별칭 + 계좌 소유자 이름"이라는 조인 결과이지,
  `Alias` 엔티티 자체의 상태 변경이 아니라서 굳이 엔티티를 거쳐 컨트롤러가
  다시 조립하게 만들 필요가 없다(계좌 API들이 엔티티를 반환하던 것과는 다른
  선택).

### 크로스 도메인 의존성

`alias` → `account`(`AccountRepository`, 이미 `public`), `alias` → `auth`
(`MemberRepository`, 이미 `public`). `account`가 `auth`를 참조하는 것과 같은
구조라 새로운 캡슐화 예외가 필요 없다.

## 개발 계획

1. **`V3__alias.sql` 마이그레이션 + `Alias` 엔티티·`AliasRepository`**
   (`findByMemberIdOrderByIdAsc`). 예상 결과: 마이그레이션 적용, 존재하지 않는
   계좌번호로는 별칭 행 자체가 안 만들어짐(FK 제약).
2. **`AccountRepository.findByAccountNumber` 추가**(계좌 도메인, 별칭이 대상
   계좌를 찾는 데 필요). 예상 결과: 계좌번호로 계좌·소유자(memberId) 조회 가능.
3. **별칭 등록 API (FR-007, E-07)** — `AliasService.register(memberId, alias,
   accountNumber)`: 대상 계좌 조회(없으면 404 `ACCOUNT_NOT_FOUND`) → 소유자
   이름 조회 → 저장 → `AliasResponse` 반환. `AliasController`:
   `POST /api/v1/aliases`(Bearer) → 201.
   예상 결과: 다른 회원 계좌번호로 별칭 등록하면 그 회원 이름이 `ownerName`으로
   나옴, 없는 계좌번호면 404.
4. **별칭 목록 API (FR-008, E-08)** — `AliasService.list(memberId)`가 등록된
   별칭마다 소유자 이름을 조인해 `List<AliasResponse>` 반환.
   `AliasController`: `GET /api/v1/aliases`(Bearer) → `AliasListResponse`.
   예상 결과: 등록한 별칭 목록이 계좌번호·예금주 이름과 함께 나옴, 없으면
   빈 배열.
5. **통합 테스트** — 등록 성공(ownerName 확인) / 없는 계좌번호면 404 / 목록
   조회(개수·필드·ownerName) / 빈 배열 / 다른 회원 별칭 안 섞임.
   예상 결과: `./gradlew test`(gradle 루트 `server/`) 전체 통과.

동시성 테스트는 필요 없다(등록·조회 모두 다른 행을 보고 판단하지 않는 단순
쓰기·읽기 — FR-006과 같은 성격).

## 실행 순서

`main`에서 `feat/#13-alias` 브랜치를 딴다. 위 5단계를 순서대로 커밋한다(단계마다
빌드·테스트 통과 확인).

## 검증

- 각 단계 커밋 전 `./gradlew test`로 전체 테스트 통과 확인.
- 동시성 테스트는 필요 없음(등록·조회 모두 단순 쓰기·읽기).
