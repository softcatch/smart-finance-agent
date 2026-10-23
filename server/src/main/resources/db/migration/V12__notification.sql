-- 송금 완료 알림(FR-024). 송금 확정 트랜잭션에서 송금과 함께 쓴다(보낸 사람·받은 사람 한 줄씩).
-- 상대 이름·금액을 행에 함께 둬서 목록(E-14)과 스트림(E-15)이 조인 없이 같은 모양을 쓴다.
CREATE TABLE notification (
    id                BIGSERIAL    PRIMARY KEY,
    member_id         BIGINT       NOT NULL REFERENCES member(id),
    transfer_id       BIGINT       NOT NULL REFERENCES transfer(id),
    direction         VARCHAR(8)   NOT NULL CHECK (direction IN ('SENT', 'RECEIVED')),
    counterpart_name  VARCHAR(50)  NOT NULL,
    amount            BIGINT       NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (transfer_id, member_id)
);
-- 목록(E-14): 내 알림을 최신순으로
CREATE INDEX idx_notification_member_id ON notification (member_id, id DESC);
