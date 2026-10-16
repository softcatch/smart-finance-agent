-- 상세 보기 ID의 주인. 분석 조건과 보고서는 FastAPI(detail_condition)가 갖고, 여기서는 본인 확인만 한다.
CREATE TABLE chat_detail (
    id          BIGSERIAL PRIMARY KEY,
    detail_id   VARCHAR(64) NOT NULL UNIQUE,
    member_id   BIGINT      NOT NULL REFERENCES member(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
