-- 회원 역할(FR-030)과 AI 사용 정지(FR-031). 관리자는 운영자가 role을 ADMIN으로 바꿔 지정한다.
ALTER TABLE member
    ADD COLUMN role            VARCHAR(10) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    ADD COLUMN ai_blocked      BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN ai_block_reason TEXT;
